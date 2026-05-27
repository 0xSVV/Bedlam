package ru.shapovalov.bedlam.feature.session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.ui.shimmer.Shimmer
import ru.shapovalov.bedlam.ui.shimmer.ShimmerBounds
import ru.shapovalov.bedlam.ui.shimmer.rememberShimmer
import ru.shapovalov.bedlam.ui.shimmer.shimmer
import ru.shapovalov.bedlam.ui.theme.spacing

internal sealed interface CardState {
    data object Loading : CardState
    data class Error(val message: String) : CardState
    data class Success(val info: SessionInfo) : CardState
}

private val SkeletonBlockHeight = 20.dp

private val SkeletonLabelWidths = listOf(
    32.dp, 32.dp, 28.dp, 112.dp, 52.dp, 28.dp, 44.dp, 52.dp, 60.dp,
)

private val SkeletonValueWidths = listOf(
    88.dp, 196.dp, 52.dp, 140.dp, 100.dp, 88.dp, 80.dp, 64.dp, 72.dp,
)

@Composable
internal fun SkeletonInfoCard() {
    val spacing = MaterialTheme.spacing
    val shimmer = rememberShimmer(ShimmerBounds.Window)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            SkeletonLabelWidths.zip(SkeletonValueWidths)
                .forEachIndexed { index, (labelWidth, valueWidth) ->
                    SkeletonRow(labelWidth = labelWidth, valueWidth = valueWidth, shimmer = shimmer)
                    if (index != SkeletonLabelWidths.lastIndex) DividerRow()
                }
        }
    }
}

@Composable
private fun SkeletonRow(labelWidth: Dp, valueWidth: Dp, shimmer: Shimmer) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ShimmerBlock(width = labelWidth, height = SkeletonBlockHeight, shimmer = shimmer)
        ShimmerBlock(width = valueWidth, height = SkeletonBlockHeight, shimmer = shimmer)
    }
}

@Composable
private fun ShimmerBlock(width: Dp, height: Dp, shimmer: Shimmer) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .shimmer(shimmer)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ErrorCard(message: String, onRetry: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
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
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(spacing.xSmall))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.session_action_retry))
            }
        }
    }
}

@Composable
internal fun InfoCard(info: SessionInfo) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            InfoRow(
                label = stringResource(R.string.session_field_ipv4),
                value = info.ipv4,
                monoValue = true
            )
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_ipv6),
                value = info.ipv6,
                monoValue = true
            )
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_asn), value = info.asn)
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_as_org),
                value = info.asOrganization
            )
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_country), value = info.country)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_city), value = info.city)
            DividerRow()
            InfoRow(label = stringResource(R.string.session_field_region), value = info.region)
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_latitude),
                value = info.latitude?.toString()
            )
            DividerRow()
            InfoRow(
                label = stringResource(R.string.session_field_longitude),
                value = info.longitude?.toString()
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
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(spacing.medium))
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monoValue) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpeedTestCard(onOpen: () -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
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
                style = MaterialTheme.typography.titleMediumEmphasized,
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
