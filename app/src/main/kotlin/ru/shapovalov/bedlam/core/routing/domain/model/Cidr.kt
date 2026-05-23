package ru.shapovalov.bedlam.core.routing.domain.model

sealed interface Cidr {
    val prefixLength: Int
    val networkBytes: ByteArray
    val byteCount: Int get() = networkBytes.size
    val bitCount: Int get() = byteCount * 8

    fun asString(): String

    data class V4(
        override val networkBytes: ByteArray,
        override val prefixLength: Int,
    ) : Cidr {
        init {
            require(networkBytes.size == 4) { "IPv4 needs 4 bytes" }
            require(prefixLength in 0..32) { "IPv4 prefix length out of range: $prefixLength" }
            require(networkBytes.contentEquals(normalize(networkBytes, prefixLength))) {
                "IPv4 networkBytes must be normalized to the prefix"
            }
        }

        override fun asString(): String = bytesToIpv4(networkBytes) + "/" + prefixLength

        override fun equals(other: Any?): Boolean =
            other is V4 && prefixLength == other.prefixLength &&
                networkBytes.contentEquals(other.networkBytes)

        override fun hashCode(): Int = networkBytes.contentHashCode() * 31 + prefixLength
    }

    data class V6(
        override val networkBytes: ByteArray,
        override val prefixLength: Int,
    ) : Cidr {
        init {
            require(networkBytes.size == 16) { "IPv6 needs 16 bytes" }
            require(prefixLength in 0..128) { "IPv6 prefix length out of range: $prefixLength" }
            require(networkBytes.contentEquals(normalize(networkBytes, prefixLength))) {
                "IPv6 networkBytes must be normalized to the prefix"
            }
        }

        override fun asString(): String = bytesToIpv6(networkBytes) + "/" + prefixLength

        override fun equals(other: Any?): Boolean =
            other is V6 && prefixLength == other.prefixLength &&
                networkBytes.contentEquals(other.networkBytes)

        override fun hashCode(): Int = networkBytes.contentHashCode() * 31 + prefixLength
    }

    companion object {
        fun parse(s: String): Cidr {
            val slash = s.indexOf('/')
            require(slash > 0) { "Missing '/' in CIDR: $s" }
            val addr = s.substring(0, slash)
            val prefix = s.substring(slash + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid prefix: $s")
            val bytes = parseIpToBytes(addr)
            return when (bytes.size) {
                4 -> V4(normalize(bytes, prefix), prefix)
                16 -> V6(normalize(bytes, prefix), prefix)
                else -> error("unreachable")
            }
        }

        fun parseOrNull(s: String): Cidr? = runCatching { parse(s) }.getOrNull()
    }
}

internal fun normalize(bytes: ByteArray, prefixLength: Int): ByteArray {
    val result = bytes.copyOf()
    val totalBits = result.size * 8
    if (prefixLength >= totalBits) return result
    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8
    if (remainingBits != 0) {
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        result[fullBytes] = (result[fullBytes].toInt() and mask).toByte()
    }
    val firstZeroByte = if (remainingBits == 0) fullBytes else fullBytes + 1
    for (i in firstZeroByte until result.size) result[i] = 0
    return result
}

internal fun parseIpToBytes(addr: String): ByteArray {
    if (':' in addr) return parseIpv6ToBytes(addr)
    return parseIpv4ToBytes(addr)
}

internal fun parseIpv4ToBytes(addr: String): ByteArray {
    val parts = addr.split('.')
    require(parts.size == 4) { "Invalid IPv4: $addr" }
    val bytes = ByteArray(4)
    for (i in 0 until 4) {
        val n = parts[i].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid IPv4 octet: $addr")
        require(n in 0..255) { "IPv4 octet out of range: $addr" }
        bytes[i] = n.toByte()
    }
    return bytes
}

internal fun parseIpv6ToBytes(addr: String): ByteArray {
    val doubleColon = addr.indexOf("::")
    val left: List<String>
    val right: List<String>
    if (doubleColon >= 0) {
        require(addr.indexOf("::", doubleColon + 1) < 0) { "Multiple '::' in IPv6: $addr" }
        left = if (doubleColon == 0) emptyList() else addr.substring(0, doubleColon).split(':')
        right = if (doubleColon + 2 >= addr.length) emptyList() else addr.substring(doubleColon + 2).split(':')
    } else {
        left = addr.split(':')
        right = emptyList()
    }
    val totalGroups = left.size + right.size
    require(totalGroups <= 8) { "Too many IPv6 groups: $addr" }
    val missing = 8 - totalGroups
    require(doubleColon >= 0 || missing == 0) { "IPv6 needs 8 groups or '::': $addr" }
    val groups = left + List(missing) { "0" } + right
    val bytes = ByteArray(16)
    for (i in 0 until 8) {
        val v = groups[i].toIntOrNull(radix = 16)
            ?: throw IllegalArgumentException("Invalid IPv6 group: $addr")
        require(v in 0..0xFFFF) { "IPv6 group out of range: $addr" }
        bytes[i * 2] = (v ushr 8).toByte()
        bytes[i * 2 + 1] = v.toByte()
    }
    return bytes
}

internal fun bytesToIpv4(bytes: ByteArray): String =
    bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

internal fun bytesToIpv6(bytes: ByteArray): String {
    val groups = IntArray(8) { i ->
        ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
    }
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i < 8) {
        if (groups[i] == 0) {
            var j = i
            while (j < 8 && groups[j] == 0) j++
            val len = j - i
            if (len > bestLen) {
                bestLen = len
                bestStart = i
            }
            i = j
        } else i++
    }
    val sb = StringBuilder()
    if (bestLen < 2) {
        for (k in 0 until 8) {
            if (k > 0) sb.append(':')
            sb.append(groups[k].toString(16))
        }
    } else {
        for (k in 0 until bestStart) {
            if (k > 0) sb.append(':')
            sb.append(groups[k].toString(16))
        }
        sb.append("::")
        for (k in bestStart + bestLen until 8) {
            if (k > bestStart + bestLen) sb.append(':')
            sb.append(groups[k].toString(16))
        }
    }
    return sb.toString()
}
