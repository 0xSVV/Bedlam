package ru.shapovalov.bedlam.feature.appselection.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.feature.appselection.presentation.AppIconCache
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponent
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppSelectionContent(component: AppSelectionComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val isAllMode = state.mode == AppFilterMode.All
    val iconSizePx = with(LocalDensity.current) { AppIconSize.roundToPx() }

    fun hideSearch() {
        searchVisible = false
        component.onQueryChanged("")
    }

    SideEffect(isAllMode) {
        if (isAllMode) hideSearch()
    }
    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = searchVisible) { hideSearch() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppSelectionTopBar(
                searchVisible = searchVisible,
                query = state.query,
                onQueryChange = component::onQueryChanged,
                onToggleSearch = { searchVisible = true },
                onCloseSearch = ::hideSearch,
                onBack = component::onBackPressed,
                showSearchButton = !isAllMode,
                focusRequester = focusRequester,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            if (!searchVisible) {
                item(key = "mode", contentType = "mode") {
                    ModeChoices(
                        selected = state.mode.takeIf { state.isFilterLoaded },
                        onSelect = component::onModeSelected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.large,
                                vertical = MaterialTheme.spacing.small,
                            ),
                    )
                }
            }
            when {
                state.isFilterLoaded && isAllMode -> item(key = "all-hint", contentType = "all-hint") {
                    AllModeHint(Modifier.fillParentMaxWidth().heightIn(min = 160.dp))
                }
                state.isLoading -> item(key = "loading", contentType = "loading") {
                    LoadingBox(Modifier.fillParentMaxWidth().heightIn(min = 160.dp))
                }
                state.filteredApps.isEmpty() -> item(key = "no-results", contentType = "no-results") {
                    Box(
                        modifier = Modifier.fillParentMaxWidth().padding(MaterialTheme.spacing.large),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.app_selection_no_results, state.query),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> items(state.filteredApps, key = InstalledApp::packageName, contentType = { "app" }) { app ->
                    AppRow(
                        app = app,
                        isSelected = app.packageName in state.selectedPackages,
                        iconCache = component.iconCache,
                        iconSizePx = iconSizePx,
                        onToggle = { component.onTogglePackage(app.packageName) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppSelectionTopBar(
    searchVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    showSearchButton: Boolean,
    focusRequester: FocusRequester,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = if (searchVisible) onCloseSearch else onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        title = {
            AnimatedContent(
                targetState = searchVisible,
                transitionSpec = {
                    if (targetState) {
                        (fadeIn() + slideInHorizontally { it / 3 }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 3 })
                    } else {
                        (fadeIn() + slideInHorizontally { -it / 3 }) togetherWith
                                (fadeOut() + slideOutHorizontally { it / 3 })
                    }
                },
                label = "search-toggle",
            ) { isSearching ->
                if (isSearching) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.app_selection_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.app_selection_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                }
            }
        },
        actions = {
            AnimatedVisibility(
                visible = !searchVisible && showSearchButton,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.app_selection_search_cd),
                    )
                }
            }
            AnimatedVisibility(
                visible = searchVisible && query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.app_selection_clear_search_cd),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModeChoices(
    selected: AppFilterMode?,
    onSelect: (AppFilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup()) {
        AppFilterMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(horizontal = MaterialTheme.spacing.small, vertical = MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Spacer(Modifier.width(MaterialTheme.spacing.medium))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(mode.descriptionRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator()
    }
}

@Composable
internal fun AllModeHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize()
            .padding(MaterialTheme.spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.app_selection_all_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    isSelected: Boolean,
    iconCache: AppIconCache,
    iconSizePx: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon by produceState(
        initialValue = iconCache.cached(app.packageName, iconSizePx),
        app.packageName,
        iconSizePx,
    ) {
        value = iconCache.load(app.packageName, iconSizePx)
    }

    @Suppress("DEPRECATION")
    ListItem(
        headlineContent = {
            Text(
                text = app.label,
                style = if (isSelected) {
                    MaterialTheme.typography.titleMediumEmphasized
                } else {
                    MaterialTheme.typography.titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(modifier = Modifier.size(AppIconSize), contentAlignment = Alignment.Center) {
                icon?.let { bitmap ->
                    Image(
                        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier.size(AppIconSize),
                    )
                }
            }
        },
        trailingContent = {
            Checkbox(checked = isSelected, onCheckedChange = null)
        },
        modifier = modifier.toggleable(
            value = isSelected,
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        ),
    )
}

private val AppIconSize = 40.dp

private fun AppFilterMode.labelRes(): Int = when (this) {
    AppFilterMode.All -> R.string.app_filter_mode_all
    AppFilterMode.Allowlist -> R.string.app_filter_mode_allowlist
    AppFilterMode.Blocklist -> R.string.app_filter_mode_blocklist
}

private fun AppFilterMode.descriptionRes(): Int = when (this) {
    AppFilterMode.All -> R.string.app_filter_all_description
    AppFilterMode.Allowlist -> R.string.app_filter_allowlist_description
    AppFilterMode.Blocklist -> R.string.app_filter_blocklist_description
}
