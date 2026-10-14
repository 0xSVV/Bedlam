package golib

import (
	"context"
	"sync"
)

const (
	dnsMaxExecuting  = 64
	dnsMaxQueued     = 128
	maxConcurrentDNS = dnsMaxExecuting
)

type dnsWork struct {
	ctx context.Context
	run func(context.Context)
}

// dnsScheduler bounds DNS jobs across every UDP flow and TCP stream in one
// session. Workers start only for admitted jobs; queued requests do not create
// goroutines.
type dnsScheduler struct {
	ctx       context.Context
	cancel    context.CancelFunc
	queue     chan dnsWork
	workers   sync.WaitGroup
	closeOnce sync.Once
}

func newDNSScheduler(executing, queued int) *dnsScheduler {
	if executing <= 0 {
		executing = dnsMaxExecuting
	}
	if queued < 0 {
		queued = 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &dnsScheduler{ctx: ctx, cancel: cancel, queue: make(chan dnsWork, queued)}
	s.workers.Add(executing)
	for range executing {
		go s.worker()
	}
	return s
}

func (s *dnsScheduler) submit(work dnsWork) bool {
	if s == nil || work.ctx == nil || work.run == nil || work.ctx.Err() != nil || s.ctx.Err() != nil {
		return false
	}
	select {
	case s.queue <- work:
		return true
	default:
		return false
	}
}

func (s *dnsScheduler) worker() {
	defer s.workers.Done()
	for {
		select {
		case <-s.ctx.Done():
			return
		case work := <-s.queue:
			if s.ctx.Err() != nil || work.ctx.Err() != nil {
				continue
			}
			work.run(work.ctx)
		}
	}
}

func (s *dnsScheduler) close() {
	if s == nil {
		return
	}
	s.closeOnce.Do(func() {
		s.cancel()
		s.workers.Wait()
		for {
			select {
			case <-s.queue:
			default:
				return
			}
		}
	})
}
