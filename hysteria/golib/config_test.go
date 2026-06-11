package golib

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestIsPortHopping(t *testing.T) {
	cases := []struct {
		port string
		want bool
	}{
		{"443", false},
		{"8000-9000", true},
		{"8000,8100,8200", true},
		{"", false},
		{"abc", false},
	}
	for _, c := range cases {
		if got := isPortHopping(c.port); got != c.want {
			t.Errorf("isPortHopping(%q) = %v, want %v", c.port, got, c.want)
		}
	}
}

func TestNormalizeCertHash(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"AB:CD:EF", "abcdef"},
		{"abcdef", "abcdef"},
		{"AB-CD", "ab-cd"},
		{" AB:CD \n", "abcd"},
		{"ab cd\tef", "abcdef"},
		{"", ""},
	}
	for _, c := range cases {
		if got := normalizeCertHash(c.in); got != c.want {
			t.Errorf("normalizeCertHash(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestAuthSummary(t *testing.T) {
	if got := authSummary(""); got != "absent" {
		t.Errorf("authSummary(\"\") = %q, want absent", got)
	}
	if got := authSummary("secret"); !strings.Contains(got, "6 chars") {
		t.Errorf("authSummary did not report length: %q", got)
	}
}

func TestBuildHopInterval_defaults(t *testing.T) {
	cfg := &clientConfig{}
	h := buildHopInterval(cfg)
	if h.Min != 30*time.Second || h.Max != 30*time.Second {
		t.Errorf("default hop = [%v,%v], want [30s,30s]", h.Min, h.Max)
	}
}

func TestBuildHopInterval_singleInterval(t *testing.T) {
	cfg := &clientConfig{HopIntervalSec: 10}
	h := buildHopInterval(cfg)
	if h.Min != 10*time.Second || h.Max != 10*time.Second {
		t.Errorf("hop = [%v,%v], want [10s,10s]", h.Min, h.Max)
	}
}

func TestBuildHopInterval_minMaxRange(t *testing.T) {
	cfg := &clientConfig{MinHopIntervalSec: 5, MaxHopIntervalSec: 20}
	h := buildHopInterval(cfg)
	if h.Min != 5*time.Second || h.Max != 20*time.Second {
		t.Errorf("hop = [%v,%v], want [5s,20s]", h.Min, h.Max)
	}
}

func TestBuildHopInterval_minMaxPreferredOverSingle(t *testing.T) {
	cfg := &clientConfig{HopIntervalSec: 99, MinHopIntervalSec: 5, MaxHopIntervalSec: 20}
	h := buildHopInterval(cfg)
	if h.Min != 5*time.Second || h.Max != 20*time.Second {
		t.Errorf("hop = [%v,%v], want [5s,20s]", h.Min, h.Max)
	}
}

func TestValidateConfig_minimum(t *testing.T) {
	if err := ValidateConfig(`{"server":"host.example:443"}`); err != nil {
		t.Errorf("expected nil, got %v", err)
	}
}

func TestValidateConfig_invalidJSON(t *testing.T) {
	if err := ValidateConfig("{not json"); err == nil {
		t.Error("expected error for invalid JSON")
	}
}

func TestValidateConfig_missingServer(t *testing.T) {
	if err := ValidateConfig(`{}`); err == nil {
		t.Error("expected error for missing server")
	}
}

func TestValidateConfig_invalidServerAddress(t *testing.T) {
	if err := ValidateConfig(`{"server":"host.example"}`); err == nil {
		t.Error("expected error for missing port")
	}
}

func TestValidateConfig_invalidCA(t *testing.T) {
	if err := ValidateConfig(`{"server":"host.example:443","tls_ca":"not a pem"}`); err == nil {
		t.Error("expected error for bad CA PEM")
	}
}

func TestValidateConfig_partialMTLSPair(t *testing.T) {
	js := `{"server":"host.example:443","tls_client_cert":"only-cert"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for cert without key")
	}
}

func TestValidateConfig_obfsCaseInsensitive(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"Salamander","obfs_password":"x"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected case-insensitive accept, got %v", err)
	}
}

func TestValidateConfig_obfsRejectsUnknown(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"foo"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for unknown obfs")
	}
}

func TestValidateConfig_obfsRequiresPassword(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"salamander"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for missing obfs password")
	}
}

func TestValidateConfig_acceptsRealmAddr(t *testing.T) {
	js := `{"server":"realm://token@rv.example/my-realm","auth":"pw"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected realm address to pass, got %v", err)
	}
}

func TestValidateConfig_acceptsRealmHTTPAddr(t *testing.T) {
	js := `{"server":"realm+http://token@rv.example:8080/my-realm","auth":"pw"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected realm+http address to pass, got %v", err)
	}
}

func TestValidateConfig_rejectsRealmWithoutToken(t *testing.T) {
	js := `{"server":"realm://rv.example/my-realm"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for realm address without token")
	}
}

func TestValidateConfig_rejectsRealmWithoutRealmID(t *testing.T) {
	js := `{"server":"realm://token@rv.example/"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for realm address without realm id")
	}
}

func TestIsRealmAddr(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"realm://t@h/r", true},
		{"realm+http://t@h/r", true},
		{"host.example:443", false},
		{"realmx://t@h/r", false},
		{"", false},
	}
	for _, c := range cases {
		if got := isRealmAddr(c.in); got != c.want {
			t.Errorf("isRealmAddr(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestValidateConfig_acceptsPlainObfs(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"plain"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected plain to pass, got %v", err)
	}
}

func TestValidateConfig_geckoRequiresPassword(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"gecko"}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for missing gecko password")
	}
}

func TestValidateConfig_geckoDefaultsPass(t *testing.T) {
	js := `{"server":"host.example:443","obfs_type":"Gecko","obfs_password":"x"}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected gecko with default sizes to pass, got %v", err)
	}
}

func TestValidateConfig_geckoSizes(t *testing.T) {
	cases := []struct {
		min, max int
		ok       bool
	}{
		{0, 0, true},
		{512, 1200, true},
		{1, 2048, true},
		{600, 0, true},   // max defaults to 1200
		{1300, 0, false}, // max defaults to 1200 < min
		{0, 100, false},  // min defaults to 512 > max
		{-1, 1200, false},
		{512, 4096, false},
		{1200, 512, false},
	}
	for _, c := range cases {
		js := fmt.Sprintf(
			`{"server":"host.example:443","obfs_type":"gecko","obfs_password":"x","obfs_gecko_min_packet":%d,"obfs_gecko_max_packet":%d}`,
			c.min, c.max)
		err := ValidateConfig(js)
		if c.ok && err != nil {
			t.Errorf("sizes [%d,%d]: expected pass, got %v", c.min, c.max, err)
		}
		if !c.ok && err == nil {
			t.Errorf("sizes [%d,%d]: expected error", c.min, c.max)
		}
	}
}

func TestValidateConfig_rejectsInvertedHopInterval(t *testing.T) {
	js := `{"server":"host.example:8000-9000","min_hop_interval":30,"max_hop_interval":10}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for min > max")
	}
}

func TestValidateConfig_rejectsTooSmallWindow(t *testing.T) {
	js := `{"server":"host.example:443","max_stream_receive_window":1000}`
	if err := ValidateConfig(js); err == nil {
		t.Error("expected error for window below 16384")
	}
}

func TestValidateConfig_acceptsZeroWindow(t *testing.T) {
	js := `{"server":"host.example:443","max_stream_receive_window":0}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected zero (= use default) to pass, got %v", err)
	}
}

func TestValidateConfig_rejectsIdleTimeoutOutOfRange(t *testing.T) {
	for _, sec := range []int{3, 121} {
		js := fmt.Sprintf(`{"server":"host.example:443","max_idle_timeout":%d}`, sec)
		if err := ValidateConfig(js); err == nil {
			t.Errorf("expected error for max_idle_timeout=%d", sec)
		}
	}
}

func TestValidateConfig_rejectsKeepAliveOutOfRange(t *testing.T) {
	for _, sec := range []int{1, 61} {
		js := fmt.Sprintf(`{"server":"host.example:443","keep_alive_period":%d}`, sec)
		if err := ValidateConfig(js); err == nil {
			t.Errorf("expected error for keep_alive_period=%d", sec)
		}
	}
}

func TestValidateConfig_acceptsInRangeQUIC(t *testing.T) {
	js := `{"server":"host.example:443","max_stream_receive_window":16384,"max_idle_timeout":30,"keep_alive_period":10}`
	if err := ValidateConfig(js); err != nil {
		t.Errorf("expected in-range QUIC params to pass, got %v", err)
	}
}
