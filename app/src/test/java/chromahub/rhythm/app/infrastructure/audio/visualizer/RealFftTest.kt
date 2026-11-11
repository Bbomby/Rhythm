/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RealFftTest {

    @Test
    fun matchesNaiveDftOnRandomInput() {
        val n = 64
        val random = Random(42)
        val input = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val out = FloatArray(n / 2 + 1)
        RealFft(n).powerSpectrum(input, out)

        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                re += input[t] * cos(2 * PI * k * t / n)
                im -= input[t] * sin(2 * PI * k * t / n)
            }
            val expected = re * re + im * im
            assertEquals("bin $k", expected, out[k].toDouble(), 1e-3 * maxOf(1.0, expected))
        }
    }

    @Test
    fun sineLandsInItsBin() {
        val n = 256
        val bin = 32
        val input = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val out = FloatArray(n / 2 + 1)
        RealFft(n).powerSpectrum(input, out)
        val peak = out.indices.maxByOrNull { out[it] }
        assertEquals(bin, peak)
        // A unit sine puts (N/2)^2 of power into its bin.
        assertEquals((n / 2.0) * (n / 2.0), out[bin].toDouble(), 1.0)
    }

    @Test
    fun reusesStateBetweenCalls() {
        val fft = RealFft(128)
        val out = FloatArray(65)
        fft.powerSpectrum(FloatArray(128) { 1f }, out)
        fft.powerSpectrum(FloatArray(128), out)
        assertEquals(0f, out.max(), 0f)
    }

    @Test
    fun rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException::class.java) { RealFft(100) }
    }
}
