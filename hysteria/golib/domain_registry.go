package golib

import (
	"encoding/binary"
	"fmt"
	"net/netip"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const minimumDomainRouteLease = 30 * time.Second

type domainLeaseKey struct {
	address    netip.Addr
	ruleID     string
	generation uint64
}

type domainAddressRegistry struct {
	mu         sync.Mutex
	limit      int
	generation uint64
	entries    map[domainLeaseKey]time.Time
}

func newDomainAddressRegistry(limit int) *domainAddressRegistry {
	return &domainAddressRegistry{limit: limit, entries: make(map[domainLeaseKey]time.Time)}
}

func (r *domainAddressRegistry) admit(ruleID string, addresses []netip.Addr, ttl time.Duration, now time.Time) bool {
	if ruleID == "" || len(addresses) == 0 {
		return len(addresses) == 0
	}
	if ttl < minimumDomainRouteLease {
		ttl = minimumDomainRouteLease
	}
	expires := now.Add(ttl)
	r.mu.Lock()
	defer r.mu.Unlock()
	for key, until := range r.entries {
		if !now.Before(until) {
			delete(r.entries, key)
		}
	}
	newKeys := make(map[domainLeaseKey]struct{}, len(addresses))
	for _, address := range addresses {
		if !address.IsValid() || address.IsUnspecified() || address.IsMulticast() || address.IsLoopback() || address.IsLinkLocalUnicast() {
			return false
		}
		newKeys[domainLeaseKey{address: address.Unmap(), ruleID: ruleID, generation: r.generation}] = struct{}{}
	}
	additional := 0
	for key := range newKeys {
		if _, exists := r.entries[key]; !exists {
			additional++
		}
	}
	if len(r.entries)+additional > r.limit {
		return false
	}
	for key := range newKeys {
		if _, exists := r.entries[key]; !exists {
			r.entries[key] = expires
		}
	}
	return true
}

func (r *domainAddressRegistry) hasAnyOwner(address netip.Addr, now time.Time) bool {
	if r == nil {
		return false
	}
	address = address.Unmap()
	r.mu.Lock()
	defer r.mu.Unlock()
	for key, until := range r.entries {
		if key.generation != r.generation || !now.Before(until) {
			delete(r.entries, key)
			continue
		}
		if key.address == address {
			return true
		}
	}
	return false
}

func (r *domainAddressRegistry) hasRule(ruleID string, address netip.Addr, now time.Time) bool {
	address = address.Unmap()
	r.mu.Lock()
	defer r.mu.Unlock()
	key := domainLeaseKey{address: address, ruleID: ruleID, generation: r.generation}
	until, ok := r.entries[key]
	if ok && !now.Before(until) {
		delete(r.entries, key)
		ok = false
	}
	return ok
}

// reset creates a new policy/network generation and retires every old lease.
func (r *domainAddressRegistry) reset() {
	r.mu.Lock()
	r.generation++
	r.entries = make(map[domainLeaseKey]time.Time)
	r.mu.Unlock()
}

// domainAnswerAddresses validates the DNS transaction and only returns A/AAAA
// records reachable from the question through answer-section CNAMEs.
func domainAnswerAddresses(query, response []byte) ([]netip.Addr, time.Duration, error) {
	if len(response) < dnsHeaderLen {
		return nil, 0, fmt.Errorf("short DNS response")
	}
	if query == nil || len(query) < dnsHeaderLen || binary.BigEndian.Uint16(query[:2]) != binary.BigEndian.Uint16(response[:2]) {
		return nil, 0, fmt.Errorf("DNS transaction ID mismatch")
	}
	var parser dnsmessage.Parser
	header, err := parser.Start(response)
	if err != nil || !header.Response || header.RCode != dnsmessage.RCodeSuccess {
		return nil, 0, fmt.Errorf("invalid DNS answer header")
	}
	question, err := parser.Question()
	if err != nil || question.Class != dnsmessage.ClassINET {
		return nil, 0, fmt.Errorf("invalid DNS answer question")
	}
	qname, err := normalizeDNSName(question.Name.String())
	if err != nil {
		return nil, 0, err
	}
	wantName, ok := dnsQuestionName(query)
	wantType, typeOK := dnsQuestionType(query)
	if !ok || !typeOK || wantName != qname || wantType != uint16(question.Type) {
		return nil, 0, fmt.Errorf("DNS question mismatch")
	}
	if err := parser.SkipAllQuestions(); err != nil {
		return nil, 0, fmt.Errorf("invalid DNS question section: %w", err)
	}
	type cnameRecord struct {
		target string
		ttl    time.Duration
	}
	type addressRecord struct {
		ip  netip.Addr
		ttl time.Duration
	}
	cnames := map[string]cnameRecord{}
	addresses := map[string][]addressRecord{}
	for {
		header, err := parser.AnswerHeader()
		if err == dnsmessage.ErrSectionDone {
			break
		}
		if err != nil {
			return nil, 0, fmt.Errorf("malformed DNS answer header: %w", err)
		}
		if header.Class != dnsmessage.ClassINET {
			if err := parser.SkipAnswer(); err != nil {
				return nil, 0, err
			}
			continue
		}
		owner, err := normalizeDNSName(header.Name.String())
		if err != nil {
			if err := parser.SkipAnswer(); err != nil {
				return nil, 0, err
			}
			continue
		}
		ttl := time.Duration(header.TTL) * time.Second
		switch header.Type {
		case dnsmessage.TypeA:
			body, err := parser.AResource()
			if err != nil {
				return nil, 0, err
			}
			addresses[owner] = append(addresses[owner], addressRecord{ip: netip.AddrFrom4(body.A), ttl: ttl})
		case dnsmessage.TypeAAAA:
			body, err := parser.AAAAResource()
			if err != nil {
				return nil, 0, err
			}
			addresses[owner] = append(addresses[owner], addressRecord{ip: netip.AddrFrom16(body.AAAA), ttl: ttl})
		case dnsmessage.TypeCNAME:
			body, err := parser.CNAMEResource()
			if err != nil {
				return nil, 0, err
			}
			target, err := normalizeDNSName(body.CNAME.String())
			if err == nil {
				cnames[owner] = cnameRecord{target: target, ttl: ttl}
			}
		default:
			if err := parser.SkipAnswer(); err != nil {
				return nil, 0, err
			}
		}
	}
	qtype, _ := dnsQuestionType(query)
	name := qname
	chainTTL := time.Duration(0)
	hasChainTTL := false
	seen := map[string]bool{}
	var out []netip.Addr
	minTTL := time.Duration(0)
	hasMinTTL := false
	for depth := 0; depth < 16; depth++ {
		if seen[name] {
			break
		}
		seen[name] = true
		for _, rec := range addresses[name] {
			if (qtype == 1 && rec.ip.Is4()) || (qtype == dnsTypeAAAA && rec.ip.Is6()) {
				out = append(out, rec.ip)
				ttl := rec.ttl
				if hasChainTTL && (ttl == 0 || chainTTL < ttl) {
					ttl = chainTTL
				}
				if !hasMinTTL || ttl < minTTL {
					minTTL = ttl
					hasMinTTL = true
				}
			}
		}
		cname, ok := cnames[name]
		if !ok {
			break
		}
		if !hasChainTTL || cname.ttl < chainTTL {
			chainTTL = cname.ttl
		}
		hasChainTTL = true
		name = cname.target
	}
	return out, minTTL, nil
}

func (r *domainAddressRegistry) hasFresh(ruleID string, addresses []netip.Addr, now time.Time) bool {
	for _, address := range addresses {
		if !r.hasRule(ruleID, address, now) {
			return false
		}
	}
	return true
}
