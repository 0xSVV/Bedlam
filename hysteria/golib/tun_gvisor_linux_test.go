//go:build with_gvisor

package golib

import (
	"slices"
	"testing"

	"github.com/sagernet/gvisor/pkg/tcpip/link/fdbased"
)

func TestTunReadsPacketsIntoViewsSizedLikeGVisorsDefault(t *testing.T) {
	want := []int{128, 256, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768}
	if !slices.Equal(fdbased.BufConfig, want) {
		t.Errorf("fdbased.BufConfig = %v, want %v so a queued segment does not hold a 64 KiB view", fdbased.BufConfig, want)
	}
}
