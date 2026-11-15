package golib

import (
	"context"
	"net"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
)

type denyTCPFlow struct{ calls int }

func (f *denyTCPFlow) Allow(string, int32, string, int32) bool {
	f.calls++
	return false
}

func TestTCPFlowFilterRejectsBeforeForwardingOrDirectDNS(t *testing.T) {
	for _, destination := range []string{"203.0.113.7:443", "172.19.0.2:53"} {
		t.Run(destination, func(t *testing.T) {
			filter := &denyTCPFlow{}
			session := &Session{}
			session.SetTCPFlowFilter(filter)
			connection, peer := net.Pipe()
			defer connection.Close()
			defer peer.Close()
			handler := &tunHandler{session: session}
			err := handler.newConnection(context.Background(), connection,
				M.ParseSocksaddr("172.19.0.1:45000"), M.ParseSocksaddr(destination))
			if err == nil || filter.calls != 1 {
				t.Fatalf("rejected flow: error=%v, filter calls=%d", err, filter.calls)
			}
		})
	}
}
