package golib

import (
	"container/list"
	"context"
	"encoding/binary"
	"strconv"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"
)

const (
	dnsCacheMaxEntries = 1024
	dnsCacheMinTTL     = 5 * time.Second
	dnsCacheMaxTTL     = 1 * time.Hour
)

type dnsCacheEntry struct {
	key      string
	response []byte
	storedAt time.Time
	expiry   time.Time
	elem     *list.Element // position in LRU order
}

type dnsCache struct {
	mu      sync.RWMutex
	entries map[string]*dnsCacheEntry
	lru     *list.List // front = most recently used
	sf      singleflight.Group
}

func newDNSCache() *dnsCache {
	return &dnsCache{
		entries: make(map[string]*dnsCacheEntry),
		lru:     list.New(),
	}
}

func (c *dnsCache) resolve(ctx context.Context, r dnsResolver, query []byte, onTunnel func(tx, rx int)) ([]byte, error) {
	txID, qKey, ok := parseDNSQuery(query)
	if !ok {
		resp, err := r.exchange(ctx, query)
		countTunnelDNS(onTunnel, query, resp, err)
		return resp, err
	}

	cacheKey := r.id() + "\x00" + qKey

	if resp := c.lookup(cacheKey, txID); resp != nil {
		return resp, nil
	}

	result, err, _ := c.sf.Do(cacheKey, func() (any, error) {
		if resp := c.lookup(cacheKey, txID); resp != nil {
			return resp, nil
		}

		// Coalesced callers share this result, so the shared work gets its own
		// full budget instead of inheriting whatever the leader had left.
		sctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), dnsQueryTimeout)
		defer cancel()

		resp, err := r.exchange(sctx, query)
		countTunnelDNS(onTunnel, query, resp, err)
		if err != nil {
			return nil, err
		}

		if ttl := cacheableTTL(resp); ttl > 0 {
			c.store(cacheKey, resp, ttl)
		}
		return resp, nil
	})
	if err != nil {
		return nil, err
	}

	shared := result.([]byte)
	out := make([]byte, len(shared))
	copy(out, shared)
	if len(out) >= 2 {
		binary.BigEndian.PutUint16(out[:2], txID)
	}
	return out, nil
}

// A cache hit needs no network, so callers can answer it without spending one
// of the in-flight query slots.
func (c *dnsCache) tryCached(r dnsResolver, query []byte) []byte {
	txID, qKey, ok := parseDNSQuery(query)
	if !ok {
		return nil
	}
	return c.lookup(r.id()+"\x00"+qKey, txID)
}

func (c *dnsCache) lookup(key string, txID uint16) []byte {
	c.mu.Lock()
	entry, ok := c.entries[key]
	if !ok || time.Now().After(entry.expiry) {
		c.mu.Unlock()
		return nil
	}
	c.lru.MoveToFront(entry.elem)
	resp := make([]byte, len(entry.response))
	copy(resp, entry.response)
	age := time.Since(entry.storedAt)
	c.mu.Unlock()
	decrementTTLs(resp, age)
	if len(resp) >= 2 {
		binary.BigEndian.PutUint16(resp[:2], txID)
	}
	return resp
}

func countTunnelDNS(onTunnel func(tx, rx int), query, resp []byte, err error) {
	if onTunnel == nil {
		return
	}
	rx := 0
	if err == nil {
		rx = len(resp)
	}
	onTunnel(len(query), rx)
}

func (c *dnsCache) store(key string, response []byte, ttl time.Duration) {
	respCopy := make([]byte, len(response))
	copy(respCopy, response)

	c.mu.Lock()
	defer c.mu.Unlock()

	if existing, ok := c.entries[key]; ok {
		existing.response = respCopy
		existing.storedAt = time.Now()
		existing.expiry = time.Now().Add(ttl)
		c.lru.MoveToFront(existing.elem)
		return
	}

	for len(c.entries) >= dnsCacheMaxEntries {
		oldest := c.lru.Back()
		if oldest == nil {
			break
		}
		c.removeLocked(oldest.Value.(*dnsCacheEntry))
	}

	entry := &dnsCacheEntry{
		key:      key,
		response: respCopy,
		storedAt: time.Now(),
		expiry:   time.Now().Add(ttl),
	}
	entry.elem = c.lru.PushFront(entry)
	c.entries[key] = entry
}

func (c *dnsCache) removeLocked(e *dnsCacheEntry) {
	c.lru.Remove(e.elem)
	delete(c.entries, e.key)
}

func (c *dnsCache) clear() {
	c.mu.Lock()
	c.entries = make(map[string]*dnsCacheEntry)
	c.lru.Init()
	c.mu.Unlock()
}

func parseDNSQuery(query []byte) (uint16, string, bool) {
	question, ok := dnsQuestion(query)
	if !ok {
		return 0, "", false
	}
	txID := binary.BigEndian.Uint16(query[0:2])
	return txID, question + "\x00" + dnsQuerySignature(query), true
}

// dnsQuestion returns the single question as a comparison key, with the name
// lowercased so 0x20-randomising clients share cache entries. Label length
// bytes are at most 63 and compression pointers start at 0xc0, so neither can
// be mistaken for an upper-case letter.
func dnsQuestion(msg []byte) (string, bool) {
	if len(msg) < 12 || binary.BigEndian.Uint16(msg[4:6]) != 1 {
		return "", false
	}
	end := skipName(msg, 12)
	if end < 0 || end+4 > len(msg) {
		return "", false
	}
	q := make([]byte, end+4-12)
	copy(q, msg[12:end+4])
	for i := 0; i < len(q)-4; i++ {
		if c := q[i]; c >= 'A' && c <= 'Z' {
			q[i] = c + ('a' - 'A')
		}
	}
	return string(q), true
}

// dnsQuerySignature separates cache entries whose answers are not
// interchangeable: an EDNS0 client accepting 4096 bytes must not be answered
// from an entry created for a 512-byte client, and DO/CD change the content.
func dnsQuerySignature(query []byte) string {
	var flags byte
	if query[2]&0x01 != 0 {
		flags |= 1
	}
	if query[3]&0x10 != 0 {
		flags |= 2
	}
	udpSize, do, ok := ednsOptions(query)
	if !ok {
		return "f" + strconv.Itoa(int(flags))
	}
	d := 0
	if do {
		d = 1
	}
	return "f" + strconv.Itoa(int(flags)) +
		".e" + strconv.Itoa(int(udpSize)) +
		"." + strconv.Itoa(d)
}

func ednsOptions(msg []byte) (udpSize uint16, do bool, ok bool) {
	if len(msg) < 12 {
		return 0, false, false
	}
	qdCount := binary.BigEndian.Uint16(msg[4:6])
	anCount := binary.BigEndian.Uint16(msg[6:8])
	nsCount := binary.BigEndian.Uint16(msg[8:10])
	arCount := binary.BigEndian.Uint16(msg[10:12])

	pos := 12
	for i := uint16(0); i < qdCount; i++ {
		np := skipName(msg, pos)
		if np < 0 || np+4 > len(msg) {
			return 0, false, false
		}
		pos = np + 4
	}
	for i := 0; i < int(anCount)+int(nsCount); i++ {
		np := skipName(msg, pos)
		if np < 0 || np+10 > len(msg) {
			return 0, false, false
		}
		pos = np + 10 + int(binary.BigEndian.Uint16(msg[np+8:np+10]))
		if pos > len(msg) {
			return 0, false, false
		}
	}
	for i := uint16(0); i < arCount; i++ {
		np := skipName(msg, pos)
		if np < 0 || np+10 > len(msg) {
			return 0, false, false
		}
		if binary.BigEndian.Uint16(msg[np:np+2]) == 41 {
			return binary.BigEndian.Uint16(msg[np+2 : np+4]),
				binary.BigEndian.Uint32(msg[np+4:np+8])&0x8000 != 0,
				true
		}
		pos = np + 10 + int(binary.BigEndian.Uint16(msg[np+8:np+10]))
		if pos > len(msg) {
			return 0, false, false
		}
	}
	return 0, false, false
}

func cacheableTTL(response []byte) time.Duration {
	if len(response) < 12 {
		return 0
	}
	if response[3]&0x0f != 0 {
		return 0
	}
	// A truncated answer is incomplete by definition; caching it would also
	// pin the TC bit and suppress the client's own TCP retry for the TTL.
	if response[2]&0x02 != 0 {
		return 0
	}
	qdCount := binary.BigEndian.Uint16(response[4:6])
	anCount := binary.BigEndian.Uint16(response[6:8])
	if qdCount != 1 || anCount == 0 {
		return 0
	}

	pos := 12
	for i := uint16(0); i < qdCount; i++ {
		np := skipName(response, pos)
		if np < 0 || np+4 > len(response) {
			return 0
		}
		pos = np + 4
	}

	var minTTL uint32
	for i := uint16(0); i < anCount; i++ {
		np := skipName(response, pos)
		if np < 0 || np+10 > len(response) {
			return 0
		}
		ttl := binary.BigEndian.Uint32(response[np+4 : np+8])
		rdLen := binary.BigEndian.Uint16(response[np+8 : np+10])
		if i == 0 || ttl < minTTL {
			minTTL = ttl
		}
		pos = np + 10 + int(rdLen)
		if pos > len(response) {
			return 0
		}
	}

	if minTTL == 0 {
		return 0
	}
	d := time.Duration(minTTL) * time.Second
	if d < dnsCacheMinTTL {
		d = dnsCacheMinTTL
	}
	if d > dnsCacheMaxTTL {
		d = dnsCacheMaxTTL
	}
	return d
}

// A cached answer has to age. Replaying the stored TTL would give the client a
// full fresh lifetime on every hit, so the record could never expire.
func decrementTTLs(response []byte, age time.Duration) {
	if len(response) < 12 || age <= 0 {
		return
	}
	secs := uint32(age / time.Second)
	if secs == 0 {
		return
	}
	qdCount := binary.BigEndian.Uint16(response[4:6])
	records := int(binary.BigEndian.Uint16(response[6:8])) +
		int(binary.BigEndian.Uint16(response[8:10])) +
		int(binary.BigEndian.Uint16(response[10:12]))

	pos := 12
	for i := uint16(0); i < qdCount; i++ {
		np := skipName(response, pos)
		if np < 0 || np+4 > len(response) {
			return
		}
		pos = np + 4
	}
	for i := 0; i < records; i++ {
		np := skipName(response, pos)
		if np < 0 || np+10 > len(response) {
			return
		}
		// An OPT record keeps flags and the extended rcode in the TTL field.
		if binary.BigEndian.Uint16(response[np:np+2]) != 41 {
			ttl := binary.BigEndian.Uint32(response[np+4 : np+8])
			if ttl > secs {
				ttl -= secs
			} else {
				ttl = 1
			}
			binary.BigEndian.PutUint32(response[np+4:np+8], ttl)
		}
		rdLen := binary.BigEndian.Uint16(response[np+8 : np+10])
		pos = np + 10 + int(rdLen)
		if pos > len(response) {
			return
		}
	}
}

func skipName(data []byte, pos int) int {
	for {
		if pos >= len(data) {
			return -1
		}
		l := int(data[pos])
		if l == 0 {
			return pos + 1
		}
		if l&0xc0 == 0xc0 {
			if pos+2 > len(data) {
				return -1
			}
			return pos + 2
		}
		if l&0xc0 != 0 {
			return -1
		}
		pos += 1 + l
	}
}
