// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
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
}
