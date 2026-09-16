package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.graphics.shapes.RoundedPolygon

internal class ConnectionButtonMorph(
    private val restingShape: RoundedPolygon,
    private val loadingShapes: List<RoundedPolygon>,
    connecting: Boolean,
) {
    var fromShape by mutableStateOf(restingShape)
        private set
    var toShape by mutableStateOf(restingShape)
        private set
    var showIcon by mutableStateOf(!connecting)
        private set

    private val progressAnimatable = Animatable(0f)
    val progress: Float get() = progressAnimatable.value

    suspend fun animateLoading() {
        showIcon = false
        repeat(MaxLoadingMorphCycles) {
            loadingShapes.forEach { morphTo(it) }
        }
        returnToCurrentShape()
        morphTo(restingShape)
        showIcon = true
    }

    suspend fun settle() {
        showIcon = true
        returnToCurrentShape()
        morphTo(restingShape)
    }

    private suspend fun returnToCurrentShape() {
        if (fromShape != toShape && progressAnimatable.value > 0f) {
            progressAnimatable.animateTo(0f, ConnectionMorphAnimationSpec)
            toShape = fromShape
            progressAnimatable.snapTo(0f)
        }
    }

    private suspend fun morphTo(nextShape: RoundedPolygon) {
        if (fromShape == nextShape) return
        toShape = nextShape
        progressAnimatable.snapTo(0f)
        progressAnimatable.animateTo(1f, ConnectionMorphAnimationSpec)
        fromShape = nextShape
        toShape = nextShape
        progressAnimatable.snapTo(0f)
    }
}

private const val MaxLoadingMorphCycles = 5

private val ConnectionMorphAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.1f,
)
