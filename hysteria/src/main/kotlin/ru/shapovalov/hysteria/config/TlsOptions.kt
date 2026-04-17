package ru.shapovalov.hysteria.config

data class TlsOptions(
    val tlsSni: String,
    val tlsInsecure: Boolean,
    val tlsPinSHA256: String,
    val tlsCa: String,
    val tlsClientCert: String,
    val tlsClientKey: String,
)
