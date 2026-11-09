package ru.shapovalov.bedlam.ui.shimmer

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.withSaveLayer

@Composable
internal fun rememberShimmerEffect(theme: ShimmerTheme): ShimmerEffect {
    val effect = remember(theme.animationSpec) { ShimmerEffect(theme.animationSpec) }
    LaunchedEffect(effect) { effect.startAnimation() }
    return effect
}

internal class ShimmerEffect(private val animationSpec: AnimationSpec<Float>) {
    private val animation = Animatable(0f)
    val progress: Float get() = animation.value

    suspend fun startAnimation() {
        animation.animateTo(1f, animationSpec)
    }
}

internal class ShimmerRenderer(theme: ShimmerTheme, width: Float) {
    private val rotation = theme.rotation
    private val matrix = Matrix()
    private val shader = LinearGradientShader(
        from = Offset(-width / 2, 0f),
        to = Offset(width / 2, 0f),
        colors = theme.shaderColors,
        colorStops = theme.shaderColorStops,
    )
    private val paint = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Fill
        blendMode = theme.blendMode
        shader = this@ShimmerRenderer.shader
    }
    private val emptyPaint = Paint()

    fun ContentDrawScope.draw(area: ShimmerArea, progress: Float) {
        if (area.shimmerBounds.isEmpty || area.viewBounds.isEmpty) return
        val pivot = area.pivotPoint
        val traversal = -area.translationDistance / 2 + area.translationDistance * progress + pivot.x
        matrix.setTranslate(traversal, 0f)
        matrix.postRotate(rotation, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
        val bounds = size.toRect()
        drawIntoCanvas { canvas ->
            canvas.withSaveLayer(bounds, emptyPaint) {
                drawContent()
                canvas.drawRect(bounds, paint)
            }
        }
    }
}
