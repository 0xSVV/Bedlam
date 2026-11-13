package golib

import (
	"crypto/tls"
	"encoding/json"
	"encoding/pem"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
)

// Runs the production NewSession bootstrap against an actual DoH server,
// connects to an actual Hysteria QUIC server, and exchanges tunneled TCP data.
// Neither test hostname exists in system DNS.
func TestDirectDNS_ClientServerConnectsUsingEncryptedBootstrap(t *testing.T) {
	cert, roots := testCert(t)
	previousRoots := dnsRootCAs()
	dnsRoots = roots
	defer func() { dnsRoots = previousRoots }()
	var dnsQueries atomic.Int32
	dnsServer := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query, err := io.ReadAll(r.Body)
		if err != nil {
			return
		}
		name, ok := dnsQuestionName(query)
		if !ok || name != "tunnel-server.invalid" {
			t.Errorf("unexpected bootstrap query %q", name)
		}
		dnsQueries.Add(1)
		response := dnsResponseFor(query, 60, [4]byte{127, 0, 0, 1})
		response[10], response[11] = 0, 0
		if qtype, _ := dnsQuestionType(query); qtype == dnsTypeAAAA {
			response = response[:skipName(query, 12)+4]
			response[6], response[7] = 0, 0
		}
		w.Header().Set("Content-Type", dohContentType)
		_, _ = w.Write(response)
	}))
	dnsServer.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	dnsServer.EnableHTTP2 = true
	dnsServer.StartTLS()
	defer dnsServer.Close()
	_, dnsPort, _ := net.SplitHostPort(dnsServer.Listener.Addr().String())

	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		conn, err := echo.Accept()
		if err == nil {
			defer conn.Close()
			_, _ = io.Copy(conn, conn)
		}
	}()
	packetConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	tunnelServer, err := server.NewServer(&server.Config{
		TLSConfig: server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Conn: packetConn,
		Authenticator: acceptAllAuth{},
		Outbound: tcpOnlyOutbound(func(addr string) (net.Conn, error) {
			if addr != "echo.invalid:443" {
				t.Errorf("unexpected tunnel destination %q", addr)
			}
			return net.Dial("tcp", echo.Addr().String())
		}),
	})
	if err != nil {
		packetConn.Close()
		t.Fatal(err)
	}
	defer tunnelServer.Close()
	go func() { _ = tunnelServer.Serve() }()
	_, tunnelPort, _ := net.SplitHostPort(packetConn.LocalAddr().String())

	config, _ := json.Marshal(map[string]any{
		"server": "tunnel-server.invalid:" + tunnelPort,
		"auth": "fixture",
		"tls_sni": "dns.test",
		"tls_ca": string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]})),
	})
	directConfig, _ := json.Marshal(map[string]any{
		"transport": "https",
		"servers": []string{"https://dns.test:" + dnsPort + "/dns-query"},
		"bootstrap": map[string][]string{"dns.test": {"127.0.0.1"}},
	})
	protector := &directTestProtector{allow: true}
	session, err := NewSession(string(config), string(directConfig), protector, &recordingHandler{})
	if err != nil {
		t.Fatalf("encrypted bootstrap and QUIC handshake: %v", err)
	}
	defer session.Close()
	conn, err := session.currentClient().TCP("echo.invalid:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	payload := "Bedlam 1.7.0 encrypted bootstrap"
	if _, err := io.WriteString(conn, payload); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != payload || dnsQueries.Load() == 0 || protector.calls.Load() == 0 {
		t.Fatalf("echo=%q DNS queries=%d protected DNS sockets=%d", response, dnsQueries.Load(), protector.calls.Load())
	}
}
