package ru.shapovalov.bedlam.core.vpn

import ru.shapovalov.bedlam.core.log.AppLog

sealed interface SelfStop {
    val reason: String
    val logLine: String
        get() = "Tunnel failed: $reason"
    val isWarning: Boolean
        get() = false

    data class Failure(val message: String) : SelfStop {
        override val reason: String
            get() = message.ifBlank { "The tunnel failed" }
    }

    data class Interruption(override val reason: String) : SelfStop {
        override val logLine: String
            get() = "Tunnel interrupted: $reason"
        override val isWarning: Boolean
            get() = true
    }

    data class ReapplyFailure(val error: Throwable) : SelfStop {
        override val reason: String = "Could not apply the changed settings"
        override val logLine: String
            get() = "Tunnel failed: $reason: ${error.describe()}"
    }

    data class StartupFailure(val error: Throwable) : SelfStop {
        override val reason: String
            get() = error.message?.takeIf { it.isNotBlank() } ?: STARTUP_FAILED
        override val logLine: String
            get() = "Tunnel failed: $STARTUP_FAILED: ${error.describe()}"

        private companion object {
            const val STARTUP_FAILED = "Could not start the VPN"
        }
    }

    sealed interface Unstartable : SelfStop {
        override val logLine: String
            get() = "Could not start the tunnel: $reason"
    }

    data object NoActiveProfile : Unstartable {
        override val reason: String = "No active profile"
    }

    data object InvalidConfig : Unstartable {
        override val reason: String = "The profile config could not be read"
    }

    data object ForegroundRefused : Unstartable {
        override val reason: String = "Android refused to start the VPN service"
    }
}

private fun Throwable.describe(): String {
    val name = this::class.java.simpleName
    val detail = message?.takeIf { it.isNotBlank() } ?: return name
    return "$name: $detail"
}

class SelfStopReporter(
    private val appLog: AppLog,
    private val runtimeStateRepository: VpnRuntimeStateRepository,
    private val postAlert: (String) -> Unit,
) {
    suspend fun report(stop: SelfStop) {
        if (stop.isWarning) {
            appLog.warn(AppLog.SOURCE_VPN, stop.logLine)
        } else {
            appLog.error(AppLog.SOURCE_VPN, stop.logLine)
        }
        runtimeStateRepository.markFailed(stop.reason)
        postAlert(stop.reason)
    }
}
