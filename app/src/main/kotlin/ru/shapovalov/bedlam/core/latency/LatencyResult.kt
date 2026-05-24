package ru.shapovalov.bedlam.core.latency

sealed interface LatencyResult {
    data object Idle : LatencyResult
    data object Measuring : LatencyResult
    data class Success(val ms: Long) : LatencyResult
    data object Unreachable : LatencyResult
}
