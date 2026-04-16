package ru.shapovalov.bedlam

import android.content.Intent
import android.net.VpnService
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
import golib.Golib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri
import ru.shapovalov.hysteria.toJson

class MainActivity : ComponentActivity() {

    private val client: HysteriaClient = HysteriaClientImpl()

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

    private fun requestVpnPermissionThen(block: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            onVpnReady = block
            vpnPermissionLauncher.launch(intent)
        } else {
            block()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(client = client, onStartVpn = { configJson ->
                    requestVpnPermissionThen {
                        val intent = Intent(this, BedlamVpnService::class.java)
                        intent.putExtra(BedlamVpnService.EXTRA_CONFIG_JSON, configJson)
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
                        try {
                            val config = parseHysteriaUri(uri)
                            val json = config.toJson()
                            log("Connecting to ${config.server.server}...")
                            onStartVpn(json)
                        } catch (e: Exception) {
                            errorText = e.message ?: "Invalid URI"
                            log("Error: $errorText")
                        }
                    },
                    enabled = !isConnected && !isConnecting && uri.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isConnecting) "Connecting..." else "Connect")
                }

                Button(
                    onClick = {
                        log("Disconnecting...")
                        onStopVpn()
                    }, enabled = isConnected || isConnecting, colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ), modifier = Modifier.weight(1f)
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
                        scope.launch(Dispatchers.IO) {
                            val result = Golib.testUDP()
                            log("UDP test: $result")
                        }
                    }, enabled = isConnected, modifier = Modifier.weight(1f)
                ) {
                    Text("Test UDP")
                }

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val result = Golib.testDNSOverTCP()
                            log("DNS/TCP test: $result")
                        }
                    }, enabled = isConnected, modifier = Modifier.weight(1f)
                ) {
                    Text("Test DNS/TCP")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Status: $connectionState",
                style = MaterialTheme.typography.labelLarge,
                color = when (connectionState) {
                    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                    is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

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
