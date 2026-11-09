// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import io.github.higor0227.selfband.core.export.VideoSpec
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import io.github.higor0227.selfband.core.project.ProjectRepository
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.CanvasScenes
import io.github.higor0227.selfband.core.video.DecoderInstances
import javax.inject.Inject
import javax.inject.Singleton

/** How a project's video export is split into passes ([VideoPasses]). */
internal class PassPlan(
    /** The whole scene (originals; the export never uses proxies). */
    val scene: CanvasScene,
    /** Videos decoded at once at most. */
    val maxDecoders: Int,
    /** Layer ranges per pass, or null if the device cannot decode enough videos at once. */
    val groups: List<IntRange>?,
)

/**
 * Decoders per pass (SPEC 3.5): the user's choice in the settings if any, else the fewest
 * instances declared by the decoders involved — the originals' and, for the intermediates,
 * the output codec's.
 */
@Singleton
internal class VideoPassPlanner @Inject constructor(
    private val repository: ProjectRepository,
    private val settings: AppSettingsRepository,
) {
    /** Reads video files: call off the main thread. [maxDecoders] overrides everything (retries). */
    fun plan(project: Project, spec: VideoSpec, maxDecoders: Int? = null): PassPlan {
        val scene = CanvasScenes.build(
            project,
            { trackId, name -> repository.takeAudioFile(project.id, trackId, name) },
            { name -> repository.projectMediaFile(project.id, name) },
        )
        val max = maxDecoders ?: settings.settings.value.exportDecodersPerPass ?: run {
            val originals = DecoderInstances.limit(scene.decodedLayers.map { it.file })
            val intermediate = DecoderInstances.limitFor(spec.mimeType, spec.width, spec.height)
            listOfNotNull(originals, intermediate).minOrNull() ?: Int.MAX_VALUE
        }
        return PassPlan(scene, max, VideoPasses.plan(scene, max))
    }
}
