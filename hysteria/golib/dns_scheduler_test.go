package golib

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestDNSSchedulerBoundsExecutingAndQueuedWork(t *testing.T) {
	s := newDNSScheduler(2, 3)
	defer s.close()
	started := make(chan struct{}, 5)
	release := make(chan struct{})
	var active, peak atomic.Int32
	work := func(ctx context.Context) {
		n := active.Add(1)
		for old := peak.Load(); n > old && !peak.CompareAndSwap(old, n); old = peak.Load() {
		}
		started <- struct{}{}
		select {
		case <-release:
		case <-ctx.Done():
		}
		active.Add(-1)
	}

	for range 2 {
		if !s.submit(dnsWork{ctx: context.Background(), run: work}) {
			t.Fatal("first two jobs should be admitted")
		}
	}
	for range 2 {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatal("worker did not start admitted job")
		}
	}
	for range 3 {
		if !s.submit(dnsWork{ctx: context.Background(), run: work}) {
			t.Fatal("queued job should be admitted")
		}
	}
	if s.submit(dnsWork{ctx: context.Background(), run: work}) {
		t.Fatal("job beyond active plus queue capacity should be rejected")
	}
	if got := peak.Load(); got != 2 {
		t.Fatalf("peak active work = %d, want 2", got)
	}
	close(release)
	deadline := time.After(time.Second)
	for i := 0; i < 3; i++ {
		select {
		case <-started:
		case <-deadline:
			t.Fatal("queued work did not drain after capacity became available")
		}
	}
}

func TestDNSSchedulerRejectsExpiredQueuedWork(t *testing.T) {
	s := newDNSScheduler(1, 1)
	defer s.close()
	started := make(chan struct{})
	release := make(chan struct{})
	if !s.submit(dnsWork{ctx: context.Background(), run: func(context.Context) {
		close(started)
		<-release
	}}) {
		t.Fatal("first job was rejected")
	}
	<-started
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ran := make(chan struct{}, 1)
	if !s.submit(dnsWork{ctx: ctx, run: func(context.Context) { ran <- struct{}{} }}) {
		t.Fatal("queued job was rejected")
	}
	cancel()
	close(release)
	select {
	case <-ran:
		t.Fatal("expired queued work started")
	case <-time.After(20 * time.Millisecond):
	}
}
