package ru.shapovalov.bedlam.core.profile.data.local

import android.util.Log
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

val unreadableHysteriaConfig = HysteriaConfig(
    server = ServerCredentials(),
    tls = TlsOptions(),
)

@ProvidedTypeConverter
class HysteriaConfigConverter(private val json: Json) {

    @TypeConverter
    fun toJson(config: HysteriaConfig): String = json.encodeToString(config)

    @TypeConverter
    fun fromJson(value: String): HysteriaConfig =
        runCatching { json.decodeFromString<HysteriaConfig>(value) }
            .getOrElse {
                Log.w(TAG, "Profile config is unreadable; substituting a blank one", it)
                unreadableHysteriaConfig
            }

    private companion object {
        const val TAG = "HysteriaConfigConverter"
    }
}
