package golib

import "sync/atomic"

var (
	txBytes atomic.Int64
	rxBytes atomic.Int64
)

func GetTxBytes() int64 {
	return txBytes.Load()
}

func GetRxBytes() int64 {
	return rxBytes.Load()
}

func resetStats() {
	txBytes.Store(0)
	rxBytes.Store(0)
}

func addTx(n int) {
	if n > 0 {
		txBytes.Add(int64(n))
	}
}

func addRx(n int) {
	if n > 0 {
		rxBytes.Add(int64(n))
	}
}
