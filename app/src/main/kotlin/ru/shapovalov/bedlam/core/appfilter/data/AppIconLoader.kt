package ru.shapovalov.bedlam.core.appfilter.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import me.tatarka.inject.annotations.Inject
import kotlin.math.roundToInt

@Inject
class AppIconLoader(
    private val context: Context,
) {

    fun load(packageName: String, sizePx: Int): Bitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toSquareBitmap(sizePx)
    }.getOrNull()
}

private fun Drawable.toSquareBitmap(sizePx: Int): Bitmap {
    val width = intrinsicWidth
    val height = intrinsicHeight
    if (width <= 0 || height <= 0 || width == height) return toBitmap(sizePx, sizePx)
    val scale = sizePx.toFloat() / maxOf(width, height)
    val fittedWidth = (width * scale).roundToInt().coerceIn(1, sizePx)
    val fittedHeight = (height * scale).roundToInt().coerceIn(1, sizePx)
    val left = (sizePx - fittedWidth) / 2
    val top = (sizePx - fittedHeight) / 2
    val bitmap = createBitmap(sizePx, sizePx)
    setBounds(left, top, left + fittedWidth, top + fittedHeight)
    draw(Canvas(bitmap))
    return bitmap
}
