package golib

import (
	"fmt"
	"sync"
)

const (
	LogLevelDebug = "DEBUG"
	LogLevelInfo  = "INFO"
	LogLevelWarn  = "WARN"
	LogLevelError = "ERROR"
)

type LogHandler interface {
	OnLog(level string, message string)
}

var (
	logMu      sync.Mutex
	logHandler LogHandler
)

func SetLogHandler(handler LogHandler) {
	logMu.Lock()
	defer logMu.Unlock()
	logHandler = handler
}

func logMsg(level, format string, args ...interface{}) {
	logMu.Lock()
	h := logHandler
	logMu.Unlock()
	if h != nil {
		h.OnLog(level, fmt.Sprintf(format, args...))
	}
}
