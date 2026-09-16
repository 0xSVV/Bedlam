package ru.shapovalov.bedlam.core.vpn.notification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import ru.shapovalov.bedlam.ui.theme.primaryDark
import ru.shapovalov.bedlam.ui.theme.primaryLight
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighDark
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighLight

class SparklineRenderer(
    private val widthPx: Int = WIDTH_PX,
    private val heightPx: Int = HEIGHT_PX,
) {

    private val bitmap = createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    fun render(values: List<Long>, night: Boolean): Bitmap {
        val palette = if (night) NightPalette else DayPalette
        bitmap.eraseColor(0)

        canvas.drawRoundRect(
            0f, 0f, widthPx.toFloat(), heightPx.toFloat(), CORNER, CORNER,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card },
        )

        val plotW = widthPx - PADDING * 2
        val plotH = heightPx - PADDING * 2
        val fractions = Sparkline.fractions(values, Sparkline.peak(values))
        drawSeries(canvas, fractions, PADDING, PADDING, plotW, plotH, palette.series)
        return bitmap
    }

    private fun drawSeries(
        canvas: Canvas,
        fractions: List<Float>,
        left: Float,
        top: Float,
        plotW: Float,
        plotH: Float,
        color: Int,
    ) {
        if (fractions.isEmpty()) return
        val baseline = top + plotH
        fun pointX(index: Int): Float =
            if (fractions.size == 1) left + plotW else left + plotW * index / (fractions.size - 1)

        fun pointY(fraction: Float): Float = baseline - plotH * fraction

        val line = Path()
        if (fractions.size == 1) {
            val y = pointY(fractions[0])
            line.moveTo(left, y)
            line.lineTo(left + plotW, y)
        } else {
            fractions.forEachIndexed { index, fraction ->
                val x = pointX(index)
                val y = pointY(fraction)
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
        }

        val area = Path(line)
        area.lineTo(left + plotW, baseline)
        area.lineTo(left, baseline)
        area.close()
        canvas.drawPath(
            area,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
                alpha = FILL_ALPHA
            },
        )

        canvas.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = LINE_WIDTH
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
        )
    }

    private class Palette(
        val card: Int,
        val series: Int,
    )

    private companion object {
        const val WIDTH_PX = 256
        const val HEIGHT_PX = 256
        const val PADDING = 22f
        const val CORNER = 40f
        const val LINE_WIDTH = 7f
        const val FILL_ALPHA = 70

        val DayPalette = Palette(
            card = surfaceContainerHighLight.toArgb(),
            series = primaryLight.toArgb(),
        )
        val NightPalette = Palette(
            card = surfaceContainerHighDark.toArgb(),
            series = primaryDark.toArgb(),
        )
    }
}
