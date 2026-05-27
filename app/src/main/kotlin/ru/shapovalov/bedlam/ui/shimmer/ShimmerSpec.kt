package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.animation.core.AnimationConstants.DefaultDurationMillis
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Stable

@Stable
fun shimmerSpec(
    durationMillis: Int = DefaultDurationMillis,
    delayMillis: Int = 0,
    easing: Easing = LinearEasing,
) = KeyframesSpec(
    KeyframesSpec.KeyframesSpecConfig<Float>().apply {
        0f at 0 using easing
        1f at durationMillis
        if (delayMillis > 0) {
            1f at durationMillis + delayMillis
        }
        this.durationMillis = durationMillis + delayMillis
    },
)
