package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

/**
 * Settings for Realm rendezvous mode, used only when the server address is a
 * `realm://` or `realm+http://` URI. STUN servers and the local port can also
 * be supplied as query params on the address itself, which take precedence.
 */
@Serializable
data class RealmOptions(
    val stunServers: List<String> = emptyList(),
    val stunTimeoutMs: Int = 0,
    val punchTimeoutMs: Int = 0,
    val insecure: Boolean = false,
)
