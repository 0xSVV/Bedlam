package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DiagnosticResult
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.config.HysteriaConfig

class FakeHysteriaClient(
    initial: ConnectionState = ConnectionState.Disconnected(),
) : HysteriaClient {

    val connectionState = MutableStateFlow(initial)
    val logEntries = MutableSharedFlow<HysteriaClient.LogEntry>(
        replay = 256,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    var validation: (HysteriaConfig) -> Result<Unit> = { Result.success(Unit) }
    var validateCalls = 0
        private set

    override val state: StateFlow<ConnectionState> = connectionState

    override fun validateConfig(config: HysteriaConfig): Result<Unit> {
        validateCalls++
        return validation(config)
    }

    override suspend fun start(
        config: HysteriaConfig,
        tunConfig: TunConfig,
        protector: HysteriaClient.SocketProtector,
        tun: HysteriaClient.TunFactory,
    ) = Unit

    override suspend fun updateTun(tunConfig: TunConfig, tun: HysteriaClient.TunFactory) = Unit
    override suspend fun stop(reason: DisconnectReason) = Unit
    override fun shutdown(reason: DisconnectReason) = Unit
    override suspend fun closeSession() = Unit
    override suspend fun resetConnections() = Unit
    override suspend fun checkConnection() = Unit
    override fun stats(): HysteriaClient.TrafficStats? = null

    override fun logs(minLevel: HysteriaClient.LogLevel): Flow<HysteriaClient.LogEntry> =
        logEntries.filter { it.level >= minLevel }

    override suspend fun testUdp(): DiagnosticResult = DiagnosticResult.Error("unused")
    override suspend fun testDnsOverTcp(): DiagnosticResult = DiagnosticResult.Error("unused")
}
