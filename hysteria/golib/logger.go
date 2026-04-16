package golib

import (
	"fmt"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
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

type tunLogger struct{}

type tunHandler struct {
	client client.Client
}

var (
	logMutex      sync.Mutex
	logHandler LogHandler
)

func SetLogHandler(handler LogHandler) {
	logMutex.Lock()
	defer logMutex.Unlock()
	logHandler = handler
}

func log(level, format string, args ...interface{}) {
	logMutex.Lock()
	h := logHandler
	logMutex.Unlock()
	if h != nil {
		h.OnLog(level, fmt.Sprintf(format, args...))
	}
}

func (l *tunLogger) Trace(args ...any) { log(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Debug(args ...any) { log(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Info(args ...any)  { log(LogLevelInfo, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Warn(args ...any)  { log(LogLevelWarn, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Error(args ...any) { log(LogLevelError, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Fatal(args ...any) { log(LogLevelError, "TUN stack fatal: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Panic(args ...any) { log(LogLevelError, "TUN stack panic: %v", fmt.Sprint(args...)) }
