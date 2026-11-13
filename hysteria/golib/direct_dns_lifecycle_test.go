package golib

import (
	"context"
	"net"
	"testing"
	"time"

	singtun "github.com/sagernet/sing-tun"
)

type directTestTun struct{ singtun.Tun }

func (directTestTun) Close() error { return nil }

func TestDirectDNS_StopTUNClosesPooledDirectSocket(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	closed := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			closed <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		query, err := readDNSFrame(conn)
		if err != nil {
			closed <- err
			return
		}
		if err := writeDNSFrame(conn, dnsResponseFor(query, 60, [4]byte{192, 0, 2, 17})); err != nil {
			closed <- err
			return
		}
		_, err = conn.Read(make([]byte, 1))
		closed <- err
	}()
	session := &Session{protector: &directTestProtector{allow: true}, tunIface: directTestTun{}}
	up, err := newDirectDNSUpstream(session, directTestConfig(t, "tcp", ln.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := up.exchange(ctx, dnsQuery("direct.example")); err != nil {
		t.Fatal(err)
	}
	session.tunDirectDNS = up
	if err := session.StopTUN(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-closed:
		if err == nil || isTimeoutClass(err) {
			t.Fatalf("server must observe socket closure, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("StopTUN left the old network's direct DNS connection open")
	}
	if session.tunDirectDNS != nil {
		t.Fatal("StopTUN retained the old Direct DNS upstream")
	}
}
