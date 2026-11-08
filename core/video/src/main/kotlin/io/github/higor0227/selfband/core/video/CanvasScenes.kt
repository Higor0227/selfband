// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import io.github.higor0227.selfband.core.model.BackgroundKind
import io.github.higor0227.selfband.core.model.CanvasLayout
import io.github.higor0227.selfband.core.model.CanvasSettings
import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.LayoutTemplates
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.model.displayAspect
import io.github.higor0227.selfband.core.model.value
import java.io.File

object CanvasScenes {
    /**
     * What the canvas shows for [project]: one layer per track whose active take has video,
     * placed by the layout in effect. [canvas] overrides the stored canvas (live edits).
     * [file] resolves a take file name inside a track's directory, [projectFile] a file name
     * inside the project directory (the background image).
     */
    fun build(
        project: Project,
        file: (trackId: String, fileName: String) -> File,
        projectFile: (fileName: String) -> File,
        canvas: CanvasSettings = project.canvas,
        templates: List<LayoutTemplate> = LayoutTemplates.builtIn,
    ): CanvasScene {
        val p = if (canvas == project.canvas) project else project.copy(canvas = canvas)
        val tracks = p.tracks.associateBy { it.id }
        val layers = CanvasLayout.effectiveLayers(p, templates).mapNotNull { layer ->
            tracks[layer.trackId]?.let { sceneLayer(p, it, layer, file) }
        }
        val blur = CanvasLayout.blurSource(p)?.let { track ->
            val video = track.activeTake?.video ?: return@let null
            val cover = CanvasLayout.coverCrop(video.displayAspect, p.aspectRatio.value)
            sceneLayer(p, track, Layer(trackId = track.id, frame = NormRect.FULL, crop = cover), file)
        }
        val image = p.canvas.backgroundImage
            ?.takeIf { p.canvas.background == BackgroundKind.IMAGE }
            ?.let(projectFile)
        return CanvasScene(p.aspectRatio, p.sampleRate, p.canvas.backgroundColor, layers, blur, image)
    }

    private fun sceneLayer(p: Project, track: Track, layer: Layer, file: (String, String) -> File): SceneLayer? {
        val take = track.activeTake ?: return null
        val video = take.video ?: return null
        return SceneLayer(
            file = file(track.id, video.file),
            width = video.width,
            height = video.height,
            rotationDegrees = video.rotationDegrees,
            frameRate = video.frameRate,
            // Without a measured start, assume the video began with the take's audio.
            startFrame = video.startFrame(p.sampleRate) ?: take.startFrame.toDouble(),
            layer = layer,
        )
    }
}
