package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponent
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.defaultBandwidthOptions
import ru.shapovalov.hysteria.config.defaultBehaviorOptions
import ru.shapovalov.hysteria.config.defaultCongestionOptions
import ru.shapovalov.hysteria.config.defaultQuicOptions
import ru.shapovalov.hysteria.config.defaultTransportOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileConfigContent(component: ProfileConfigComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val saveErrorMessage = state.saveError?.let {
        stringResource(R.string.profile_config_save_error, it)
    }
    LaunchedEffect(saveErrorMessage) {
        val msg = saveErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        component.onDismissError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.original?.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.profile_config_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackPressed) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = { TopActions(state, component) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CenteredSpinner()
                state.notFound -> NotFoundMessage()
                state.draft != null -> ConfigBody(
                    draft = state.draft!!,
                    editMode = state.editMode,
                    onDraftChanged = component::onDraftChanged,
                )
            }
        }
    }
}

@Composable
private fun TopActions(
    state: ProfileConfigStore.State,
    component: ProfileConfigComponent,
) {
    val draftReady = state.draft != null && !state.isLoading && !state.notFound
    if (!draftReady) return

    if (!state.editMode) {
        IconButton(onClick = component::onEnterEditMode) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.profile_config_action_edit),
            )
        }
        return
    }

    TextButton(onClick = component::onDiscardChanges, enabled = !state.isSaving) {
        Text(stringResource(R.string.action_cancel))
    }
    TextButton(
        onClick = component::onSave,
        enabled = !state.isSaving && state.isDirty,
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun ConfigBody(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        ServerSection(draft, editMode, onDraftChanged)
        TlsSection(draft, editMode, onDraftChanged)
        ObfuscationSection(draft, editMode, onDraftChanged)
        QuicSection(draft, editMode, onDraftChanged)
        CongestionSection(draft, editMode, onDraftChanged)
        BandwidthSection(draft, editMode, onDraftChanged)
        TransportSection(draft, editMode, onDraftChanged)
        BehaviorSection(draft, editMode, onDraftChanged)
        Spacer(Modifier.height(spacing.large))
    }
}

@Composable
private fun ServerSection(
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
            onChange = { onDraftChanged(draft.copy(server = draft.server.copy(auth = it))) },
        )
    }
}

@Composable
private fun TlsSection(
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
            onChange = { onDraftChanged(draft.copy(tls = tls.copy(tlsClientKey = it))) },
        )
    }
}

@Composable
private fun ObfuscationSection(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val obfs = draft.obfuscation ?: ObfuscationOptions("", "")
    val caution = stringResource(R.string.profile_config_caution_obfs)
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
            onChange = { onDraftChanged(draft.copy(obfuscation = obfs.copy(obfuscationPassword = it))) },
        )
    }
}

@Composable
private fun QuicSection(
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
            onChange = { onDraftChanged(draft.copy(quic = quic.copy(disablePathMTUDiscovery = it))) },
        )
    }
}

@Composable
private fun CongestionSection(
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
            onChange = { onDraftChanged(draft.copy(congestion = congestion.copy(bbrProfile = it))) },
        )
    }
}

@Composable
private fun BandwidthSection(
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
            onChange = { onDraftChanged(draft.copy(bandwidth = bandwidth.copy(maxRxMbps = it))) },
        )
    }
}

@Composable
private fun TransportSection(
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
            onChange = { onDraftChanged(draft.copy(transport = transport.copy(maxHopIntervalSec = it))) },
        )
    }
}

@Composable
private fun BehaviorSection(
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
            onChange = { onDraftChanged(draft.copy(behavior = behavior.copy(fastOpen = it))) },
        )
        SwitchRow(
            label = "lazy",
            value = behavior.lazy,
            editMode = editMode,
            onChange = { onDraftChanged(draft.copy(behavior = behavior.copy(lazy = it))) },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small),
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = spacing.large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                modifier = Modifier.padding(top = spacing.xSmall),
                verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
                content = content,
            )
        }
    }
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    editMode: Boolean,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    caution: String? = null,
) {
    FieldRowFrame(label = label, caution = caution.takeIf { editMode }) {
        if (editMode) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ReadOnlyValue(value = value)
        }
    }
}

@Composable
private fun IntFieldRow(
    label: String,
    value: Int,
    editMode: Boolean,
    onChange: (Int) -> Unit,
    caution: String? = null,
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
        parse = { it.toIntOrNull() },
        onChange = onChange,
    )
}

@Composable
private fun LongFieldRow(
    label: String,
    value: Long,
    editMode: Boolean,
    onChange: (Long) -> Unit,
    caution: String? = null,
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
        parse = { it.toLongOrNull() },
        onChange = onChange,
    )
}

@Composable
private fun <T> NumericFieldRow(
    label: String,
    text: String,
    editMode: Boolean,
    caution: String?,
    parse: (String) -> T?,
    onChange: (T) -> Unit,
) {
    FieldRowFrame(label = label, caution = caution.takeIf { editMode }) {
        if (editMode) {
            var local by remember(text) { mutableStateOf(text) }
            OutlinedTextField(
                value = local,
                onValueChange = { entry ->
                    local = entry
                    val source = entry.ifEmpty { "0" }
                    parse(source)?.let(onChange)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ReadOnlyValue(value = text)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    editMode: Boolean,
    onChange: (Boolean) -> Unit,
    caution: String? = null,
) {
    if (!editMode) {
        FieldRowFrame(label = label, caution = null) {
            ReadOnlyValue(value = value.toString())
        }
        return
    }
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.xSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = value, onCheckedChange = onChange)
        }
        CautionLabel(caution)
    }
}

@Composable
private fun FieldRowFrame(
    label: String,
    caution: String?,
    content: @Composable () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.xSmall),
        verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        CautionLabel(caution)
    }
}

@Composable
private fun CautionLabel(caution: String?) {
    if (caution.isNullOrBlank()) return
    Text(
        text = caution,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ReadOnlyValue(value: String) {
    Text(
        text = value.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_config_field_empty),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotFoundMessage() {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.profile_config_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
