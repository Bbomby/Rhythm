/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerBandProfileTest {

    @Test
    fun genreTagsPickTheirProfile() {
        val cases = mapOf(
            "Dubstep" to VisualizerBandProfile.EDM,
            "Drum & Bass" to VisualizerBandProfile.EDM,
            "Progressive House" to VisualizerBandProfile.EDM,
            "Hip-Hop/Rap" to VisualizerBandProfile.HIP_HOP,
            "R&B" to VisualizerBandProfile.HIP_HOP,
            "Trap" to VisualizerBandProfile.HIP_HOP,
            "Heavy Metal" to VisualizerBandProfile.ROCK,
            "Electronic Rock" to VisualizerBandProfile.ROCK,
            "Classical; Baroque" to VisualizerBandProfile.CLASSICAL,
            "Original Soundtrack" to VisualizerBandProfile.CLASSICAL,
            "Smooth Jazz" to VisualizerBandProfile.JAZZ,
            "Lo-Fi" to VisualizerBandProfile.AMBIENT,
            "Ambient Electronic" to VisualizerBandProfile.AMBIENT,
        )
        for ((genre, profile) in cases) {
            assertEquals(genre, profile, VisualizerBandProfile.forGenre(genre))
        }
    }

    @Test
    fun unmatchedOrMissingGenresFallBack() {
        assertNull(VisualizerBandProfile.forGenre(null))
        assertNull(VisualizerBandProfile.forGenre("  "))
        assertNull(VisualizerBandProfile.forGenre("Pop"))
        // Whole words only: "grape" must not match "rap", "therapy" must not match "rap" either.
        assertNull(VisualizerBandProfile.forGenre("Grapevine Therapy"))
    }

    @Test
    fun builtInsAreValidAndUnique() {
        val builtIns = VisualizerBandProfile.BUILT_IN
        assertEquals(builtIns.size, builtIns.map { it.id }.toSet().size)
        for (p in builtIns) {
            assertTrue(p.builtIn)
            assertEquals("${p.name} is already sane", p, p.sanitized())
        }
    }

    @Test
    fun jsonRoundTripKeepsUserProfiles() {
        val mine = VisualizerBandProfile.EDM.copy(id = "mine", name = "My drops", gain = 1.6f, builtIn = false)
        val back = VisualizerBandProfile.fromJson(VisualizerBandProfile.toJson(listOf(mine)))
        assertEquals(listOf(mine), back)
    }

    @Test
    fun brokenJsonIsSanitizedNotFatal() {
        assertEquals(emptyList<VisualizerBandProfile>(), VisualizerBandProfile.fromJson("not json"))
        assertEquals(emptyList<VisualizerBandProfile>(), VisualizerBandProfile.fromJson(null))

        val json = """[{"id":"x","bassLowHz":300,"bassHighHz":100,"gain":-2,"builtIn":true}, null]"""
        val profiles = VisualizerBandProfile.fromJson(json)
        assertEquals(1, profiles.size)
        val p = profiles.single()
        assertEquals("Custom", p.name)
        // Inverted bass band falls back to the default band; missing values get defaults.
        assertEquals(VisualizerBandProfile.BALANCED.bassLowHz, p.bassLowHz)
        assertEquals(VisualizerBandProfile.BALANCED.bassHighHz, p.bassHighHz)
        assertTrue(p.midLowHz < p.midHighHz && p.trebleLowHz < p.trebleHighHz)
        assertEquals(1f, p.gain)
        assertTrue(p.attackMs > 0f && p.releaseMs > 0f && p.averagingSeconds > 0f && p.beatSensitivity > 0f)
        assertFalse("stored profiles are never built-in", p.builtIn)
    }

    @Test
    fun valuesAreClamped() {
        val p = VisualizerBandProfile.BALANCED.copy(gain = 50f, trebleHighHz = 90_000f, attackMs = 1e9f).sanitized()
        assertEquals(4f, p.gain)
        assertEquals(VisualizerBandProfile.MAX_HZ, p.trebleHighHz)
        assertEquals(2000f, p.attackMs)
    }
}
