package golib

import (
	"errors"
	"net"
	"os"
	"testing"
	"time"
)

func TestHyPacketConn_readReportsRemote(t *testing.T) {
	fake := newFakeUDPConn(nil)
	p := newHyPacketConn(fake, "1.1.1.1:443")
	defer p.Close()

	fake.inject([]byte("hello"), "1.1.1.1:443")
	buf := make([]byte, 64)
	n, addr, err := p.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if string(buf[:n]) != "hello" {
		t.Errorf("read %q", buf[:n])
	}
	if addr.String() != "1.1.1.1:443" || addr.Network() != "hysteria" {
		t.Errorf("addr = %v/%v", addr.Network(), addr)
	}
	if p.RemoteAddr().String() != "1.1.1.1:443" {
		t.Errorf("RemoteAddr = %v", p.RemoteAddr())
	}
	if _, ok := p.LocalAddr().(*net.UDPAddr); ok {
		t.Error("LocalAddr must not be a *net.UDPAddr")
	}
}

func TestHyPacketConn_writeIgnoresAddr(t *testing.T) {
	fake := newFakeUDPConn(nil)
	p := newHyPacketConn(fake, "1.1.1.1:443")
	defer p.Close()

	n, err := p.WriteTo([]byte("ping"), &net.UDPAddr{IP: net.IPv4(9, 9, 9, 9), Port: 1})
	if err != nil || n != 4 {
		t.Fatalf("WriteTo = %d, %v", n, err)
	}
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if len(fake.sent) != 1 || fake.sent[0].addr != "1.1.1.1:443" || string(fake.sent[0].data) != "ping" {
		t.Errorf("sent = %+v", fake.sent)
	}
}

func TestHyPacketConn_setReadDeadlineUnblocks(t *testing.T) {
	fake := newFakeUDPConn(nil)
	p := newHyPacketConn(fake, "1.1.1.1:443")
	defer p.Close()

	done := make(chan error, 1)
	go func() {
		_, _, err := p.ReadFrom(make([]byte, 16))
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	_ = p.SetReadDeadline(time.Now())
	select {
	case err := <-done:
		nerr, ok := err.(net.Error)
		if !ok || !nerr.Timeout() || !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatalf("err = %v, want a timeout net.Error", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ReadFrom did not return after the deadline")
	}

	_ = p.SetReadDeadline(time.Time{})
	fake.inject([]byte("x"), "1.1.1.1:443")
	if _, _, err := p.ReadFrom(make([]byte, 16)); err != nil {
		t.Fatalf("read after clearing the deadline: %v", err)
	}
}

func TestHyPacketConn_closeUnblocksWithErrClosed(t *testing.T) {
	fake := newFakeUDPConn(nil)
	p := newHyPacketConn(fake, "1.1.1.1:443")

	done := make(chan error, 1)
	go func() {
		_, _, err := p.ReadFrom(make([]byte, 16))
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	p.Close()
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("err = %v, want net.ErrClosed", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ReadFrom did not return after Close")
	}
	if _, err := p.WriteTo([]byte("x"), nil); !errors.Is(err, net.ErrClosed) {
		t.Errorf("WriteTo after close = %v", err)
	}
	select {
	case <-fake.closed:
	default:
		t.Error("underlying session should be closed")
	}
}

func TestHyPacketConn_receiveErrorSurfacesAsPermanent(t *testing.T) {
	fake := newFakeUDPConn(nil)
	p := newHyPacketConn(fake, "1.1.1.1:443")
	defer p.Close()

	fake.Close()
	_, _, err := p.ReadFrom(make([]byte, 16))
	if err == nil {
		t.Fatal("expected an error after the session died")
	}
	if nerr, ok := err.(net.Error); ok && nerr.Temporary() {
		t.Fatalf("err = %v must not be temporary, or quic-go would keep the dead connection", err)
	}
}
