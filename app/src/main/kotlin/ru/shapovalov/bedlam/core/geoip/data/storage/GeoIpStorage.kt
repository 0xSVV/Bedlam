package ru.shapovalov.bedlam.core.geoip.data.storage

import android.content.Context
import me.tatarka.inject.annotations.Inject
import java.io.File

/** On-disk lifecycle of the `geoip.dat` file with atomic temp+rename writes. */
@Inject
class GeoIpStorage(private val context: Context) {

    private val baseDir: File by lazy {
        File(context.filesDir, "geoip").apply { if (!exists()) mkdirs() }
    }

    val databaseFile: File get() = File(baseDir, "geoip.dat")
    val tempFile: File get() = File(baseDir, "geoip.dat.tmp")

    fun exists(): Boolean = databaseFile.exists()

    fun readBytes(): ByteArray = databaseFile.readBytes()

    fun replace(newBytes: ByteArray) {
        val tmp = tempFile
        tmp.writeBytes(newBytes)
        if (!tmp.renameTo(databaseFile)) {
            databaseFile.delete()
            check(tmp.renameTo(databaseFile)) { "Failed to install GeoIP database" }
        }
    }

    fun delete() {
        databaseFile.delete()
        tempFile.delete()
    }
}
