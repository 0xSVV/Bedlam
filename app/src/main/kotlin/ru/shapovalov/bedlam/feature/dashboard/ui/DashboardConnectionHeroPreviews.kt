package ru.shapovalov.bedlam.feature.dashboard.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.ConnectionInfo
import ru.shapovalov.hysteria.api.DisconnectReason

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class HeroPreviews

@Composable
private fun HeroPreview(
    connectionState: ConnectionState,
    connectedSinceMillis: Long? = null,
    hasActiveProfile: Boolean = true,
) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ConnectionHero(
                connectionState = connectionState,
                connectedSinceMillis = connectedSinceMillis,
                hasActiveProfile = hasActiveProfile,
                onToggle = {},
                onOpenSession = {},
            )
        }
    }
}

@HeroPreviews
@Composable
private fun ConnectionHeroDisconnectedNoProfilePreview() {
    HeroPreview(
        connectionState = ConnectionState.Disconnected(DisconnectReason.NEVER_STARTED),
        hasActiveProfile = false,
    )
}

@HeroPreviews
@Composable
private fun ConnectionHeroDisconnectedWithProfilePreview() {
    HeroPreview(
        connectionState = ConnectionState.Disconnected(DisconnectReason.USER),
        hasActiveProfile = true,
    )
}

@HeroPreviews
@Composable
private fun ConnectionHeroConnectingPreview() {
    HeroPreview(connectionState = ConnectionState.Connecting)
}

@HeroPreviews
@Composable
private fun ConnectionHeroConnectedPreview() {
    val connectedSince = System.currentTimeMillis() - 3_725_000L
    HeroPreview(
        connectionState = ConnectionState.Connected(
            info = ConnectionInfo(
                serverAddress = "vpn.example.com:443",
                udpEnabled = true,
                attempt = 0,
            ),
            connectedSinceMillis = connectedSince,
        ),
        connectedSinceMillis = connectedSince,
    )
}

@HeroPreviews
@Composable
private fun ConnectionHeroReconnectingPreview() {
    HeroPreview(
        connectionState = ConnectionState.Reconnecting(
            attempt = 2,
            reason = "connection lost",
        ),
    )
}

@HeroPreviews
@Composable
private fun ConnectionHeroErrorPreview() {
    HeroPreview(
        connectionState = ConnectionState.Error("authentication error, HTTP status code: 401"),
    )
}
