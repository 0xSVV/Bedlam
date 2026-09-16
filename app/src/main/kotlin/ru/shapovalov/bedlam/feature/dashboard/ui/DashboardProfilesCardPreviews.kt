package ru.shapovalov.bedlam.feature.dashboard.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class ProfilesCardPreviews

private val previewProfiles = listOf(
    previewProfile("home", "Home", "home.example.com:443"),
    previewProfile("office", "Office relay with a long name that ellipsizes", "relay.example.net:8443"),
    previewProfile("travel", "Travel", "203.0.113.10:443"),
    previewProfile("backup", "Backup", "backup.example.org:443"),
)

private fun previewProfile(id: String, name: String, address: String) = Profile(
    id = id,
    name = name,
    config = HysteriaConfig(
        server = ServerCredentials(address = address, auth = "secret"),
        tls = TlsOptions(),
    ),
    createdAt = 0L,
    updatedAt = 0L,
)

@Composable
private fun ProfilesCardPreview(
    activeProfileId: String?,
    latencies: Map<String, LatencyResult>,
) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProfilesCard(
                profiles = previewProfiles,
                activeProfileId = activeProfileId,
                latencies = latencies,
                onSelect = {},
                onOpenConfig = {},
                onPingAll = {},
            )
        }
    }
}

@ProfilesCardPreviews
@Composable
private fun ProfilesCardNotMeasuredPreview() {
    ProfilesCardPreview(activeProfileId = null, latencies = emptyMap())
}

@ProfilesCardPreviews
@Composable
private fun ProfilesCardMeasuringPreview() {
    ProfilesCardPreview(
        activeProfileId = "home",
        latencies = previewProfiles.associate { it.id to LatencyResult.Measuring },
    )
}

@ProfilesCardPreviews
@Composable
private fun ProfilesCardMeasuredPreview() {
    ProfilesCardPreview(
        activeProfileId = "office",
        latencies = mapOf(
            "home" to LatencyResult.Success(42),
            "office" to LatencyResult.Success(180),
            "travel" to LatencyResult.Success(640),
            "backup" to LatencyResult.Unreachable,
        ),
    )
}
