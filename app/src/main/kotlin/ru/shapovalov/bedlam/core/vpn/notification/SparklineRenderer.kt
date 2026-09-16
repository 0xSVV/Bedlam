package ru.shapovalov.bedlam.core.vpn.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import ru.shapovalov.bedlam.ui.theme.primaryDark
import ru.shapovalov.bedlam.ui.theme.primaryLight
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighDark
import ru.shapovalov.bedlam.ui.theme.surfaceContainerHighLight
import kotlin.math.roundToInt

class SparklineRenderer(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val sizePx = (largeIconDp() * density).roundToInt()
    private val padding = PADDING_DP * density
    private val corner = CORNER_DP * density

    private val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Path()
    private val area = Path()

    fun render(values: List<Long>, night: Boolean): Bitmap {
        val palette = if (night) NightPalette else DayPalette
        cardPaint.color = palette.card
        fillPaint.color = palette.fill
        linePaint.color = palette.line

        bitmap.eraseColor(0)
        val size = sizePx.toFloat()
        canvas.drawRoundRect(0f, 0f, size, size, corner, corner, cardPaint)

        val fractions = Sparkline.fractions(values, Sparkline.peak(values))
        if (fractions.isEmpty()) return bitmap

        val left = padding
        val plotW = size - padding * 2
        val plotH = size - padding * 2
        val baseline = size - padding

        line.rewind()
        if (fractions.size == 1) {
            val y = baseline - plotH * fractions[0]
            line.moveTo(left, y)
            line.lineTo(left + plotW, y)
        } else {
            fractions.forEachIndexed { index, fraction ->
                val x = left + plotW * index / (fractions.size - 1)
                val y = baseline - plotH * fraction
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
        }

        area.rewind()
        area.addPath(line)
        area.lineTo(left + plotW, baseline)
        area.lineTo(left, baseline)
        area.close()

        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)
        return bitmap
    }

    private class Palette(val card: Int, val line: Int) {
        val fill: Int = ColorUtils.setAlphaComponent(line, FILL_ALPHA)
    }

    private companion object {
        const val PADDING_DP = 4f
        const val CORNER_DP = 8f
        const val LINE_DP = 1.5f
        const val FILL_ALPHA = 70

        fun largeIconDp(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 48 else 36

        val DayPalette = Palette(
            card = surfaceContainerHighLight.toArgb(),
            line = primaryLight.toArgb(),
        )
        val NightPalette = Palette(
            card = surfaceContainerHighDark.toArgb(),
            line = primaryDark.toArgb(),
        )
    }
}
