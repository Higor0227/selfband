// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sample() = Project(
        id = "p1", name = "Song", createdAtMillis = 1, updatedAtMillis = 2, sampleRate = 48_000,
        tracks = listOf(
            Track(
                id = "tr1", name = "#1", activeTakeId = "tk1",
                takes = listOf(
                    Take(
                        id = "tk1", audioFile = "tk1.wav", channelCount = 2, lengthFrames = 96_000,
                        recordStartFrame = 0, autoOffsetFrames = -640,
                        alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 640.0, xrunsDuringTake = 1),
                        createdAtMillis = 3,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun roundTrip() {
        val project = sample()
        val decoded = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), project))
        assertEquals(project, decoded)
        assertEquals(96_000 - 640L, decoded.lengthFrames)
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val text = json.encodeToString(Project.serializer(), sample())
            .replaceFirst("{", "{\"futureField\":{\"x\":1},")
        assertEquals(sample(), json.decodeFromString(Project.serializer(), text))
    }

    @Test
    fun activeTakeFallsBackToLatest() {
        val track = sample().tracks.first().copy(activeTakeId = "missing")
        assertEquals("tk1", track.activeTake?.id)
    }

    @Test
    fun cameraSettingsRoundTrip() {
        val camera = CameraSettings(
            lens = CameraLens.BACK, resolution = VideoResolution.P2160, frameRate = 60,
            stabilization = true, grid = true, ghostTrackId = "tr1", ghostOpacity = 0.3f,
        ).withLocks(CameraLens.BACK) {
            it.copy(
                exposure = ExposureLock(16_666_666, 200),
                whiteBalance = WhiteBalanceLock(listOf(2.0f, 1.0f, 1.0f, 1.6f), List(18) { i -> if (i % 2 == 1) 128 else i }),
                focus = FocusLock(3.5f),
            )
        }
        val project = sample().copy(camera = camera)
        val decoded = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), project))
        assertEquals(camera, decoded.camera)
        assertEquals(CameraLocks(), decoded.camera.locksFor(CameraLens.FRONT))
    }

    @Test
    fun projectsWithoutCameraSettingsGetDefaults() {
        // A project.json written before phase 2b has no "camera" object.
        val full = json.parseToJsonElement(json.encodeToString(Project.serializer(), sample())).jsonObject
        val old = kotlinx.serialization.json.JsonObject(full - "camera")
        val decoded = json.decodeFromJsonElement(Project.serializer(), old)
        assertEquals(CameraSettings(), decoded.camera)
    }
}
