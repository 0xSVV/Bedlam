package golib

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const dohFixtureQueryLimit = 512

type dohServer struct {
	srv      *httptest.Server
	requests atomic.Int32
	proto    atomic.Int32
	maxBody  atomic.Int32
	delay    atomic.Int64

	mu     sync.Mutex
	bodies [][]byte
}

func (d *dohServer) record(q []byte) {
	d.mu.Lock()
	d.bodies = append(d.bodies, append([]byte(nil), q...))
	d.mu.Unlock()
}

func (d *dohServer) lastBody() []byte {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.bodies) == 0 {
		return nil
	}
	return d.bodies[len(d.bodies)-1]
}

func rejectOversizeDoH(w http.ResponseWriter, q []byte, limit int32) bool {
	if limit <= 0 || len(q) <= int(limit) {
		return false
	}
	w.Header().Set("Content-Type", "text/html; charset=UTF-8")
	w.WriteHeader(http.StatusRequestEntityTooLarge)
	_, _ = io.WriteString(w, "<html><body>413 Request Entity Too Large</body></html>")
	return true
}

func rejectUnparsableDoH(w http.ResponseWriter, q []byte) bool {
	if _, ok := dnsQuestion(q); ok {
		return false
	}
	w.Header().Set("Content-Type", "text/plain; charset=UTF-8")
	w.WriteHeader(http.StatusBadRequest)
	_, _ = io.WriteString(w, "Bad Request")
	return true
}

func newDoHServer(t *testing.T, ip [4]byte, status int) *dohServer {
	t.Helper()
	d := &dohServer{}
	d.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		d.requests.Add(1)
		d.proto.Store(int32(r.ProtoMajor))
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		if ct := r.Header.Get("Content-Type"); ct != dohContentType {
			t.Errorf("content-type = %q", ct)
		}
		q, err := io.ReadAll(r.Body)
		d.record(q)
		if wait := time.Duration(d.delay.Load()); wait > 0 {
			select {
			case <-time.After(wait):
			case <-r.Context().Done():
				return
			}
		}
		if rejectOversizeDoH(w, q, d.maxBody.Load()) {
			return
		}
		if err != nil || len(q) < 12 {
			t.Errorf("bad body: %v", err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if binary.BigEndian.Uint16(q[:2]) != 0 {
			t.Errorf("wire ID = %#x, want 0", binary.BigEndian.Uint16(q[:2]))
		}
		if rejectUnparsableDoH(w, q) {
			return
		}
		if status != http.StatusOK {
			w.WriteHeader(status)
			return
		}
		resp := dnsResponse("example.com", 60, ip)
		resp[0], resp[1] = 0, 0
		w.Header().Set("Content-Type", dohContentType)
		_, _ = w.Write(resp)
	}))
	d.srv.EnableHTTP2 = true
	d.srv.StartTLS()
	t.Cleanup(d.srv.Close)
	return d
}

func (d *dohServer) pool() *x509.CertPool {
	pool := x509.NewCertPool()
	pool.AddCert(d.srv.Certificate())
	return pool
}

func (d *dohServer) url() string { return d.srv.URL + "/dns-query" }

func (d *dohServer) client() *fakeClient {
	addr := d.srv.Listener.Addr().String()
	return &fakeClient{tcp: func(string) (net.Conn, error) { return net.Dial("tcp", addr) }}
}

func TestHTTPSResolver_postsDnsMessage(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	q := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q[:2], 0x5a5a)
	resp, err := r.exchange(context.Background(), q)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x5a5a {
		t.Errorf("txid = %#x, want the caller's 0x5a5a restored", binary.BigEndian.Uint16(resp[:2]))
	}
	if resp[len(resp)-1] != 1 {
		t.Errorf("answer = %v", resp)
	}
	if r.id() != "https|"+d.url() {
		t.Errorf("id = %q", r.id())
	}
}

func TestHTTPSResolver_usesHTTP2AndReusesConnection(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	var dials atomic.Int32
	fc := d.client()
	inner := fc.tcp
	fc.tcp = func(addr string) (net.Conn, error) {
		dials.Add(1)
		return inner(addr)
	}
	r, err := newHTTPSResolver(fc, d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	for i := 0; i < 3; i++ {
		if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
	}
	if d.proto.Load() != 2 {
		t.Errorf("negotiated HTTP/%d, want HTTP/2", d.proto.Load())
	}
	if dials.Load() != 1 {
		t.Errorf("dialed %d times, want 1", dials.Load())
	}
	if d.requests.Load() != 3 {
		t.Errorf("server saw %d requests, want 3", d.requests.Load())
	}
}

func TestHTTPSResolver_non200IsError(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusServiceUnavailable)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err == nil {
		t.Fatal("HTTP 503 must be an error")
	}
}

func TestHTTPSResolver_rejectsUnknownCA(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err == nil {
		t.Fatal("untrusted certificate must fail")
	}
}

func TestNewHTTPSResolver_dialsHostFromURL(t *testing.T) {
	r, err := newHTTPSResolver(&fakeClient{}, "https://dns.google/dns-query", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	if r.dial != "dns.google:443" || r.tlsCfg.ServerName != "dns.google" {
		t.Errorf("dial=%q sni=%q", r.dial, r.tlsCfg.ServerName)
	}
}

func TestHTTPSResolver_bodyIsTheQueryWithAZeroID(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	twoQuestions := dnsQuery("example.com")
	twoQuestions[5] = 2
	cases := []struct {
		name     string
		query    []byte
		rejected bool
	}{
		{"plain", dnsQuery("example.com"), false},
		{"EDNS with DO", withEDNS(dnsQuery("example.com"), 4096, true), false},
		{"padded to 128", withPadding(dnsQuery("example.com"), 128), false},
		{"padded to the limit", withPadding(dnsQuery("example.com"), dohFixtureQueryLimit), false},
		{"padded past the limit", withPadding(dnsQuery("example.com"), dohFixtureQueryLimit+1), false},
		{"padded to 4096", withPadding(dnsQuery("example.com"), 4096), false},
		{"two questions", twoQuestions, true},
		{"1400 bytes of non-DNS", nonDNSPayload(1400), true},
	}
	for _, c := range cases {
		binary.BigEndian.PutUint16(c.query[:2], 0xabcd)
		sent := append([]byte(nil), c.query...)
		_, err := r.exchange(context.Background(), c.query)
		if c.rejected && err == nil {
			t.Errorf("%s: the server's 400 must be an error", c.name)
		}
		if !c.rejected && err != nil {
			t.Fatalf("%s: exchange: %v", c.name, err)
		}
		want := append([]byte(nil), sent...)
		want[0], want[1] = 0, 0
		if got := d.lastBody(); !bytes.Equal(got, want) {
			t.Errorf("%s: body is %d bytes, want the %d-byte query with a zero ID", c.name, len(got), len(sent))
		}
		if !bytes.Equal(c.query, sent) {
			t.Errorf("%s: exchange modified the query it was given", c.name)
		}
	}
	if got := d.requests.Load(); got != int32(len(cases)) {
		t.Errorf("server saw %d requests, want %d", got, len(cases))
	}
}

func TestHTTPSResolver_refusesARuntQueryWithoutARequest(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	for _, runt := range [][]byte{{1, 2, 3}, nonDNSPayload(dnsHeaderLen - 1)} {
		if _, err := r.exchange(context.Background(), runt); !errors.Is(err, errDNSMalformed) {
			t.Errorf("%d bytes: err = %v, want errDNSMalformed", len(runt), err)
		}
	}
	if d.requests.Load() != 0 {
		t.Errorf("server saw %d requests for runt queries", d.requests.Load())
	}
}

func TestDNSUpstream_failsOverPastAnHTTP413(t *testing.T) {
	strict := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	strict.maxBody.Store(dohFixtureQueryLimit)
	lenient := newDoHServer(t, [4]byte{2, 2, 2, 2}, http.StatusOK)
	a, err := newHTTPSResolver(strict.client(), strict.url(), &tls.Config{RootCAs: strict.pool()})
	if err != nil {
		t.Fatal(err)
	}
	b, err := newHTTPSResolver(lenient.client(), lenient.url(), &tls.Config{RootCAs: lenient.pool()})
	if err != nil {
		t.Fatal(err)
	}
	up := &dnsUpstream{resolvers: []dnsResolver{a, b}, ident: "https|strict,lenient"}
	defer up.close()

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	resp, err := up.exchange(ctx, withPadding(dnsQuery("example.com"), 700))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 2 {
		t.Errorf("answer = %v, want the second server's", resp)
	}
	if strict.requests.Load() != 1 || lenient.requests.Load() != 1 {
		t.Errorf("requests strict=%d lenient=%d, want 1 each", strict.requests.Load(), lenient.requests.Load())
	}
	if up.preferred.Load() != 1 {
		t.Errorf("preferred = %d, want 1", up.preferred.Load())
	}
}
