package golib

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

type dohServer struct {
	srv      *httptest.Server
	requests atomic.Int32
	proto    atomic.Int32
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
		if err != nil || len(q) < 12 {
			t.Errorf("bad body: %v", err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if binary.BigEndian.Uint16(q[:2]) != 0 {
			t.Errorf("wire ID = %#x, want 0", binary.BigEndian.Uint16(q[:2]))
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
