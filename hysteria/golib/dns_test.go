package golib

import "testing"

func TestIsDNSPort(t *testing.T) {
	cases := []struct {
		addr string
		want bool
	}{
		{"1.1.1.1:53", true},
		{"[2001:db8::1]:53", true},
		{"1.1.1.1:54", false},
		{"1.1.1.1:5300", false},
		{"1.1.1.1", false},
		{"", false},
	}
	for _, c := range cases {
		if got := isDNSPort(c.addr); got != c.want {
			t.Errorf("isDNSPort(%q) = %v, want %v", c.addr, got, c.want)
		}
	}
}
