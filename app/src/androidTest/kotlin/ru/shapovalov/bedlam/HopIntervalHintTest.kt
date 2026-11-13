package ru.shapovalov.bedlam

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import ru.shapovalov.bedlam.feature.profileconfig.ui.TransportSection
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

class HopIntervalHintTest {
    @get:Rule val compose = createComposeRule()
    // This read-only fixture intentionally ignores edits; there is nothing to destructure.
    @Suppress("DestructuringDeclaration")
    @Test fun editorExplainsThatHopIntervalIsClientOnly() {
        compose.setContent {
            BedlamTheme {
                TransportSection(
                    draft = HysteriaConfig(server = ServerCredentials("example.com:443", "test"), tls = TlsOptions()),
                    editMode = true,
                    onDraftChanged = {},
                )
            }
        }
        compose.onNodeWithText("Client-side interval for switching between server ports").assertExists()
        compose.onNodeWithText("Hop intervals must agree with the server").assertDoesNotExist()
    }
}
