package ru.shapovalov.bedlam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore
import ru.shapovalov.bedlam.feature.dashboard.ui.ImportProfileSheet

@RunWith(AndroidJUnit4::class)
class ImportProfileSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabsKeepSeparateDraftsAndOnlyShowTheirOwnError() {
        val error = mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                ImportProfileSheet(
                    DashboardStore.ImportSheetSeed("invalid-link", ProfileImportFormat.Link),
                    isImporting = false, error = error.value, failures = emptyList(), closing = false,
                    onDismiss = {},
                ) { _, _, _ -> error.value = "Invalid link fixture" }
            }
        }
        compose.onNodeWithText("Import", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Invalid link fixture").assertExists()
        compose.onNodeWithText("JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("JSON").assertIsOn()
        compose.onNodeWithText("Invalid link fixture").assertDoesNotExist()
        val jsonField = compose.onNode(hasSetTextAction() and hasText("Profile JSON"))
        assertEquals("", jsonField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        jsonField.performTextInput("{}")
        // Exercise the tab action without racing the IME animation after text input.
        compose.onNodeWithText("Link").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Link").assertIsOn()
        val linkField = compose.onNode(hasSetTextAction() and hasText("hysteria2:// link"))
        assertEquals("invalid-link", linkField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Invalid link fixture").assertExists()
        compose.onNodeWithText("JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("JSON").assertIsOn()
        assertEquals("{}", jsonField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun automaticFormatDetectionKeepsPastedJson() {
        compose.setContent {
            MaterialTheme {
                ImportProfileSheet(
                    DashboardStore.ImportSheetSeed("", ProfileImportFormat.Link),
                    isImporting = false, error = null, failures = emptyList(), closing = false,
                    onDismiss = {},
                ) { _, _, _ -> }
            }
        }
        compose.onNode(hasSetTextAction() and hasText("hysteria2:// link")).performTextInput("{}")
        compose.onNodeWithText("JSON").assertIsOn()
        assertEquals(
            "{}",
            compose.onNode(hasSetTextAction() and hasText("Profile JSON"))
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }
}
