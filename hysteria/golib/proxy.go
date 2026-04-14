package golib

import (
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"

	"github.com/apernet/hysteria/core/v2/client"
)

type socksProxyConfig struct {
	Username   string
	Password   string
	DisableUDP bool
}

type httpProxyConfig struct {
	Username string
	Password string
}

func startSOCKS5(c client.Client, addr string, cfg socksProxyConfig) error {
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
			go handleSOCKS5(c, conn, cfg)
		}
	}()
	return nil
}

func handleSOCKS5(c client.Client, conn net.Conn, cfg socksProxyConfig) {
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		conn.Close()
		return
	}
	if buf[0] != 0x05 {
		conn.Close()
		return
	}

	methods := make([]byte, buf[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		conn.Close()
		return
	}

	requireAuth := cfg.Username != "" && cfg.Password != ""

	if requireAuth {
		found := false
		for _, m := range methods {
			if m == 0x02 {
				found = true
				break
			}
		}
		if !found {
			conn.Write([]byte{0x05, 0xFF})
			conn.Close()
			return
		}
		conn.Write([]byte{0x05, 0x02})

		// RFC 1929 username/password sub-negotiation
		authBuf := make([]byte, 2)
		if _, err := io.ReadFull(conn, authBuf); err != nil {
			conn.Close()
			return
		}
		if authBuf[0] != 0x01 {
			conn.Close()
			return
		}
		ulen := int(authBuf[1])
		username := make([]byte, ulen)
		if _, err := io.ReadFull(conn, username); err != nil {
			conn.Close()
			return
		}
		plenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, plenBuf); err != nil {
			conn.Close()
			return
		}
		password := make([]byte, plenBuf[0])
		if _, err := io.ReadFull(conn, password); err != nil {
			conn.Close()
			return
		}

		if string(username) != cfg.Username || string(password) != cfg.Password {
			conn.Write([]byte{0x01, 0x01})
			conn.Close()
			return
		}
		conn.Write([]byte{0x01, 0x00})
	} else {
		conn.Write([]byte{0x05, 0x00})
	}

	buf = make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		conn.Close()
		return
	}

	if buf[0] != 0x05 || buf[1] != 0x01 {
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		conn.Close()
		return
	}

	var targetAddr string
	switch buf[3] {
	case 0x01: // IPv4
		addr := make([]byte, 4)
		if _, err := io.ReadFull(conn, addr); err != nil {
			conn.Close()
			return
		}
		targetAddr = net.IP(addr).String()
	case 0x03: // Domain
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			conn.Close()
			return
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(conn, domain); err != nil {
			conn.Close()
			return
		}
		targetAddr = string(domain)
	case 0x04: // IPv6
		addr := make([]byte, 16)
		if _, err := io.ReadFull(conn, addr); err != nil {
			conn.Close()
			return
		}
		targetAddr = "[" + net.IP(addr).String() + "]"
	default:
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		conn.Close()
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		conn.Close()
		return
	}
	port := binary.BigEndian.Uint16(portBuf)
	target := net.JoinHostPort(targetAddr, strconv.Itoa(int(port)))

	remote, err := c.TCP(target)
	if err != nil {
		conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		conn.Close()
		return
	}

	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	relay(conn, remote)
}

func startHTTPProxy(c client.Client, addr string, cfg httpProxyConfig) error {
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
			go handleHTTPConnect(c, conn, cfg)
		}
	}()
	return nil
}

func handleHTTPConnect(c client.Client, conn net.Conn, cfg httpProxyConfig) {
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		conn.Close()
		return
	}
	request := string(buf[:n])

	if cfg.Username != "" && cfg.Password != "" {
		expected := base64.StdEncoding.EncodeToString([]byte(cfg.Username + ":" + cfg.Password))
		authOk := false
		for _, line := range strings.Split(request, "\r\n") {
			lower := strings.ToLower(line)
			if !strings.HasPrefix(lower, "proxy-authorization:") {
				continue
			}
			value := strings.TrimSpace(line[len("proxy-authorization:"):])
			if strings.HasPrefix(strings.ToLower(value), "basic ") {
				token := strings.TrimSpace(value[6:])
				if token == expected {
					authOk = true
				}
			}
			break
		}
		if !authOk {
			conn.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Hysteria\"\r\n\r\n"))
			conn.Close()
			return
		}
	}

	var method, host string
	fmt.Sscanf(request, "%s %s", &method, &host)

	if method != "CONNECT" {
		conn.Write([]byte("HTTP/1.1 405 Method Not Allowed\r\n\r\n"))
		conn.Close()
		return
	}

	if !strings.Contains(host, ":") {
		host = host + ":443"
	}

	remote, err := c.TCP(host)
	if err != nil {
		conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		conn.Close()
		return
	}

	conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	relay(conn, remote)
}

func relay(a, b net.Conn) {
	defer a.Close()
	defer b.Close()

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(a, b)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(b, a)
		errc <- err
	}()

	<-errc
}
