package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.normalize

object CidrMath {

    fun coalesce(cidrs: List<Cidr>): List<Cidr> {
        val v4: List<Cidr> = cidrs.filterIsInstance<Cidr.V4>()
        val v6: List<Cidr> = cidrs.filterIsInstance<Cidr.V6>()
        return coalesceSameFamily(v4) + coalesceSameFamily(v6)
    }

    fun subtract(base: List<Cidr>, exclude: List<Cidr>): List<Cidr> {
        val baseV4: List<Cidr> = base.filterIsInstance<Cidr.V4>()
        val baseV6: List<Cidr> = base.filterIsInstance<Cidr.V6>()
        val excV4: List<Cidr> = exclude.filterIsInstance<Cidr.V4>()
        val excV6: List<Cidr> = exclude.filterIsInstance<Cidr.V6>()
        return subtractSameFamily(baseV4, excV4) +
            subtractSameFamily(baseV6, excV6)
    }

    fun contains(outer: Cidr, inner: Cidr): Boolean {
        if (outer::class != inner::class) return false
        if (outer.prefixLength > inner.prefixLength) return false
        return commonPrefixBits(outer.networkBytes, inner.networkBytes) >= outer.prefixLength
    }

    fun overlaps(a: Cidr, b: Cidr): Boolean {
        if (a::class != b::class) return false
        val shorter = minOf(a.prefixLength, b.prefixLength)
        return commonPrefixBits(a.networkBytes, b.networkBytes) >= shorter
    }

    private fun coalesceSameFamily(input: List<Cidr>): List<Cidr> {
        if (input.isEmpty()) return emptyList()
        val deduped = removeContained(input)

        val working = deduped.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            working.sortWith(networkThenPrefix())
            var i = 0
            while (i < working.size - 1) {
                val a = working[i]
                val b = working[i + 1]
                if (a.prefixLength > 0 &&
                    a.prefixLength == b.prefixLength &&
                    areBuddies(a, b)
                ) {
                    working[i] = parentOf(a)
                    working.removeAt(i + 1)
                    changed = true
                } else {
                    i++
                }
            }
        }

        val finalList = removeContained(working).toMutableList()
        finalList.sortWith(networkThenPrefix())
        return finalList
    }

    private fun removeContained(input: List<Cidr>): List<Cidr> {
        val sorted = input.sortedWith(
            compareBy<Cidr> { it.prefixLength }.then(networkThenPrefix())
        )
        val out = mutableListOf<Cidr>()
        for (c in sorted) {
            if (out.any { contains(it, c) }) continue
            out.add(c)
        }
        return out
    }

    private fun subtractSameFamily(base: List<Cidr>, exclude: List<Cidr>): List<Cidr> {
        if (base.isEmpty()) return emptyList()
        if (exclude.isEmpty()) return coalesceSameFamily(base)
        var current = base.toMutableList()
        for (e in exclude) {
            val next = mutableListOf<Cidr>()
            for (c in current) {
                next += subtractOne(c, e)
            }
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return coalesceSameFamily(current)
    }

    private fun subtractOne(c: Cidr, e: Cidr): List<Cidr> {
        if (c::class != e::class) return listOf(c)
        if (contains(e, c)) return emptyList()
        if (!overlaps(c, e)) return listOf(c)
        val pieces = mutableListOf<Cidr>()
        val stack = ArrayDeque<Cidr>()
        stack.addLast(c)
        while (stack.isNotEmpty()) {
            val piece = stack.removeLast()
            when {
                contains(e, piece) -> Unit
                !overlaps(piece, e) -> pieces += piece
                else -> {
                    val (lo, hi) = split(piece)
                    stack.addLast(hi)
                    stack.addLast(lo)
                }
            }
        }
        return pieces
    }

    private fun split(c: Cidr): Pair<Cidr, Cidr> {
        require(c.prefixLength < c.bitCount) { "Cannot split a /max CIDR" }
        val childPrefix = c.prefixLength + 1
        val loBytes = normalize(c.networkBytes, childPrefix)
        val hiBytes = c.networkBytes.copyOf()
        val bit = c.prefixLength
        val byteIdx = bit / 8
        val bitInByte = 7 - (bit % 8)
        hiBytes[byteIdx] = (hiBytes[byteIdx].toInt() or (1 shl bitInByte)).toByte()
        val hiNorm = normalize(hiBytes, childPrefix)
        return when (c) {
            is Cidr.V4 -> Cidr.V4(loBytes, childPrefix) to Cidr.V4(hiNorm, childPrefix)
            is Cidr.V6 -> Cidr.V6(loBytes, childPrefix) to Cidr.V6(hiNorm, childPrefix)
        }
    }

    private fun parentOf(c: Cidr): Cidr {
        val parentPrefix = c.prefixLength - 1
        val bytes = normalize(c.networkBytes, parentPrefix)
        return when (c) {
            is Cidr.V4 -> Cidr.V4(bytes, parentPrefix)
            is Cidr.V6 -> Cidr.V6(bytes, parentPrefix)
        }
    }

    private fun areBuddies(a: Cidr, b: Cidr): Boolean {
        val parentPrefix = a.prefixLength - 1
        if (parentPrefix < 0) return false
        return normalize(a.networkBytes, parentPrefix)
            .contentEquals(normalize(b.networkBytes, parentPrefix))
    }

    private fun commonPrefixBits(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        var bits = 0
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai == bi) {
                bits += 8
                continue
            }
            val xor = ai xor bi
            var lz = 0
            var mask = 0x80
            while (mask > 0 && xor and mask == 0) {
                lz++
                mask = mask ushr 1
            }
            bits += lz
            return bits
        }
        return bits
    }

    private fun networkThenPrefix(): Comparator<Cidr> =
        compareBy(BytewiseAscendingComparator) { c: Cidr -> c.networkBytes }
            .thenBy { it.prefixLength }

    private object BytewiseAscendingComparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a[i].toInt() and 0xFF
                val bi = b[i].toInt() and 0xFF
                val c = ai.compareTo(bi)
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }
    }
}
