//go:build with_gvisor

package golib

import "github.com/sagernet/gvisor/pkg/tcpip/link/fdbased"

var tunReadViewSizes = []int{128, 256, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768}

func init() {
	fdbased.BufConfig = tunReadViewSizes
}
