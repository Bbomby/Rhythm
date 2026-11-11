/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** At 1000 Hz one sample is one millisecond, which keeps the position arithmetic readable. */
class PcmRingBufferTest {

    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }
    private fun expected(from: Int, count: Int) = ramp(from, count)

    private fun buffer(capacity: Int = 1024) = PcmRingBuffer(capacity).apply { reset(1000) }

    @Test
    fun readsTheWindowEndingAtThePlayingPosition() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 0L)
        val out = FloatArray(10)
        assertTrue(ring.read(50_000L, out))
        assertArrayEquals(expected(40, 10), out, 0f)
    }

    @Test
    fun usesEachChunksOwnTimestamp() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 0L)
        // A discontinuity: the second chunk is stamped 1 s later than its sample count implies.
        ring.write(ramp(100, 100), 100, ptsUs = 1_100_000L)
        val out = FloatArray(10)
        ring.read(1_150_000L, out)
        assertArrayEquals(expected(140, 10), out, 0f)
    }

    @Test
    fun windowsSpanChunkBoundaries() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 0L)
        ring.write(ramp(100, 100), 100, ptsUs = 100_000L)
        val out = FloatArray(10)
        ring.read(105_000L, out)
        assertArrayEquals(expected(95, 10), out, 0f)
    }

    @Test
    fun positionBeforeHistoryReadsNothing() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 1_000_000L)
        val out = FloatArray(4) { 9f }
        assertFalse(ring.read(500_000L, out))
        assertArrayEquals(FloatArray(4), out, 0f)
    }

    @Test
    fun positionPastTheWrittenAudioClampsToTheNewestSamples() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 0L)
        val out = FloatArray(5)
        ring.read(10_000_000L, out)
        assertArrayEquals(expected(95, 5), out, 0f)
    }

    @Test
    fun earlyWindowIsZeroPaddedOnTheOldSide() {
        val ring = buffer()
        ring.write(ramp(1, 100), 100, ptsUs = 0L)
        val out = FloatArray(6)
        assertTrue(ring.read(3_000L, out))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f), out, 0f)
    }

    @Test
    fun wrapsAroundAndForgetsOverwrittenAudio() {
        val ring = buffer(capacity = 16)
        ring.write(ramp(0, 40), 40, ptsUs = 0L)
        val out = FloatArray(20)
        ring.read(40_000L, out)
        // Only the newest 16 samples (24..39) survive; the 4 older slots in the window read as zero.
        assertArrayEquals(FloatArray(4) + expected(24, 16), out, 0f)
    }

    @Test
    fun clearDropsHistory() {
        val ring = buffer()
        ring.write(ramp(0, 100), 100, ptsUs = 0L)
        ring.clear()
        assertFalse(ring.read(50_000L, FloatArray(4)))
    }

    @Test
    fun unknownRateReadsNothing() {
        val ring = PcmRingBuffer(64)
        ring.write(ramp(0, 10), 10, ptsUs = 0L)
        assertFalse(ring.read(5_000L, FloatArray(4)))
    }
}
