package ru.shapovalov.hysteria.internal

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val udpEnabled: Boolean) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
