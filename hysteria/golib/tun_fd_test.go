package golib

import (
	"testing"

	"golang.org/x/sys/unix"
)

func TestDuplicatedTunDescriptorHasIndependentLifetime(t *testing.T) {
	fds := []int{-1, -1}
	if err := unix.Pipe(fds); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
	})

	dup, err := unix.Dup(fds[0])
	if err != nil {
		t.Fatal(err)
	}
	if err := unix.Close(dup); err != nil {
		t.Fatal(err)
	}

	if _, err := unix.Write(fds[1], []byte{0x5a}); err != nil {
		t.Fatalf("original descriptor was closed with its duplicate: %v", err)
	}
	buf := make([]byte, 1)
	if _, err := unix.Read(fds[0], buf); err != nil {
		t.Fatal(err)
	}
	if buf[0] != 0x5a {
		t.Fatalf("read %x, want 5a", buf[0])
	}
}
