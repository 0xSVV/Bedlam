package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.feature.routing.presentation.DirectDnsInput
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.DnsTransport

@Composable
internal fun DirectDnsCard(
    config: RoutingConfig,
    onSave: (DnsMode, DnsTransport, List<String>, Map<String, List<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(config.directDnsMode, config.directDnsTransport, config.directCustomDns, config.directDnsBootstrap) {
        DirectDnsEditor(config, onSave, modifier)
    }
}

@Composable
private fun DirectDnsEditor(
    config: RoutingConfig,
    onSave: (DnsMode, DnsTransport, List<String>, Map<String, List<String>>) -> Unit,
    modifier: Modifier,
) {
    val initialServers = config.directCustomDns.joinToString("\n")
    val initialBootstrap = config.directDnsBootstrap.entries.joinToString("\n") { (key, value) ->
        "$key=${value.joinToString(", ")}"
    }
    var modeName by rememberSaveable { mutableStateOf(config.directDnsMode.name) }
    var transportName by rememberSaveable { mutableStateOf(config.directDnsTransport.name) }
    var servers by rememberSaveable { mutableStateOf(initialServers) }
    var bootstrap by rememberSaveable { mutableStateOf(initialBootstrap) }
    val mode = DnsMode.valueOf(modeName)
    val transport = DnsTransport.valueOf(transportName)
    val transports = directTransports(mode)
    val parsed = remember(mode, transport, servers, bootstrap) {
        runCatching { DirectDnsInput.parse(mode, transport, servers, bootstrap) }.getOrNull()
    }
    val changed = (mode != config.directDnsMode) || (transport != config.directDnsTransport) ||
        (servers != initialServers) || (bootstrap != initialBootstrap)
    val focus = LocalFocusManager.current
    RoutingCard(modifier) {
        CardTitle(stringResource(R.string.routing_direct_dns_title))
        DropdownRow(
            title = stringResource(R.string.routing_dns_provider_title),
            value = mode.label(),
            options = DnsMode.entries,
            renderLabel = { it.label() },
            onPick = {
                modeName = it.name
                if (transport !in directTransports(it)) {
                    transportName = if (it == DnsMode.System) DnsTransport.Tcp.name
                    else directTransports(it).last().name
                }
            },
        )
        DropdownRow(
            title = stringResource(R.string.routing_dns_transport_title),
            value = transport.label(),
            options = transports,
            renderLabel = { it.label() },
            onPick = { transportName = it.name },
        )
        Column(Modifier.padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.small)) {
            Text(
                stringResource(R.string.routing_direct_dns_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode == DnsMode.Custom) {
                OutlinedTextField(
                    value = servers,
                    onValueChange = { servers = it },
                    label = { Text(stringResource(R.string.routing_dns_custom_label)) },
                    placeholder = { Text(transport.placeholder()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.small),
                    minLines = 1,
                    maxLines = 4,
                )
                if ((transport == DnsTransport.Tls) || (transport == DnsTransport.Https)) {
                    OutlinedTextField(
                        value = bootstrap,
                        onValueChange = { bootstrap = it },
                        label = { Text(stringResource(R.string.routing_direct_dns_bootstrap_label)) },
                        placeholder = { Text("dns.example=192.0.2.1, 2001:db8::53") },
                        supportingText = { Text(stringResource(R.string.routing_direct_dns_bootstrap_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.small),
                        minLines = 1,
                        maxLines = 4,
                    )
                }
                if (changed && (parsed == null)) {
                    Text(
                        stringResource(R.string.routing_direct_dns_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                    )
                }
            }
            Button(
                enabled = changed && (parsed != null),
                onClick = {
                    with(parsed ?: return@Button) {
                        focus.clearFocus()
                        onSave(
                            mode, transport,
                            if (mode == DnsMode.Custom) this.servers else config.directCustomDns,
                            if (mode == DnsMode.Custom) this.bootstrap else config.directDnsBootstrap,
                        )
                    }
                },
                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

private fun directTransports(mode: DnsMode): List<DnsTransport> =
    DnsPresets.supportedTransports(mode).filter {
        when (it) {
            DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Tls, DnsTransport.Https -> true
            else -> false
        }
    }
