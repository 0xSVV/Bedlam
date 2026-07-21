package golib

import (
	"crypto/tls"
	"errors"
	"testing"

	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

func TestIsTerminal_echRejection(t *testing.T) {
	err := coreErrs.ConnectError{Err: &tls.ECHRejectionError{}}
	if !(&reconnectClient{}).isTerminal(err) {
		t.Error("expected ECH rejection to be terminal")
	}
}

func TestIsTerminal_certFailureWithECH(t *testing.T) {
	err := coreErrs.ConnectError{
		Err: &tls.CertificateVerificationError{Err: errors.New("bad cert")},
	}
	if !(&reconnectClient{echConfigured: true}).isTerminal(err) {
		t.Error("expected cert failure to be terminal when ECH is configured")
	}
	if (&reconnectClient{}).isTerminal(err) {
		t.Error("expected cert failure to stay retryable without ECH")
	}
}

func TestIsTerminal_authAndConfig(t *testing.T) {
	rc := &reconnectClient{}
	if !rc.isTerminal(coreErrs.AuthError{}) {
		t.Error("expected auth error to be terminal")
	}
	if !rc.isTerminal(coreErrs.ConfigError{Field: "x", Reason: "bad"}) {
		t.Error("expected config error to be terminal")
	}
	if rc.isTerminal(errors.New("transient")) {
		t.Error("expected plain error to be retryable")
	}
}
