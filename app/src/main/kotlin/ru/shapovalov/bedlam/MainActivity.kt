package ru.shapovalov.bedlam

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import ru.shapovalov.hysteria.internal.ConnectionState
import ru.shapovalov.hysteria.internal.HysteriaClientImpl
import ru.shapovalov.hysteria.internal.HysteriaConfig

class MainActivity : ComponentActivity() {

    private val client: HysteriaClient = HysteriaClientImpl()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(client)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(client: HysteriaClient) {
    var server by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
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
                            client.connect(
                                HysteriaConfig(
                                    server = server,
                                    auth = auth,
                                    tlsSni = sni,
                                    disablePathMTUDiscovery = true,
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Disconnect" else "Connect")
            }

            Text(
                text = "Status: ${
                    when (val s = connectionState) {
                        is ConnectionState.Disconnected -> "Disconnected"
                        is ConnectionState.Connecting -> "Connecting..."
                        is ConnectionState.Connected -> "Connected"
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
