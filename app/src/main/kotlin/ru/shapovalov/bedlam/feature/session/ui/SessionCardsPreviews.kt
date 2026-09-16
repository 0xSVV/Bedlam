package ru.shapovalov.bedlam.feature.session.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.ui.theme.BedlamTheme

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class SessionCardPreviews

@Composable
private fun SessionCardPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@SessionCardPreviews
@Composable
private fun SkeletonInfoCardPreview() {
    SessionCardPreview { SkeletonInfoCard() }
}

@SessionCardPreviews
@Composable
private fun DisconnectedCardPreview() {
    SessionCardPreview { DisconnectedCard() }
}

@SessionCardPreviews
@Composable
private fun ErrorCardPreview() {
    SessionCardPreview {
        ErrorCard(message = "Lookup timed out after 10 seconds", onRetry = {})
    }
}

@SessionCardPreviews
@Composable
private fun InfoCardPreview() {
    SessionCardPreview {
        InfoCard(
            info = SessionInfo(
                ipv4 = "203.0.113.7",
                ipv6 = "2001:db8:85a3::8a2e:370:7334",
                asn = "AS64500",
                asOrganization = "Example Networks",
                country = "Netherlands",
                city = "Amsterdam",
                region = "North Holland",
                latitude = 52.3676,
                longitude = 4.9041,
            ),
        )
    }
}

@SessionCardPreviews
@Composable
private fun InfoCardPartialPreview() {
    SessionCardPreview {
        InfoCard(
            info = SessionInfo(
                ipv4 = "203.0.113.7",
                ipv6 = null,
                asn = null,
                asOrganization = null,
                country = null,
                city = null,
                region = null,
                latitude = null,
                longitude = null,
            ),
        )
    }
}

@SessionCardPreviews
@Composable
private fun SpeedTestCardPreview() {
    SessionCardPreview { SpeedTestCard(onOpen = {}) }
}

@SessionCardPreviews
@Composable
private fun SpeedTestErrorPreview() {
    SessionCardPreview { SpeedTestError(onRetry = {}) }
}
