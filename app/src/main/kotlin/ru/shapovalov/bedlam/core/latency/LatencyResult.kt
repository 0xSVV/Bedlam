package ru.shapovalov.bedlam.core.latency

sealed interface LatencyResult {
    data object Idle : LatencyResult
    data object Measuring : LatencyResult
    data class Success(val ms: Long) : LatencyResult
    data object Unreachable : LatencyResult
    data object TimedOut : LatencyResult
    data object NoNetwork : LatencyResult
    data object ConnectionFailed : LatencyResult
}
