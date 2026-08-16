package golib

import (
	"context"
	"encoding/binary"
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

// dnsResponseFor echoes the query's own header and question, so the answer
// always matches the question that was asked.
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

func TestCacheableTTL_rejectsNonZeroRcode(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	resp[3] = 0x83 // rcode=3, NXDOMAIN
	if got := cacheableTTL(resp); got != 0 {
		t.Errorf("ttl = %v, want 0 (NXDOMAIN)", got)
	}
}

func TestCacheableTTL_rejectsZeroAnswers(t *testing.T) {
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	resp[6] = 0
	resp[7] = 0
	if got := cacheableTTL(resp); got != 0 {
		t.Errorf("ttl = %v, want 0 (no answers)", got)
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

// withEDNS appends an OPT record advertising udpSize, optionally with DO set.
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
