package golib

import (
	"errors"
	"io"
	"net"
	"sync"
	"testing"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

type fakeClient struct {
	tcp func(addr string) (net.Conn, error)
	udp func() (client.HyUDPConn, error)
}

func (f *fakeClient) TCP(addr string) (net.Conn, error) {
	if f.tcp == nil {
		return nil, errors.New("fake client: TCP not configured")
	}
	return f.tcp(addr)
}

func (f *fakeClient) UDP() (client.HyUDPConn, error) {
	if f.udp == nil {
		return nil, coreErrs.DialError{Message: "UDP not enabled"}
	}
	return f.udp()
}

func (f *fakeClient) Close() error { return nil }

// pipeDNSServer returns the client end of a net.Pipe whose peer answers each
// framed DNS query with respond(query); a nil answer closes the peer.
func pipeDNSServer(t *testing.T, respond func(query []byte) []byte) net.Conn {
	t.Helper()
	c, s := net.Pipe()
	go func() {
		defer s.Close()
		for {
			q, err := readDNSFrame(s)
			if err != nil {
				return
			}
			resp := respond(q)
			if resp == nil {
				return
			}
			if err := writeDNSFrame(s, resp); err != nil {
				return
			}
		}
	}()
	return c
}

type udpMsg struct {
	data []byte
	addr string
}

// fakeUDPConn is an in-memory HyUDPConn: every Send is answered by respond,
// whose datagrams become subsequent Receive results.
type fakeUDPConn struct {
	respond   func(data []byte, addr string) [][]byte
	recv      chan udpMsg
	closed    chan struct{}
	closeOnce sync.Once

	mu   sync.Mutex
	sent []udpMsg
}

func newFakeUDPConn(respond func(data []byte, addr string) [][]byte) *fakeUDPConn {
	return &fakeUDPConn{
		respond: respond,
		recv:    make(chan udpMsg, 256),
		closed:  make(chan struct{}),
	}
}

func (c *fakeUDPConn) Send(b []byte, addr string) error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	data := append([]byte(nil), b...)
	c.mu.Lock()
	c.sent = append(c.sent, udpMsg{data, addr})
	c.mu.Unlock()
	if c.respond == nil {
		return nil
	}
	for _, resp := range c.respond(data, addr) {
		select {
		case c.recv <- udpMsg{resp, addr}:
		case <-c.closed:
			return net.ErrClosed
		}
	}
	return nil
}

func (c *fakeUDPConn) Receive() ([]byte, string, error) {
	select {
	case m := <-c.recv:
		return m.data, m.addr, nil
	case <-c.closed:
		return nil, "", io.EOF
	}
}

func (c *fakeUDPConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

func (c *fakeUDPConn) sentCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.sent)
}

func (c *fakeUDPConn) inject(data []byte, addr string) {
	c.recv <- udpMsg{append([]byte(nil), data...), addr}
}
