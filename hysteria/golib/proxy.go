package golib

import (
	"crypto/subtle"
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
			return
		}
		conn.Write([]byte{0x05, 0x02})

		if !readSOCKS5Auth(conn, cfg) {
			conn.Write([]byte{0x01, 0x01})
			return
		}
		conn.Write([]byte{0x01, 0x00})
	} else {
		conn.Write([]byte{0x05, 0x00})
	}

	buf = make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return
	}

	if buf[0] != 0x05 || buf[1] != 0x01 {
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	target, err := readSOCKS5Addr(conn, buf[3])
	if err != nil {
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	remote, err := c.TCP(target)
	if err != nil {
		conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	relay(conn, remote)
}

func readSOCKS5Auth(conn net.Conn, cfg socksProxyConfig) bool {
	authBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, authBuf); err != nil {
		return false
	}
	if authBuf[0] != 0x01 {
		return false
	}

	username := make([]byte, authBuf[1])
	if _, err := io.ReadFull(conn, username); err != nil {
		return false
	}

	plenBuf := make([]byte, 1)
	if _, err := io.ReadFull(conn, plenBuf); err != nil {
		return false
	}

	password := make([]byte, plenBuf[0])
	if _, err := io.ReadFull(conn, password); err != nil {
		return false
	}

	usernameOk := subtle.ConstantTimeCompare(username, []byte(cfg.Username)) == 1
	passwordOk := subtle.ConstantTimeCompare(password, []byte(cfg.Password)) == 1
	return usernameOk && passwordOk
}

func readSOCKS5Addr(conn net.Conn, addrType byte) (string, error) {
	var host string
	switch addrType {
	case 0x01: // IPv4
		buf := make([]byte, 4)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	case 0x03: // Domain
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return "", err
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(conn, domain); err != nil {
			return "", err
		}
		host = string(domain)
	case 0x04: // IPv6
		buf := make([]byte, 16)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = "[" + net.IP(buf).String() + "]"
	default:
		return "", fmt.Errorf("unsupported address type: %d", addrType)
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return "", err
	}
	port := binary.BigEndian.Uint16(portBuf)
	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
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
	defer conn.Close()

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return
	}
	request := string(buf[:n])

	if cfg.Username != "" && cfg.Password != "" {
		if !checkHTTPProxyAuth(request, cfg) {
			conn.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Hysteria\"\r\n\r\n"))
			return
		}
	}

	var method, host string
	fmt.Sscanf(request, "%s %s", &method, &host)

	if method != "CONNECT" {
		conn.Write([]byte("HTTP/1.1 405 Method Not Allowed\r\n\r\n"))
		return
	}

	if !strings.Contains(host, ":") {
		host = host + ":443"
	}

	remote, err := c.TCP(host)
	if err != nil {
		conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}

	conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	relay(conn, remote)
}

func checkHTTPProxyAuth(request string, cfg httpProxyConfig) bool {
	expected := base64.StdEncoding.EncodeToString([]byte(cfg.Username + ":" + cfg.Password))
	for _, line := range strings.Split(request, "\r\n") {
		if !strings.HasPrefix(strings.ToLower(line), "proxy-authorization:") {
			continue
		}
		value := strings.TrimSpace(line[len("proxy-authorization:"):])
		if strings.HasPrefix(strings.ToLower(value), "basic ") {
			token := strings.TrimSpace(value[6:])
			return subtle.ConstantTimeCompare([]byte(token), []byte(expected)) == 1
		}
		break
	}
	return false
}

func relay(a, b net.Conn) {
	defer a.Close()
	defer b.Close()

	done := make(chan struct{}, 1)
	go func() {
		io.Copy(a, b)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(b, a)
		done <- struct{}{}
	}()

	<-done
}
