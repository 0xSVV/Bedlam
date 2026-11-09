package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity

@Composable
fun Modifier.shimmer(customShimmer: Shimmer = rememberShimmer(ShimmerBounds.View)): Modifier {
    val width = with(LocalDensity.current) { customShimmer.theme.shimmerWidth.toPx() }
    return this then ShimmerElement(customShimmer, width)
}

private data class ShimmerElement(val shimmer: Shimmer, val width: Float) : ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(shimmer, width)
    override fun update(node: ShimmerNode) = node.update(shimmer, width)
    override fun InspectorInfo.inspectableProperties() {
        name = "shimmer"
        properties["shimmer"] = shimmer
    }
}

private class ShimmerNode(private var shimmer: Shimmer, private var width: Float) : Modifier.Node(), DrawModifierNode {
    private var area = ShimmerArea(width, shimmer.theme.rotation)
    private var renderer = ShimmerRenderer(shimmer.theme, width)

    fun update(shimmer: Shimmer, width: Float) {
        if (this.shimmer.theme != shimmer.theme || this.width != width) {
            area = ShimmerArea(width, shimmer.theme.rotation)
            renderer = ShimmerRenderer(shimmer.theme, width)
        }
        this.shimmer = shimmer
        this.width = width
    }

    override fun ContentDrawScope.draw() {
        area.viewBounds = requireLayoutCoordinates().unclippedBoundsInWindow()
        area.updateBounds(shimmer.bounds)
        with(renderer) { draw(area, shimmer.effect.progress) }
    }
}
