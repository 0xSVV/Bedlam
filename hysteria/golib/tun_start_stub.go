//go:build !with_gvisor

package golib

import singtun "github.com/sagernet/sing-tun"

func deferTunRead(tun singtun.Tun) (singtun.Tun, func()) {
	return tun, func() {}
}
