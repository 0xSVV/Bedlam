package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

@Composable
fun rememberShimmer(shimmerBounds: ShimmerBounds, theme: ShimmerTheme = LocalShimmerTheme.current): Shimmer {
    val effect = rememberShimmerEffect(theme)
    val bounds = rememberShimmerBounds(shimmerBounds)
    val shimmer = remember(theme, effect) { Shimmer(theme, effect, bounds) }
    if (shimmerBounds != ShimmerBounds.Custom) {
        SideEffect(bounds) { shimmer.updateBounds(bounds) }
    }
    return shimmer
}

class Shimmer internal constructor(
    internal val theme: ShimmerTheme,
    internal val effect: ShimmerEffect,
    bounds: Rect?,
) {
    internal var bounds by mutableStateOf(bounds)
        private set

    fun updateBounds(bounds: Rect?) {
        this.bounds = bounds
    }
}
