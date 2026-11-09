package ru.shapovalov.bedlam.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.feature.dashboard.ui.ConnectionHero
import ru.shapovalov.bedlam.feature.session.ui.SkeletonInfoCard
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.ConnectionState

class BenchmarkRenderingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BedlamTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 72.dp)) {
                        when (intent.getStringExtra("scenario")) {
                            "shimmer" -> SkeletonInfoCard()
                            else -> ConnectionHero(
                                connectionState = ConnectionState.Connecting,
                                connectedSinceMillis = null,
                                hasActiveProfile = true,
                                activeProfileName = "Local performance lab",
                                pendingSwitchName = null,
                                onToggle = {}, onConfirmSwitch = {}, onCancelSwitch = {}, onOpenSession = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
