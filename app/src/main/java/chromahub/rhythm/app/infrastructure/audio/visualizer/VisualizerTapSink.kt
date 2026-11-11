/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the decoded PCM going into a player's audio sink, for the visualizer. Audio passes through untouched.
 *
 * A sink wrapper rather than an AudioProcessor because:
 * - with hi-res float output enabled, DefaultAudioSink skips the processor chain, so a processor would see nothing;
 * - [handleBuffer] carries each buffer's presentation time and [getCurrentPositionUs] reports what is playing now, on
 *   the same clock, so the visualizer can read exactly the audio being heard despite the AudioTrack's buffering,
 *   playback speed, silence skipping and seeks.
 *
 * It taps before Rhythm's own DSP and volume, so the visuals follow the track rather than the volume setting.
 * Capture only happens while [isCapturing] says a visualizer is on screen; otherwise this is a plain forwarder.
 */
@OptIn(UnstableApi::class)
class VisualizerTapSink(
    sink: AudioSink,
    private val isCapturing: () -> Boolean = { VisualizerAudioBus.isCapturing },
    private val nanoTime: () -> Long = System::nanoTime,
) : ForwardingAudioSink(sink) {

    companion object {
        /** Input above this rate is averaged down by an integer factor; the analysis needs nothing above ~20 kHz. */
        const val MAX_TAP_RATE = 48_000

        /** Never extrapolate the playout clock further than this past the last position report. */
        private const val MAX_EXTRAPOLATION_NS = 200_000_000L
    }

    val ring = PcmRingBuffer()

    // Format, set on the playback thread in configure().
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var bytesPerFrame = 0
    private var decimation = 1

    // Capture state, playback thread only.
    private var scratch = FloatArray(4096)
    private var pendingSum = 0f
    private var pendingFrames = 0
    private var lastBuffer: ByteBuffer? = null
    private var lastPtsUs = C.TIME_UNSET

    // Playout clock: written on the playback thread, read on the render thread.
    private val clockLock = Any()
    private var clockPositionUs = C.TIME_UNSET
    private var clockNanos = 0L
    @Volatile private var playing = false
    @Volatile private var speed = 1f

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val pcm = MimeTypes.AUDIO_RAW == format.sampleMimeType && bytesPerSample(format.pcmEncoding) > 0
        if (pcm && format.channelCount > 0 && format.sampleRate > 0) {
            encoding = format.pcmEncoding
            channels = format.channelCount
            bytesPerFrame = bytesPerSample(encoding) * channels
            decimation = maxOf(1, format.sampleRate / MAX_TAP_RATE)
            val tapRate = format.sampleRate / decimation
            // A gapless move to a track at the same rate keeps the outgoing track's tail, which is still playing.
            if (tapRate != ring.sampleRate) ring.reset(tapRate)
        } else {
            // Passthrough/offload (compressed) or unknown: nothing we can analyse.
            encoding = C.ENCODING_INVALID
            ring.reset(0)
        }
        resetCapture()
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // DefaultAudioSink may return false and get the same, partly consumed buffer again: capture it only once.
        val seenBefore = buffer === lastBuffer && presentationTimeUs == lastPtsUs
        if (!seenBefore) {
            lastBuffer = buffer
            lastPtsUs = presentationTimeUs
            if (encoding != C.ENCODING_INVALID && isCapturing()) capture(buffer, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) {
            synchronized(clockLock) {
                clockPositionUs = position
                clockNanos = nanoTime()
            }
        }
        return position
    }

    override fun play() {
        playing = true
        super.play()
    }

    override fun pause() {
        playing = false
        super.pause()
    }

    override fun flush() {
        ring.clear()
        resetCapture()
        synchronized(clockLock) { clockPositionUs = C.TIME_UNSET }
        super.flush()
    }

    override fun reset() {
        playing = false
        ring.clear()
        resetCapture()
        synchronized(clockLock) { clockPositionUs = C.TIME_UNSET }
        super.reset()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        speed = playbackParameters.speed
        super.setPlaybackParameters(playbackParameters)
    }

    /**
     * The presentation time playing now, in the same time base as the buffers' timestamps, extrapolated from the last
     * position report while playing. [C.TIME_UNSET] before the first report.
     */
    fun playoutPositionUs(): Long {
        val position: Long
        val at: Long
        synchronized(clockLock) {
            position = clockPositionUs
            at = clockNanos
        }
        if (position == C.TIME_UNSET || !playing) return position
        val elapsedNs = (nanoTime() - at).coerceIn(0L, MAX_EXTRAPOLATION_NS)
        return position + (elapsedNs / 1000f * speed).toLong()
    }

    val isPlaying: Boolean get() = playing

    /** Fills [out] with the audio ending at the current playout position; see [PcmRingBuffer.read]. */
    fun readWindow(out: FloatArray): Boolean {
        val position = playoutPositionUs()
        if (position == C.TIME_UNSET) {
            out.fill(0f)
            return false
        }
        return ring.read(position, out)
    }

    private fun resetCapture() {
        pendingSum = 0f
        pendingFrames = 0
        lastBuffer = null
        lastPtsUs = C.TIME_UNSET
    }

    private fun capture(buffer: ByteBuffer, ptsUs: Long) {
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = source.remaining() / bytesPerFrame
        if (frames <= 0) return
        val maxOut = frames / decimation + 1
        if (scratch.size < maxOut) scratch = FloatArray(maxOf(maxOut, scratch.size * 2))
        var outCount = 0
        val scale = 1f / channels
        var position = source.position()
        repeat(frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += readSample(source, position)
                position += bytesPerSample(encoding)
            }
            pendingSum += sum * scale
            pendingFrames++
            if (pendingFrames == decimation) {
                scratch[outCount++] = pendingSum / decimation
                pendingSum = 0f
                pendingFrames = 0
            }
        }
        ring.write(scratch, outCount, ptsUs)
    }

    private fun readSample(source: ByteBuffer, index: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> source.getShort(index) / 32768f
        C.ENCODING_PCM_FLOAT -> source.getFloat(index)
        C.ENCODING_PCM_8BIT -> ((source.get(index).toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_24BIT -> {
            val value = (source.get(index).toInt() and 0xFF) or
                ((source.get(index + 1).toInt() and 0xFF) shl 8) or
                (source.get(index + 2).toInt() shl 16)
            value / 8_388_608f
        }
        C.ENCODING_PCM_32BIT -> source.getInt(index) / 2_147_483_648f
        else -> 0f
    }

    /** Little-endian integer and float PCM only; 0 for anything we don't decode (big-endian, compressed). */
    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
}
