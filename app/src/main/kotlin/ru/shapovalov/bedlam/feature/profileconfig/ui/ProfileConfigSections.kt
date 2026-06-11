package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.shapovalov.bedlam.R
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.RealmOptions
import ru.shapovalov.hysteria.config.defaultBandwidthOptions
import ru.shapovalov.hysteria.config.defaultBehaviorOptions
import ru.shapovalov.hysteria.config.defaultCongestionOptions
import ru.shapovalov.hysteria.config.defaultQuicOptions
import ru.shapovalov.hysteria.config.defaultTransportOptions

@Composable
internal fun ServerSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    SectionCard(title = stringResource(R.string.profile_config_section_server)) {
        TextFieldRow(
            label = "address",
            value = draft.server.address,
            editMode = editMode,
            onChange = { onDraftChanged(draft.copy(server = draft.server.copy(address = it))) },
        )
        TextFieldRow(
            label = "auth",
            value = draft.server.auth,
            editMode = editMode,
            caution = stringResource(R.string.profile_config_caution_auth),
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(server = draft.server.copy(auth = it))) },
        )
    }
}

@Composable
internal fun TlsSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val tls = draft.tls
    SectionCard(title = stringResource(R.string.profile_config_section_tls)) {
        TextFieldRow(
            label = "tlsSni",
            value = tls.tlsSni,
            editMode = editMode,
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsSni = it))) },
        )
        SwitchRow(
            label = "tlsInsecure",
            value = tls.tlsInsecure,
            editMode = editMode,
            caution = stringResource(R.string.profile_config_caution_tls_insecure),
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsInsecure = it))) },
        )
        TextFieldRow(
            label = "tlsPinSHA256",
            value = tls.tlsPinSHA256,
            editMode = editMode,
            caution = stringResource(R.string.profile_config_caution_tls_pin),
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsPinSHA256 = it))) },
        )
        TextFieldRow(
            label = "tlsCa",
            value = tls.tlsCa,
            editMode = editMode,
            singleLine = false,
            caution = stringResource(R.string.profile_config_caution_tls_ca),
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsCa = it))) },
        )
        TextFieldRow(
            label = "tlsClientCert",
            value = tls.tlsClientCert,
            editMode = editMode,
            singleLine = false,
            caution = stringResource(R.string.profile_config_caution_tls_client),
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsClientCert = it))) },
        )
        TextFieldRow(
            label = "tlsClientKey",
            value = tls.tlsClientKey,
            editMode = editMode,
            singleLine = false,
            caution = stringResource(R.string.profile_config_caution_tls_client),
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsClientKey = it))) },
        )
    }
}

@Composable
internal fun ObfuscationSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val obfs = draft.obfuscation ?: ObfuscationOptions("", "")
    val caution = stringResource(R.string.profile_config_caution_obfs)
    val isGecko = obfs.obfuscationType.equals("gecko", ignoreCase = true)
    SectionCard(title = stringResource(R.string.profile_config_section_obfuscation)) {
        TextFieldRow(
            label = "obfuscationType",
            value = obfs.obfuscationType,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(obfuscation = obfs.copy(obfuscationType = it))) },
        )
        TextFieldRow(
            label = "obfuscationPassword",
            value = obfs.obfuscationPassword,
            editMode = editMode,
            caution = caution,
            showDivider = isGecko,
            onChange = { onDraftChanged(draft.copy(obfuscation = obfs.copy(obfuscationPassword = it))) },
        )
        if (isGecko) {
            IntFieldRow(
                label = "geckoMinPacketSize",
                value = obfs.geckoMinPacketSize,
                editMode = editMode,
                caution = caution,
                onChange = {
                    onDraftChanged(draft.copy(obfuscation = obfs.copy(geckoMinPacketSize = it)))
                },
            )
            IntFieldRow(
                label = "geckoMaxPacketSize",
                value = obfs.geckoMaxPacketSize,
                editMode = editMode,
                caution = caution,
                showDivider = false,
                onChange = {
                    onDraftChanged(draft.copy(obfuscation = obfs.copy(geckoMaxPacketSize = it)))
                },
            )
        }
    }
}

@Composable
internal fun RealmSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val realm = draft.realm ?: RealmOptions()
    val caution = stringResource(R.string.profile_config_caution_realm)
    SectionCard(title = stringResource(R.string.profile_config_section_realm)) {
        TextFieldRow(
            label = "stunServers",
            value = realm.stunServers.joinToString(", "),
            editMode = editMode,
            caution = caution,
            onChange = { entry ->
                val servers = entry.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
                onDraftChanged(draft.copy(realm = realm.copy(stunServers = servers)))
            },
        )
        IntFieldRow(
            label = "stunTimeoutMs",
            value = realm.stunTimeoutMs,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(realm = realm.copy(stunTimeoutMs = it))) },
        )
        IntFieldRow(
            label = "punchTimeoutMs",
            value = realm.punchTimeoutMs,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(realm = realm.copy(punchTimeoutMs = it))) },
        )
        SwitchRow(
            label = "insecure",
            value = realm.insecure,
            editMode = editMode,
            caution = caution,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(realm = realm.copy(insecure = it))) },
        )
    }
}

@Composable
internal fun QuicSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val quic = draft.quic ?: defaultQuicOptions
    val caution = stringResource(R.string.profile_config_caution_quic)
    SectionCard(title = stringResource(R.string.profile_config_section_quic)) {
        LongFieldRow(
            label = "initStreamReceiveWindow",
            value = quic.initStreamReceiveWindow,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(initStreamReceiveWindow = it))) },
        )
        LongFieldRow(
            label = "maxStreamReceiveWindow",
            value = quic.maxStreamReceiveWindow,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(maxStreamReceiveWindow = it))) },
        )
        LongFieldRow(
            label = "initConnReceiveWindow",
            value = quic.initConnReceiveWindow,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(initConnReceiveWindow = it))) },
        )
        LongFieldRow(
            label = "maxConnReceiveWindow",
            value = quic.maxConnReceiveWindow,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(maxConnReceiveWindow = it))) },
        )
        IntFieldRow(
            label = "maxIdleTimeoutSec",
            value = quic.maxIdleTimeoutSec,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(maxIdleTimeoutSec = it))) },
        )
        IntFieldRow(
            label = "keepAlivePeriodSec",
            value = quic.keepAlivePeriodSec,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(keepAlivePeriodSec = it))) },
        )
        SwitchRow(
            label = "disablePathMTUDiscovery",
            value = quic.disablePathMTUDiscovery,
            editMode = editMode,
            caution = caution,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(disablePathMTUDiscovery = it))) },
        )
    }
}

@Composable
internal fun CongestionSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val congestion = draft.congestion ?: defaultCongestionOptions
    val caution = stringResource(R.string.profile_config_caution_congestion)
    SectionCard(title = stringResource(R.string.profile_config_section_congestion)) {
        TextFieldRow(
            label = "congestionType",
            value = congestion.congestionType,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(congestion = congestion.copy(congestionType = it))) },
        )
        TextFieldRow(
            label = "bbrProfile",
            value = congestion.bbrProfile,
            editMode = editMode,
            caution = caution,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(congestion = congestion.copy(bbrProfile = it))) },
        )
    }
}

@Composable
internal fun BandwidthSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val bandwidth = draft.bandwidth ?: defaultBandwidthOptions
    val caution = stringResource(R.string.profile_config_caution_bandwidth)
    SectionCard(title = stringResource(R.string.profile_config_section_bandwidth)) {
        IntFieldRow(
            label = "maxTxMbps",
            value = bandwidth.maxTxMbps,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(bandwidth = bandwidth.copy(maxTxMbps = it))) },
        )
        IntFieldRow(
            label = "maxRxMbps",
            value = bandwidth.maxRxMbps,
            editMode = editMode,
            caution = caution,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(bandwidth = bandwidth.copy(maxRxMbps = it))) },
        )
    }
}

@Composable
internal fun TransportSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val transport = draft.transport ?: defaultTransportOptions
    val caution = stringResource(R.string.profile_config_caution_hop)
    SectionCard(title = stringResource(R.string.profile_config_section_transport)) {
        IntFieldRow(
            label = "hopIntervalSec",
            value = transport.hopIntervalSec,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(transport = transport.copy(hopIntervalSec = it))) },
        )
        IntFieldRow(
            label = "minHopIntervalSec",
            value = transport.minHopIntervalSec,
            editMode = editMode,
            caution = caution,
            onChange = { onDraftChanged(draft.copy(transport = transport.copy(minHopIntervalSec = it))) },
        )
        IntFieldRow(
            label = "maxHopIntervalSec",
            value = transport.maxHopIntervalSec,
            editMode = editMode,
            caution = caution,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(transport = transport.copy(maxHopIntervalSec = it))) },
        )
    }
}

@Composable
internal fun BehaviorSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val behavior = draft.behavior ?: defaultBehaviorOptions
    SectionCard(title = stringResource(R.string.profile_config_section_behavior)) {
        SwitchRow(
            label = "fastOpen",
            value = behavior.fastOpen,
            editMode = editMode,
            showDivider = false,
            onChange = { onDraftChanged(draft.copy(behavior = behavior.copy(fastOpen = it))) },
        )
    }
}
