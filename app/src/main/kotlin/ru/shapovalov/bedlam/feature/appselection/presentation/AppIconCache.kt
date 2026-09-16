package ru.shapovalov.bedlam.feature.appselection.presentation

import android.graphics.Bitmap
import android.util.LruCache
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.shapovalov.bedlam.core.appfilter.data.AppIconLoader

class AppIconCache(
    private val loader: AppIconLoader,
) : InstanceKeeper.Instance {

    private val cache = object : LruCache<IconKey, Bitmap>(IconCacheMaxBytes) {
        override fun sizeOf(key: IconKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(packageName: String, sizePx: Int): Bitmap? =
        cache.get(IconKey(packageName, sizePx))

    suspend fun load(packageName: String, sizePx: Int): Bitmap? {
        val key = IconKey(packageName, sizePx)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key) ?: loader.load(packageName, sizePx)?.also { cache.put(key, it) }
        }
    }

    override fun onDestroy() {
        cache.evictAll()
    }

    private data class IconKey(val packageName: String, val sizePx: Int)
}

private const val IconCacheMaxBytes = 16 * 1024 * 1024
