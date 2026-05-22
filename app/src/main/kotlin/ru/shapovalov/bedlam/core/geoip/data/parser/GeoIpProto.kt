@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ru.shapovalov.bedlam.core.geoip.data.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// Schema for v2fly geoip.dat
// https://github.com/v2fly/v2ray-core/blob/master/app/router/config.proto

@Serializable
internal data class CidrProto(
    @ProtoNumber(1) val ip: ByteArray = ByteArray(0),
    @ProtoNumber(2) val prefix: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is CidrProto && prefix == other.prefix && ip.contentEquals(other.ip)
    override fun hashCode(): Int = ip.contentHashCode() * 31 + prefix
}

@Serializable
internal data class GeoIpProto(
    @ProtoNumber(1) val countryCode: String = "",
    @ProtoNumber(2) val cidr: List<CidrProto> = emptyList(),
)

@Serializable
internal data class GeoIpListProto(
    @ProtoNumber(1) val entry: List<GeoIpProto> = emptyList(),
)
