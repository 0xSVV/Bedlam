package ru.shapovalov.bedlam.feature.appselection.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.bedlam.ui.theme.spacing

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class AppSelectionPartPreviews

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 320)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 320,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class AppSelectionBodyPreviews

@Composable
private fun AppSelectionPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun TopBarPreview(searchVisible: Boolean, query: String, showSearchButton: Boolean) {
    AppSelectionPreview {
        AppSelectionTopBar(
            searchVisible = searchVisible,
            query = query,
            onQueryChange = {},
            onToggleSearch = {},
            onCloseSearch = {},
            onBack = {},
            showSearchButton = showSearchButton,
            focusRequester = remember { FocusRequester() },
        )
    }
}

@AppSelectionPartPreviews
@Composable
private fun AppSelectionTopBarPreview() {
    TopBarPreview(searchVisible = false, query = "", showSearchButton = true)
}

@AppSelectionPartPreviews
@Composable
private fun AppSelectionTopBarSearchPreview() {
    TopBarPreview(searchVisible = true, query = "browser", showSearchButton = true)
}

@Composable
private fun ModeChipsPreview(selected: AppFilterMode?) {
    AppSelectionPreview {
        ModeChips(
            selected = selected,
            onSelect = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.large,
                    vertical = MaterialTheme.spacing.small,
                ),
        )
    }
}

@AppSelectionPartPreviews
@Composable
private fun ModeChipsLoadingPreview() {
    ModeChipsPreview(selected = null)
}

@AppSelectionPartPreviews
@Composable
private fun ModeChipsAllowlistPreview() {
    ModeChipsPreview(selected = AppFilterMode.Allowlist)
}

@AppSelectionBodyPreviews
@Composable
private fun AppSelectionLoadingPreview() {
    AppSelectionPreview { LoadingBox() }
}

@AppSelectionBodyPreviews
@Composable
private fun AppSelectionAllModePreview() {
    AppSelectionPreview { AllModeHint() }
}
