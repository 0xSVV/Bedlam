package ru.shapovalov.hysteria.config

val defaultTlsOptions = TlsOptions(
    tlsSni = "",
    tlsInsecure = true,
    tlsPinSHA256 = "",
    tlsCa = "",
    tlsClientCert = "",
    tlsClientKey = ""
)

val defaultQuicOptions = QuicOptions(
    initStreamReceiveWindow = 0,
    maxStreamReceiveWindow = 0,
    initConnReceiveWindow = 0,
    maxConnReceiveWindow = 0,
    maxIdleTimeoutSec = 0,
    keepAlivePeriodSec = 0,
    disablePathMTUDiscovery = true
)

val defaultCongestionOptions = CongestionOptions(
    congestionType = "",
    bbrProfile = ""
)

val defaultBandwidthOptions = BandwidthOptions(
    maxTxMbps = 0,
    maxRxMbps = 0
)

val defaultTransportOptions = TransportOptions(
    hopIntervalSec = 0,
    minHopIntervalSec = 0,
    maxHopIntervalSec = 0
)

val defaultBehaviorOptions = BehaviorOptions(
    fastOpen = true,
    lazy = true
)


