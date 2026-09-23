package ru.shapovalov.bedlam.feature.profileconfig.ui

import ru.shapovalov.hysteria.HysteriaLink
import ru.shapovalov.hysteria.LinkConnectionGap
import ru.shapovalov.hysteria.LinkTuningGap
import ru.shapovalov.hysteria.buildHysteriaLink
import ru.shapovalov.hysteria.config.HysteriaConfig

internal enum class LinkWarning {
    InsecureWithoutPinElsewhere,
    CustomCa,
    ClientCertificate,
    Quic,
    Congestion,
    Bandwidth,
    HopInterval,
    ObfuscationPacketSize,
}

internal sealed interface LinkExport {
    data class Ready(val uri: String) : LinkExport

    data class NeedsConfirmation(val uri: String, val warnings: List<LinkWarning>) : LinkExport

    data object Unavailable : LinkExport
}

internal fun planLinkExport(config: HysteriaConfig, name: String): LinkExport {
    val link = buildHysteriaLink(config, name)
    val uri = link.uri ?: return LinkExport.Unavailable
    val warnings = link.warnings()
    return if (warnings.isEmpty()) LinkExport.Ready(uri) else LinkExport.NeedsConfirmation(uri, warnings)
}

private fun HysteriaLink.warnings(): List<LinkWarning> = buildList {
    if (insecureIgnoresPinElsewhere) add(LinkWarning.InsecureWithoutPinElsewhere)
    if (LinkConnectionGap.CustomCa in connectionGaps) add(LinkWarning.CustomCa)
    if (LinkConnectionGap.ClientCertificate in connectionGaps) add(LinkWarning.ClientCertificate)
    tuningGaps.sortedBy { it.ordinal }.forEach { add(it.toWarning()) }
}

private fun LinkTuningGap.toWarning(): LinkWarning = when (this) {
    LinkTuningGap.Quic -> LinkWarning.Quic
    LinkTuningGap.Congestion -> LinkWarning.Congestion
    LinkTuningGap.Bandwidth -> LinkWarning.Bandwidth
    LinkTuningGap.HopInterval -> LinkWarning.HopInterval
    LinkTuningGap.ObfuscationPacketSize -> LinkWarning.ObfuscationPacketSize
}
