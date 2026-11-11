/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide hand-off between the playback service's audio taps and whatever visualizer is on screen.
 *
 * The player engine marks the tap of its current master player as [activeTap] (it changes at every crossfade swap).
 * Visualizers [acquire] while visible, which turns capture on, and [release] when hidden, which turns it off again so
 * playback pays nothing for a visualizer nobody is looking at.
 */
object VisualizerAudioBus {

    private val consumers = AtomicInteger(0)

    @Volatile
    var activeTap: VisualizerTapSink? = null
        private set

    val isCapturing: Boolean get() = consumers.get() > 0

    fun acquire() {
        consumers.incrementAndGet()
    }

    fun release() {
        consumers.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun setActiveTap(tap: VisualizerTapSink?) {
        activeTap = tap
    }

    /** Clears [activeTap] only if it is still [tap], so a released engine can't unset a newer one. */
    fun clearActiveTap(tap: VisualizerTapSink?) {
        if (tap != null && activeTap === tap) activeTap = null
    }

    /** Sample rate of what [readWindow] returns, or 0 when no audio is available. */
    val sampleRate: Int get() = activeTap?.ring?.sampleRate ?: 0

    val isPlaying: Boolean get() = activeTap?.isPlaying == true

    /** Audio ending at what is playing now; zero-filled and false when there is none. */
    fun readWindow(out: FloatArray): Boolean {
        val tap = activeTap
        if (tap == null) {
            out.fill(0f)
            return false
        }
        return tap.readWindow(out)
    }
}
