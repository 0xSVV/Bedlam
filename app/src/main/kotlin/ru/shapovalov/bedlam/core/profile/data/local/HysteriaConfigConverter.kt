package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import ru.shapovalov.hysteria.config.HysteriaConfig

@ProvidedTypeConverter
class HysteriaConfigConverter(private val json: Json) {

    @TypeConverter
    fun toJson(config: HysteriaConfig): String = json.encodeToString(config)

    @TypeConverter
    fun fromJson(value: String): HysteriaConfig = json.decodeFromString(value)
}
