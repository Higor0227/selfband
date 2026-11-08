// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixingTest {
    private fun track(id: String, muted: Boolean = false, solo: Boolean = false, db: Double = 0.0, pan: Float = 0f) =
        Track(id = id, name = id, muted = muted, solo = solo, volumeDb = db, pan = pan)

    @Test
    fun centeredPanIsUnityAndSidesOnlyAttenuate() {
        assertEquals(StereoGain(1f, 1f), Mixing.pan(0f))
        assertEquals(StereoGain(1f, 0f), Mixing.pan(-1f))
        assertEquals(StereoGain(0f, 1f), Mixing.pan(1f))
        val half = Mixing.pan(0.5f)
        assertEquals(0.5f, half.left, 1e-6f)
        assertEquals(1f, half.right, 1e-6f)
        // Out of range is clamped; never above unity.
        assertEquals(StereoGain(0f, 1f), Mixing.pan(3f))
        for (i in -10..10) {
            val g = Mixing.pan(i / 10f)
            assertTrue(g.left <= 1f && g.right <= 1f)
        }
    }

    @Test
    fun volumeAndPanCombine() {
        val g = Mixing.trackGain(track("a", db = -6.0206, pan = -0.5f), anySolo = false)
        assertEquals(0.5f, g.left, 1e-4f)
        assertEquals(0.25f, g.right, 1e-4f)
        assertEquals(StereoGain.SILENT, Mixing.trackGain(track("a", db = Levels.MIN_DB), anySolo = false))
    }

    @Test
    fun soloSilencesTheOthersAndMuteWins() {
        val p = Project(
            id = "p", name = "p", createdAtMillis = 0, updatedAtMillis = 0, sampleRate = 48_000,
            tracks = listOf(track("a", solo = true), track("b"), track("c", solo = true, muted = true)),
        )
        val gains = p.tracks.map { Mixing.trackGain(p, it) }
        assertEquals(StereoGain(1f, 1f), gains[0])
        assertEquals(StereoGain.SILENT, gains[1])
        assertEquals(StereoGain.SILENT, gains[2])
        // Without any solo everything unmuted plays.
        val noSolo = p.copy(tracks = p.tracks.map { it.copy(solo = false, muted = false) })
        assertTrue(noSolo.tracks.all { Mixing.trackGain(noSolo, it) == StereoGain(1f, 1f) })
    }

    @Test
    fun masterGainAndDefaults() {
        assertEquals(1f, Mixing.masterGain(MixerSettings()), 1e-6f)
        assertEquals(0f, Mixing.masterGain(MixerSettings(masterVolumeDb = Mixing.MIN_MASTER_DB)), 0f)
        assertTrue(MixerSettings().limiter)
        // Projects saved before the mixer read with neutral settings.
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"id":"t","name":"#1","muted":false,"volumeDb":-3.0}"""
        val t = json.decodeFromString(Track.serializer(), old)
        assertEquals(0f, t.pan, 0f)
        assertEquals(false, t.solo)
    }
}
