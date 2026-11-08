package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.graphics.shapes.Morph

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class MorphGeometry {
    private val path = Path()
    private val matrix = Matrix()
    private var previousMorph: Morph? = null
    private var previousProgress = Float.NaN
    private var previousSize = Size.Unspecified

    fun path(morph: Morph, progress: Float, size: Size): Path {
        if (morph === previousMorph && progress == previousProgress && size == previousSize) return path
        morph.toPath(progress = progress, path = path)
        matrix.reset()
        matrix.scale(x = size.width, y = size.height)
        path.transform(matrix)
        path.translate(size.center - path.getBounds().center)
        previousMorph = morph
        previousProgress = progress
        previousSize = size
        return path
    }
}
