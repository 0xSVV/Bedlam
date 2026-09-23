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
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	coreErrs "github.com/apernet/hysteria/core/v2/errors"
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
	d := newUnstartedDoHServer(t, ip, status)
	d.srv.StartTLS()
	return d
}

func newUnstartedDoHServer(t *testing.T, ip [4]byte, status int) *dohServer {
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

	_, err = r.exchange(context.Background(), dnsQuery("example.com"))
	var statusErr *dohStatusError
	if !errors.As(err, &statusErr) || statusErr.status != http.StatusServiceUnavailable {
		t.Fatalf("err = %v, want an HTTP 503 status error", err)
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
	if up.firstIndex() != 1 {
		t.Errorf("preferred = %d, want 1", up.firstIndex())
	}
}

func TestHTTPSResolver_http413IsAStatusErrorWithTheQuerySize(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	d.maxBody.Store(dohFixtureQueryLimit)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := r.exchange(ctx, withPadding(dnsQuery("example.com"), dohFixtureQueryLimit)); err != nil {
		t.Fatalf("a query at the limit must pass: %v", err)
	}
	_, err = r.exchange(ctx, withPadding(dnsQuery("example.com"), dohFixtureQueryLimit+1))
	var statusErr *dohStatusError
	if !errors.As(err, &statusErr) {
		t.Fatalf("err = %v, want a DoH status error", err)
	}
	if statusErr.status != http.StatusRequestEntityTooLarge || statusErr.queryLen != dohFixtureQueryLimit+1 || statusErr.proto != "HTTP/2.0" {
		t.Errorf("status = %d over %q for %d bytes, want 413 over HTTP/2.0 for %d", statusErr.status, statusErr.proto, statusErr.queryLen, dohFixtureQueryLimit+1)
	}
	var certErr *tls.CertificateVerificationError
	if isTimeoutClass(err) || errors.Is(err, errDNSMalformed) || errors.As(err, &certErr) {
		t.Errorf("HTTP 413 misclassified: %v", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "HTTP 413 over HTTP/2.0 for a 513-byte query") {
		t.Errorf("message %q lacks the status, protocol or query size", msg)
	}
	if strings.Contains(msg, "html") || strings.Contains(msg, "Entity") || strings.Contains(msg, "example") {
		t.Errorf("message %q leaks the response body or the query name", msg)
	}
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("a small query after a 413 must pass: %v", err)
	}
	if d.requests.Load() != 3 {
		t.Errorf("server saw %d requests, want 3", d.requests.Load())
	}
}

func TestHTTPSResolver_malformedQueryIsAStatusError(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err = r.exchange(ctx, nonDNSPayload(300))
	var statusErr *dohStatusError
	if !errors.As(err, &statusErr) || statusErr.status != http.StatusBadRequest || statusErr.queryLen != 300 {
		t.Fatalf("err = %v, want HTTP 400 for a 300-byte query", err)
	}
	if strings.Contains(err.Error(), "Bad Request") {
		t.Errorf("message %q leaks the response body", err)
	}
}

func TestHTTPSResolver_tlsFailureIsNotAStatusError(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	r, err := newHTTPSResolver(d.client(), d.url(), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err = r.exchange(ctx, dnsQuery("example.com"))
	var statusErr *dohStatusError
	var certErr *tls.CertificateVerificationError
	if !errors.As(err, &certErr) || errors.As(err, &statusErr) || isTimeoutClass(err) {
		t.Fatalf("err = %v, want a certificate failure", err)
	}
	if !strings.Contains(err.Error(), "TLS handshake with "+r.dial) {
		t.Errorf("message %q does not name the failing stage", err)
	}
	if !strings.Contains(err.Error(), "request with a 29-byte query") {
		t.Errorf("message %q lacks the query size", err)
	}
	if d.requests.Load() != 0 {
		t.Errorf("server saw %d requests", d.requests.Load())
	}
}

func TestHTTPSResolver_dialFailureNamesTheStage(t *testing.T) {
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		return nil, coreErrs.DialError{Message: "TCP relay refused"}
	}}
	r, err := newHTTPSResolver(fc, "https://dns.test/dns-query", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err = r.exchange(ctx, dnsQuery("example.com"))
	var dialErr coreErrs.DialError
	var statusErr *dohStatusError
	if !errors.As(err, &dialErr) || errors.As(err, &statusErr) || isTimeoutClass(err) {
		t.Fatalf("err = %v, want the tunnel dial error", err)
	}
	if !strings.Contains(err.Error(), "dial dns.test:443") {
		t.Errorf("message %q does not name the failing stage", err)
	}
}

func TestHTTPSResolver_timeoutIsNotAStatusError(t *testing.T) {
	d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	d.delay.Store(int64(2 * time.Second))
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	_, err = r.exchange(ctx, dnsQuery("example.com"))
	var statusErr *dohStatusError
	if !isTimeoutClass(err) || errors.As(err, &statusErr) {
		t.Fatalf("err = %v, want a timeout", err)
	}
}

func TestDNSUpstream_http413EverywhereKeepsTheStatus(t *testing.T) {
	var resolvers []dnsResolver
	var servers []*dohServer
	for i := 0; i < 2; i++ {
		d := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
		d.maxBody.Store(dohFixtureQueryLimit)
		r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
		if err != nil {
			t.Fatal(err)
		}
		servers = append(servers, d)
		resolvers = append(resolvers, r)
	}
	up := &dnsUpstream{resolvers: resolvers, ident: "https|a,b"}
	defer up.close()

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	start := time.Now()
	_, err := up.exchange(ctx, withPadding(dnsQuery("example.com"), 700))
	var statusErr *dohStatusError
	if !errors.As(err, &statusErr) || statusErr.status != http.StatusRequestEntityTooLarge {
		t.Fatalf("err = %v, want HTTP 413", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Errorf("a rejected query took %v", elapsed)
	}
	for i, d := range servers {
		if d.requests.Load() != 1 {
			t.Errorf("server %d saw %d requests, want 1", i, d.requests.Load())
		}
	}
}

type mutingListener struct {
	net.Listener
	mu    sync.Mutex
	conns []*mutableConn
}

type mutableConn struct {
	net.Conn
	muted atomic.Bool
}

func (l *mutingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	mc := &mutableConn{Conn: c}
	l.mu.Lock()
	l.conns = append(l.conns, mc)
	l.mu.Unlock()
	return mc, nil
}

func (l *mutingListener) muteAccepted() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, c := range l.conns {
		c.muted.Store(true)
	}
}

func (c *mutableConn) Read(b []byte) (int, error) {
	for {
		n, err := c.Conn.Read(b)
		if n == 0 || err != nil || !c.muted.Load() {
			return n, err
		}
	}
}

func (c *mutableConn) Write(b []byte) (int, error) {
	if c.muted.Load() {
		return len(b), nil
	}
	return c.Conn.Write(b)
}

type stallingListener struct {
	net.Listener
	stalled atomic.Bool
	resumed chan struct{}
	once    sync.Once
}

type stallingConn struct {
	net.Conn
	l *stallingListener
}

func newStallingListener(l net.Listener) *stallingListener {
	return &stallingListener{Listener: l, resumed: make(chan struct{})}
}

func (l *stallingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return stallingConn{Conn: c, l: l}, nil
}

func (l *stallingListener) resume() { l.once.Do(func() { close(l.resumed) }) }

func (c stallingConn) Write(b []byte) (int, error) {
	if c.l.stalled.Load() {
		<-c.l.resumed
	}
	return c.Conn.Write(b)
}

type lateCloseConn struct {
	net.Conn
}

func (c lateCloseConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if err != nil {
		time.Sleep(50 * time.Millisecond)
	}
	return n, err
}

func TestDNSUpstream_loneDoHServerRedialsOnceItsConnectionGoesSilent(t *testing.T) {
	d := newUnstartedDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	ln := &mutingListener{Listener: d.srv.Listener}
	d.srv.Listener = ln
	d.srv.StartTLS()
	var dials atomic.Int32
	fc := d.client()
	inner := fc.tcp
	fc.tcp = func(addr string) (net.Conn, error) {
		dials.Add(1)
		c, err := inner(addr)
		if err != nil {
			return nil, err
		}
		return lateCloseConn{c}, nil
	}
	r, err := newHTTPSResolver(fc, d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	up := &dnsUpstream{resolvers: []dnsResolver{r}, ident: r.id()}
	defer up.close()
	if _, err := up.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("warm-up: %v", err)
	}
	ln.muteAccepted()
	waiting := make(chan struct{})
	go func() {
		defer close(waiting)
		ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
		defer cancel()
		_, _ = up.exchange(ctx, dnsQuery("example.com"))
	}()
	defer func() { <-waiting }()

	failed := 0
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		_, err := up.exchange(ctx, dnsQuery("example.com"))
		cancel()
		if err == nil {
			break
		}
		if failed++; failed == 4 {
			t.Fatalf("%d lookups in a row failed after the connection went silent, with %d dials; last: %v", failed, dials.Load(), err)
		}
		if !isTimeoutClass(err) {
			t.Errorf("lookup %d: err = %v, want a timeout", failed, err)
		}
	}
	if failed != 1 {
		t.Errorf("%d lookups failed, want only the one that found the connection silent", failed)
	}
	if got := dials.Load(); got != 2 {
		t.Errorf("dialed %d times, want the warm-up connection and one redial", got)
	}
}

func TestHTTPSResolver_keepsALiveConnectionWhenOneQueryTimesOut(t *testing.T) {
	held := make(chan struct{}, 2)
	release := make(chan struct{})
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		q, _ := io.ReadAll(req.Body)
		if name, _ := dnsQuestion(q); strings.Contains(name, "slow") {
			held <- struct{}{}
			select {
			case <-release:
			case <-req.Context().Done():
				return
			}
		}
		w.Header().Set("Content-Type", dohContentType)
		_, _ = w.Write(dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1}))
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	t.Cleanup(srv.Close)
	var dials atomic.Int32
	addr := srv.Listener.Addr().String()
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		dials.Add(1)
		return net.Dial("tcp", addr)
	}}
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	r, err := newHTTPSResolver(fc, srv.URL+"/dns-query", &tls.Config{RootCAs: pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	if _, err := r.exchange(context.Background(), dnsQuery("warm.example")); err != nil {
		t.Fatalf("warm-up: %v", err)
	}

	pending := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err := r.exchange(ctx, dnsQuery("slow.example"))
		pending <- err
	}()
	<-held
	expiring := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_, err := r.exchange(ctx, dnsQuery("slower.example"))
		expiring <- err
	}()
	<-held
	if _, err := r.exchange(context.Background(), dnsQuery("fast.example")); err != nil {
		t.Fatalf("a query the server answers at once: %v", err)
	}
	if err := <-expiring; !isTimeoutClass(err) {
		t.Fatalf("err = %v, want the held query to time out", err)
	}
	close(release)
	if err := <-pending; err != nil {
		t.Errorf("a query in flight on the connection that kept answering failed: %v", err)
	}
	if got := dials.Load(); got != 1 {
		t.Errorf("dialed %d times, want the connection kept", got)
	}
}

func TestHTTPSResolver_retiredConnectionFinishesTheQueriesStillOnIt(t *testing.T) {
	held := make(chan struct{}, 1)
	arrived := make(chan struct{}, 1)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		q, _ := io.ReadAll(req.Body)
		name, _ := dnsQuestion(q)
		switch {
		case strings.Contains(name, "slow"):
			held <- struct{}{}
			<-req.Context().Done()
			return
		case strings.Contains(name, "late"):
			arrived <- struct{}{}
		}
		w.Header().Set("Content-Type", dohContentType)
		_, _ = w.Write(dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1}))
	}))
	ln := newStallingListener(srv.Listener)
	srv.Listener = ln
	srv.EnableHTTP2 = true
	srv.StartTLS()
	t.Cleanup(srv.Close)
	t.Cleanup(ln.resume)
	var dials atomic.Int32
	addr := srv.Listener.Addr().String()
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		dials.Add(1)
		return net.Dial("tcp", addr)
	}}
	pool := x509.NewCertPool()
	pool.AddCert(srv.Certificate())
	r, err := newHTTPSResolver(fc, srv.URL+"/dns-query", &tls.Config{RootCAs: pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	if _, err := r.exchange(context.Background(), dnsQuery("warm.example")); err != nil {
		t.Fatalf("warm-up: %v", err)
	}
	ln.stalled.Store(true)

	expired := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_, err := r.exchange(ctx, dnsQuery("slow.example"))
		expired <- err
	}()
	<-held
	answered := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err := r.exchange(ctx, dnsQuery("late.example"))
		answered <- err
	}()
	<-arrived
	if err := <-expired; !isTimeoutClass(err) {
		t.Fatalf("err = %v, want the query the server sat on to time out", err)
	}
	ln.resume()
	if err := <-answered; err != nil {
		t.Errorf("a query sent before the connection was retired failed although the server answered it: %v", err)
	}
	if _, err := r.exchange(context.Background(), dnsQuery("next.example")); err != nil {
		t.Fatalf("a lookup after the retire: %v", err)
	}
	if got := dials.Load(); got != 2 {
		t.Errorf("dialed %d times, want the connection that went quiet replaced once", got)
	}
}
