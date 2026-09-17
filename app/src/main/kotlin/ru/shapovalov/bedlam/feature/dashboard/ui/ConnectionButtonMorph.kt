package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ConnectionButtonMorph(
    private val restingShape: RoundedPolygon,
    private val loadingShapes: List<RoundedPolygon>,
    private val confirmShape: RoundedPolygon,
    private val cancelShape: RoundedPolygon,
    connecting: Boolean,
    confirming: Boolean = false,
) {
    init {
        require(loadingShapes.distinct().size > 1)
    }

    val button = MorphingShape(
        when {
            confirming -> confirmShape
            connecting -> loadingShapes.first()
            else -> restingShape
        }
    )
    val cancelButton = MorphingShape(if (confirming) cancelShape else restingShape)

    var showIcon by mutableStateOf(confirming || !connecting)
        private set
    var isSplit by mutableStateOf(confirming)
        private set

    private val splitAnimatable = Animatable(if (confirming) 1f else 0f)
    val split: Float get() = splitAnimatable.value

    suspend fun animateLoading(): Nothing {
        showIcon = false
        merge()
        while (true) {
            for (shape in loadingShapes) {
                button.morphTo(shape)
                awaitMotionEnabled()
            }
        }
    }

    suspend fun settle() {
        showIcon = true
        coroutineScope {
            launch { button.morphTo(restingShape) }
            launch { merge() }
        }
    }

    suspend fun split() {
        showIcon = true
        if (!isSplit) {
            button.returnToCurrentShape()
            cancelButton.snapTo(button.fromShape)
            isSplit = true
        }
        coroutineScope {
            launch { button.morphTo(confirmShape) }
            launch { cancelButton.morphTo(cancelShape) }
            launch { splitAnimatable.animateTo(1f, SplitAnimationSpec) }
        }
    }

    private suspend fun merge() {
        if (!isSplit) return
        coroutineScope {
            launch { cancelButton.morphTo(restingShape) }
            launch { splitAnimatable.animateTo(0f, SplitAnimationSpec) }
        }
        isSplit = false
    }

    private suspend fun awaitMotionEnabled() {
        val motionDurationScale = currentCoroutineContext()[MotionDurationScale] ?: return
        if (motionDurationScale.scaleFactor == 0f) {
            snapshotFlow { motionDurationScale.scaleFactor }.first { it > 0f }
        }
    }
}

private val SplitAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.001f,
)
