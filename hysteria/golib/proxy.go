package golib

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"

	"github.com/apernet/hysteria/core/v2/client"
)

func startSOCKS5(c client.Client, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	socksListener = ln
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleSOCKS5(c, conn)
		}
	}()
	return nil
}

func handleSOCKS5(c client.Client, conn net.Conn) {
	defer conn.Close()

	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}

	methods := make([]byte, buf[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	conn.Write([]byte{0x05, 0x00})

	buf = make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return
	}
	if buf[0] != 0x05 || buf[1] != 0x01 {
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var targetAddr string
	switch buf[3] {
	case 0x01:
		addr := make([]byte, 4)
		if _, err := io.ReadFull(conn, addr); err != nil {
			return
		}
		targetAddr = net.IP(addr).String()
	case 0x03:
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(conn, domain); err != nil {
			return
		}
		targetAddr = string(domain)
	case 0x04:
		addr := make([]byte, 16)
		if _, err := io.ReadFull(conn, addr); err != nil {
			return
		}
		targetAddr = "[" + net.IP(addr).String() + "]"
	default:
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf)
	target := net.JoinHostPort(targetAddr, strconv.Itoa(int(port)))

	remote, err := c.TCP(target)
	if err != nil {
		conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	relay(conn, remote)
}

func startHTTPProxy(c client.Client, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	httpListener = ln
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleHTTPConnect(c, conn)
		}
	}()
	return nil
}

func handleHTTPConnect(c client.Client, conn net.Conn) {
	defer conn.Close()

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return
	}
	request := string(buf[:n])

	var method, host string
	fmt.Sscanf(request, "%s %s", &method, &host)

	if method != "CONNECT" {
		conn.Write([]byte("HTTP/1.1 405 Method Not Allowed\r\n\r\n"))
		return
	}

	if _, _, err := net.SplitHostPort(host); err != nil {
		host = host + ":443"
	}

	remote, err := c.TCP(host)
	if err != nil {
		conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer remote.Close()

	conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	relay(conn, remote)
}

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(b, a)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(a, b)
		done <- struct{}{}
	}()
	<-done
}
