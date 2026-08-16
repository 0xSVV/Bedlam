package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

func udpEcho(ip [4]byte) func(data []byte, addr string) [][]byte {
	return func(data []byte, addr string) [][]byte {
		return [][]byte{dnsResponseFor(data, 60, ip)}
	}
}

func TestUDPResolver_rewritesAndRestoresTxid(t *testing.T) {
	var seen []uint16
	var mu sync.Mutex
	fake := newFakeUDPConn(func(data []byte, addr string) [][]byte {
		mu.Lock()
		seen = append(seen, binary.BigEndian.Uint16(data[:2]))
		mu.Unlock()
		if addr != "1.1.1.1:53" {
			t.Errorf("sent to %q", addr)
		}
		return udpEcho([4]byte{1, 1, 1, 1})(data, addr)
	})
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	q := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q[:2], 0x1234)
	resp, err := r.exchange(context.Background(), q)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x1234 {
		t.Errorf("response txid = %#x, want the caller's 0x1234", binary.BigEndian.Uint16(resp[:2]))
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 1 {
		t.Fatalf("sent %d datagrams, want 1", len(seen))
	}
	if r.id() != "udp|1.1.1.1:53" {
		t.Errorf("id = %q", r.id())
	}
}

func TestUDPResolver_demuxesConcurrentQueries(t *testing.T) {
	fake := newFakeUDPConn(udpEcho([4]byte{1, 1, 1, 1}))
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			q := dnsQuery(fmt.Sprintf("h%02d.example", i))
			want, _ := dnsQuestion(q)
			resp, err := r.exchange(ctx, q)
			if err != nil {
				t.Errorf("exchange %d: %v", i, err)
				return
			}
			got, ok := dnsQuestion(resp)
			if !ok || got != want {
				t.Errorf("query %d received the answer to a different question", i)
			}
		}(i)
	}
	wg.Wait()
	if fake.sentCount() != 32 {
		t.Errorf("sent %d datagrams, want 32", fake.sentCount())
	}
}

func TestUDPResolver_retransmitsOnce(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	fake := newFakeUDPConn(func(data []byte, addr string) [][]byte {
		mu.Lock()
		calls++
		n := calls
		mu.Unlock()
		if n == 1 {
			return nil
		}
		return udpEcho([4]byte{1, 1, 1, 1})(data, addr)
	})
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if elapsed := time.Since(start); elapsed < dnsUDPRetransmit || elapsed > dnsUDPRetransmit+2*time.Second {
		t.Errorf("elapsed %v, want ~%v (one retransmit)", elapsed, dnsUDPRetransmit)
	}
	if fake.sentCount() != 2 {
		t.Errorf("sent %d datagrams, want 2", fake.sentCount())
	}
}

func TestUDPResolver_truncatedFallsBackToTCP(t *testing.T) {
	fake := newFakeUDPConn(func(data []byte, addr string) [][]byte {
		resp := dnsResponseFor(data, 60, [4]byte{1, 1, 1, 1})
		resp[2] |= 0x02
		return [][]byte{resp}
	})
	tcpDialed := false
	fc := &fakeClient{
		udp: func() (client.HyUDPConn, error) { return fake, nil },
		tcp: func(addr string) (net.Conn, error) {
			tcpDialed = true
			return pipeDNSServer(t, func(q []byte) []byte {
				resp := dnsResponse("example.com", 60, [4]byte{2, 2, 2, 2})
				copy(resp[:2], q[:2])
				return resp
			}), nil
		},
	}
	r := newUDPResolver(fc, "1.1.1.1:53")
	defer r.close()

	resp, err := r.exchange(context.Background(), dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if !tcpDialed {
		t.Fatal("truncated answer should trigger a TCP retry")
	}
	if resp[len(resp)-1] != 2 {
		t.Errorf("answer = %v, want the TCP answer", resp)
	}
}

func TestUDPResolver_reopensAfterReceiveEOF(t *testing.T) {
	first := newFakeUDPConn(udpEcho([4]byte{1, 1, 1, 1}))
	second := newFakeUDPConn(udpEcho([4]byte{2, 2, 2, 2}))
	opened := 0
	fc := &fakeClient{udp: func() (client.HyUDPConn, error) {
		opened++
		if opened == 1 {
			return first, nil
		}
		return second, nil
	}}
	r := newUDPResolver(fc, "1.1.1.1:53")
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	first.Close()
	deadline := time.Now().Add(2 * time.Second)
	for {
		r.mu.Lock()
		gone := r.session == nil
		r.mu.Unlock()
		if gone || time.Now().After(deadline) {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}

	resp, err := r.exchange(context.Background(), dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("second exchange: %v", err)
	}
	if opened != 2 {
		t.Errorf("opened %d sessions, want 2", opened)
	}
	if resp[len(resp)-1] != 2 {
		t.Errorf("answer = %v, want the second session's answer", resp)
	}
}

func TestUDPResolver_inflightFailsWhenSessionDies(t *testing.T) {
	first := newFakeUDPConn(nil)
	second := newFakeUDPConn(udpEcho([4]byte{2, 2, 2, 2}))
	opened := 0
	fc := &fakeClient{udp: func() (client.HyUDPConn, error) {
		opened++
		if opened == 1 {
			return first, nil
		}
		return second, nil
	}}
	r := newUDPResolver(fc, "1.1.1.1:53")
	defer r.close()

	go func() {
		time.Sleep(50 * time.Millisecond)
		first.Close()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange should retry on a fresh session: %v", err)
	}
	if resp[len(resp)-1] != 2 {
		t.Errorf("answer = %v", resp)
	}
}

func TestUDPResolver_udpDisabledFallsBackToTCP(t *testing.T) {
	tcpCalls := 0
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		tcpCalls++
		return pipeDNSServer(t, func(q []byte) []byte {
			resp := dnsResponse("example.com", 60, [4]byte{3, 3, 3, 3})
			copy(resp[:2], q[:2])
			return resp
		}), nil
	}}
	r := newUDPResolver(fc, "1.1.1.1:53")
	defer r.close()

	for i := 0; i < 2; i++ {
		resp, err := r.exchange(context.Background(), dnsQuery("example.com"))
		if err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
		if resp[len(resp)-1] != 3 {
			t.Errorf("answer = %v", resp)
		}
	}
	if tcpCalls != 2 {
		t.Errorf("tcp calls = %d, want 2", tcpCalls)
	}
	if !r.isTCPOnly() {
		t.Error("resolver should remember that UDP is unavailable")
	}
}

func TestUDPResolver_teardownDuringReplyDoesNotPanic(t *testing.T) {
	for i := 0; i < 200; i++ {
		fake := newFakeUDPConn(nil)
		r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")

		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = r.exchange(ctx, dnsQuery("example.com"))
		}()

		// Let the query register, then race a reply against teardown.
		time.Sleep(time.Millisecond)
		r.mu.Lock()
		var txID uint16
		for id := range r.inflight {
			txID = id
		}
		r.mu.Unlock()
		resp := dnsResponse("example.com", 60, [4]byte{1, 1, 1, 1})
		binary.BigEndian.PutUint16(resp[:2], txID)

		go fake.inject(resp, "1.1.1.1:53")
		go r.close()

		wg.Wait()
		cancel()
	}
}

func TestUDPResolver_ignoresRepliesFromOtherSources(t *testing.T) {
	fake := newFakeUDPConn(nil)
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := r.exchange(ctx, dnsQuery("bank.example"))
		done <- err
	}()

	time.Sleep(20 * time.Millisecond)
	r.mu.Lock()
	var txID uint16
	for id := range r.inflight {
		txID = id
	}
	r.mu.Unlock()

	spoof := dnsResponse("bank.example", 3600, [4]byte{6, 6, 6, 6})
	binary.BigEndian.PutUint16(spoof[:2], txID)
	fake.inject(spoof, "203.0.113.66:9999")

	if err := <-done; err == nil {
		t.Fatal("a reply from an unrelated source must not be accepted")
	}
}

func TestUDPResolver_ignoresRepliesForAnotherQuestion(t *testing.T) {
	fake := newFakeUDPConn(nil)
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := r.exchange(ctx, dnsQuery("bank.example"))
		done <- err
	}()

	time.Sleep(20 * time.Millisecond)
	r.mu.Lock()
	var txID uint16
	for id := range r.inflight {
		txID = id
	}
	r.mu.Unlock()

	wrong := dnsResponse("attacker.example", 3600, [4]byte{6, 6, 6, 6})
	binary.BigEndian.PutUint16(wrong[:2], txID)
	fake.inject(wrong, "1.1.1.1:53")

	if err := <-done; err == nil {
		t.Fatal("a reply whose question differs must not be accepted")
	}
}

func TestUDPResolver_acceptsEquivalentSourceForm(t *testing.T) {
	fake := newFakeUDPConn(nil)
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "[2606:4700:4700::1111]:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := r.exchange(ctx, dnsQuery("example.com"))
		done <- err
	}()

	time.Sleep(20 * time.Millisecond)
	r.mu.Lock()
	var txID uint16
	for id := range r.inflight {
		txID = id
	}
	r.mu.Unlock()

	resp := dnsResponse("example.com", 60, [4]byte{1, 1, 1, 1})
	binary.BigEndian.PutUint16(resp[:2], txID)
	fake.inject(resp, "[2606:4700:4700:0000::1111]:53")

	if err := <-done; err != nil {
		t.Fatalf("the same address written differently must be accepted: %v", err)
	}
}

func TestUDPResolver_txIDsAreNotSequential(t *testing.T) {
	seen := map[uint16]bool{}
	for i := 0; i < 64; i++ {
		id, err := randomTxID()
		if err != nil {
			t.Fatalf("randomTxID: %v", err)
		}
		seen[id] = true
	}
	if len(seen) < 60 {
		t.Errorf("only %d distinct IDs out of 64", len(seen))
	}
}

func TestUDPResolver_contextTimeout(t *testing.T) {
	fake := newFakeUDPConn(nil)
	r := newUDPResolver(&fakeClient{udp: func() (client.HyUDPConn, error) { return fake, nil }}, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_, err := r.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want deadline exceeded", err)
	}
	r.mu.Lock()
	pending := len(r.inflight)
	r.mu.Unlock()
	if pending != 0 {
		t.Errorf("%d queries left registered", pending)
	}
}
