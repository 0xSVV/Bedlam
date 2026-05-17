package golib

type FdProtector interface {
	Protect(fd int32) bool
}
