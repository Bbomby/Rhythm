/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

/**
 * Mono float history of the decoded audio, with each written chunk stamped by its presentation time.
 *
 * The playback thread writes chunks as the decoder produces them, which runs ahead of what the speaker is playing by
 * the AudioTrack buffer. The render thread reads the window ending at the position that is *playing now*, so the
 * visuals line up with what is heard. One writer and one reader; both go through this object's lock, which is held
 * only for array copies.
 */
class PcmRingBuffer(capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 2^18 samples: about 5.4 s at 48 kHz, comfortably more than any AudioTrack buffer. */
        const val DEFAULT_CAPACITY = 1 shl 18
        private const val CHUNK_SLOTS = 1024
    }

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    private val data = FloatArray(capacity)
    private val mask = capacity - 1

    /** Total samples written since the last [reset]; the next sample goes to `writeIndex and mask`. */
    private var writeIndex = 0L

    private val chunkStart = LongArray(CHUNK_SLOTS)
    private val chunkPtsUs = LongArray(CHUNK_SLOTS)
    private var chunkCount = 0L

    @Volatile
    var sampleRate: Int = 0
        private set

    @Synchronized
    fun reset(sampleRate: Int) {
        this.sampleRate = sampleRate
        clearLocked()
    }

    /** Drops all history (seek, flush, new stream) but keeps the sample rate. */
    @Synchronized
    fun clear() = clearLocked()

    private fun clearLocked() {
        writeIndex = 0L
        chunkCount = 0L
    }

    @Synchronized
    fun write(samples: FloatArray, count: Int, ptsUs: Long) {
        if (count <= 0) return
        val slot = (chunkCount % CHUNK_SLOTS).toInt()
        chunkStart[slot] = writeIndex
        chunkPtsUs[slot] = ptsUs
        chunkCount++
        for (i in 0 until count) {
            data[((writeIndex + i) and mask.toLong()).toInt()] = samples[i]
        }
        writeIndex += count
    }

    /**
     * Fills [out] with the samples that end at [positionUs] (oldest first). Samples outside the retained history are
     * zero. Returns false when nothing real could be read (no data, unknown rate, or position before the history).
     */
    @Synchronized
    fun read(positionUs: Long, out: FloatArray): Boolean {
        val rate = sampleRate
        val endIndex = if (rate > 0 && chunkCount > 0) indexAt(positionUs, rate) else -1L
        if (endIndex < 0) {
            out.fill(0f)
            return false
        }
        val oldest = maxOf(0L, writeIndex - data.size)
        val startIndex = endIndex - out.size
        var any = false
        for (i in out.indices) {
            val index = startIndex + i
            if (index in oldest until writeIndex) {
                out[i] = data[(index and mask.toLong()).toInt()]
                any = true
            } else {
                out[i] = 0f
            }
        }
        return any
    }

    /** Exclusive end index for [positionUs], or -1 when the position precedes every retained chunk. */
    private fun indexAt(positionUs: Long, rate: Int): Long {
        val retained = minOf(chunkCount, CHUNK_SLOTS.toLong())
        // Newest chunk first: the one that starts at or before the position.
        for (back in 0 until retained) {
            val slot = ((chunkCount - 1 - back) % CHUNK_SLOTS).toInt()
            val pts = chunkPtsUs[slot]
            if (pts <= positionUs) {
                val offset = (positionUs - pts) * rate / 1_000_000L
                return minOf(chunkStart[slot] + offset, writeIndex)
            }
        }
        return -1L
    }
}
