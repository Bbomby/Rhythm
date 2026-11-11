/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * How the visualizer listens: which frequencies count as bass, mid and treble, and how the levels are shaped.
 *
 * The analyzer reports each band relative to the track's own recent average, so 1.0 means "typical for this music
 * right now" and hits rise above it. [gain] scales that swing, [attackMs]/[releaseMs] smooth it, [averagingSeconds]
 * sets how quickly "typical" adapts, and [beatSensitivity] sets how easily a bass onset counts as a beat.
 *
 * Built-in profiles are tuned per genre; users can copy and edit them. Stored as JSON, like [CustomEqualizerPreset].
 */
@Immutable
data class VisualizerBandProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bassLowHz: Float,
    val bassHighHz: Float,
    val midLowHz: Float,
    val midHighHz: Float,
    val trebleLowHz: Float,
    val trebleHighHz: Float,
    val gain: Float = 1f,
    val attackMs: Float = 30f,
    val releaseMs: Float = 250f,
    val averagingSeconds: Float = 4f,
    val beatSensitivity: Float = 1f,
    val builtIn: Boolean = false,
) {

    /**
     * A copy with every value in a usable range and each band's low edge below its high edge. JSON written by an
     * older version, or edited by hand, can hold zeros or nulls (Gson bypasses Kotlin defaults).
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun sanitized(): VisualizerBandProfile {
        fun band(low: Float, high: Float, defaultLow: Float, defaultHigh: Float): Pair<Float, Float> {
            val lo = (if (low.isFinite() && low > 0f) low else defaultLow).coerceIn(MIN_HZ, MAX_HZ - 1f)
            val hi = (if (high.isFinite() && high > 0f) high else defaultHigh).coerceIn(MIN_HZ + 1f, MAX_HZ)
            return if (lo < hi) Pair(lo, hi) else Pair(defaultLow, defaultHigh)
        }
        fun value(v: Float, default: Float, min: Float, max: Float) =
            (if (v.isFinite() && v > 0f) v else default).coerceIn(min, max)

        val (bLo, bHi) = band(bassLowHz, bassHighHz, BALANCED.bassLowHz, BALANCED.bassHighHz)
        val (mLo, mHi) = band(midLowHz, midHighHz, BALANCED.midLowHz, BALANCED.midHighHz)
        val (tLo, tHi) = band(trebleLowHz, trebleHighHz, BALANCED.trebleLowHz, BALANCED.trebleHighHz)
        return copy(
            id = if (id == null || id.isBlank()) UUID.randomUUID().toString() else id,
            name = (name ?: "").trim().ifEmpty { "Custom" },
            bassLowHz = bLo, bassHighHz = bHi,
            midLowHz = mLo, midHighHz = mHi,
            trebleLowHz = tLo, trebleHighHz = tHi,
            gain = value(gain, 1f, 0.1f, 4f),
            attackMs = value(attackMs, 30f, 1f, 2000f),
            releaseMs = value(releaseMs, 250f, 10f, 5000f),
            averagingSeconds = value(averagingSeconds, 4f, 0.5f, 30f),
            beatSensitivity = value(beatSensitivity, 1f, 0.25f, 4f),
        )
    }

    companion object {
        const val MIN_HZ = 10f
        const val MAX_HZ = 24_000f

        val BALANCED = VisualizerBandProfile(
            id = "builtin_balanced", name = "Balanced",
            bassLowHz = 20f, bassHighHz = 250f, midLowHz = 250f, midHighHz = 4000f,
            trebleLowHz = 4000f, trebleHighHz = 16000f,
            gain = 1f, attackMs = 30f, releaseMs = 250f, averagingSeconds = 4f, beatSensitivity = 1f, builtIn = true,
        )

        /** Sub-bass and kick carry the drop; fast, punchy response. */
        val EDM = VisualizerBandProfile(
            id = "builtin_edm", name = "EDM / Dubstep",
            bassLowHz = 25f, bassHighHz = 140f, midLowHz = 140f, midHighHz = 3000f,
            trebleLowHz = 3000f, trebleHighHz = 16000f,
            gain = 1.25f, attackMs = 15f, releaseMs = 180f, averagingSeconds = 3f, beatSensitivity = 1.2f, builtIn = true,
        )

        /** 808s and kicks low, vocals across the mids. */
        val HIP_HOP = VisualizerBandProfile(
            id = "builtin_hiphop", name = "Hip-hop / R&B",
            bassLowHz = 30f, bassHighHz = 160f, midLowHz = 160f, midHighHz = 3500f,
            trebleLowHz = 3500f, trebleHighHz = 14000f,
            gain = 1.15f, attackMs = 20f, releaseMs = 220f, averagingSeconds = 3.5f, beatSensitivity = 1.1f, builtIn = true,
        )

        /** Kick and bass guitar low, guitars in a wide mid, cymbals high. */
        val ROCK = VisualizerBandProfile(
            id = "builtin_rock", name = "Rock / Metal",
            bassLowHz = 50f, bassHighHz = 250f, midLowHz = 250f, midHighHz = 3000f,
            trebleLowHz = 3000f, trebleHighHz = 14000f,
            gain = 1.1f, attackMs = 20f, releaseMs = 200f, averagingSeconds = 3f, beatSensitivity = 1f, builtIn = true,
        )

        /** Wide dynamics and few transients: slow smoothing, a long average, beats only on real accents. */
        val CLASSICAL = VisualizerBandProfile(
            id = "builtin_classical", name = "Classical",
            bassLowHz = 30f, bassHighHz = 300f, midLowHz = 300f, midHighHz = 4000f,
            trebleLowHz = 4000f, trebleHighHz = 14000f,
            gain = 0.8f, attackMs = 80f, releaseMs = 600f, averagingSeconds = 8f, beatSensitivity = 0.6f, builtIn = true,
        )

        /** Upright bass low, ride cymbal high, moderate response. */
        val JAZZ = VisualizerBandProfile(
            id = "builtin_jazz", name = "Jazz / Blues",
            bassLowHz = 40f, bassHighHz = 250f, midLowHz = 250f, midHighHz = 3500f,
            trebleLowHz = 3500f, trebleHighHz = 15000f,
            gain = 0.95f, attackMs = 40f, releaseMs = 350f, averagingSeconds = 5f, beatSensitivity = 0.8f, builtIn = true,
        )

        /** Slow drift rather than pulses. */
        val AMBIENT = VisualizerBandProfile(
            id = "builtin_ambient", name = "Ambient / Chill",
            bassLowHz = 20f, bassHighHz = 200f, midLowHz = 200f, midHighHz = 3000f,
            trebleLowHz = 3000f, trebleHighHz = 12000f,
            gain = 0.7f, attackMs = 150f, releaseMs = 1000f, averagingSeconds = 10f, beatSensitivity = 0.4f, builtIn = true,
        )

        val BUILT_IN: List<VisualizerBandProfile> = listOf(BALANCED, EDM, HIP_HOP, ROCK, CLASSICAL, JAZZ, AMBIENT)

        /**
         * Genre keywords per profile, matched as whole words in the normalised tag. Checked in this order, so
         * "ambient electronic" is Ambient and "electronic rock" is Rock.
         */
        private val GENRE_KEYWORDS: List<Pair<VisualizerBandProfile, List<String>>> = listOf(
            AMBIENT to listOf(
                "ambient", "chill", "chillout", "lofi", "lo fi", "new age", "drone", "downtempo", "trip hop",
                "meditation", "sleep", "relaxing",
            ),
            CLASSICAL to listOf(
                "classical", "orchestra", "orchestral", "symphony", "symphonic", "baroque", "opera", "chamber",
                "choral", "romantic era", "soundtrack", "score", "piano solo",
            ),
            JAZZ to listOf("jazz", "blues", "swing", "bebop", "bossa nova", "soul", "funk", "big band"),
            HIP_HOP to listOf(
                "hip hop", "hiphop", "rap", "trap", "r b", "rnb", "grime", "drill", "boom bap", "phonk",
            ),
            ROCK to listOf(
                "rock", "metal", "punk", "grunge", "hardcore", "metalcore", "emo", "shoegaze", "indie rock",
            ),
            EDM to listOf(
                "edm", "electronic", "electronica", "electro", "dubstep", "house", "techno", "trance",
                "drum and bass", "drum n bass", "drum bass", "dnb", "jungle", "garage", "hardstyle", "bass music", "dance",
                "synthwave", "breakbeat", "idm",
            ),
        )

        /** The built-in profile for a genre tag, or null when nothing matches (the caller picks its fallback). */
        fun forGenre(genre: String?): VisualizerBandProfile? {
            if (genre.isNullOrBlank()) return null
            val normalised = " " + genre.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
            return GENRE_KEYWORDS.firstOrNull { (_, words) -> words.any { " $it " in normalised } }?.first
        }

        private val gson = Gson()

        fun toJson(profiles: List<VisualizerBandProfile>): String = gson.toJson(profiles)

        fun fromJson(json: String?): List<VisualizerBandProfile> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val type = object : TypeToken<List<VisualizerBandProfile?>>() {}.type
                gson.fromJson<List<VisualizerBandProfile?>>(json, type)
                    ?.filterNotNull()
                    ?.map { it.sanitized().copy(builtIn = false) }
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
