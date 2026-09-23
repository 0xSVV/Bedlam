package golib

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testCert(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "dns.test"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{"dns.test"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(leaf)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}, pool
}

func loopbackDoTServer(t *testing.T, cert tls.Certificate, respond func(conn int, query []byte) []byte) func() (net.Conn, error) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		n := 0
		for {
			s, err := ln.Accept()
			if err != nil {
				return
			}
			n++
			go func(s net.Conn, id int) {
				tc := tls.Server(s, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"dot"}})
				defer tc.Close()
				if err := tc.Handshake(); err != nil {
					return
				}
				for {
					q, err := readDNSFrame(tc)
					if err != nil {
						return
					}
					resp := respond(id, q)
					if resp == nil {
						return
					}
					if err := writeDNSFrame(tc, resp); err != nil {
						return
					}
				}
			}(s, n)
		}
	}()
	return func() (net.Conn, error) { return net.Dial("tcp", ln.Addr().String()) }
}

func echoDoT(ip [4]byte) func(query []byte) []byte {
	return func(q []byte) []byte {
		resp := dnsResponse("example.com", 60, ip)
		copy(resp[:2], q[:2])
		return resp
	}
}

func TestTLSResolver_roundTrip(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed.Add(1)
		if addr != "dns.test:853" {
			t.Errorf("dialed %q", addr)
		}
		return dial()
	}}
	r := newTLSResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	resp, err := r.exchange(context.Background(), dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 1 {
		t.Errorf("answer = %v", resp)
	}
	if r.id() != "tls|dns.test:853" {
		t.Errorf("id = %q", r.id())
	}
}

func TestTLSResolver_reusesPooledConn(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed.Add(1)
		return dial()
	}}
	r := newTLSResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	for i := 0; i < 3; i++ {
		if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
	}
	if dialed.Load() != 1 {
		t.Errorf("dialed %d times, want 1 (pooled connection reused)", dialed.Load())
	}
}

func TestTLSResolver_retriesOnStalePooledConn(t *testing.T) {
	cert, pool := testCert(t)
	var answers atomic.Int32
	dial := loopbackDoTServer(t, cert, func(conn int, q []byte) []byte {
		if conn == 1 && answers.Add(1) > 1 {
			return nil
		}
		return echoDoT([4]byte{byte(conn), 0, 0, 0})(q)
	})
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed.Add(1)
		return dial()
	}}
	r := newTLSResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	resp, err := r.exchange(context.Background(), dnsQuery("example.org"))
	if err != nil {
		t.Fatalf("second exchange should redial: %v", err)
	}
	if dialed.Load() != 2 {
		t.Errorf("dialed %d times, want 2", dialed.Load())
	}
	if resp[len(resp)-4] != 2 {
		t.Errorf("answer came from connection %d, want 2", resp[len(resp)-4])
	}
}

func TestTLSResolver_rejectsUnknownCA(t *testing.T) {
	cert, _ := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) { return dial() }}
	r := newTLSResolver(fc, "dns.test:853", nil)
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err == nil {
		t.Fatal("handshake with an untrusted certificate must fail")
	}
}

func TestTLSResolver_verifiesServerName(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) { return dial() }}
	r := newTLSResolver(fc, "other.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err == nil {
		t.Fatal("certificate for dns.test must not verify for other.test")
	}
}

type recordingDoTServer struct {
	addr        string
	serverNames chan string

	mu     sync.Mutex
	dialed []string
}

func newRecordingDoTServer(t *testing.T, cert tls.Certificate) *recordingDoTServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	srv := &recordingDoTServer{addr: ln.Addr().String(), serverNames: make(chan string, 8)}
	go func() {
		for {
			s, err := ln.Accept()
			if err != nil {
				return
			}
			go func(s net.Conn) {
				tc := tls.Server(s, &tls.Config{
					Certificates: []tls.Certificate{cert},
					NextProtos:   []string{"dot"},
					GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
						srv.serverNames <- hello.ServerName
						return nil, nil
					},
				})
				defer tc.Close()
				for {
					q, err := readDNSFrame(tc)
					if err != nil {
						return
					}
					if err := writeDNSFrame(tc, echoDoT([4]byte{1, 1, 1, 1})(q)); err != nil {
						return
					}
				}
			}(s)
		}
	}()
	return srv
}

func (s *recordingDoTServer) client() *fakeClient {
	return &fakeClient{tcp: func(addr string) (net.Conn, error) {
		s.mu.Lock()
		s.dialed = append(s.dialed, addr)
		s.mu.Unlock()
		return net.Dial("tcp", s.addr)
	}}
}

func (s *recordingDoTServer) dialedAddrs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.dialed...)
}

func TestNewTLSResolver_dialsHostAndNamesIt(t *testing.T) {
	cert, _ := testCert(t)
	srv := newRecordingDoTServer(t, cert)
	r := newTLSResolver(srv.client(), "one.one.one.one:853", nil)
	defer r.close()

	cfg := r.tlsCfg
	if cfg.ServerName != "one.one.one.one" {
		t.Errorf("server name = %q, want one.one.one.one", cfg.ServerName)
	}
	if cfg.InsecureSkipVerify || cfg.RootCAs == nil {
		t.Error("the certificate must be verified against the system roots")
	}
	if len(cfg.NextProtos) != 1 || cfg.NextProtos[0] != "dot" {
		t.Errorf("ALPN = %q, want [dot]", cfg.NextProtos)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := r.exchange(ctx, dnsQuery("example.com"))
	var certErr *tls.CertificateVerificationError
	if !errors.As(err, &certErr) {
		t.Fatalf("err = %v, want a certificate outside the system roots to be refused", err)
	}
	select {
	case name := <-srv.serverNames:
		if name != "one.one.one.one" {
			t.Errorf("server saw SNI %q, want one.one.one.one", name)
		}
	default:
		t.Fatal("the server saw no ClientHello")
	}
	if dialed := srv.dialedAddrs(); len(dialed) != 1 || dialed[0] != "one.one.one.one:853" {
		t.Errorf("dialed %q, want the unresolved [one.one.one.one:853]", dialed)
	}
}

func TestTLSResolver_serverSeesHostAsSNI(t *testing.T) {
	cert, pool := testCert(t)
	srv := newRecordingDoTServer(t, cert)
	r := newTLSResolver(srv.client(), "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("exchange: %v", err)
	}
	select {
	case name := <-srv.serverNames:
		if name != "dns.test" {
			t.Errorf("server saw SNI %q, want dns.test", name)
		}
	default:
		t.Fatal("the server saw no ClientHello")
	}
	if dialed := srv.dialedAddrs(); len(dialed) != 1 || dialed[0] != "dns.test:853" {
		t.Errorf("dialed %q, want [dns.test:853]", dialed)
	}
}

func TestTLSResolver_ipServerName(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) { return dial() }}
	r := newTLSResolver(fc, "127.0.0.1:853", &tls.Config{RootCAs: pool})
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("IP SAN should verify: %v", err)
	}
}

func TestTLSResolver_handshakeOutlivesACancelledQueryUntilThePoolCloses(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		if c, err := ln.Accept(); err == nil {
			accepted <- c
		}
	}()
	_, pool := testCert(t)
	fc := &fakeClient{tcp: func(string) (net.Conn, error) { return net.Dial("tcp", ln.Addr().String()) }}
	r := newTLSResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	ctx, cancel := context.WithCancel(context.Background())
	server := make(chan net.Conn, 1)
	go func() {
		s := <-accepted
		server <- s
		cancel()
	}()
	start := time.Now()
	_, err = r.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("cancel during the handshake took %v", elapsed)
	}

	s := <-server
	defer s.Close()
	buf := make([]byte, 4096)
	_ = s.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	for {
		if _, err := s.Read(buf); err != nil {
			if !isTimeoutClass(err) {
				t.Fatalf("the handshake ended with the query that started it: %v", err)
			}
			break
		}
	}
	r.close()
	_ = s.SetReadDeadline(time.Now().Add(time.Second))
	for {
		if _, err := s.Read(buf); err != nil {
			if isTimeoutClass(err) {
				t.Error("closing the resolver left the handshake's connection open")
			}
			return
		}
	}
}

func TestTLSResolver_retriesAStalePooledStreamWithinTheAttempt(t *testing.T) {
	cert, pool := testCert(t)
	var stale atomic.Bool
	release := make(chan struct{})
	t.Cleanup(func() { close(release) })
	dial := loopbackDoTServer(t, cert, func(conn int, q []byte) []byte {
		if stale.Load() && conn == 1 {
			<-release
			return nil
		}
		return echoDoT([4]byte{byte(conn), 0, 0, 0})(q)
	})
	r := newTLSResolver(&fakeClient{tcp: func(string) (net.Conn, error) { return dial() }}, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()
	fillPool(t, r.pool, 1)
	stale.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("a new DoT stream answers, so the query must succeed: %v", err)
	}
	if conn := answerConn(resp); conn != 2 {
		t.Errorf("answer came from connection %d, want a new connection 2", conn)
	}
}
