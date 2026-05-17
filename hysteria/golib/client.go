package golib

type EventHandler interface {
	OnConnected(udpEnabled bool, attempt int32)

	OnReconnecting(attempt int32, reason string)

	OnError(message string)
}

type loggingHandler struct {
	inner EventHandler
}

func (h *loggingHandler) OnConnected(udpEnabled bool, attempt int32) {
	if attempt == 0 {
		log(LogLevelInfo, srcTunnel, "Connected (UDP: %v)", udpEnabled)
	} else {
		log(LogLevelInfo, srcTunnel, "Reconnected (UDP: %v, attempts=%d)", udpEnabled, attempt)
	}
	if h.inner != nil {
		h.inner.OnConnected(udpEnabled, attempt)
	}
}

func (h *loggingHandler) OnReconnecting(attempt int32, reason string) {
	log(LogLevelWarn, srcTunnel, "Reconnecting (attempt %d): %s", attempt, reason)
	if h.inner != nil {
		h.inner.OnReconnecting(attempt, reason)
	}
}

func (h *loggingHandler) OnError(message string) {
	log(LogLevelError, srcTunnel, "Tunnel error: %s", message)
	if h.inner != nil {
		h.inner.OnError(message)
	}
}
