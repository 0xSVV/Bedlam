package golib

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/dns/dnsmessage"
)

func TestDomainAddressRegistry_admissionIsBoundedAndGenerationScoped(t *testing.T) {
	now := time.Unix(100, 0)
	r := newDomainAddressRegistry(1)
	addr := netip.MustParseAddr("203.0.113.9")
	if !r.admit("rule-a", []netip.Addr{addr}, 0, now) {
		t.Fatal("admit failed")
	}
	if !r.hasRule("rule-a", addr, now.Add(29*time.Second)) {
		t.Fatal("30s minimum route lease missing")
	}
	if r.admit("rule-b", []netip.Addr{netip.MustParseAddr("203.0.113.10")}, time.Minute, now) {
		t.Fatal("registry exceeded its cap")
	}
	// Replaying the same cached response must not extend the original lease.
	if !r.admit("rule-a", []netip.Addr{addr}, time.Hour, now.Add(20*time.Second)) {
		t.Fatal("existing lease should remain admissible")
	}
	if r.hasRule("rule-a", addr, now.Add(31*time.Second)) {
		t.Fatal("replay extended the lease")
	}
	r.reset()
	if r.hasRule("rule-a", addr, now.Add(time.Second)) {
		t.Fatal("reset retained an old generation")
	}
}

func TestDomainAnswerAddresses_usesMinimumTTL(t *testing.T) {
	query := dnsQuery("example.com")
	response := dnsResponse("example.com", 10, [4]byte{203, 0, 113, 5})
	// DNS answer wire contains one A record with TTL 10s. The parser must not
	// invent a longer lease; the route registry applies its separate 30s floor.
	addresses, ttl, err := domainAnswerAddresses(query, response)
	if err != nil {
		t.Fatal(err)
	}
	if len(addresses) != 1 || addresses[0].String() != "203.0.113.5" {
		t.Fatalf("addresses=%v", addresses)
	}
	if ttl != 10*time.Second {
		t.Fatalf("ttl=%v, want 10s", ttl)
	}
}

func TestDomainAnswerAddresses_zeroTTLInCNAMEChainIsPreserved(t *testing.T) {
	query := dnsQuery("example.com")
	name := dnsmessage.MustNewName("example.com.")
	middle := dnsmessage.MustNewName("edge.example.net.")
	final := dnsmessage.MustNewName("origin.example.org.")
	builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: binary.BigEndian.Uint16(query[:2]), Response: true, RCode: dnsmessage.RCodeSuccess,
	})
	builder.EnableCompression()
	if err := builder.StartQuestions(); err != nil {
		t.Fatal(err)
	}
	if err := builder.Question(dnsmessage.Question{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}); err != nil {
		t.Fatal(err)
	}
	if err := builder.StartAnswers(); err != nil {
		t.Fatal(err)
	}
	if err := builder.CNAMEResource(dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET, TTL: 0}, dnsmessage.CNAMEResource{CNAME: middle}); err != nil {
		t.Fatal(err)
	}
	if err := builder.CNAMEResource(dnsmessage.ResourceHeader{Name: middle, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.CNAMEResource{CNAME: final}); err != nil {
		t.Fatal(err)
	}
	if err := builder.AResource(dnsmessage.ResourceHeader{Name: final, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 120}, dnsmessage.AResource{A: [4]byte{203, 0, 113, 6}}); err != nil {
		t.Fatal(err)
	}
	response, err := builder.Finish()
	if err != nil {
		t.Fatal(err)
	}
	addresses, ttl, err := domainAnswerAddresses(query, response)
	if err != nil {
		t.Fatal(err)
	}
	if len(addresses) != 1 || ttl != 0 {
		t.Fatalf("addresses=%v ttl=%v, want one address and zero TTL", addresses, ttl)
	}
}

func TestDNSPolicy_selectsSameDirectResolverForUDPAndTCPQueries(t *testing.T) {
	policy, err := newDNSPolicy([]directDomainRule{{ID: "domain-1", Name: "example.com"}}, "enabled")
	if err != nil {
		t.Fatal(err)
	}
	remote := &dnsUpstream{ident: "remote", resolvers: []dnsResolver{&stubResolver{name: "remote"}}}
	direct := &dnsUpstream{ident: "direct", resolvers: []dnsResolver{&stubResolver{name: "direct"}}}
	h := &tunHandler{session: &Session{}, dns: remote, directDNS: direct, dnsPolicy: policy}
	for _, query := range [][]byte{dnsQuery("example.com"), dnsQuery("other.example")} {
		got, ruleID := h.selectDNSUpstream(query)
		if string(query) == string(dnsQuery("example.com")) {
			if got != direct || ruleID != "domain-1" {
				t.Fatalf("direct selection=(%p,%q)", got, ruleID)
			}
		} else if got != remote || ruleID != "" {
			t.Fatalf("remote selection=(%p,%q)", got, ruleID)
		}
	}
}

func TestServeDNSPackets_directAnswerLeaseExistsBeforeResponseWrite(t *testing.T) {
	directResolver := &stubResolver{name: "direct", reply: echoAnswer([4]byte{203, 0, 113, 7})}
	remoteResolver := &stubResolver{name: "remote", reply: echoAnswer([4]byte{198, 51, 100, 2})}
	h := testHandler(t, remoteResolver)
	h.directDNS = &dnsUpstream{ident: "direct", resolvers: []dnsResolver{directResolver}}
	h.dnsPolicy, _ = newDNSPolicy([]directDomainRule{{ID: "domain-1", Name: "example.com"}}, "enabled")
	h.session.domainIPs = newDomainAddressRegistry(8)
	packets := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)
	packets.in <- fakePacket{data: dnsQuery("example.com"), addr: dest}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = h.serveDNSPackets(ctx, packets, dest.String()); close(done) }()
	select {
	case answer := <-packets.out:
		address := netip.MustParseAddr("203.0.113.7")
		if !h.session.domainIPs.hasRule("domain-1", address, time.Now()) {
			t.Fatal("DNS response escaped before its address lease was admitted")
		}
		if directResolver.calls.Load() != 1 || remoteResolver.calls.Load() != 0 {
			t.Fatalf("direct=%d remote=%d", directResolver.calls.Load(), remoteResolver.calls.Load())
		}
		if len(answer.data) == 0 {
			t.Fatal("empty DNS response")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("DNS response timeout")
	}
	cancel()
	_ = packets.Close()
	<-done
}

func TestServeDNSStream_directAnswerLeaseExistsBeforeResponseWrite(t *testing.T) {
	directResolver := &stubResolver{name: "direct", reply: echoAnswer([4]byte{203, 0, 113, 8})}
	remoteResolver := &stubResolver{name: "remote", reply: echoAnswer([4]byte{198, 51, 100, 2})}
	h := testHandler(t, remoteResolver)
	h.directDNS = &dnsUpstream{ident: "direct", resolvers: []dnsResolver{directResolver}}
	h.dnsPolicy, _ = newDNSPolicy([]directDomainRule{{ID: "domain-1", Name: "example.com"}}, "enabled")
	h.session.domainIPs = newDomainAddressRegistry(8)
	clientConn, serverConn := net.Pipe()
	done := make(chan struct{})
	go func() { _ = h.serveDNSStream(context.Background(), serverConn); close(done) }()
	if err := writeDNSFrame(clientConn, dnsQuery("example.com")); err != nil {
		t.Fatal(err)
	}
	answer, err := readDNSFrame(clientConn)
	if err != nil {
		t.Fatal(err)
	}
	if !h.session.domainIPs.hasRule("domain-1", netip.MustParseAddr("203.0.113.8"), time.Now()) {
		t.Fatal("DNS response escaped before its address lease was admitted")
	}
	if directResolver.calls.Load() != 1 || remoteResolver.calls.Load() != 0 {
		t.Fatalf("direct=%d remote=%d", directResolver.calls.Load(), remoteResolver.calls.Load())
	}
	if len(answer) == 0 {
		t.Fatal("empty DNS response")
	}
	_ = clientConn.Close()
	_ = serverConn.Close()
	<-done
}
