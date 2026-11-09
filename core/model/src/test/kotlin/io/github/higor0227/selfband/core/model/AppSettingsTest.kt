// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    private fun project() = Project(
        id = "p",
        name = "Song",
        createdAtMillis = 0,
        updatedAtMillis = 0,
        sampleRate = 48_000,
        camera = CameraSettings(grid = true, ghostOpacity = 0.6f),
    )

    @Test
    fun `defaults are applied to a new project`() {
        val settings = AppSettings(
            aspectRatio = AspectRatio.LANDSCAPE_16_9,
            metronome = MetronomeSettings(enabled = true, bpm = 90.0, beatsPerBar = 6, beatUnit = 8, countInBars = 2),
            camera = CameraDefaults(CameraLens.BACK, VideoResolution.P2160, 60, stabilization = true),
        )
        val p = settings.applyTo(project())
        assertEquals(AspectRatio.LANDSCAPE_16_9, p.aspectRatio)
        assertEquals(settings.metronome, p.metronome)
        assertEquals(CameraLens.BACK, p.camera.lens)
        assertEquals(VideoResolution.P2160, p.camera.resolution)
        assertEquals(60, p.camera.frameRate)
        assertTrue(p.camera.stabilization)
        // Camera fields that are not defaults are left alone.
        assertTrue(p.camera.grid)
        assertEquals(0.6f, p.camera.ghostOpacity)
        assertEquals("Song", p.name)
    }

    @Test
    fun `out of range metronome defaults are clamped`() {
        val p = AppSettings(metronome = MetronomeSettings(bpm = 1000.0, countInBars = 9)).applyTo(project())
        assertEquals(MetronomeSettings.MAX_BPM, p.metronome.bpm, 0.0)
        assertEquals(MetronomeSettings.MAX_COUNT_IN_BARS, p.metronome.countInBars)
    }

    @Test
    fun `default settings leave a new project as it was`() {
        val p = project()
        assertEquals(p, AppSettings().applyTo(p))
    }

    @Test
    fun `export folder names are made safe`() {
        assertEquals("Selfband", ExportFolder.sanitize(""))
        assertEquals("Selfband", ExportFolder.sanitize("   "))
        assertEquals("Selfband", ExportFolder.sanitize(".."))
        assertEquals("Minha Banda", ExportFolder.sanitize("  Minha Banda  "))
        assertEquals("a_b_c", ExportFolder.sanitize("a/b\\c"))
        assertEquals("x_y", ExportFolder.sanitize("x:y"))
        assertEquals("Ensaio", ExportFolder.sanitize("Ensaio."))
        assertEquals(ExportFolder.MAX_LENGTH, ExportFolder.sanitize("a".repeat(100)).length)
        val sanitized = ExportFolder.sanitize("../../etc")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.startsWith("."))
    }

    @Test
    fun `old settings files read with the new defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AppSettings.serializer(), """{"schemaVersion":1,"proxyMode":"OFF"}""")
        assertEquals(ProxyMode.OFF, old.proxyMode)
        assertEquals(ThemeMode.SYSTEM, old.theme)
        assertEquals(ExportFolder.DEFAULT, old.exportFolder)
        assertEquals(ExportDefaults(), old.export)
        val round = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), AppSettings(theme = ThemeMode.DARK, verboseLog = true)),
        )
        assertEquals(ThemeMode.DARK, round.theme)
        assertTrue(round.verboseLog)
    }
}
