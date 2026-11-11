/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class VisualizerTapSinkTest {

    /** A stand-in for DefaultAudioSink: records calls and returns what the test sets. */
    private class FakeSink {
        var handleBufferResult = true
        var positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        val calls = mutableListOf<String>()

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "handleBuffer" -> handleBufferResult
                "getCurrentPositionUs" -> positionUs
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
    }

    private var capturing = true
    private var now = 0L
    private val fake = FakeSink()
    private val tap = VisualizerTapSink(fake.sink, isCapturing = { capturing }, nanoTime = { now })

    private fun configure(encoding: Int, channels: Int = 2, rate: Int = 1000, mime: String = MimeTypes.AUDIO_RAW) {
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setPcmEncoding(if (mime == MimeTypes.AUDIO_RAW) encoding else Format.NO_VALUE)
            .setChannelCount(channels)
            .setSampleRate(rate)
            .build()
        tap.configure(AudioSink.AudioSinkConfig.Builder(format).build())
    }

    private fun pcm16(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    /** Reports [positionUs] as the playout position through the wrapped sink, as the renderer's polling would. */
    private fun reportPosition(positionUs: Long) {
        fake.positionUs = positionUs
        tap.getCurrentPositionUs(false)
    }

    @Test
    fun downmixesStereo16BitToMonoAndForwardsUntouched() {
        configure(C.ENCODING_PCM_16BIT)
        // Frames (L, R): (16384, 0) -> 0.25, (-16384, -16384) -> -0.5
        val buffer = pcm16(16384, 0, -16384, -16384)
        assertTrue(tap.handleBuffer(buffer, 0L, 1))
        assertEquals("the sink, not the tap, consumes the buffer", 0, buffer.position())
        assertTrue("handleBuffer" in fake.calls)

        reportPosition(2_000L)
        val out = FloatArray(2)
        assertTrue(tap.readWindow(out))
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), out, 1e-6f)
    }

    @Test
    fun capturesARetriedBufferOnce() {
        configure(C.ENCODING_PCM_16BIT, channels = 1)
        val buffer = pcm16(1000, 2000, 3000)
        fake.handleBufferResult = false
        assertFalse(tap.handleBuffer(buffer, 0L, 1))
        fake.handleBufferResult = true
        assertTrue(tap.handleBuffer(buffer, 0L, 1))

        reportPosition(10_000L) // well past the end: clamps to the newest samples
        val out = FloatArray(4)
        tap.readWindow(out)
        // Captured once, so exactly three samples exist (one zero of padding before them).
        assertArrayEquals(floatArrayOf(0f, 1000 / 32768f, 2000 / 32768f, 3000 / 32768f), out, 1e-6f)
    }

    @Test
    fun capturesNothingWhileNoVisualizerIsShown() {
        configure(C.ENCODING_PCM_16BIT, channels = 1)
        capturing = false
        tap.handleBuffer(pcm16(1000, 2000), 0L, 1)
        reportPosition(2_000L)
        assertFalse(tap.readWindow(FloatArray(2)))
        assertTrue("audio still reaches the sink", "handleBuffer" in fake.calls)
    }

    @Test
    fun floatAnd24BitInputAreRead() {
        configure(C.ENCODING_PCM_FLOAT, channels = 1)
        val floats = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-0.25f)
        floats.flip()
        tap.handleBuffer(floats, 0L, 1)
        reportPosition(2_000L)
        val out = FloatArray(2)
        tap.readWindow(out)
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), out, 1e-6f)

        configure(C.ENCODING_PCM_24BIT, channels = 1, rate = 2000) // new rate: history resets
        // 0x400000 = +0.5, 0xC00000 = -0.5 (little-endian, 3 bytes each)
        val packed = ByteBuffer.wrap(byteArrayOf(0, 0, 0x40, 0, 0, 0xC0.toByte()))
        tap.handleBuffer(packed, 0L, 1)
        reportPosition(1_000L)
        tap.readWindow(out)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), out, 1e-6f)
    }

    @Test
    fun hiResInputIsDecimatedToAtMost48k() {
        configure(C.ENCODING_PCM_16BIT, channels = 1, rate = 192_000)
        assertEquals(48_000, tap.ring.sampleRate)
        // Four input samples average into one tap sample.
        tap.handleBuffer(pcm16(4000, 8000, 12000, 16000, 0, 0, 0, 0), 0L, 1)
        reportPosition(1_000_000L)
        val out = FloatArray(2)
        tap.readWindow(out)
        assertArrayEquals(floatArrayOf(10000 / 32768f, 0f), out, 1e-6f)
    }

    @Test
    fun compressedPassthroughIsIgnored() {
        configure(C.ENCODING_INVALID, mime = MimeTypes.AUDIO_AC3, rate = 48_000)
        tap.handleBuffer(ByteBuffer.allocateDirect(64), 0L, 1)
        reportPosition(1_000L)
        assertFalse(tap.readWindow(FloatArray(4)))
        assertTrue("handleBuffer" in fake.calls)
    }

    @Test
    fun playoutClockExtrapolatesOnlyWhilePlaying() {
        configure(C.ENCODING_PCM_16BIT)
        assertEquals(C.TIME_UNSET, tap.playoutPositionUs())

        tap.setPlaybackParameters(PlaybackParameters(2f))
        tap.play()
        now = 1_000_000_000L
        reportPosition(5_000_000L)
        now += 50_000_000L // 50 ms later at 2x speed
        assertEquals(5_100_000L, tap.playoutPositionUs())

        now += 10_000_000_000L // a stalled report never runs away: capped at 200 ms
        assertEquals(5_400_000L, tap.playoutPositionUs())

        tap.pause()
        assertEquals(5_000_000L, tap.playoutPositionUs())
    }

    @Test
    fun flushForgetsAudioAndPosition() {
        configure(C.ENCODING_PCM_16BIT, channels = 1)
        tap.handleBuffer(pcm16(1000, 2000), 0L, 1)
        reportPosition(2_000L)
        tap.flush()
        assertEquals(C.TIME_UNSET, tap.playoutPositionUs())
        reportPosition(2_000L)
        assertFalse(tap.readWindow(FloatArray(2)))
        assertTrue("flush" in fake.calls)
    }
}
