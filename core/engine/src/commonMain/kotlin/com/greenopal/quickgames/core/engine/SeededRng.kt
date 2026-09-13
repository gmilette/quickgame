package com.greenopal.quickgames.core.engine

class SeededRng(seed: Long) {
    private var state: Int = (seed xor (seed ushr 32)).toInt()

    fun nextUInt(): UInt {
        state += 0x6D2B79F5.toInt()
        var t = state.toUInt()
        t = (t xor (t shr 15)) * (t or 1u)
        t = t xor (t + (t xor (t shr 7)) * (t or 61u))
        return t xor (t shr 14)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return (nextUInt() % bound.toUInt()).toInt()
    }

    fun nextDouble(): Double = nextUInt().toDouble() / 4294967296.0

    fun <T> shuffle(items: List<T>): List<T> {
        val result = items.toMutableList()
        for (i in result.indices.reversed()) {
            if (i == 0) break
            val j = nextInt(i + 1)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result
    }
}
