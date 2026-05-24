package ru.shapovalov.bedlam.ui.shimmer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow

fun LayoutCoordinates.unclippedBoundsInWindow(): Rect {
    return try {
        val positionInWindow = positionInWindow()
        Rect(
            positionInWindow.x,
            positionInWindow.y,
            positionInWindow.x + size.width,
            positionInWindow.y + size.height,
        )
    } catch (_: IllegalStateException) {
        Rect.Zero
    }
}
