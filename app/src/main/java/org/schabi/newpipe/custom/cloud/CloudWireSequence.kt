/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import java.math.BigInteger

/** Wire-compatible sparse sequence compiler; no native SQL or merge authority. */
internal object CloudWireSequence {
    data class Row(val rank: String, val key: CloudKey)
    private val end = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
    private val center = BigInteger.ONE.shiftLeft(127)
    private val stride = BigInteger.ONE.shiftLeft(64)
    private val refill = BigInteger.ONE.shiftLeft(32)
    fun rank(value: String): BigInteger {
        require(Regex("[0-9a-f]{32}").matches(value)) { "INVALID_SEQUENCE_RANK" }
        return BigInteger(value, 16).also { require(it > BigInteger.ZERO && it < end) { "INVALID_SEQUENCE_RANK" } }
    }
    fun plan(before: List<Row>, desired: List<CloudKey>): List<Row> {
        require(before.size <= CloudCatalogCodec.MAX_MEMBERSHIPS && desired.size <= CloudCatalogCodec.MAX_MEMBERSHIPS) { "SEQUENCE_LIMIT" }
        var last = BigInteger.ZERO
        before.forEach {
            val current = rank(it.rank)
            require(current > last) { "SEQUENCE_NOT_ORDERED" }
            last = current
        }
        val queues = HashMap<CloudKey, ArrayDeque<Int>>()
        before.forEachIndexed { index, row -> queues.getOrPut(row.key) { ArrayDeque() }.addLast(index) }
        val matches = desired.map { queues[it]?.removeFirstOrNull() ?: -1 }
        val tails = ArrayList<Int>()
        val indices = ArrayList<Int>()
        val previous = IntArray(matches.size) { -1 }
        for (i in matches.indices) {
            val value = matches[i]
            if (value < 0) continue
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tails[mid] < value) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[i] = indices[lo - 1]
            if (lo == tails.size) {
                tails.add(value)
                indices.add(i)
            } else {
                tails[lo] = value
                indices[lo] = i
            }
        }
        val keep = HashSet<Int>()
        var k = indices.lastOrNull() ?: -1
        while (k >= 0) {
            keep.add(k)
            k = previous[k]
        }
        val positions = arrayOfNulls<BigInteger>(desired.size)
        desired.indices.forEach { if (it in keep) positions[it] = rank(before[matches[it]].rank) }
        fun fill(start: Int, stop: Int, enoughRoom: Boolean): Boolean {
            val count = stop - start
            if (count == 0) return true
            val lower = if (start == 0) BigInteger.ZERO else checkNotNull(positions[start - 1])
            val upper = if (stop == desired.size) end else checkNotNull(positions[stop])
            val available = (upper - lower) / BigInteger.valueOf(count.toLong() + 1)
            if (available == BigInteger.ZERO || enoughRoom && available < refill) return false
            val step: BigInteger
            val first: BigInteger
            when {
                start == 0 && stop == desired.size -> {
                    step = stride
                    first = center - stride * BigInteger.valueOf((count / 2).toLong())
                }

                start == 0 -> {
                    step = available.min(stride)
                    first = upper - step * BigInteger.valueOf(count.toLong())
                }

                stop == desired.size -> {
                    step = available.min(stride)
                    first = lower + step
                }

                else -> {
                    step = available
                    first = lower + step
                }
            }
            for (i in 0 until count) positions[start + i] = first + step * BigInteger.valueOf(i.toLong())
            return true
        }
        var scan = 0
        while (scan < desired.size) {
            if (positions[scan] != null) {
                scan++
                continue
            }
            val start = scan
            var stop = start
            while (stop < desired.size && positions[stop] == null) stop++
            if (fill(start, stop, false)) {
                scan = stop
                continue
            }
            var fitted = false
            for (radius in listOf(4, 8, 16, 32, 64, 127)) {
                var left = maxOf(0, start - radius)
                var right = minOf(desired.size, stop + radius)
                while (left > 0 && positions[left - 1] == null && right - left <= 256) left--
                while (right < desired.size && positions[right] == null && right - left <= 256) right++
                if (right - left > 256) break
                if (fill(left, right, true)) {
                    scan = right
                    fitted = true
                    break
                }
            }
            check(fitted) { "SEQUENCE_LOCAL_REBALANCE_REQUIRED" }
        }
        last = BigInteger.ZERO
        return positions.mapIndexed { index, raw ->
            val value = checkNotNull(raw)
            check(value > last && value < end) { "SEQUENCE_INTERNAL_ORDER" }
            last = value
            Row(value.toString(16).padStart(32, '0'), desired[index])
        }
    }
}
