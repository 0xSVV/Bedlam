package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

internal class ShimmerArea(
    private val widthOfShimmer: Float,
    rotationInDegree: Float,
) {
    private val reducedRotation = rotationInDegree.reduceRotation().toRadian()

    private var requestedShimmerBounds: Rect? = null
    private var shimmerSize: Size = Size.Zero

    var translationDistance = 0f
        private set

    var pivotPoint = Offset.Unspecified
        private set

    var shimmerBounds = Rect.Zero
        private set

    var viewBounds = Rect.Zero
        set(value) {
            if (value == field) return
            field = value
            computeShimmerBounds()
        }

    fun updateBounds(shimmerBounds: Rect?) {
        if (this.requestedShimmerBounds == shimmerBounds) return
        requestedShimmerBounds = shimmerBounds
        computeShimmerBounds()
    }

    private fun computeShimmerBounds() {
        if (viewBounds.isEmpty) return
        shimmerBounds = requestedShimmerBounds ?: viewBounds
        pivotPoint = -viewBounds.topLeft + shimmerBounds.center
        val newShimmerSize = shimmerBounds.size
        if (shimmerSize != newShimmerSize) {
            shimmerSize = newShimmerSize
            computeTranslationDistance()
        }
    }

    private fun computeTranslationDistance() {
        val width = shimmerSize.width / 2
        val height = shimmerSize.height / 2
        val distanceCornerToCenter = sqrt(width.pow(2) + height.pow(2))
        val beta = acos(width / distanceCornerToCenter)
        val alpha = beta - reducedRotation
        val distanceCornerToRotatedCenterLine = cos(alpha) * distanceCornerToCenter
        translationDistance = distanceCornerToRotatedCenterLine * 2 + widthOfShimmer
    }

    private fun Float.reduceRotation(): Float {
        if (this < 0f) throw IllegalArgumentException("Shimmer rotation must be positive")
        var rotation = this % 180
        rotation -= 90
        rotation = -abs(rotation)
        return rotation + 90
    }

    private fun Float.toRadian(): Float = this / 180 * 3.1415927f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ShimmerArea
        if (widthOfShimmer != other.widthOfShimmer) return false
        if (reducedRotation != other.reducedRotation) return false
        return true
    }

    override fun hashCode(): Int {
        var result = widthOfShimmer.hashCode()
        result = 31 * result + reducedRotation.hashCode()
        return result
    }
}
