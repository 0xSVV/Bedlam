package ru.shapovalov.bedlam.core.geoip.data.parser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.normalize

/** Decodes a v2fly `geoip.dat` into a map of country code → CIDRs. */
@OptIn(ExperimentalSerializationApi::class)
@Inject
class V2FlyGeoIpParser {

    private val protoBuf = ProtoBuf {}

    fun parse(bytes: ByteArray): Map<CountryCode, List<Cidr>> {
        val list = protoBuf.decodeFromByteArray(GeoIpListProto.serializer(), bytes)
        val out = LinkedHashMap<CountryCode, List<Cidr>>(list.entry.size)
        for (entry in list.entry) {
            val code = CountryCode.ofOrNull(entry.countryCode) ?: continue
            val cidrs = entry.cidr.mapNotNull { it.toCidr() }
            if (cidrs.isNotEmpty()) out[code] = cidrs
        }
        return out
    }

    private fun CidrProto.toCidr(): Cidr? {
        return when (ip.size) {
            4 -> {
                if (prefix !in 0..32) return null
                Cidr.V4(normalize(ip, prefix), prefix)
            }
            16 -> {
                if (prefix !in 0..128) return null
                Cidr.V6(normalize(ip, prefix), prefix)
            }
            else -> null
        }
    }
}
