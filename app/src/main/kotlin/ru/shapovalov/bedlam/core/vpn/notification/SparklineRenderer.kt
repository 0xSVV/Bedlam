package ru.shapovalov.bedlam.core.vpn.notification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import ru.shapovalov.bedlam.ui.theme.onSurfaceVariantDark
import ru.shapovalov.bedlam.ui.theme.onSurfaceVariantLight
import ru.shapovalov.bedlam.ui.theme.primaryDark
import ru.shapovalov.bedlam.ui.theme.primaryLight
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighDark
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighLight
import ru.shapovalov.bedlam.ui.theme.tertiaryDark
import ru.shapovalov.bedlam.ui.theme.tertiaryLight

class SparklineRenderer(
    private val widthPx: Int = WIDTH_PX,
    private val heightPx: Int = HEIGHT_PX,
) {

    fun render(samples: RateHistory.Samples, night: Boolean): Bitmap {
        val palette = if (night) NightPalette else DayPalette
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawRoundRect(
            0f, 0f, widthPx.toFloat(), heightPx.toFloat(), CORNER, CORNER,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card },
        )

        val left = PADDING
        val top = PADDING
        val plotW = widthPx - PADDING * 2
        val plotH = heightPx - PADDING * 2
        val baseline = top + plotH

        canvas.drawLine(
            left, baseline, left + plotW, baseline,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.axis
                strokeWidth = AXIS_WIDTH
                alpha = AXIS_ALPHA
            },
        )

        val peak = Sparkline.peak(samples.tx, samples.rx)
        drawSeries(canvas, Sparkline.fractions(samples.rx, peak), left, top, plotW, plotH, palette.download, fill = true)
        drawSeries(canvas, Sparkline.fractions(samples.tx, peak), left, top, plotW, plotH, palette.upload, fill = false)
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
        fill: Boolean,
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

        if (fill) {
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
        }

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
        val axis: Int,
        val download: Int,
        val upload: Int,
    )

    private companion object {
        const val WIDTH_PX = 256
        const val HEIGHT_PX = 256
        const val PADDING = 22f
        const val CORNER = 40f
        const val LINE_WIDTH = 7f
        const val AXIS_WIDTH = 2f
        const val AXIS_ALPHA = 90
        const val FILL_ALPHA = 70

        val DayPalette = Palette(
            card = surfaceContainerHighLight.toArgb(),
            axis = onSurfaceVariantLight.toArgb(),
            download = primaryLight.toArgb(),
            upload = tertiaryLight.toArgb(),
        )
        val NightPalette = Palette(
            card = surfaceContainerHighDark.toArgb(),
            axis = onSurfaceVariantDark.toArgb(),
            download = primaryDark.toArgb(),
            upload = tertiaryDark.toArgb(),
        )
    }
}
