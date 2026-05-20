package ru.shapovalov.bedlam.feature.session.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponent
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionContent(component: SessionComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing
    var showSpeedTest by remember { mutableStateOf(false) }

    BackHandler {
        if (showSpeedTest) showSpeedTest = false else component.onBackPressed()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (showSpeedTest) R.string.session_speed_test_title else R.string.session_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (showSpeedTest) showSpeedTest = false else component.onBackPressed() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!showSpeedTest) {
                        IconButton(onClick = component::onRefresh, enabled = !state.isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.session_action_refresh_cd),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (showSpeedTest) {
            SpeedTestWebView(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            when {
                state.isLoading && state.info == null -> SkeletonInfoCard()
                state.errorMessage != null && state.info == null -> ErrorCard(
                    message = state.errorMessage!!,
                    onRetry = component::onRefresh,
                )
                state.info != null -> InfoCard(state.info!!)
            }

            Text(
                text = stringResource(R.string.session_ip_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.small),
            )

            SpeedTestCard(onOpen = { showSpeedTest = true })

            Spacer(Modifier.height(spacing.large))
        }
    }
}

private val SkeletonValueWidths = listOf(120.dp, 200.dp, 90.dp, 160.dp, 60.dp, 110.dp, 110.dp, 80.dp, 90.dp)

@Composable
private fun SkeletonInfoCard() {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            SkeletonValueWidths.forEachIndexed { index, width ->
                SkeletonRow(labelWidth = if (index % 2 == 0) 64.dp else 100.dp, valueWidth = width)
                if (index != SkeletonValueWidths.lastIndex) DividerRow()
            }
        }
    }
}

@Composable
private fun SkeletonRow(labelWidth: Dp, valueWidth: Dp) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ShimmerBlock(width = labelWidth, height = 14.dp)
        ShimmerBlock(width = valueWidth, height = 14.dp)
    }
}

@Composable
private fun ShimmerBlock(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .shimmer(),
    )
}

@Composable
private fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val transparent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)
    return this.then(
        Modifier.drawWithCache {
            val width = size.width
            val bandWidth = width * 0.6f
            val travel = width + bandWidth
            val start = -bandWidth + travel * progress
            val brush = Brush.linearGradient(
                colors = listOf(transparent, highlight, transparent),
                start = Offset(start, 0f),
                end = Offset(start + bandWidth, size.height),
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush = brush, topLeft = Offset.Zero, size = Size(width, size.height), blendMode = BlendMode.SrcAtop)
            }
        },
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text = stringResource(R.string.session_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(spacing.xSmall))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.session_action_retry))
            }
        }
    }
}

@Composable
private fun InfoCard(info: SessionInfo) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            InfoRow(label = stringResource(R.string.session_field_ipv4), value = info.ipv4, monoValue = true)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_ipv6), value = info.ipv6, monoValue = true)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_asn), value = info.asn)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_as_org), value = info.asOrganization)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_country), value = info.country)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_city), value = info.city)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_region), value = info.region)
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_latitude),
                value = info.latitude?.toString(),
            )
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_longitude),
                value = info.longitude?.toString(),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?, monoValue: Boolean = false) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(spacing.medium))
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monoValue) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DividerRow() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SpeedTestCard(onOpen: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.large, vertical = spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
        ) {
            Text(
                text = stringResource(R.string.session_speed_test_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.session_speed_test_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpeedTestWebView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(SPEED_TEST_URL)
            }
        },
    )
}

private const val SPEED_TEST_URL = "https://speed.cloudflare.com/"
