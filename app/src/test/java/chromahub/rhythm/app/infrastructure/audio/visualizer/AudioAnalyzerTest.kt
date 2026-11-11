/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import chromahub.rhythm.app.shared.data.model.VisualizerBandProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class AudioAnalyzerTest {

    private val sampleRate = 48_000

    private fun tone(hz: Double, amplitude: Double): (Double) -> Double = { t -> amplitude * sin(2 * PI * hz * t) }

    /** Decaying 55 Hz hits every [period] seconds, like a kick drum at 60/period BPM. */
    private fun kicks(amplitude: Double, period: Double = 0.5): (Double) -> Double = { t ->
        if (t < 0) 0.0 else amplitude * exp(-(t % period) / 0.08) * sin(2 * PI * 55 * t)
    }

    private operator fun ((Double) -> Double).plus(other: (Double) -> Double): (Double) -> Double =
        { t -> this(t) + other(t) }

    /**
     * Feeds [analyzer] the window ending at each frame time, as the renderer would. Signals are evaluated before t = 0
     * too, so a steady signal is already playing when the visualizer starts rather than fading in.
     */
    private fun run(
        analyzer: AudioAnalyzer,
        from: Double,
        to: Double,
        fps: Int,
        signal: (Double) -> Double,
        onFrame: (Double, AudioFeatures) -> Unit = { _, _ -> },
    ) {
        val window = FloatArray(analyzer.fftSize)
        val first = (from * fps).toInt() + 1
        val last = (to * fps).toInt()
        for (frame in first..last) {
            val t = frame.toDouble() / fps
            val end = (t * sampleRate).toLong()
            for (j in window.indices) {
                val n = end - window.size + j
                window[j] = signal(n.toDouble() / sampleRate).toFloat()
            }
            onFrame(t, analyzer.process(window, sampleRate, 1f / fps, hasAudio = true))
        }
    }

    private fun countBeats(profile: VisualizerBandProfile, seconds: Double, signal: (Double) -> Double): Int {
        val analyzer = AudioAnalyzer(profile = profile)
        var beats = 0
        run(analyzer, 0.0, seconds, 60, signal) { _, f -> if (f.beat) beats++ }
        return beats
    }

    private val background = tone(1000.0, 0.1) + tone(6000.0, 0.05)

    @Test
    fun noAudioGivesQuietFiniteLevels() {
        val f = AudioAnalyzer().process(FloatArray(2048), sampleRate, 1f / 60, hasAudio = false)
        assertFalse(f.hasAudio)
        assertFalse(f.beat)
        for (v in listOf(f.bass, f.mid, f.treb, f.bassAtt, f.volume)) {
            assertTrue(v.isFinite())
            assertEquals(0f, v, 0f)
        }
    }

    @Test
    fun digitalSilenceDoesNotProduceNaNs() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 1.0, 60, { 0.0 }) { _, f ->
            assertTrue(f.bass.isFinite() && f.mid.isFinite() && f.treb.isFinite())
            assertTrue(f.spectrum.all { it.isFinite() } && f.waveform.all { it.isFinite() })
        }
    }

    @Test
    fun steadyMusicSitsNearOne() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 3.0, 60, background + tone(80.0, 0.3))
        val f = analyzer.features
        assertEquals(1f, f.bass, 0.1f)
        assertEquals(1f, f.mid, 0.1f)
        assertEquals(1f, f.treb, 0.1f)
    }

    @Test
    fun aBassHitRaisesBassButNotTreble() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 3.0, 60, background)
        val withBass = background + { t: Double -> if (t >= 3.0) 0.5 * sin(2 * PI * 60 * t) else 0.0 }
        run(analyzer, 3.0, 3.1, 60, withBass)
        val f = analyzer.features
        assertTrue("bass ${f.bass}", f.bass > 2f)
        assertEquals(1f, f.treb, 0.2f)
    }

    @Test
    fun detectsEveryKickAt120Bpm() {
        val beats = countBeats(VisualizerBandProfile.BALANCED, 10.0, background + kicks(0.6))
        // 20 kicks; the very first can seed the running average instead of counting as a beat.
        assertTrue("beats $beats", beats in 19..20)
    }

    @Test
    fun aSteadyBassToneIsNotABeat() {
        assertEquals(0, countBeats(VisualizerBandProfile.BALANCED, 5.0, background + tone(55.0, 0.5)))
    }

    @Test
    fun aHeldBassNoteIsOneBeatNotARepeatingOne() {
        // A drop that stays loud: bass sits above the threshold for seconds while the average catches up.
        // It must fire once on the way up, not every MIN_BEAT_INTERVAL while it stays high.
        val drop = background + { t: Double -> if (t >= 3.0) 0.5 * sin(2 * PI * 55 * t) else 0.0 }
        val analyzer = AudioAnalyzer()
        var beats = 0
        run(analyzer, 0.0, 5.0, 60, drop) { t, f ->
            if (f.beat) beats++
            if (t > 3.5 && t < 4.0) assertTrue("still above threshold at $t: ${f.bass}", f.bass > 1.4f)
        }
        assertEquals(1, beats)
    }

    @Test
    fun genreProfilesChangeWhatCountsAsABeat() {
        // Mild accents over a sustained bass line: punchy for EDM, not worth a beat for Classical.
        val mild = background + tone(55.0, 0.3) + kicks(0.25)
        val edm = countBeats(VisualizerBandProfile.EDM, 10.0, mild)
        val classical = countBeats(VisualizerBandProfile.CLASSICAL, 10.0, mild)
        assertTrue("edm $edm", edm >= 15)
        assertEquals(0, classical)
    }

    @Test
    fun spectrumPeaksAtTheTonesBand() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 0.5, 60, tone(1000.0, 0.5))
        val spectrum = analyzer.features.spectrum
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        val ratio = 16_000.0 / 30.0
        val lo = 30.0 * ratio.pow(peak.toDouble() / spectrum.size)
        val hi = 30.0 * ratio.pow((peak + 1.0) / spectrum.size)
        assertTrue("band $peak spans $lo..$hi Hz", 1000.0 in (lo / 1.1)..(hi * 1.1))
        assertTrue(spectrum.all { it in 0f..1f })
    }

    @Test
    fun waveformIsAutoScaled() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 1.0, 60, tone(200.0, 0.1))
        val wave = analyzer.features.waveform
        assertTrue(wave.all { it in -1f..1f })
        assertTrue("quiet input still fills the range", wave.maxOf { abs(it) } > 0.9f)
    }

    @Test
    fun smoothingDoesNotDependOnFrameRate() {
        val signal = background + { t: Double -> if (t < 2.0) 0.4 * sin(2 * PI * 70 * t) else 0.0 }
        fun bassAttAt(fps: Int): Float {
            val analyzer = AudioAnalyzer()
            run(analyzer, 0.0, 2.2, fps, signal)
            return analyzer.features.bassAtt
        }
        assertEquals(bassAttAt(30), bassAttAt(120), 0.08f)
    }

    @Test
    fun resetForgetsTheRunningAverage() {
        val analyzer = AudioAnalyzer()
        run(analyzer, 0.0, 2.0, 60, background + tone(60.0, 0.5))
        analyzer.reset()
        // After a reset the first loud frame seeds the average again, so it reads as typical, not as a hit.
        run(analyzer, 2.0, 2.0 + 1.0 / 60, 60, background + tone(60.0, 0.05))
        assertEquals(1f, analyzer.features.bass, 0.05f)
    }
}
