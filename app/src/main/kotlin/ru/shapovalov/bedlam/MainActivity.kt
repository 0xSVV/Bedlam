package ru.shapovalov.bedlam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri

class MainActivity : ComponentActivity() {

    private val client: HysteriaClient = HysteriaClientImpl

    private var onVpnReady: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            onVpnReady?.invoke()
        } else {
            Log.w("Bedlam", "VPN permission denied")
        }
        onVpnReady = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Log.w("Bedlam", "Notification permission denied")
    }

    private fun requestVpnPermissionThen(block: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            onVpnReady = block
            vpnPermissionLauncher.launch(intent)
        } else {
            block()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        setContent {
            MaterialTheme {
                MainScreen(client = client, onStartVpn = { uri ->
                    requestVpnPermissionThen {
                        val intent = Intent(this, BedlamVpnService::class.java)
                        intent.putExtra(BedlamVpnService.EXTRA_URI, uri)
                        startService(intent)
                    }
                }, onStopVpn = {
                    val intent = Intent(this, BedlamVpnService::class.java)
                    intent.action = BedlamVpnService.ACTION_STOP
                    startService(intent)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    client: HysteriaClient, onStartVpn: (String) -> Unit, onStopVpn: () -> Unit
) {
    var uri by rememberSaveable { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    val connectionState by client.state.collectAsState()
    val scope = rememberCoroutineScope()

    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    val isActive = isConnected || isConnecting

    val parsedConfig: HysteriaConfig? = remember(uri) {
        if (uri.isBlank()) null
        else runCatching { parseHysteriaUri(uri) }.getOrNull()
    }

    fun log(msg: String) {
        Log.d("Bedlam", msg)
        logText = "$logText\n$msg".trimStart()
    }

    DisposableEffect(Unit) {
        client.setLogListener(object : HysteriaClient.LogListener {
            override fun onLog(level: String, message: String) {
                Log.d("Bedlam/Go", "[$level] $message")
                logText = "$logText\n[$level] $message".trimStart()
            }
        })
        onDispose { client.setLogListener(null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Bedlam") })
        }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uri,
                onValueChange = {
                    uri = it
                    errorText = ""
                },
                label = { Text("Connection URI") },
                placeholder = { Text("hysteria2://auth@host:port/?sni=...") },
                singleLine = true,
                enabled = !isActive,
                isError = errorText.isNotEmpty(),
                supportingText = if (errorText.isNotEmpty()) {
                    { Text(errorText, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        errorText = ""
                        val result = runCatching { parseHysteriaUri(uri) }
                        result.onSuccess { config ->
                            log("Connecting to ${config.server.server}...")
                            onStartVpn(uri)
                        }.onFailure { e ->
                            errorText = e.message ?: "Invalid URI"
                            log("Error: $errorText")
                        }
                    },
                    enabled = !isActive && uri.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isConnecting) "Connecting..." else "Connect")
                }

                Button(
                    onClick = {
                        log("Disconnecting...")
                        onStopVpn()
                    },
                    enabled = isActive,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val result = client.testUdp()
                            log("UDP test: $result")
                        }
                    }, enabled = isConnected, modifier = Modifier.weight(1f)
                ) {
                    Text("Test UDP")
                }

                Button(
                    onClick = {
                        scope.launch {
                            val result = client.testDnsOverTcp()
                            log("DNS/TCP test: $result")
                        }
                    }, enabled = isConnected, modifier = Modifier.weight(1f)
                ) {
                    Text("Test DNS/TCP")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Status: ${connectionState.display()}",
                style = MaterialTheme.typography.labelLarge,
                color = when (connectionState) {
                    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                    is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                    is ConnectionState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (isActive && parsedConfig != null) {
                ConfigCard(parsedConfig)
            }

            Text(
                text = logText.ifEmpty { "Logs will appear here..." },
                style = MaterialTheme.typography.bodySmall,
                color = if (logText.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun ConfigCard(config: HysteriaConfig) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Configuration", style = MaterialTheme.typography.titleSmall)
            ConfigRow("Server", config.server.server)
            if (config.server.auth.isNotEmpty()) {
                ConfigRow("Auth", config.server.auth)
            }
            if (config.tls.tlsSni.isNotEmpty()) {
                ConfigRow("SNI", config.tls.tlsSni)
            }
            if (config.tls.tlsInsecure) {
                ConfigRow("Insecure TLS", "yes")
            }
            if (config.tls.tlsPinSHA256.isNotEmpty()) {
                ConfigRow("Pin SHA256", config.tls.tlsPinSHA256)
            }
            if (!config.obfuscation?.obfuscationType.isNullOrEmpty()) {
                ConfigRow("Obfuscation", config.obfuscation?.obfuscationType)
            }
            if (!config.obfuscation?.obfuscationPassword.isNullOrEmpty()) {
                ConfigRow("Obfs password", config.obfuscation?.obfuscationPassword)
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(2f)
        )
    }
}

private fun ConnectionState.display(): String = when (this) {
    is ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Connecting -> "Connecting..."
    is ConnectionState.Connected -> "Connected (UDP=${if (udpEnabled) "on" else "off"})"
    is ConnectionState.Error -> "Error: $message"
}
