package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo

sealed interface ShimmerBounds {
    data object Custom : ShimmerBounds
    data object View : ShimmerBounds
    data object Window : ShimmerBounds
}

@Composable
internal fun rememberShimmerBounds(shimmerBounds: ShimmerBounds): Rect? = when (shimmerBounds) {
    ShimmerBounds.Custom -> Rect.Zero
    ShimmerBounds.View -> null
    ShimmerBounds.Window -> {
        val size = LocalWindowInfo.current.containerSize
        remember(size) { Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) }
    }
}
