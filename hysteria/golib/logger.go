package golib

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

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

const (
	srcTunnel    = "tunnel"
	srcTun       = "tun"
	srcTunStack  = "tun-stack"
	srcWatchdog  = "watchdog"
	srcDNS       = "dns"
	srcTLS       = "tls"
	srcTransport = "transport"
	srcStream    = "stream"
	srcConfig    = "config"
)

type LogHandler interface {
	OnLog(level string, source string, message string)
}

type tunLogger struct{}

type tunHandler struct {
	session *Session
	client  client.Client
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

func log(level, source, format string, args ...interface{}) {
	if levelN(level) < int(minLevel.Load()) {
		return
	}
	box, _ := logHandler.Load().(*logHandlerBox)
	if box == nil || box.h == nil {
		return
	}
	box.h.OnLog(level, source, fmt.Sprintf(format, args...))
}

type rateLimiter struct {
	interval time.Duration
	mu       sync.Mutex
	last     map[string]time.Time
}

func newRateLimiter(interval time.Duration) *rateLimiter {
	return &rateLimiter{interval: interval, last: make(map[string]time.Time)}
}

func (r *rateLimiter) allow(key string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	if t, ok := r.last[key]; ok && now.Sub(t) < r.interval {
		return false
	}
	r.last[key] = now
	return true
}

func (l *tunLogger) Trace(args ...any) { log(LogLevelDebug, srcTunStack, "%v", fmt.Sprint(args...)) }
func (l *tunLogger) Debug(args ...any) { log(LogLevelDebug, srcTunStack, "%v", fmt.Sprint(args...)) }
func (l *tunLogger) Info(args ...any)  { log(LogLevelInfo, srcTunStack, "%v", fmt.Sprint(args...)) }
func (l *tunLogger) Warn(args ...any)  { log(LogLevelWarn, srcTunStack, "%v", fmt.Sprint(args...)) }
func (l *tunLogger) Error(args ...any) { log(LogLevelError, srcTunStack, "%v", fmt.Sprint(args...)) }
func (l *tunLogger) Fatal(args ...any) {
	log(LogLevelError, srcTunStack, "fatal: %v", fmt.Sprint(args...))
}
func (l *tunLogger) Panic(args ...any) {
	log(LogLevelError, srcTunStack, "panic: %v", fmt.Sprint(args...))
}
