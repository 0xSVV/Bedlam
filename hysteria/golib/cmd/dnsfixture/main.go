// dnsfixture is a local-only integration fixture for emulator VPN tests.
// Run from golib: go run ./cmd/dnsfixture
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
	"golang.org/x/net/dns/dnsmessage"
)

var queries = struct {
	sync.Mutex
	counts map[string]int
}{counts: map[string]int{}}

func answer(query []byte, transport string) []byte {
	var message dnsmessage.Message
	if err := message.Unpack(query); err != nil || len(message.Questions) != 1 {
		return nil
	}
	question := message.Questions[0]
	name := strings.TrimSuffix(question.Name.String(), ".")
	queries.Lock()
	queries.counts[transport+"|"+name]++
	queries.Unlock()
	message.Header.Response = true
	message.Header.RecursionAvailable = true
	message.Additionals = nil
	message.Answers = nil
	if question.Type == dnsmessage.TypeA {
		ip := [4]byte{203, 0, 113, 7}
		if name == "fixture-server.test" {
			ip = [4]byte{10, 0, 2, 2}
		}
		message.Answers = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 5},
			Body: &dnsmessage.AResource{A: ip},
		}}
	}
	response, _ := message.Pack()
	return response
}

func serveStream(listener net.Listener, transport string) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			for {
				var size [2]byte
				if _, err := io.ReadFull(conn, size[:]); err != nil {
					return
				}
				query := make([]byte, binary.BigEndian.Uint16(size[:]))
				if _, err := io.ReadFull(conn, query); err != nil {
					return
				}
				response := answer(query, transport)
				binary.BigEndian.PutUint16(size[:], uint16(len(response)))
				if _, err := conn.Write(append(size[:], response...)); err != nil {
					return
				}
			}
		}()
	}
}

type auth struct{}
func (auth) Authenticate(_ net.Addr, password string, _ uint64) (bool, string) {
	return password == "bedlam-1.7.0-fixture", "fixture"
}

type outbound struct{}
func (outbound) TCP(addr string) (net.Conn, error) {
	if strings.HasSuffix(addr, ":53") {
		return net.Dial("tcp", "127.0.0.1:15354")
	}
	return net.Dial("tcp", "127.0.0.1:18080")
}
func (outbound) UDP(string) (server.UDPConn, error) { return nil, fmt.Errorf("fixture supports TCP tunnel traffic") }
func (outbound) CheckUDP(string) error { return fmt.Errorf("fixture supports TCP tunnel traffic") }

func certificate() tls.Certificate {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil { log.Fatal(err) }
	template := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject: pkix.Name{CommonName: "Bedlam local integration fixture"},
		NotBefore: time.Now().Add(-time.Hour),
		NotAfter: time.Now().Add(48*time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA: true,
		DNSNames: []string{"dns.fixture.test", "fixture-server.test"},
		IPAddresses: []net.IP{net.ParseIP("10.0.2.2"), net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil { log.Fatal(err) }
	dir := filepath.Join("build", "dnsfixture")
	if err := os.MkdirAll(dir, 0700); err != nil { log.Fatal(err) }
	if err := os.WriteFile(filepath.Join(dir, "ca.pem"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil { log.Fatal(err) }
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func main() {
	cert := certificate()
	for port, transport := range map[string]string{"15353":"direct-tcp", "15354":"remote-tcp"} {
		listener, err := net.Listen("tcp", "127.0.0.1:"+port)
		if err != nil { log.Fatal(err) }
		go serveStream(listener, transport)
	}
	udp, err := net.ListenPacket("udp", "127.0.0.1:15353")
	if err != nil { log.Fatal(err) }
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, addr, err := udp.ReadFrom(buffer)
			if err != nil { return }
			_, _ = udp.WriteTo(answer(buffer[:n], "direct-udp"), addr)
		}
	}()
	dot, err := tls.Listen("tcp", "127.0.0.1:18530", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil { log.Fatal(err) }
	go serveStream(dot, "direct-tls")
	doh := &http.Server{
		Addr: "127.0.0.1:18444",
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}},
		ReadHeaderTimeout: 5*time.Second,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			body, err := io.ReadAll(io.LimitReader(r.Body, 65536))
			if err != nil { http.Error(w, "query", 400); return }
			w.Header().Set("Content-Type", "application/dns-message")
			_, _ = w.Write(answer(body, "direct-https"))
		}),
	}
	go func() { log.Fatal(doh.ListenAndServeTLS("", "")) }()
	packet, err := net.ListenPacket("udp", "127.0.0.1:18443")
	if err != nil { log.Fatal(err) }
	tunnel, err := server.NewServer(&server.Config{
		Conn: packet,
		TLSConfig: server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Authenticator: auth{},
		Outbound: outbound{},
	})
	if err != nil { log.Fatal(err) }
	go func() { log.Fatal(tunnel.Serve()) }()
	http.HandleFunc("/stats", func(w http.ResponseWriter, _ *http.Request) {
		queries.Lock()
		defer queries.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(queries.counts)
	})
	http.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "bedlam-fixture-ok") })
	log.Print("ready: Hysteria 18443, direct DNS 15353, DoT 18530, DoH 18444, HTTP/stats 18080")
	log.Fatal(http.ListenAndServe("127.0.0.1:18080", nil))
}
