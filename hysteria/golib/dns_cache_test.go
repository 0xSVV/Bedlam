package golib

import (
	"encoding/binary"
	"testing"
	"time"
)

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
	buf = append(buf, 0x00)             // root label terminator
	buf = append(buf, 0x00, 0x01)       // TYPE = A
	buf = append(buf, 0x00, 0x01)       // CLASS = IN
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
	buf = append(buf, 0x00, 0x01)       // TYPE = A
	buf = append(buf, 0x00, 0x01)       // CLASS = IN
	ttlBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(ttlBytes, ttl)
	buf = append(buf, ttlBytes...)
	buf = append(buf, 0x00, 0x04)        // RDLENGTH
	buf = append(buf, ip[:]...)
	return buf
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

func TestDNSCacheLookupRespectsExpiry(t *testing.T) {
	c := newDNSCache()
	resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	c.store("k", resp, 1*time.Nanosecond)
	time.Sleep(2 * time.Millisecond)
	if got := c.lookup("k", 1); got != nil {
		t.Error("expected expired entry to miss")
	}
}
