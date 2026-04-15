package golib

import (
	"net"
	"sync"
)

t type FdProtector interface {
	Protect(fd int32) bool
}

var (
	protectorMu sync.Mutex
	fdProtector  FdProtector
)

func SetFdProtector(protector FdProtector) {
	protectorMu.Lock()
	defer protectorMu.Unlock()
	fdProtector = protector
}

func getFdProtector() FdProtector {
	protectorMu.Lock()
	defer protectorMu.Unlock()
	return fdProtector
}

func protectPacketConn(conn net.PacketConn) {
	p := getFdProtector()
	if p == nil {
		return
	}
	if udpConn, ok := conn.(*net.UDPConn); ok {
		rawConn, err := udpConn.SyscallConn()
		if err == nil {
			rawConn.Control(func(fd uintptr) {
				p.Protect(int32(fd))
			})
		}
	}
}
