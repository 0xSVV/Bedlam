package golib

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"testing"
)

func testECHConfigList(t *testing.T) []byte {
	t.Helper()
	entry := []byte{0xfe, 0x0d, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04}
	list := make([]byte, 2+len(entry))
	binary.BigEndian.PutUint16(list, uint16(len(entry)))
	copy(list[2:], entry)
	if err := validateECHConfigList(list); err != nil {
		t.Fatalf("test fixture is invalid: %v", err)
	}
	return list
}

func TestParseECHConfigList_base64(t *testing.T) {
	list := testECHConfigList(t)
	for name, enc := range map[string]*base64.Encoding{
		"std":    base64.StdEncoding,
		"rawStd": base64.RawStdEncoding,
		"url":    base64.URLEncoding,
		"rawURL": base64.RawURLEncoding,
	} {
		got, err := parseECHConfigList(enc.EncodeToString(list))
		if err != nil {
			t.Errorf("%s: unexpected error: %v", name, err)
			continue
		}
		if !bytes.Equal(got, list) {
			t.Errorf("%s: round-trip mismatch", name)
		}
	}
}

func TestParseECHConfigList_base64Whitespace(t *testing.T) {
	list := testECHConfigList(t)
	got, err := parseECHConfigList("  " + base64.StdEncoding.EncodeToString(list) + "\n")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !bytes.Equal(got, list) {
		t.Fatal("round-trip mismatch")
	}
}

func TestParseECHConfigList_pemBlock(t *testing.T) {
	list := testECHConfigList(t)
	pemText := pem.EncodeToMemory(&pem.Block{Type: pemBlockECHConfigs, Bytes: list})
	got, err := parseECHConfigList(string(pemText))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !bytes.Equal(got, list) {
		t.Fatal("PEM round-trip mismatch")
	}
}

func TestParseECHConfigList_wrongPEMType(t *testing.T) {
	list := testECHConfigList(t)
	pemText := pem.EncodeToMemory(&pem.Block{Type: "ECH KEYS", Bytes: list})
	if _, err := parseECHConfigList(string(pemText)); err == nil {
		t.Fatal("expected error for wrong PEM block type")
	}
}

func TestParseECHConfigList_empty(t *testing.T) {
	for _, s := range []string{"", "   ", "\n"} {
		if _, err := parseECHConfigList(s); err == nil {
			t.Errorf("expected error for %q", s)
		}
	}
}

func TestParseECHConfigList_garbage(t *testing.T) {
	if _, err := parseECHConfigList("not ech at all"); err == nil {
		t.Fatal("expected error for garbage input")
	}
}

func TestParseECHConfigList_filePathRejected(t *testing.T) {
	if _, err := parseECHConfigList("/etc/ech.pem"); err == nil {
		t.Fatal("expected error for a file path")
	}
}

func TestValidateECHConfigList_trailingData(t *testing.T) {
	list := append(testECHConfigList(t), 0x00)
	if err := validateECHConfigList(list); err == nil {
		t.Fatal("expected error for trailing data")
	}
}

func TestValidateECHConfigList_truncated(t *testing.T) {
	list := testECHConfigList(t)
	if err := validateECHConfigList(list[:len(list)-1]); err == nil {
		t.Fatal("expected error for truncated list")
	}
}

func TestValidateECHConfigList_emptyList(t *testing.T) {
	if err := validateECHConfigList([]byte{0x00, 0x00}); err == nil {
		t.Fatal("expected error for list with no configs")
	}
}

func echList(entries ...[]byte) []byte {
	var body []byte
	for _, e := range entries {
		body = append(body, e...)
	}
	list := make([]byte, 2+len(body))
	binary.BigEndian.PutUint16(list, uint16(len(body)))
	copy(list[2:], body)
	return list
}

func TestValidateECHConfigList_unsupportedVersionOnly(t *testing.T) {
	list := echList([]byte{0xfe, 0x0c, 0x00, 0x02, 0xaa, 0xbb})
	if err := validateECHConfigList(list); err == nil {
		t.Fatal("expected error for list without a supported-version config")
	}
}

func TestValidateECHConfigList_mixedVersions(t *testing.T) {
	list := echList(
		[]byte{0xfe, 0x0c, 0x00, 0x02, 0xaa, 0xbb},
		[]byte{0xfe, 0x0d, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04},
	)
	if err := validateECHConfigList(list); err != nil {
		t.Fatalf("expected mixed list with one supported config to pass, got %v", err)
	}
}

func TestValidateConfig_validECH(t *testing.T) {
	list := testECHConfigList(t)
	js := `{"server":"host.example:443","tls_ech":"` + base64.StdEncoding.EncodeToString(list) + `"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected valid ECH to pass, got %v", err)
	}
}

func TestValidateConfig_invalidECH(t *testing.T) {
	js := `{"server":"host.example:443","tls_ech":"bm90IGVjaA=="}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for invalid ECH config list")
	}
}
