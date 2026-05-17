package golib

type EventHandler interface {
	OnConnected(udpEnabled bool)
	OnReconnecting(attempt int32, reason string)
	OnError(message string)
}

type loggingHandler struct {
	inner EventHandler
}

func (h *loggingHandler) OnConnected(udpEnabled bool) {
	log(LogLevelInfo, "Connected (UDP: %v)", udpEnabled)
	if h.inner != nil {
		h.inner.OnConnected(udpEnabled)
	}
}

func (h *loggingHandler) OnReconnecting(attempt int32, reason string) {
	log(LogLevelWarn, "Reconnecting (attempt %d): %s", attempt, reason)
	if h.inner != nil {
		h.inner.OnReconnecting(attempt, reason)
	}
}

func (h *loggingHandler) OnError(message string) {
	log(LogLevelError, "Tunnel error: %s", message)
	if h.inner != nil {
		h.inner.OnError(message)
	}
}
