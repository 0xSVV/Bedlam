package golib

import (
	"errors"
	"net"
	"syscall"
	"testing"
)

type syscallCapableConn struct{ net.PacketConn }

func (syscallCapableConn) SyscallConn() (syscall.RawConn, error) { return nil, nil }
func (syscallCapableConn) SetReadBuffer(int) error              { return nil }
func (syscallCapableConn) SetWriteBuffer(int) error             { return nil }

type plainConn struct{ net.PacketConn }

func TestTrackedPacketConn_delegatesUDPMethods(t *testing.T) {
	tp := &trackedPacketConn{PacketConn: syscallCapableConn{}}
	if _, err := tp.SyscallConn(); err != nil {
		t.Errorf("SyscallConn should delegate to a udp-like inner, got %v", err)
	}
	if err := tp.SetReadBuffer(1024); err != nil {
		t.Errorf("SetReadBuffer should delegate, got %v", err)
	}
	if err := tp.SetWriteBuffer(1024); err != nil {
		t.Errorf("SetWriteBuffer should delegate, got %v", err)
	}
}

func TestTrackedPacketConn_unsupportedForPlainInner(t *testing.T) {
	tp := &trackedPacketConn{PacketConn: plainConn{}}
	if _, err := tp.SyscallConn(); !errors.Is(err, errors.ErrUnsupported) {
		t.Errorf("expected ErrUnsupported for a non-udp inner, got %v", err)
	}
}
