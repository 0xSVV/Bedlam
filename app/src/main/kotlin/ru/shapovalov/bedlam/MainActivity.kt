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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.internal.*

class MainActivity : ComponentActivity() {

    private val client: HysteriaClient = HysteriaClientImpl()

    private var pendingVpnConfig: HysteriaConfig? = null
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
                MainScreen(
                    client = client,
                    onStartVpn = { config, log ->
                        requestVpnPermissionThen {
                            log("VPN permission granted, connecting...")
                            startVpn(config, log)
                        }
                    },
                    onStopVpn = { log ->
                        stopVpn(log)
                    }
                )
            }
        }
    }

    private fun startVpn(config: HysteriaConfig, log: (String) -> Unit) {
        pendingVpnConfig = config
        val intent = Intent(this, BedlamVpnService::class.java)
        startService(intent)
        log("VPN service started")
    }

    private fun stopVpn(log: (String) -> Unit) {
        val intent = Intent(this, BedlamVpnService::class.java)
        intent.action = BedlamVpnService.ACTION_STOP
        startService(intent)
        log("VPN stop requested")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    client: HysteriaClient,
    onStartVpn: (HysteriaConfig, (String) -> Unit) -> Unit,
    onStopVpn: ((String) -> Unit) -> Unit
) {
    var server by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var vpnRunning by remember { mutableStateOf(false) }
    val connectionState by client.state.collectAsState()
    val scope = rememberCoroutineScope()

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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server (host:port)") },
                placeholder = { Text("1.2.3.4:443") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = auth,
                onValueChange = { auth = it },
                label = { Text("Auth") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text("TLS SNI (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            val isConnected = connectionState is ConnectionState.Connected

            Button(
                onClick = {
                    if (isConnected) {
                        log("Disconnecting...")
                        client.disconnect()
                        log("Disconnected")
                    } else {
                        log("Connecting...")
                        scope.launch {
                            client.connect(buildConfig(server, auth, sni))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Disconnect" else "Connect")
            }

            Button(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        log("Testing UDP relay...")
                        val result = golib.Golib.testUDP()
                        log("UDP test: $result")
                    }
                },
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test UDP")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isConnected) {
                            log("Connect Hysteria first before starting VPN")
                            return@Button
                        }
                        onStartVpn(buildConfig(server, auth, sni)) { log(it) }
                        vpnRunning = true
                    },
                    enabled = isConnected && !vpnRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start VPN")
                }

                Button(
                    onClick = {
                        onStopVpn { log(it) }
                        vpnRunning = false
                    },
                    enabled = vpnRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop VPN")
                }
            }

            Text(
                text = "Status: ${
                    when (val s = connectionState) {
                        is ConnectionState.Disconnected -> "Disconnected"
                        is ConnectionState.Connecting -> "Connecting..."
                        is ConnectionState.Connected -> "Connected" + if (vpnRunning) " + VPN" else ""
                        is ConnectionState.Error -> "Error: ${s.message}"
                    }
                }",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("Log:", style = MaterialTheme.typography.titleSmall)
            Text(
                text = logText.ifEmpty { "(no logs yet)" },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun buildConfig(server: String, auth: String, sni: String) = HysteriaConfig(
    server = ServerCredentials(server = server, auth = auth),
    tls = TlsOptions(tlsSni = sni),
    obfuscation = ObfuscationOptions(),
    quic = QuicOptions(disablePathMTUDiscovery = true),
    congestion = CongestionOptions(),
    bandwidth = BandwidthOptions(),
    transport = TransportOptions(),
    behavior = BehaviorOptions(),
    socks = SocksOptions(socksAddress = ""),
    http = HttpOptions(),
)
