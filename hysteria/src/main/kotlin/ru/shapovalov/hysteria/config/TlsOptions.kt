package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class TlsOptions(
    val tlsSni: String,
    val tlsInsecure: Boolean,
    /**
     * SHA-256 hash of the server's leaf certificate (hex, colons/whitespace
     * ignored). Checked *in addition to* standard chain verification — for a
     * self-signed certificate, combine with [tlsInsecure] so the pin becomes
     * the only check.
     */
    val tlsPinSHA256: String,
    val tlsCa: String,
    val tlsClientCert: String,
    val tlsClientKey: String,
    /**
     * Encrypted Client Hello config list: inline base64 or a PEM
     * `ECH CONFIGS` block (from `sing-box generate ech-keypair`). Defaulted so
     * profile JSON persisted before this field existed still deserializes.
     */
    val ech: String = "",
)
