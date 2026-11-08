// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import io.github.higor0227.selfband.core.model.CanvasLayout
import io.github.higor0227.selfband.core.model.CanvasSettings
import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.LayoutTemplates
import io.github.higor0227.selfband.core.model.Project
import java.io.File

object CanvasScenes {
    /**
     * What the canvas shows for [project]: one layer per track whose active take has video,
     * placed by the layout in effect. [canvas] overrides the stored canvas (live edits).
     * [file] resolves a take file name inside a track's directory.
     */
    fun build(
        project: Project,
        file: (trackId: String, fileName: String) -> File,
        canvas: CanvasSettings = project.canvas,
        templates: List<LayoutTemplate> = LayoutTemplates.builtIn,
    ): CanvasScene {
        val p = if (canvas == project.canvas) project else project.copy(canvas = canvas)
        val tracks = p.tracks.associateBy { it.id }
        val layers = CanvasLayout.effectiveLayers(p, templates).mapNotNull { layer ->
            val take = tracks[layer.trackId]?.activeTake ?: return@mapNotNull null
            val video = take.video ?: return@mapNotNull null
            SceneLayer(
                file = file(layer.trackId, video.file),
                width = video.width,
                height = video.height,
                rotationDegrees = video.rotationDegrees,
                frameRate = video.frameRate,
                // Without a measured start, assume the video began with the take's audio.
                startFrame = video.startFrame(p.sampleRate) ?: take.startFrame.toDouble(),
                layer = layer,
            )
        }
        return CanvasScene(p.aspectRatio, p.sampleRate, p.canvas.backgroundColor, layers)
    }
}
