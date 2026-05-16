package golib

import (
	"fmt"
	"sync/atomic"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	LogLevelDebug = "DEBUG"
	LogLevelInfo  = "INFO"
	LogLevelWarn  = "WARN"
	LogLevelError = "ERROR"
)

const (
	logLevelDebugN = 0
	logLevelInfoN  = 1
	logLevelWarnN  = 2
	logLevelErrorN = 3
)

type LogHandler interface {
	OnLog(level string, message string)
}

type tunLogger struct{}

type tunHandler struct {
	client client.Client
}

var (
	logHandler atomic.Value
	minLevel   atomic.Int32
)

func init() {
	minLevel.Store(logLevelInfoN)
}

func SetLogHandler(handler LogHandler) {
	if handler == nil {
		logHandler.Store((*logHandlerBox)(nil))
		return
	}
	logHandler.Store(&logHandlerBox{h: handler})
}

func SetMinLogLevel(level string) {
	minLevel.Store(int32(levelN(level)))
}

type logHandlerBox struct{ h LogHandler }

func levelN(level string) int {
	switch level {
	case LogLevelDebug:
		return logLevelDebugN
	case LogLevelWarn:
		return logLevelWarnN
	case LogLevelError:
		return logLevelErrorN
	default:
		return logLevelInfoN
	}
}

func log(level, format string, args ...interface{}) {
	if levelN(level) < int(minLevel.Load()) {
		return
	}
	box, _ := logHandler.Load().(*logHandlerBox)
	if box == nil || box.h == nil {
		return
	}
	box.h.OnLog(level, fmt.Sprintf(format, args...))
}

func (l *tunLogger) Trace(args ...any) { log(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Debug(args ...any) { log(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Info(args ...any)  { log(LogLevelInfo, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Warn(args ...any)  { log(LogLevelWarn, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Error(args ...any) { log(LogLevelError, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Fatal(args ...any) {
	log(LogLevelError, "TUN stack fatal: %v", fmt.Sprint(args...))
}
func (l *tunLogger) Panic(args ...any) {
	log(LogLevelError, "TUN stack panic: %v", fmt.Sprint(args...))
}
