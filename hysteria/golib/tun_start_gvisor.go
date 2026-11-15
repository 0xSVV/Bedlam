//go:build with_gvisor

package golib

import (
	"sync"

	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	singtun "github.com/sagernet/sing-tun"
)

// sing-tun creates the NIC (which attaches the fd reader) before it installs
// transport handlers. Delay that attachment until Start returns, publishing
// all initialized handlers before any packet can reach them.
func deferTunRead(tun singtun.Tun) (singtun.Tun, func()) {
	gvisor, ok := tun.(singtun.GVisorTun)
	if !ok {
		return tun, func() {}
	}
	wrapped := &startupTun{GVisorTun: gvisor}
	return wrapped, func() {
		if wrapped.endpoint != nil {
			wrapped.endpoint.activate()
		}
	}
}

type startupTun struct {
	singtun.GVisorTun
	endpoint *startupEndpoint
}

func (t *startupTun) NewEndpoint() (stack.LinkEndpoint, stack.NICOptions, error) {
	endpoint, options, err := t.GVisorTun.NewEndpoint()
	if err != nil {
		return nil, options, err
	}
	t.endpoint = &startupEndpoint{LinkEndpoint: endpoint}
	return t.endpoint, options, nil
}

type startupEndpoint struct {
	stack.LinkEndpoint
	mu         sync.Mutex
	dispatcher stack.NetworkDispatcher
	active     bool
}

func (e *startupEndpoint) Attach(dispatcher stack.NetworkDispatcher) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.dispatcher = dispatcher
	if e.active {
		e.LinkEndpoint.Attach(dispatcher)
	}
}

func (e *startupEndpoint) IsAttached() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.dispatcher != nil
}

func (e *startupEndpoint) activate() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.active = true
	e.LinkEndpoint.Attach(e.dispatcher)
}
