package golib

import (
	"encoding/binary"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"golang.org/x/sync/singleflight"
)

const (
	dnsCacheMaxEntries = 1024
	dnsCacheMinTTL     = 5 * time.Second
	dnsCacheMaxTTL     = 1 * time.Hour
)

type dnsCacheEntry struct {
	response []byte
	expiry   time.Time
}

type dnsCache struct {
	mu      sync.RWMutex
	entries map[string]*dnsCacheEntry
	sf      singleflight.Group
}

var globalDNSCache = &dnsCache{entries: make(map[string]*dnsCacheEntry)}

func (c *dnsCache) resolve(hc client.Client, dnsServer string, query []byte) ([]byte, error) {
	txID, qKey, ok := parseDNSQuery(query)
	if !ok {
		return dnsOverTCP(hc, dnsServer, query)
	}

	cacheKey := dnsServer + "\x00" + qKey

	if resp := c.lookup(cacheKey, txID); resp != nil {
		return resp, nil
	}

	result, err, _ := c.sf.Do(cacheKey, func() (any, error) {
		if resp := c.lookup(cacheKey, txID); resp != nil {
			return resp, nil
		}

		resp, err := dnsOverTCP(hc, dnsServer, query)
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

func (c *dnsCache) lookup(key string, txID uint16) []byte {
	c.mu.RLock()
	entry, ok := c.entries[key]
	c.mu.RUnlock()
	if !ok || time.Now().After(entry.expiry) {
		return nil
	}
	resp := make([]byte, len(entry.response))
	copy(resp, entry.response)
	if len(resp) >= 2 {
		binary.BigEndian.PutUint16(resp[:2], txID)
	}
	return resp
}

func (c *dnsCache) store(key string, response []byte, ttl time.Duration) {
	respCopy := make([]byte, len(response))
	copy(respCopy, response)

	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.entries) >= dnsCacheMaxEntries {
		c.evictExpiredLocked()
		if len(c.entries) >= dnsCacheMaxEntries {
			for k := range c.entries {
				delete(c.entries, k)
				break
			}
		}
	}

	c.entries[key] = &dnsCacheEntry{
		response: respCopy,
		expiry:   time.Now().Add(ttl),
	}
}

func (c *dnsCache) clear() {
	c.mu.Lock()
	c.entries = make(map[string]*dnsCacheEntry)
	c.mu.Unlock()
}

func (c *dnsCache) evictExpiredLocked() {
	now := time.Now()
	for k, e := range c.entries {
		if now.After(e.expiry) {
			delete(c.entries, k)
		}
	}
}

func parseDNSQuery(query []byte) (uint16, string, bool) {
	if len(query) < 12 {
		return 0, "", false
	}
	txID := binary.BigEndian.Uint16(query[0:2])
	qdCount := binary.BigEndian.Uint16(query[4:6])
	if qdCount != 1 {
		return 0, "", false
	}

	pos := 12
	start := pos
	for {
		if pos >= len(query) {
			return 0, "", false
		}
		l := int(query[pos])
		if l == 0 {
			pos++
			break
		}
		if l&0xc0 == 0xc0 {
			pos += 2
			break
		}
		pos += 1 + l
	}

	if pos+4 > len(query) {
		return 0, "", false
	}
	return txID, string(query[start : pos+4]), true
}

func cacheableTTL(response []byte) time.Duration {
	if len(response) < 12 {
		return 0
	}
	if response[3]&0x0f != 0 {
		return 0
	}
	qdCount := binary.BigEndian.Uint16(response[4:6])
	anCount := binary.BigEndian.Uint16(response[6:8])
	if anCount == 0 {
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
