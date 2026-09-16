package ru.shapovalov.bedlam.feature.settings.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlockContent
import ru.shapovalov.bedlam.ui.markdown.MarkdownParser
import ru.shapovalov.bedlam.ui.markdown.markdownBlockSpacing
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.HysteriaCore

@Composable
fun AboutContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appVersion = remember(context) { context.appVersionName() }
    val readme by produceState(initialValue = emptyList<MarkdownBlock>(), context) {
        value = withContext(Dispatchers.IO) { context.readReadme() }
    }
    AboutPage(
        appVersion = appVersion,
        hysteriaVersion = HysteriaCore.VERSION,
        readme = readme,
        onBack = onBack,
        onOpenUrl = remember(context) { { url: String -> context.openUrl(url) } },
        onCopyVersions = remember(context) { { versions: String -> context.copyVersions(versions) } },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutPage(
    appVersion: String,
    hysteriaVersion: String,
    readme: List<MarkdownBlock>,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyVersions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val topBarBottom = WindowInsets.safeDrawing.getTop(density) +
            with(density) { TopAppBarDefaults.TopAppBarExpandedHeight.roundToPx() }
    val headerScrolledAway by remember(listState, topBarBottom) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                    listState.layoutInfo.visibleItemsInfo.firstOrNull()
                        ?.let { it.offset + it.size <= topBarBottom } == true
        }
    }
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

    StatusBarIconsEffect(
        darkIcons = headerScrolledAway && MaterialTheme.colorScheme.surfaceContainer.luminance() > 0.5f,
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset.calculateBottomPadding() + spacing.xLarge),
        ) {
            item(key = "header") {
                AboutHeader(
                    appVersion = appVersion,
                    hysteriaVersion = hysteriaVersion,
                    onCopyVersions = onCopyVersions,
                )
            }
            item(key = "links") {
                Column(
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    ),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_github_title),
                        subtitle = stringResource(R.string.settings_about_github_subtitle),
                        trailingIcon = R.drawable.ic_open_in_new,
                        onClick = { onOpenUrl(SOURCE_URL) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_about_license_title),
                        subtitle = stringResource(R.string.settings_about_license_subtitle),
                        trailingIcon = R.drawable.ic_open_in_new,
                        onClick = { onOpenUrl(LICENSE_URL) },
                    )
                    SettingsDivider()
                }
            }
            itemsIndexed(
                items = readme,
                key = { index, _ -> index },
                contentType = { _, block -> block::class },
            ) { index, block ->
                MarkdownBlockContent(
                    block = block,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier
                        .animateItem()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .fillMaxWidth()
                        .padding(horizontal = spacing.large)
                        .padding(top = if (index == 0) spacing.xLarge else markdownBlockSpacing(block)),
                )
            }
        }
        AboutTopBar(headerScrolledAway = headerScrolledAway, onBack = onBack)
    }
}

@Composable
private fun AboutHeader(
    appVersion: String,
    hysteriaVersion: String,
    onCopyVersions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val appLabel = stringResource(R.string.settings_about_version_app, appVersion)
    val hysteriaLabel = stringResource(R.string.settings_about_version_hysteria, hysteriaVersion)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(shadow = AboutHeaderTextShadow)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AboutHeaderBackdrop)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
    ) {
        Box(
            modifier = Modifier.headerSize(
                maxHeight = minOf(AboutHeaderMaxHeight, windowHeight * ABOUT_HEADER_MAX_WINDOW_FRACTION),
            ),
        ) {
            Image(
                painter = painterResource(R.drawable.about_header),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .aspectRatio(ABOUT_HEADER_ASPECT_RATIO, matchHeightConstraintsFirst = true),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(spacing.small)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = stringResource(R.string.settings_about_copy_versions)) {
                        onCopyVersions("$appLabel\n$hysteriaLabel")
                    }
                    .padding(horizontal = spacing.small, vertical = spacing.xSmall),
            ) {
                Text(text = appLabel, color = AboutHeaderContentColor, style = labelStyle)
                Text(
                    text = hysteriaLabel,
                    color = AboutHeaderContentColor.copy(alpha = SECONDARY_VERSION_ALPHA),
                    style = labelStyle,
                )
            }
        }
    }
}

private fun Modifier.headerSize(maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = minOf((width / ABOUT_HEADER_ASPECT_RATIO).toInt(), maxHeight.roundToPx())
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

@Composable
private fun StatusBarIconsEffect(darkIcons: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkIcons) {
            val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
            val controller = WindowCompat.getInsetsController(window, view)
            val previous = controller.isAppearanceLightStatusBars
            controller.isAppearanceLightStatusBars = darkIcons
            onDispose { controller.isAppearanceLightStatusBars = previous }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutTopBar(headerScrolledAway: Boolean, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (headerScrolledAway) colors.surfaceContainer else Color.Transparent,
        label = "about-top-bar-container",
    )
    val navigationIconColor by animateColorAsState(
        targetValue = if (headerScrolledAway) colors.onSurface else AboutHeaderContentColor,
        label = "about-top-bar-icon",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (headerScrolledAway) 1f else 0f,
        label = "about-top-bar-title",
    )
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.settings_about_title),
                modifier = Modifier.graphicsLayer { alpha = titleAlpha },
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            navigationIconContentColor = navigationIconColor,
            titleContentColor = colors.onSurface,
        ),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun Context.appVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}.getOrDefault("")

private fun Context.readReadme(): List<MarkdownBlock> = runCatching {
    assets.open(README_ASSET).bufferedReader().use { MarkdownParser(README_URL).parse(it.readText()) }
}.getOrDefault(emptyList())

private fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.copyVersions(versions: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), versions))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.settings_about_copied, Toast.LENGTH_SHORT).show()
    }
}

private const val SOURCE_URL = "https://github.com/0xSVV/Bedlam"
private const val LICENSE_URL = "$SOURCE_URL/blob/master/LICENSE"
internal const val README_URL = "$SOURCE_URL/blob/master/README.md"
private const val README_ASSET = "README.md"
private const val ABOUT_HEADER_ASPECT_RATIO = 2f
private const val ABOUT_HEADER_MAX_WINDOW_FRACTION = 0.5f
private const val SECONDARY_VERSION_ALPHA = 0.72f
private val AboutHeaderMaxHeight = 320.dp
private val AboutHeaderBackdrop = Color(0xFF030003)
private val AboutHeaderContentColor = Color.White
private val AboutHeaderTextShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
