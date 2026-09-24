package golib

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/http"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type stubResolver struct {
	name  string
	calls atomic.Int32
	reply func(query []byte) ([]byte, error)
}

func (s *stubResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	s.calls.Add(1)
	type result struct {
		resp []byte
		err  error
	}
	done := make(chan result, 1)
	go func() {
		resp, err := s.reply(query)
		done <- result{resp, err}
	}()
	select {
	case r := <-done:
		return r.resp, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (s *stubResolver) id() string { return s.name }

func (s *stubResolver) close() {}

func echoAnswer(ip [4]byte) func(query []byte) ([]byte, error) {
	return func(query []byte) ([]byte, error) {
		resp := dnsResponse("example.com", 60, ip)
		copy(resp[:2], query[:2])
		return resp, nil
	}
}

// dnsQuery builds a minimal A-record query for the given name.
func dnsQuery(name string) []byte {
	buf := []byte{
		0x12, 0x34, // txID
		0x01, 0x00, // flags: standard query, RD=1
		0x00, 0x01, // qdCount = 1
		0x00, 0x00, // anCount
		0x00, 0x00, // nsCount
		0x00, 0x00, // arCount
	}
	for _, label := range splitName(name) {
		buf = append(buf, byte(len(label)))
		buf = append(buf, []byte(label)...)
	}
	buf = append(buf, 0x00)       // root label terminator
	buf = append(buf, 0x00, 0x01) // TYPE = A
	buf = append(buf, 0x00, 0x01) // CLASS = IN
	return buf
}

func splitName(name string) []string {
	out := []string{}
	start := 0
	for i := 0; i < len(name); i++ {
		if name[i] == '.' {
			out = append(out, name[start:i])
			start = i + 1
		}
	}
	if start < len(name) {
		out = append(out, name[start:])
	}
	return out
}

// dnsResponse builds an A-record response echoing the query name, with the
// given TTL on the single answer.
func dnsResponse(name string, ttl uint32, ip [4]byte) []byte {
	// header
	buf := []byte{
		0x12, 0x34, // txID
		0x81, 0x80, // flags: response, RD, RA, rcode=0
		0x00, 0x01, // qdCount
		0x00, 0x01, // anCount
		0x00, 0x00, // nsCount
		0x00, 0x00, // arCount
	}
	// question section
	for _, label := range splitName(name) {
		buf = append(buf, byte(len(label)))
		buf = append(buf, []byte(label)...)
	}
	buf = append(buf, 0x00, 0x00, 0x01, 0x00, 0x01)
	// answer section: pointer to question name at offset 12
	buf = append(buf, 0xc0, 0x0c)
	buf = append(buf, 0x00, 0x01) // TYPE = A
	buf = append(buf, 0x00, 0x01) // CLASS = IN
	ttlBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(ttlBytes, ttl)
	buf = append(buf, ttlBytes...)
	buf = append(buf, 0x00, 0x04) // RDLENGTH
	buf = append(buf, ip[:]...)
	return buf
}

func dnsResponseFor(query []byte, ttl uint32, ip [4]byte) []byte {
	end := skipName(query, 12)
	if end < 0 || end+4 > len(query) {
		panic("dnsResponseFor: unparsable query")
	}
	resp := make([]byte, 0, end+4+16)
	resp = append(resp, query[:end+4]...)
	resp[2] = 0x81
	resp[3] = 0x80
	binary.BigEndian.PutUint16(resp[6:8], 1)
	resp = append(resp, 0xc0, 0x0c)
	resp = append(resp, 0x00, 0x01, 0x00, 0x01)
	ttlBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(ttlBytes, ttl)
	resp = append(resp, ttlBytes...)
	resp = append(resp, 0x00, 0x04)
	return append(resp, ip[:]...)
}

func TestParseDNSQuery_validQuery(t *testing.T) {
	q := dnsQuery("example.com")
	txID, key, ok := parseDNSQuery(q)
	if !ok {
		t.Fatal("expected ok")
	}
	if txID != 0x1234 {
		t.Errorf("txID = %#x, want 0x1234", txID)
	}
	if key == "" {
		t.Error("key should be non-empty")
	}
}

func TestParseDNSQuery_distinguishesNames(t *testing.T) {
	_, keyA, _ := parseDNSQuery(dnsQuery("a.example"))
	_, keyB, _ := parseDNSQuery(dnsQuery("b.example"))
	if keyA == keyB {
		t.Error("different names produced equal cache keys")
	}
}

func TestParseDNSQuery_tooShort(t *testing.T) {
	if _, _, ok := parseDNSQuery([]byte{0x12, 0x34}); ok {
		t.Error("expected !ok for too-short query")
	}
}

func TestParseDNSQuery_rejectsMultiQuestion(t *testing.T) {
	q := dnsQuery("example.com")
	// flip qdCount to 2
	q[4] = 0x00
	q[5] = 0x02
	if _, _, ok := parseDNSQuery(q); ok {
		t.Error("expected !ok for qdCount=2")
	}
}

func TestCacheableTTL_happyPath(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	d := cacheableTTL(resp)
	if d != 60*time.Second {
		t.Errorf("ttl = %v, want %v", d, 60*time.Second)
	}
}

func TestCacheableTTL_clampedToMin(t *testing.T) {
	resp := dnsResponse("example.com", 1, [4]byte{1, 2, 3, 4})
	d := cacheableTTL(resp)
	if d != dnsCacheMinTTL {
		t.Errorf("ttl = %v, want clamp to %v", d, dnsCacheMinTTL)
	}
}

func TestCacheableTTL_clampedToMax(t *testing.T) {
	resp := dnsResponse("example.com", 7*24*3600, [4]byte{1, 2, 3, 4})
	d := cacheableTTL(resp)
	if d != dnsCacheMaxTTL {
		t.Errorf("ttl = %v, want clamp to %v", d, dnsCacheMaxTTL)
	}
}

func TestCacheableTTL_rejectsFailureRcodes(t *testing.T) {
	for _, rcode := range []byte{1, 2, 4, 5, 9} {
		resp := negativeResponse("example.com", rcode, 600, 600)
		if got := cacheableTTL(resp); got != 0 {
			t.Errorf("rcode %d: ttl = %v, want 0", rcode, got)
		}
		positive := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
		positive[3] = 0x80 | rcode
		if got := cacheableTTL(positive); got != 0 {
			t.Errorf("rcode %d with an answer: ttl = %v, want 0", rcode, got)
		}
	}
}

func TestCacheableTTL_rejectsZeroAnswersWithoutSOA(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	resp[6] = 0
	resp[7] = 0
	if got := cacheableTTL(resp); got != 0 {
		t.Errorf("ttl = %v, want 0 (no answers)", got)
	}
}

func negativeResponse(name string, rcode byte, soaTTL, minimum uint32) []byte {
	return negativeResponseWithAuthority(name, rcode, 6, soaTTL, minimum)
}

func negativeResponseWithAuthority(name string, rcode byte, authorityType uint16, ttl, minimum uint32) []byte {
	resp := dnsQuery(name)
	resp[2] = 0x81
	resp[3] = 0x80 | rcode
	binary.BigEndian.PutUint16(resp[8:10], 1)
	resp = append(resp, 0xc0, 0x0c)
	resp = binary.BigEndian.AppendUint16(resp, authorityType)
	resp = append(resp, 0x00, 0x01)
	resp = binary.BigEndian.AppendUint32(resp, ttl)
	rdata := []byte{0x02, 'n', 's', 0xc0, 0x0c}
	if authorityType == 6 {
		rdata = append(rdata, 0x0a)
		rdata = append(rdata, "hostmaster"...)
		rdata = append(rdata, 0xc0, 0x0c)
		for _, v := range []uint32{2026092401, 7200, 3600, 1209600, minimum} {
			rdata = binary.BigEndian.AppendUint32(rdata, v)
		}
	}
	resp = binary.BigEndian.AppendUint16(resp, uint16(len(rdata)))
	return append(resp, rdata...)
}

func nxdomainAfterCNAME(name string, cnameTTL, soaTTL, minimum uint32) []byte {
	resp := dnsQuery(name)
	resp[2] = 0x81
	resp[3] = 0x83
	binary.BigEndian.PutUint16(resp[6:8], 1)
	binary.BigEndian.PutUint16(resp[8:10], 1)
	resp = append(resp, 0xc0, 0x0c, 0x00, 0x05, 0x00, 0x01)
	resp = binary.BigEndian.AppendUint32(resp, cnameTTL)
	resp = append(resp, 0x00, 0x05, 0x02, 'g', 'o', 0xc0, 0x0c)
	soa := negativeResponse(name, 3, soaTTL, minimum)
	return append(resp, soa[len(dnsQuery(name)):]...)
}

func TestCacheableTTL_negativeAnswers(t *testing.T) {
	cases := []struct {
		name string
		resp []byte
		want time.Duration
	}{
		{"NXDOMAIN takes the SOA MINIMUM", negativeResponse("nope.example.com", 3, 900, 60), 60 * time.Second},
		{"NXDOMAIN takes the SOA TTL", negativeResponse("nope.example.com", 3, 30, 600), 30 * time.Second},
		{"NODATA takes the SOA MINIMUM", negativeResponse("example.com", 0, 900, 120), 120 * time.Second},
		{"NODATA takes the SOA TTL", negativeResponse("example.com", 0, 45, 900), 45 * time.Second},
		{"NXDOMAIN is capped", negativeResponse("nope.example.com", 3, 86400, 3600), dnsCacheNegativeMaxTTL},
		{"NODATA is capped", negativeResponse("example.com", 0, 3600, 86400), dnsCacheNegativeMaxTTL},
		{"a short negative TTL is kept", negativeResponse("nope.example.com", 3, 2, 600), 2 * time.Second},
		{"NXDOMAIN after a CNAME ends with the CNAME", nxdomainAfterCNAME("www.example.com", 20, 600, 600), 20 * time.Second},
		{"a zero SOA TTL is not cached", negativeResponse("nope.example.com", 3, 0, 600), 0},
		{"a zero SOA MINIMUM is not cached", negativeResponse("example.com", 0, 600, 0), 0},
		{"NXDOMAIN without an SOA is not cached", negativeResponseWithAuthority("nope.example.com", 3, 2, 600, 0), 0},
		{"NODATA without an SOA is not cached", negativeResponseWithAuthority("example.com", 0, 2, 600, 0), 0},
	}
	for _, tc := range cases {
		if got := cacheableTTL(tc.resp); got != tc.want {
			t.Errorf("%s: ttl = %v, want %v", tc.name, got, tc.want)
		}
	}
}

func TestCacheableTTL_rejectsTruncatedNegativeAnswers(t *testing.T) {
	for _, rcode := range []byte{0, 3} {
		resp := negativeResponse("nope.example.com", rcode, 600, 600)
		resp[2] |= 0x02
		if got := cacheableTTL(resp); got != 0 {
			t.Errorf("rcode %d: ttl = %v, want 0 for a truncated answer", rcode, got)
		}
	}
}

func TestDNSCacheResolve_servesNXDOMAINFromTheCache(t *testing.T) {
	c := newDNSCache()
	r := &stubResolver{name: "stub"}
	r.reply = func(query []byte) ([]byte, error) {
		resp := negativeResponse("nope.example.com", 3, 600, 600)
		copy(resp[:2], query[:2])
		return resp, nil
	}
	for i := 0; i < 2; i++ {
		resp, err := c.resolve(context.Background(), r, dnsQuery("nope.example.com"), nil)
		if err != nil {
			t.Fatal(err)
		}
		if resp[3]&0x0f != 3 {
			t.Fatalf("rcode = %d, want NXDOMAIN", resp[3]&0x0f)
		}
	}
	if n := r.calls.Load(); n != 1 {
		t.Errorf("resolver calls = %d, want 1", n)
	}
}

func TestDNSCacheResolve_neverCachesServfail(t *testing.T) {
	c := newDNSCache()
	r := &stubResolver{name: "stub"}
	r.reply = func(query []byte) ([]byte, error) {
		resp := negativeResponse("example.com", 2, 600, 600)
		copy(resp[:2], query[:2])
		return resp, nil
	}
	for i := 0; i < 2; i++ {
		if _, err := c.resolve(context.Background(), r, dnsQuery("example.com"), nil); err != nil {
			t.Fatal(err)
		}
	}
	if n := r.calls.Load(); n != 2 {
		t.Errorf("resolver calls = %d, want 2", n)
	}
}

func TestCacheableTTL_rejectsBadQdCount(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	resp[4] = 0
	resp[5] = 0 // qdCount=0
	if got := cacheableTTL(resp); got != 0 {
		t.Errorf("ttl = %v, want 0 for qdCount=0", got)
	}
}

func TestCacheableTTL_rejectsTooShort(t *testing.T) {
	if got := cacheableTTL([]byte{1, 2}); got != 0 {
		t.Errorf("ttl = %v, want 0 for short response", got)
	}
}

func TestSkipName_terminator(t *testing.T) {
	// "a\0" — single label "a"
	data := []byte{0x01, 'a', 0x00}
	if got := skipName(data, 0); got != 3 {
		t.Errorf("skipName = %d, want 3", got)
	}
}

func TestSkipName_compressionPointerTerminates(t *testing.T) {
	// One label, then a compression pointer.
	data := []byte{0x01, 'a', 0xc0, 0x10}
	if got := skipName(data, 0); got != 4 {
		t.Errorf("skipName = %d, want 4", got)
	}
}

func TestSkipName_truncatedReturnsNegative(t *testing.T) {
	if got := skipName([]byte{0x05, 'a'}, 0); got >= 0 {
		t.Errorf("expected negative, got %d", got)
	}
}

func TestCacheableTTL_rejectsTruncated(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	resp[2] |= 0x02
	if got := cacheableTTL(resp); got != 0 {
		t.Errorf("ttl = %v, want 0 for a truncated answer", got)
	}
}

func TestParseDNSQuery_isCaseInsensitive(t *testing.T) {
	_, lower, ok := parseDNSQuery(dnsQuery("google.com"))
	if !ok {
		t.Fatal("expected ok")
	}
	_, mixed, ok := parseDNSQuery(dnsQuery("GooGLE.com"))
	if !ok {
		t.Fatal("expected ok")
	}
	if lower != mixed {
		t.Error("0x20-randomised names must share a cache entry")
	}
}

func TestParseDNSQuery_separatesEdnsClients(t *testing.T) {
	plain := dnsQuery("example.com")
	_, plainKey, _ := parseDNSQuery(plain)
	_, smallKey, _ := parseDNSQuery(withEDNS(plain, 512, false))
	_, bigKey, _ := parseDNSQuery(withEDNS(plain, 4096, false))
	_, dnssecKey, _ := parseDNSQuery(withEDNS(plain, 4096, true))

	keys := map[string]string{
		"no EDNS":   plainKey,
		"512":       smallKey,
		"4096":      bigKey,
		"4096 + DO": dnssecKey,
	}
	seen := map[string]string{}
	for name, key := range keys {
		if other, dup := seen[key]; dup {
			t.Errorf("%s and %s share a cache key", name, other)
		}
		seen[key] = name
	}
}

func TestParseDNSQuery_separatesCheckingDisabled(t *testing.T) {
	q := dnsQuery("example.com")
	_, plain, _ := parseDNSQuery(q)
	cd := append([]byte(nil), q...)
	cd[3] |= 0x10
	_, withCD, _ := parseDNSQuery(cd)
	if plain == withCD {
		t.Error("CD=1 must not share a cache entry with CD=0")
	}
}

func withEDNS(query []byte, udpSize uint16, do bool) []byte {
	out := append([]byte(nil), query...)
	binary.BigEndian.PutUint16(out[10:12], 1) // arCount
	ttl := uint32(0)
	if do {
		ttl |= 0x8000
	}
	opt := []byte{0x00}
	opt = binary.BigEndian.AppendUint16(opt, 41)
	opt = binary.BigEndian.AppendUint16(opt, udpSize)
	opt = binary.BigEndian.AppendUint32(opt, ttl)
	opt = binary.BigEndian.AppendUint16(opt, 0)
	return append(out, opt...)
}

func withPadding(query []byte, total int) []byte {
	pad := total - len(query) - 15
	if pad < 0 {
		panic("withPadding: total is smaller than the query")
	}
	out := append([]byte(nil), query...)
	binary.BigEndian.PutUint16(out[10:12], binary.BigEndian.Uint16(out[10:12])+1)
	out = append(out, 0x00)
	out = binary.BigEndian.AppendUint16(out, 41)
	out = binary.BigEndian.AppendUint16(out, 1232)
	out = binary.BigEndian.AppendUint32(out, 0)
	out = binary.BigEndian.AppendUint16(out, uint16(4+pad))
	out = binary.BigEndian.AppendUint16(out, 12)
	out = binary.BigEndian.AppendUint16(out, uint16(pad))
	return append(out, make([]byte, pad)...)
}

func nonDNSPayload(n int) []byte {
	out := make([]byte, n)
	for i := range out {
		out[i] = byte(i*131 + 7)
	}
	return out
}

func TestDNSCacheResolve_forwardsValidQueriesUnchanged(t *testing.T) {
	c := newDNSCache()
	var mu sync.Mutex
	var seen [][]byte
	r := &stubResolver{name: "https|fixture", reply: func(q []byte) ([]byte, error) {
		mu.Lock()
		seen = append(seen, append([]byte(nil), q...))
		mu.Unlock()
		resp := dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
		resp[10], resp[11] = 0, 0
		return resp, nil
	}}
	queries := [][]byte{
		withEDNS(dnsQuery("a.example"), 512, false),
		withEDNS(dnsQuery("b.example"), 4096, true),
		withPadding(dnsQuery("c.example"), 128),
		withPadding(dnsQuery("d.example"), 1400),
		append(dnsQuery("e.example"), 0, 0, 0, 0),
	}
	for _, q := range queries {
		if _, err := c.resolve(context.Background(), r, q, nil); err != nil {
			t.Fatalf("%d-byte query: %v", len(q), err)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != len(queries) {
		t.Fatalf("resolver saw %d queries, want %d", len(seen), len(queries))
	}
	for i, q := range queries {
		if !bytes.Equal(seen[i], q) {
			t.Errorf("query %d reached the resolver altered", i)
		}
	}
}

func TestValidDNSQuery_acceptsQueries(t *testing.T) {
	checkingDisabled := dnsQuery("example.com")
	checkingDisabled[3] |= 0x10
	cases := []struct {
		name  string
		query []byte
	}{
		{"plain", dnsQuery("example.com")},
		{"mixed case", dnsQuery("ExAmPlE.CoM")},
		{"checking disabled", checkingDisabled},
		{"EDNS with DO", withEDNS(dnsQuery("example.com"), 1232, true)},
		{"padded to 128", withPadding(dnsQuery("example.com"), 128)},
		{"padded to 4096", withPadding(dnsQuery("example.com"), 4096)},
		{"trailing bytes", append(dnsQuery("example.com"), 0, 0, 0, 0)},
	}
	for _, c := range cases {
		if !validDNSQuery(c.query) {
			t.Errorf("%s: rejected", c.name)
		}
	}
}

func TestValidDNSQuery_rejectsNonQueries(t *testing.T) {
	q := dnsQuery("example.com")
	mutate := func(f func([]byte) []byte) []byte { return f(append([]byte(nil), q...)) }
	cases := []struct {
		name    string
		payload []byte
	}{
		{"empty", nil},
		{"shorter than a header", q[:11]},
		{"header only", q[:12]},
		{"no question", mutate(func(b []byte) []byte { b[5] = 0; return b })},
		{"two questions", mutate(func(b []byte) []byte { b[5] = 2; return b })},
		{"cut question", q[:len(q)-1]},
		{"reserved label type", mutate(func(b []byte) []byte { b[12] = 0x40; return b })},
		{"response", dnsResponse("example.com", 60, [4]byte{1, 1, 1, 1})},
		{"missing additional record", mutate(func(b []byte) []byte { b[11] = 1; return b })},
		{"record overruns the payload", mutate(func(b []byte) []byte {
			e := withEDNS(b, 1232, false)
			e[len(e)-2], e[len(e)-1] = 0xff, 0xff
			return e
		})},
		{"garbage record counts", mutate(func(b []byte) []byte { b[6], b[7] = 0x12, 0x34; return b })},
		{"148 bytes of non-DNS", nonDNSPayload(148)},
		{"1400 bytes of non-DNS", nonDNSPayload(1400)},
	}
	for _, c := range cases {
		if validDNSQuery(c.payload) {
			t.Errorf("%s: accepted", c.name)
		}
	}
}

func TestDNSCacheResolve_refusesNonDNSPayloads(t *testing.T) {
	c := newDNSCache()
	r := &stubResolver{name: "tcp|1.1.1.1:53", reply: echoAnswer([4]byte{1, 1, 1, 1})}
	twoQuestions := dnsQuery("example.com")
	twoQuestions[5] = 2
	var tunnelled int
	count := func(tx, rx int) { tunnelled += tx + rx }

	for _, payload := range [][]byte{nonDNSPayload(1400), nonDNSPayload(8), twoQuestions} {
		_, err := c.resolve(context.Background(), r, payload, count)
		if !errors.Is(err, errDNSQueryInvalid) {
			t.Errorf("%d bytes: err = %v, want errDNSQueryInvalid", len(payload), err)
		}
		if err != nil && !strings.Contains(err.Error(), fmt.Sprintf("(%d bytes)", len(payload))) {
			t.Errorf("err %q lacks the payload size", err)
		}
	}
	if r.calls.Load() != 0 {
		t.Errorf("resolver called %d times for payloads that are not DNS queries", r.calls.Load())
	}
	if tunnelled != 0 {
		t.Errorf("counted %d tunnel bytes for refused payloads", tunnelled)
	}
}

func TestDNSCacheResolve_nonDNSPayloadReachesNoTransport(t *testing.T) {
	answerQueries := func(received *atomic.Int32) func(q []byte) []byte {
		return func(q []byte) []byte {
			received.Add(1)
			if _, ok := dnsQuestion(q); !ok {
				return nil
			}
			resp := dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
			resp[10], resp[11] = 0, 0
			return resp
		}
	}

	var tcpFrames atomic.Int32
	tcp := newTCPResolver(&fakeClient{tcp: func(string) (net.Conn, error) {
		return pipeDNSServer(t, answerQueries(&tcpFrames)), nil
	}}, "192.0.2.53:53")

	var udpFallbackFrames atomic.Int32
	udpWithoutRelay := newUDPResolver(&fakeClient{tcp: func(string) (net.Conn, error) {
		return pipeDNSServer(t, answerQueries(&udpFallbackFrames)), nil
	}}, "192.0.2.53:53")

	cert, pool := testCert(t)
	var dotFrames atomic.Int32
	dotAnswer := answerQueries(&dotFrames)
	dotDial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return dotAnswer(q) })
	dot := newTLSResolver(&fakeClient{tcp: func(string) (net.Conn, error) { return dotDial() }}, "dns.test:853", &tls.Config{RootCAs: pool})

	dohFixture := newDoHServer(t, [4]byte{1, 1, 1, 1}, http.StatusOK)
	dohFixture.maxBody.Store(dohFixtureQueryLimit)
	doh, err := newHTTPSResolver(dohFixture.client(), dohFixture.url(), &tls.Config{RootCAs: dohFixture.pool()})
	if err != nil {
		t.Fatal(err)
	}

	doh3Fixture := newDoH3Server(t, [4]byte{1, 1, 1, 1})
	doh3Fixture.maxBody.Store(dohFixtureQueryLimit)
	doh3Client, _ := doh3Fixture.client(t)
	doh3, err := newH3Resolver(doh3Client, doh3Fixture.url(), &tls.Config{RootCAs: doh3Fixture.pool})
	if err != nil {
		t.Fatal(err)
	}

	transports := []struct {
		resolver dnsResolver
		received func() int32
	}{
		{tcp, tcpFrames.Load},
		{udpWithoutRelay, udpFallbackFrames.Load},
		{dot, dotFrames.Load},
		{doh, dohFixture.requests.Load},
		{doh3, doh3Fixture.requests.Load},
	}
	for _, tr := range transports {
		t.Cleanup(tr.resolver.close)
		c := newDNSCache()
		for _, junk := range [][]byte{nonDNSPayload(300), nonDNSPayload(1400)} {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			_, err := c.resolve(ctx, tr.resolver, junk, nil)
			cancel()
			if !errors.Is(err, errDNSQueryInvalid) {
				t.Errorf("%s: %d bytes of non-DNS: err = %v, want errDNSQueryInvalid", tr.resolver.id(), len(junk), err)
			}
		}
		if n := tr.received(); n != 0 {
			t.Errorf("%s: the upstream received %d non-DNS payloads", tr.resolver.id(), n)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, err := c.resolve(ctx, tr.resolver, dnsQuery("example.com"), nil)
		cancel()
		if err != nil {
			t.Errorf("%s: a real query after the refusals: %v", tr.resolver.id(), err)
		}
	}
}

func TestEdnsOptions(t *testing.T) {
	if _, _, ok := ednsOptions(dnsQuery("example.com")); ok {
		t.Error("a query without an OPT record must report none")
	}
	size, do, ok := ednsOptions(withEDNS(dnsQuery("example.com"), 4096, true))
	if !ok || size != 4096 || !do {
		t.Errorf("ednsOptions = %d, %v, %v", size, do, ok)
	}
}

func TestDNSCacheStoreLookup(t *testing.T) {
	c := newDNSCache()
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	c.store("k", resp, 30*time.Second)
	got := c.lookup("k", 0xabcd)
	if got == nil {
		t.Fatal("expected hit")
	}
	if binary.BigEndian.Uint16(got[:2]) != 0xabcd {
		t.Errorf("txID not rewritten on lookup")
	}
}

func TestDNSCacheLRUEvicts(t *testing.T) {
	c := newDNSCache()
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	// Fill beyond capacity.
	for i := 0; i < dnsCacheMaxEntries+5; i++ {
		c.store(string(rune(i)), resp, time.Minute)
	}
	c.mu.RLock()
	size := len(c.entries)
	c.mu.RUnlock()
	if size > dnsCacheMaxEntries {
		t.Errorf("cache size %d exceeds max %d", size, dnsCacheMaxEntries)
	}
}

func TestDNSCacheResolve_usesResolverAndKeysById(t *testing.T) {
	c := newDNSCache()
	a := &stubResolver{name: "tcp|1.1.1.1:53", reply: echoAnswer([4]byte{1, 1, 1, 1})}
	b := &stubResolver{name: "tls|1.1.1.1:853", reply: echoAnswer([4]byte{2, 2, 2, 2})}
	q := dnsQuery("example.com")

	first, err := c.resolve(context.Background(), a, q, nil)
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	second, err := c.resolve(context.Background(), a, q, nil)
	if err != nil {
		t.Fatalf("resolve (cached): %v", err)
	}
	if a.calls.Load() != 1 {
		t.Errorf("resolver a called %d times, want 1 (second call served from cache)", a.calls.Load())
	}
	if first[len(first)-1] != 1 || second[len(second)-1] != 1 {
		t.Errorf("cached answer changed: %v vs %v", first, second)
	}

	other, err := c.resolve(context.Background(), b, q, nil)
	if err != nil {
		t.Fatalf("resolve via b: %v", err)
	}
	if b.calls.Load() != 1 {
		t.Errorf("resolver b called %d times, want 1 (different id must not share cache)", b.calls.Load())
	}
	if other[len(other)-1] != 2 {
		t.Errorf("answer from b = %v, want 2.2.2.2", other)
	}
}

func TestDNSCacheResolve_rewritesTxid(t *testing.T) {
	c := newDNSCache()
	r := &stubResolver{name: "tcp|1.1.1.1:53", reply: echoAnswer([4]byte{1, 1, 1, 1})}
	q := dnsQuery("example.com")
	if _, err := c.resolve(context.Background(), r, q, nil); err != nil {
		t.Fatalf("resolve: %v", err)
	}
	q2 := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q2[:2], 0x4242)
	resp, err := c.resolve(context.Background(), r, q2, nil)
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x4242 {
		t.Errorf("txid = %#x, want 0x4242", binary.BigEndian.Uint16(resp[:2]))
	}
}

type lateAnswerResolver struct {
	name  string
	calls atomic.Int32
	hooks chan func([]byte)
}

func (r *lateAnswerResolver) exchange(ctx context.Context, _ []byte) ([]byte, error) {
	r.calls.Add(1)
	r.hooks <- lateAnswer(ctx)
	return nil, context.DeadlineExceeded
}

func (r *lateAnswerResolver) id() string { return r.name }

func (r *lateAnswerResolver) close() {}

func TestDNSCacheResolve_servesTheRetryFromALateAnswer(t *testing.T) {
	c := newDNSCache()
	r := &lateAnswerResolver{name: "tls|late", hooks: make(chan func([]byte), 1)}
	q := dnsQuery("example.com")
	if _, err := c.resolve(context.Background(), r, q, nil); err == nil {
		t.Fatal("the resolver gave no answer in time, so the query must fail")
	}
	(<-r.hooks)(dnsResponseFor(q, 60, [4]byte{9, 9, 9, 9}))

	retry := dnsQuery("example.com")
	binary.BigEndian.PutUint16(retry[:2], 0x7777)
	resp, err := c.resolve(context.Background(), r, retry, nil)
	if err != nil {
		t.Fatalf("the retry: %v", err)
	}
	if r.calls.Load() != 1 {
		t.Errorf("resolver called %d times, want the retry answered from the late answer in the cache", r.calls.Load())
	}
	if resp[len(resp)-1] != 9 || binary.BigEndian.Uint16(resp[:2]) != 0x7777 {
		t.Errorf("retry answer = %v, want the late answer under the retry's ID", resp)
	}
}

func TestDNSCacheResolve_dropsALateAnswerTheCacheCannotKeep(t *testing.T) {
	c := newDNSCache()
	r := &lateAnswerResolver{name: "tls|late", hooks: make(chan func([]byte), 1)}
	q := dnsQuery("example.com")
	if _, err := c.resolve(context.Background(), r, q, nil); err == nil {
		t.Fatal("the resolver gave no answer in time, so the query must fail")
	}
	truncated := dnsResponseFor(q, 60, [4]byte{9, 9, 9, 9})
	truncated[2] |= 0x02
	(<-r.hooks)(truncated)

	if _, err := c.resolve(context.Background(), r, dnsQuery("example.com"), nil); err == nil {
		t.Fatal("the retry must go to the resolver and fail again")
	}
	if r.calls.Load() != 2 {
		t.Errorf("resolver called %d times, want a truncated late answer kept out of the cache", r.calls.Load())
	}
}

func TestDNSCacheResolve_singleflight(t *testing.T) {
	c := newDNSCache()
	release := make(chan struct{})
	r := &stubResolver{name: "tcp|1.1.1.1:53"}
	r.reply = func(query []byte) ([]byte, error) {
		<-release
		return echoAnswer([4]byte{1, 1, 1, 1})(query)
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := c.resolve(context.Background(), r, dnsQuery("example.com"), nil); err != nil {
				t.Errorf("resolve: %v", err)
			}
		}()
	}
	time.Sleep(20 * time.Millisecond)
	close(release)
	wg.Wait()
	if r.calls.Load() != 1 {
		t.Errorf("resolver called %d times, want 1", r.calls.Load())
	}
}

func TestDNSCacheLookupRespectsExpiry(t *testing.T) {
	c := newDNSCache()
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	c.store("k", resp, 1*time.Nanosecond)
	time.Sleep(2 * time.Millisecond)
	if got := c.lookup("k", 1); got != nil {
		t.Error("expected expired entry to miss")
	}
}

func firstAnswerTTL(t *testing.T, resp []byte) uint32 {
	t.Helper()
	pos := 12
	for i := uint16(0); i < binary.BigEndian.Uint16(resp[4:6]); i++ {
		np := skipName(resp, pos)
		if np < 0 {
			t.Fatal("unparsable question section")
		}
		pos = np + 4
	}
	np := skipName(resp, pos)
	if np < 0 || np+10 > len(resp) {
		t.Fatal("unparsable answer section")
	}
	return binary.BigEndian.Uint32(resp[np+4 : np+8])
}

func TestDNSCache_lookupCountsDownTTL(t *testing.T) {
	c := newDNSCache()
	c.store("k", dnsResponse("example.com", 300, [4]byte{1, 1, 1, 1}), 300*time.Second)
	c.entries["k"].storedAt = time.Now().Add(-100 * time.Second)

	resp := c.lookup("k", 0x1234)
	if resp == nil {
		t.Fatal("expected a cache hit")
	}
	if ttl := firstAnswerTTL(t, resp); ttl != 200 {
		t.Errorf("ttl = %d, want 200", ttl)
	}
}

func authoritySOA(t *testing.T, resp []byte) (ttl, minimum uint32) {
	t.Helper()
	pos := skipName(resp, 12) + 4
	for i := uint16(0); i < binary.BigEndian.Uint16(resp[6:8]); i++ {
		np := skipName(resp, pos)
		if np < 0 || np+10 > len(resp) {
			t.Fatal("unparsable answer section")
		}
		pos = np + 10 + int(binary.BigEndian.Uint16(resp[np+8:np+10]))
	}
	np := skipName(resp, pos)
	if np < 0 || np+10 > len(resp) || binary.BigEndian.Uint16(resp[np:np+2]) != 6 {
		t.Fatal("no SOA in the authority section")
	}
	end := np + 10 + int(binary.BigEndian.Uint16(resp[np+8:np+10]))
	return binary.BigEndian.Uint32(resp[np+4 : np+8]), binary.BigEndian.Uint32(resp[end-4 : end])
}

func TestDNSCache_lookupCountsDownNegativeAnswers(t *testing.T) {
	cases := []struct {
		name            string
		resp            []byte
		age             time.Duration
		wantTTL         uint32
		wantMinimumKept uint32
	}{
		{"NXDOMAIN from the SOA TTL", negativeResponse("nope.example.com", 3, 300, 900), 100 * time.Second, 200, 900},
		{"NXDOMAIN from the SOA MINIMUM", negativeResponse("nope.example.com", 3, 900, 300), 100 * time.Second, 200, 300},
		{"NODATA from the SOA MINIMUM", negativeResponse("example.com", 0, 3600, 250), 50 * time.Second, 200, 250},
		{"an expired countdown floors at one", negativeResponse("nope.example.com", 3, 900, 60), 100 * time.Second, 1, 60},
		{"a fresh hit takes the SOA MINIMUM", negativeResponse("nope.example.com", 3, 86400, 60), 0, 60, 60},
	}
	for _, tc := range cases {
		c := newDNSCache()
		c.store("k", tc.resp, dnsCacheNegativeMaxTTL)
		c.entries["k"].storedAt = time.Now().Add(-tc.age)

		resp := c.lookup("k", 0x1234)
		if resp == nil {
			t.Fatalf("%s: expected a cache hit", tc.name)
		}
		ttl, minimum := authoritySOA(t, resp)
		if ttl != tc.wantTTL {
			t.Errorf("%s: SOA ttl = %d, want %d", tc.name, ttl, tc.wantTTL)
		}
		if minimum != tc.wantMinimumKept {
			t.Errorf("%s: SOA MINIMUM = %d, want %d unchanged", tc.name, minimum, tc.wantMinimumKept)
		}
	}
}

func TestDNSCache_lookupFloorsTTLAtOne(t *testing.T) {
	c := newDNSCache()
	c.store("k", dnsResponse("example.com", 300, [4]byte{1, 1, 1, 1}), 3600*time.Second)
	c.entries["k"].storedAt = time.Now().Add(-400 * time.Second)

	resp := c.lookup("k", 0x1234)
	if resp == nil {
		t.Fatal("expected a cache hit")
	}
	if ttl := firstAnswerTTL(t, resp); ttl != 1 {
		t.Errorf("ttl = %d, want 1", ttl)
	}
}

func TestDNSCache_lookupLeavesTheStoredEntryIntact(t *testing.T) {
	c := newDNSCache()
	c.store("k", dnsResponse("example.com", 300, [4]byte{1, 1, 1, 1}), 3600*time.Second)
	c.entries["k"].storedAt = time.Now().Add(-100 * time.Second)

	for i := 0; i < 3; i++ {
		resp := c.lookup("k", 0x1234)
		if ttl := firstAnswerTTL(t, resp); ttl != 200 {
			t.Fatalf("lookup %d: ttl = %d, want 200 every time", i, ttl)
		}
	}
}

func TestDecrementTTLs_freshHitKeepsAZeroTTL(t *testing.T) {
	resp := dnsResponse("example.com", 0, [4]byte{1, 1, 1, 1})
	decrementTTLs(resp, 0)
	if ttl := firstAnswerTTL(t, resp); ttl != 0 {
		t.Errorf("ttl = %d, want 0", ttl)
	}
}

func TestDecrementTTLs_freshHitTakesTheSOAMinimum(t *testing.T) {
	resp := negativeResponse("nope.example.com", 3, 86400, 60)
	decrementTTLs(resp, 0)
	if ttl, _ := authoritySOA(t, resp); ttl != 60 {
		t.Errorf("SOA ttl = %d, want 60", ttl)
	}
}

func TestDecrementTTLs_keepsTheOptRecordIntact(t *testing.T) {
	resp := withEDNS(dnsResponse("example.com", 300, [4]byte{1, 1, 1, 1}), 4096, true)
	decrementTTLs(resp, 100*time.Second)

	if ttl := firstAnswerTTL(t, resp); ttl != 200 {
		t.Errorf("answer ttl = %d, want 200", ttl)
	}
	if flags := binary.BigEndian.Uint32(resp[len(resp)-6 : len(resp)-2]); flags != 0x8000 {
		t.Errorf("OPT flags = %#x, want 0x8000 (the TTL field is not a lifetime)", flags)
	}
}

func TestDNSCache_lateAnswerFillsOnlyAMissingOrExpiredEntry(t *testing.T) {
	c := newDNSCache()
	q := dnsQuery("example.com")
	c.store("fresh", dnsResponseFor(q, 60, [4]byte{2, 2, 2, 2}), time.Minute)
	c.storeLate("fresh", dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1}))
	if resp := c.lookup("fresh", 0x1234); resp == nil || resp[len(resp)-1] != 2 {
		t.Errorf("entry = %v, want the answer already cached kept over the late one", resp)
	}

	c.store("expired", dnsResponseFor(q, 60, [4]byte{2, 2, 2, 2}), time.Nanosecond)
	time.Sleep(2 * time.Millisecond)
	c.storeLate("expired", dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1}))
	if resp := c.lookup("expired", 0x1234); resp == nil || resp[len(resp)-1] != 1 {
		t.Errorf("entry = %v, want the late answer to replace an expired one", resp)
	}
}

func TestDNSCache_lateAnswerRacingTheWinnersStoreNeverReplacesIt(t *testing.T) {
	if runtime.GOMAXPROCS(0) < 2 {
		t.Skip("the race needs two threads")
	}
	q := dnsQuery("example.com")
	winner := dnsResponseFor(q, 60, [4]byte{2, 2, 2, 2})
	late := dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
	const rounds = 200000
	replaced := 0
	for i := 0; i < rounds; i++ {
		c := newDNSCache()
		stored := make(chan struct{})
		go func() {
			c.storeLate("key", late)
			close(stored)
		}()
		c.store("key", winner, time.Minute)
		<-stored
		if resp := c.lookup("key", 0x1234); resp == nil || resp[len(resp)-1] != 2 {
			replaced++
		}
	}
	if replaced > 0 {
		t.Errorf("a late answer replaced the winner's fresh answer in %d of %d races", replaced, rounds)
	}
}
