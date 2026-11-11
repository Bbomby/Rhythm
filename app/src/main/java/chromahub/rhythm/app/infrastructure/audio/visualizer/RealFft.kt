/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative radix-2 FFT for real input, sized once and reused every frame without allocating.
 *
 * [powerSpectrum] writes |X[k]|^2 for k in 0..size/2 (DC to Nyquist inclusive).
 */
class RealFft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { -sin(2.0 * PI * it / size).toFloat() }
    private val bitReversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Number of bins [powerSpectrum] writes: DC through Nyquist. */
    val binCount: Int get() = size / 2 + 1

    fun powerSpectrum(input: FloatArray, out: FloatArray) {
        require(input.size >= size) { "input holds ${input.size} samples, need $size" }
        require(out.size >= binCount) { "output holds ${out.size} bins, need $binCount" }
        for (i in 0 until size) {
            re[bitReversed[i]] = input[i]
            im[i] = 0f
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val o = j + half
                    val tr = wr * re[o] - wi * im[o]
                    val ti = wr * im[o] + wi * re[o]
                    re[o] = re[j] - tr
                    im[o] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
        for (k in 0 until binCount) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}
