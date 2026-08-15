package golib

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// testCert returns a self-signed certificate valid for dns.test and 127.0.0.1
// together with a pool that trusts it.
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

// loopbackDoTServer serves framed DNS over TLS on a loopback listener; each
// accepted connection is answered by respond, and a nil answer closes it.
// It returns a dial function that opens a fresh connection to the server.
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
