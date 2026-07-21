package golib

import (
	"encoding/binary"
	"testing"
)

func TestBuildServFail(t *testing.T) {
	query := buildDNSQuery()
	resp := buildServFail(query)
	if resp == nil {
		t.Fatal("expected a response")
	}
	if binary.BigEndian.Uint16(resp[0:2]) != binary.BigEndian.Uint16(query[0:2]) {
		t.Error("transaction ID must be preserved")
	}
	if resp[2]&0x80 == 0 {
		t.Error("QR bit must be set")
	}
	if resp[3]&0x0f != 0x02 {
		t.Errorf("RCODE must be SERVFAIL (2), got %d", resp[3]&0x0f)
	}
	if binary.BigEndian.Uint16(resp[6:8]) != 0 {
		t.Error("ANCOUNT must be zero")
	}
	if binary.BigEndian.Uint16(resp[4:6]) != binary.BigEndian.Uint16(query[4:6]) {
		t.Error("QDCOUNT must be preserved")
	}
}

func TestBuildServFail_tooShort(t *testing.T) {
	if buildServFail([]byte{0x00, 0x01}) != nil {
		t.Error("expected nil for a malformed query")
	}
}
