package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class ShimmerTheme(
    val animationSpec: AnimationSpec<Float>,
    val blendMode: BlendMode,
    val rotation: Float,
    val shaderColors: List<Color>,
    val shaderColorStops: List<Float>?,
    val shimmerWidth: Dp,
)

val defaultShimmerTheme: ShimmerTheme = ShimmerTheme(
    animationSpec = infiniteRepeatable(
        animation = shimmerSpec(
            durationMillis = 800,
            easing = LinearEasing,
            delayMillis = 1_500,
        ),
        repeatMode = RepeatMode.Restart,
    ),
    blendMode = BlendMode.DstIn,
    rotation = 15.0f,
    shaderColors = listOf(
        Color.White.copy(alpha = 0.25f),
        Color.White.copy(alpha = 1.00f),
        Color.White.copy(alpha = 0.25f),
    ),
    shaderColorStops = listOf(0.0f, 0.5f, 1.0f),
    shimmerWidth = 400.dp,
)

val LocalShimmerTheme = staticCompositionLocalOf { defaultShimmerTheme }
