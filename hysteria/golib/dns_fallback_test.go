package golib

import (
	"context"
	"fmt"
	"io"
	"sync/atomic"
	"testing"

	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

type seqClient struct {
	*fakeClient
	seq atomic.Uint64
}

func (s *seqClient) SessionSeq() uint64 { return s.seq.Load() }

type timeoutErr struct{}

func (timeoutErr) Error() string   { return "fake timeout" }
func (timeoutErr) Timeout() bool   { return true }
func (timeoutErr) Temporary() bool { return true }

func TestFallbackGate_threshold(t *testing.T) {
	var g fallbackGate
	for i := 0; i < fallbackGateThreshold-1; i++ {
		g.noteTimeout()
	}
	if g.tripped() {
		t.Fatalf("gate tripped after %d timeouts", fallbackGateThreshold-1)
	}
	g.noteTimeout()
	if !g.tripped() {
		t.Fatal("gate should trip at the threshold")
	}
	g.reset()
	if g.tripped() {
		t.Fatal("reset should clear the gate")
	}
}

func TestIsTimeoutClass(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"wrapped deadline", fmt.Errorf("DNS query to x: %w", context.DeadlineExceeded), true},
		{"canceled", fmt.Errorf("DNS query to x: %w", context.Canceled), false},
		{"net timeout", fmt.Errorf("dial: %w", error(timeoutErr{})), true},
		{"eof", io.EOF, false},
		{"dial error", coreErrs.DialError{Message: "UDP disabled"}, false},
	}
	for _, c := range cases {
		if got := isTimeoutClass(c.err); got != c.want {
			t.Errorf("%s: isTimeoutClass = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestSessionSeq_zeroWithoutSequencer(t *testing.T) {
	if got := sessionSeq(&fakeClient{}); got != 0 {
		t.Errorf("sessionSeq = %d, want 0", got)
	}
}
