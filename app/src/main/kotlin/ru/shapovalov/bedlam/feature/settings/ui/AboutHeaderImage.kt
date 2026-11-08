package ru.shapovalov.bedlam.feature.settings.ui

import android.content.res.Resources
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.shapovalov.bedlam.R
import kotlin.math.roundToInt

internal object AboutHeaderImage {
    private val cache = LruCache<Int, ImageBitmap>(2)

    fun cached(width: Int): ImageBitmap? = cache.get(width)

    suspend fun load(resources: Resources, width: Int): ImageBitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resources, R.drawable.about_header)
        cache.get(width) ?: ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val targetWidth = width.coerceIn(1, info.size.width)
            val targetHeight = (info.size.height.toFloat() * targetWidth / info.size.width)
                .roundToInt()
                .coerceAtLeast(1)
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap().also { cache.put(width, it) }
    }
}
