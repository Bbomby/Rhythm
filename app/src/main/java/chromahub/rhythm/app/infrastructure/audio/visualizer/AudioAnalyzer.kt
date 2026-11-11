/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.audio.visualizer

import chromahub.rhythm.app.shared.data.model.VisualizerBandProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the renderer gets each frame. One instance is reused; read it on the thread that called [AudioAnalyzer.process].
 */
class AudioFeatures(spectrumBands: Int, waveformPoints: Int) {
    /** Band levels relative to the track's recent average: ~1.0 is typical, hits go above. 0 in silence. */
    var bass = 0f
    var mid = 0f
    var treb = 0f

    /** The same, smoothed with the profile's attack and release. */
    var bassAtt = 0f
    var midAtt = 0f
    var trebAtt = 0f

    /** True on the frame a beat (bass onset) is detected. */
    var beat = false

    /** 1.0 on a beat, decaying towards 0. */
    var beatPulse = 0f

    /** RMS of the analysis window, 0..1 (full scale). */
    var volume = 0f

    /** False when there is no audio to analyse (nothing playing, or the visualizer just started). */
    var hasAudio = false

    /** Log-spaced bands, 0..1 (dB-mapped), smoothed on the way down. */
    val spectrum = FloatArray(spectrumBands)

    /** Recent waveform, auto-scaled to roughly -1..1. */
    val waveform = FloatArray(waveformPoints)
}

/**
 * Turns a window of mono samples into [AudioFeatures], shaped by a [VisualizerBandProfile]. Pure Kotlin; the
 * renderer calls [process] once per frame with the window [VisualizerAudioBus.readWindow] returns.
 */
class AudioAnalyzer(
    val fftSize: Int = 2048,
    spectrumBands: Int = 64,
    waveformPoints: Int = 256,
    profile: VisualizerBandProfile = VisualizerBandProfile.BALANCED,
) {
    companion object {
        /** About -80 dBFS: below this a band counts as silent. */
        private const val SILENCE_AMPLITUDE = 1e-4f
        private const val MAX_LEVEL = 4f
        private const val MIN_BEAT_INTERVAL_S = 0.22f
        private const val BEAT_PULSE_DECAY_S = 0.15f
        private const val SPECTRUM_FLOOR_DB = -72f
        private const val SPECTRUM_LOW_HZ = 30f
        private const val SPECTRUM_HIGH_HZ = 16_000f
        private const val WAVE_PEAK_RELEASE_S = 1.5f
        private const val WAVE_MIN_PEAK = 0.05f

        private fun smoothing(dt: Float, tauSeconds: Float): Float =
            if (tauSeconds <= 0f) 1f else 1f - exp(-dt / tauSeconds)
    }

    var profile: VisualizerBandProfile = profile.sanitized()
        set(value) {
            field = value.sanitized()
        }

    val features = AudioFeatures(spectrumBands, waveformPoints)

    private val fft = RealFft(fftSize)
    private val hann = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val amplitudeScale = 2f / hann.sum()
    private val windowed = FloatArray(fftSize)
    private val power = FloatArray(fft.binCount)

    private val average = FloatArray(3)
    private var averageReady = false
    private var aboveBeatThreshold = false
    private var sinceBeat = Float.MAX_VALUE
    private var wavePeak = WAVE_MIN_PEAK

    /** Resets the adaptive state (call on a track change or seek so the old average doesn't linger). */
    fun reset() {
        average.fill(0f)
        averageReady = false
        aboveBeatThreshold = false
        sinceBeat = Float.MAX_VALUE
        wavePeak = WAVE_MIN_PEAK
    }

    /**
     * @param samples at least [fftSize] mono samples, oldest first; the last [fftSize] are analysed.
     * @param sampleRate rate of [samples].
     * @param dtSeconds time since the previous call (smoothing is frame-rate independent).
     * @param hasAudio false when [samples] is filler; levels then fall away with the release time.
     */
    fun process(samples: FloatArray, sampleRate: Int, dtSeconds: Float, hasAudio: Boolean): AudioFeatures {
        val f = features
        val dt = dtSeconds.coerceIn(0f, 0.25f)
        val p = profile
        f.beat = false
        sinceBeat += dt
        f.beatPulse *= exp(-dt / BEAT_PULSE_DECAY_S)

        if (!hasAudio || sampleRate <= 0 || samples.size < fftSize) {
            f.hasAudio = false
            f.bass = 0f; f.mid = 0f; f.treb = 0f
            decayAttack(dt)
            val release = smoothing(dt, p.releaseMs / 1000f)
            for (i in f.spectrum.indices) f.spectrum[i] -= f.spectrum[i] * release
            f.waveform.fill(0f)
            f.volume = 0f
            return f
        }
        f.hasAudio = true

        val offset = samples.size - fftSize
        var sumSquares = 0f
        for (i in 0 until fftSize) {
            val s = samples[offset + i]
            sumSquares += s * s
            windowed[i] = s * hann[i]
        }
        f.volume = sqrt(sumSquares / fftSize).coerceIn(0f, 1f)
        fft.powerSpectrum(windowed, power)

        val binHz = sampleRate.toFloat() / fftSize
        val raw0 = bandAmplitude(p.bassLowHz, p.bassHighHz, binHz)
        val raw1 = bandAmplitude(p.midLowHz, p.midHighHz, binHz)
        val raw2 = bandAmplitude(p.trebleLowHz, p.trebleHighHz, binHz)

        if (!averageReady && max(raw0, max(raw1, raw2)) > SILENCE_AMPLITUDE) {
            average[0] = raw0; average[1] = raw1; average[2] = raw2
            averageReady = true
        }
        val avgK = smoothing(dt, p.averagingSeconds)
        f.bass = level(raw0, 0, avgK, p.gain)
        f.mid = level(raw1, 1, avgK, p.gain)
        f.treb = level(raw2, 2, avgK, p.gain)
        decayAttack(dt)

        detectBeat(f.bass, p.beatSensitivity)
        computeSpectrum(binHz, smoothing(dt, p.releaseMs / 1000f))
        computeWaveform(samples, dt)
        return f
    }

    private fun bandAmplitude(lowHz: Float, highHz: Float, binHz: Float): Float {
        val first = max(1, (lowHz / binHz).toInt())
        val last = min(power.size - 1, (highHz / binHz).toInt())
        if (last < first) return 0f
        var sum = 0f
        for (k in first..last) sum += power[k]
        return sqrt(sum) * amplitudeScale
    }

    private fun level(raw: Float, band: Int, avgK: Float, gain: Float): Float {
        if (!averageReady) return 0f
        average[band] += (raw - average[band]) * avgK
        val relative = raw / max(average[band], SILENCE_AMPLITUDE)
        return (1f + (relative - 1f) * gain).coerceIn(0f, MAX_LEVEL)
    }

    private fun decayAttack(dt: Float) {
        val p = profile
        val attack = smoothing(dt, p.attackMs / 1000f)
        val release = smoothing(dt, p.releaseMs / 1000f)
        val f = features
        f.bassAtt += (f.bass - f.bassAtt) * (if (f.bass > f.bassAtt) attack else release)
        f.midAtt += (f.mid - f.midAtt) * (if (f.mid > f.midAtt) attack else release)
        f.trebAtt += (f.treb - f.trebAtt) * (if (f.treb > f.trebAtt) attack else release)
    }

    /** A beat is the bass crossing a threshold on the way up, at most one per [MIN_BEAT_INTERVAL_S]. */
    private fun detectBeat(bass: Float, sensitivity: Float) {
        val threshold = 1f + 0.4f / sensitivity
        if (bass >= threshold) {
            if (!aboveBeatThreshold && sinceBeat >= MIN_BEAT_INTERVAL_S) {
                features.beat = true
                features.beatPulse = 1f
                sinceBeat = 0f
            }
            aboveBeatThreshold = true
        } else if (bass < threshold * 0.9f) {
            aboveBeatThreshold = false
        }
    }

    private fun computeSpectrum(binHz: Float, release: Float) {
        val bands = features.spectrum
        val nyquist = binHz * (power.size - 1)
        val high = min(SPECTRUM_HIGH_HZ, nyquist)
        val ratio = (high / SPECTRUM_LOW_HZ).toDouble()
        for (i in bands.indices) {
            val lo = (SPECTRUM_LOW_HZ * ratio.pow(i.toDouble() / bands.size)).toFloat()
            val hi = (SPECTRUM_LOW_HZ * ratio.pow((i + 1).toDouble() / bands.size)).toFloat()
            val first = max(1, (lo / binHz).toInt())
            val last = max(first, min(power.size - 1, (hi / binHz).toInt()))
            var peak = 0f
            for (k in first..last) peak = max(peak, power[k])
            val amplitude = sqrt(peak) * amplitudeScale
            val db = if (amplitude > 0f) 20f * log10(amplitude) else SPECTRUM_FLOOR_DB
            val target = ((db - SPECTRUM_FLOOR_DB) / -SPECTRUM_FLOOR_DB).coerceIn(0f, 1f)
            bands[i] = if (target > bands[i]) target else bands[i] + (target - bands[i]) * release
        }
    }

    private fun computeWaveform(samples: FloatArray, dt: Float) {
        val wave = features.waveform
        val span = min(samples.size, wave.size * 4)
        val start = samples.size - span
        val stride = span.toFloat() / wave.size
        var peak = 0f
        for (i in wave.indices) {
            val s = samples[start + (i * stride).toInt()]
            wave[i] = s
            peak = max(peak, abs(s))
        }
        wavePeak = max(peak, wavePeak * exp(-dt / WAVE_PEAK_RELEASE_S)).coerceAtLeast(WAVE_MIN_PEAK)
        val scale = 1f / wavePeak
        for (i in wave.indices) wave[i] = (wave[i] * scale).coerceIn(-1f, 1f)
    }
}
