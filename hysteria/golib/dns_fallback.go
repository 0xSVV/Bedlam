package golib

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
)

const fallbackGateThreshold = 3

// fallbackGate decides when a UDP-backed resolver should try its TCP-backed
// fallback: a UDP relay that accepts sessions but delivers nothing produces
// only timeouts, never a DialError, so consecutive timed-out exchanges stand
// in for the explicit signal. The caller switches only after the fallback
// answers a real query, so a tunnel-wide outage cannot trip it.
type fallbackGate struct {
	mu    sync.Mutex
	fails int
}

func (g *fallbackGate) noteTimeout() {
	g.mu.Lock()
	g.fails++
	g.mu.Unlock()
}

func (g *fallbackGate) reset() {
	g.mu.Lock()
	g.fails = 0
	g.mu.Unlock()
}

func (g *fallbackGate) tripped() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.fails >= fallbackGateThreshold
}

func isTimeoutClass(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

type sessionSequencer interface {
	SessionSeq() uint64
}

func sessionSeq(c client.Client) uint64 {
	if s, ok := c.(sessionSequencer); ok {
		return s.SessionSeq()
	}
	return 0
}
