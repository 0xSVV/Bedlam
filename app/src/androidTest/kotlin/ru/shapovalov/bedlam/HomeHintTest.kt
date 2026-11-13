package ru.shapovalov.bedlam

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.feature.dashboard.ui.ConnectionHero
import ru.shapovalov.hysteria.ConnectionState

@RunWith(AndroidJUnit4::class)
class HomeHintTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun existingProfilesWithoutSelectionPromptForSelection() {
        showHero(hasProfiles = true)
        compose.onNodeWithText("Select a profile to connect").assertExists()
        compose.onNodeWithText("Import a hysteria2:// link or a profile JSON to get started").assertDoesNotExist()
    }

    @Test
    fun emptyLibraryStillPromptsForImport() {
        showHero(hasProfiles = false)
        compose.onNodeWithText("Import a hysteria2:// link or a profile JSON to get started").assertExists()
        compose.onNodeWithText("Select a profile to connect").assertDoesNotExist()
    }

    private fun showHero(hasProfiles: Boolean) = compose.setContent {
        MaterialTheme {
            ConnectionHero(
                connectionState = ConnectionState.Disconnected(),
                connectedSinceMillis = null,
                hasActiveProfile = false,
                hasProfiles = hasProfiles,
                pendingSwitchName = null,
                onToggle = {}, onConfirmSwitch = {}, onCancelSwitch = {},
            ) {}
        }
    }
}
