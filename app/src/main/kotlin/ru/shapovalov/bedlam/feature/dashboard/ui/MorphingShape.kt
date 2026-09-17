package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.graphics.shapes.RoundedPolygon

internal class MorphingShape(initialShape: RoundedPolygon) {

    var fromShape by mutableStateOf(initialShape)
        private set
    var toShape by mutableStateOf(initialShape)
        private set

    private val progressAnimatable = Animatable(0f)
    val progress: Float get() = progressAnimatable.value

    suspend fun snapTo(shape: RoundedPolygon) {
        fromShape = shape
        toShape = shape
        progressAnimatable.snapTo(0f)
    }

    suspend fun morphTo(nextShape: RoundedPolygon) {
        if (toShape != nextShape) returnToCurrentShape()
        if (fromShape == nextShape) return
        if (toShape != nextShape) {
            toShape = nextShape
            progressAnimatable.snapTo(0f)
        }
        progressAnimatable.animateTo(1f, MorphAnimationSpec)
        fromShape = nextShape
        progressAnimatable.snapTo(0f)
    }

    suspend fun returnToCurrentShape() {
        if (toShape == fromShape) return
        progressAnimatable.animateTo(0f, MorphAnimationSpec)
        toShape = fromShape
        progressAnimatable.snapTo(0f)
    }
}

private val MorphAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.1f,
)
