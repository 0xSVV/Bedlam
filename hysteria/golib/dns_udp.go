package golib

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const dnsUDPRetransmit = 1500 * time.Millisecond

var errUDPSessionClosed = errors.New("udp dns session closed")

// udpSession owns one Hysteria UDP conn. Waiters select on dead rather than
// on their own channel being closed, so a reply racing with teardown can
// never be delivered to a closed channel.
type udpSession struct {
	conn      client.HyUDPConn
	dead      chan struct{}
	closeOnce sync.Once
}

func (s *udpSession) kill() {
	s.closeOnce.Do(func() {
		close(s.dead)
		_ = s.conn.Close()
	})
}

type udpWaiter struct {
	ch       chan []byte
	question string
}

type udpResolver struct {
	client client.Client
	server string

	mu       sync.Mutex
	session  *udpSession
	inflight map[uint16]*udpWaiter
	closed   bool
	tcpOnly  bool

	sendMu sync.Mutex
}

func newUDPResolver(c client.Client, server string) *udpResolver {
	return &udpResolver{
		client:   c,
		server:   server,
		inflight: map[uint16]*udpWaiter{},
	}
}

func (r *udpResolver) id() string { return "udp|" + r.server }

func (r *udpResolver) close() {
	r.mu.Lock()
	r.closed = true
	s := r.session
	r.session = nil
	r.inflight = map[uint16]*udpWaiter{}
	r.mu.Unlock()
	if s != nil {
		s.kill()
	}
}

func (r *udpResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if len(query) < 12 {
		return nil, fmt.Errorf("%w: query too short", errDNSMalformed)
	}
	if r.isTCPOnly() {
		return dnsOverTCPContext(ctx, r.client, r.server, query)
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		resp, retry, err := r.exchangeOnce(ctx, query)
		if err == nil {
			return resp, nil
		}
		var dialErr coreErrs.DialError
		if errors.As(err, &dialErr) {
			r.markTCPOnly()
			if udpDisabledLimiter.allow(r.server) {
				log(LogLevelWarn, srcDNS, "UDP relay unavailable (%s); using TCP for %s", err, r.server)
			}
			return dnsOverTCPContext(ctx, r.client, r.server, query)
		}
		lastErr = err
		if !retry || ctx.Err() != nil {
			break
		}
	}
	return nil, lastErr
}

func (r *udpResolver) exchangeOnce(ctx context.Context, query []byte) ([]byte, bool, error) {
	s, err := r.ensureSession()
	if err != nil {
		return nil, false, err
	}
	origID := binary.BigEndian.Uint16(query[:2])
	question, ok := dnsQuestion(query)
	if !ok {
		return nil, false, fmt.Errorf("%w: unparsable question", errDNSMalformed)
	}
	w, txID, err := r.register(s, question)
	if err != nil {
		return nil, true, err
	}
	defer r.unregister(txID, w)

	q := make([]byte, len(query))
	copy(q, query)
	binary.BigEndian.PutUint16(q[:2], txID)

	if err := r.send(s, q); err != nil {
		r.dropSession(s)
		return nil, true, fmt.Errorf("send DNS query: %w", err)
	}

	retransmit := time.NewTimer(dnsUDPRetransmit)
	defer retransmit.Stop()
	for {
		select {
		case resp := <-w.ch:
			out := make([]byte, len(resp))
			copy(out, resp)
			binary.BigEndian.PutUint16(out[:2], origID)
			if out[2]&0x02 != 0 {
				full, terr := dnsOverTCPContext(ctx, r.client, r.server, query)
				if terr != nil {
					return out, false, nil
				}
				return full, false, nil
			}
			return out, false, nil
		case <-s.dead:
			return nil, true, errUDPSessionClosed
		case <-retransmit.C:
			if err := r.send(s, q); err != nil {
				r.dropSession(s)
				return nil, true, fmt.Errorf("resend DNS query: %w", err)
			}
		case <-ctx.Done():
			return nil, false, fmt.Errorf("DNS query to %s: %w", r.server, ctx.Err())
		}
	}
}

func (r *udpResolver) ensureSession() (*udpSession, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return nil, net.ErrClosed
	}
	if r.session != nil {
		return r.session, nil
	}
	conn, err := r.client.UDP()
	if err != nil {
		return nil, err
	}
	s := &udpSession{conn: conn, dead: make(chan struct{})}
	r.session = s
	go r.recvLoop(s)
	return s, nil
}

// recvLoop delivers replies while holding r.mu. The send is non-blocking and
// the channel is never closed, so this can neither block nor panic.
func (r *udpResolver) recvLoop(s *udpSession) {
	for {
		data, from, err := s.conn.Receive()
		if err != nil {
			r.dropSession(s)
			return
		}
		if !r.isFromUpstream(from) {
			if dnsSourceLimiter.allow(r.server) {
				log(LogLevelWarn, srcDNS, "Ignoring DNS reply for %s from %s", r.server, from)
			}
			continue
		}
		if len(data) < dnsHeaderLen || data[2]&0x80 == 0 {
			continue
		}
		question, ok := dnsQuestion(data)
		if !ok {
			continue
		}
		txID := binary.BigEndian.Uint16(data[:2])
		pkt := make([]byte, len(data))
		copy(pkt, data)

		r.mu.Lock()
		if r.session == s {
			if w := r.inflight[txID]; w != nil && w.question == question {
				select {
				case w.ch <- pkt:
				default:
				}
			}
		}
		r.mu.Unlock()
	}
}

// isFromUpstream rejects datagrams that did not come from the configured
// server: without it a 16-bit transaction ID is the only thing standing
// between an off-path answer and the shared DNS cache.
func (r *udpResolver) isFromUpstream(from string) bool {
	if from == r.server {
		return true
	}
	fh, fp, ferr := net.SplitHostPort(from)
	sh, sp, serr := net.SplitHostPort(r.server)
	if ferr != nil || serr != nil || fp != sp {
		return false
	}
	fa, ferr := netip.ParseAddr(fh)
	sa, serr := netip.ParseAddr(sh)
	return ferr == nil && serr == nil && fa.Unmap() == sa.Unmap()
}

func (r *udpResolver) dropSession(s *udpSession) {
	r.mu.Lock()
	if r.session != s {
		r.mu.Unlock()
		s.kill()
		return
	}
	r.session = nil
	r.inflight = map[uint16]*udpWaiter{}
	r.mu.Unlock()
	s.kill()
}

func (r *udpResolver) register(s *udpSession, question string) (*udpWaiter, uint16, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.session != s {
		return nil, 0, errUDPSessionClosed
	}
	if len(r.inflight) >= 0xffff {
		return nil, 0, errors.New("too many in-flight DNS queries")
	}
	var txID uint16
	for {
		id, err := randomTxID()
		if err != nil {
			return nil, 0, err
		}
		if _, taken := r.inflight[id]; !taken {
			txID = id
			break
		}
	}
	w := &udpWaiter{ch: make(chan []byte, 1), question: question}
	r.inflight[txID] = w
	return w, txID, nil
}

func (r *udpResolver) unregister(txID uint16, w *udpWaiter) {
	r.mu.Lock()
	if cur, ok := r.inflight[txID]; ok && cur == w {
		delete(r.inflight, txID)
	}
	r.mu.Unlock()
}

func (r *udpResolver) send(s *udpSession, q []byte) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	return s.conn.Send(q, r.server)
}

func (r *udpResolver) isTCPOnly() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.tcpOnly
}

func (r *udpResolver) markTCPOnly() {
	r.mu.Lock()
	r.tcpOnly = true
	r.mu.Unlock()
}

// randomTxID draws from crypto/rand: a predictable sequence would let an
// attacker who never saw the query forge a matching answer.
func randomTxID() (uint16, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(0x10000))
	if err != nil {
		return 0, fmt.Errorf("generate DNS transaction ID: %w", err)
	}
	return uint16(n.Int64()), nil
}

var (
	udpDisabledLimiter = newRateLimiter(30 * time.Second)
	dnsSourceLimiter   = newRateLimiter(30 * time.Second)
)
