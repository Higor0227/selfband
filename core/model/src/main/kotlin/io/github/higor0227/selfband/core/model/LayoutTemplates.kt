// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A canvas layout template (SPEC 2.6): data, not code. It only places the layers; everything
 * stays editable afterwards. [slots] maps an [AspectRatio] name, or "default", to the slots
 * for that format, filled by the video tracks in track order.
 *
 * Templates saved by the user also have a [name] and the look of each slot ([styles], same
 * order as the "default" slots).
 */
@Serializable
data class LayoutTemplate(
    val id: String,
    val slots: Map<String, List<NormRect>>,
    val name: String? = null,
    val styles: List<SlotStyle>? = null,
) {
    fun slotsFor(aspectRatio: AspectRatio): List<NormRect> =
        slots[aspectRatio.name] ?: slots[DEFAULT].orEmpty()

    companion object {
        const val DEFAULT = "default"
    }
}

/** Look of one slot of a saved template: everything a layer has except its track, frame and crop. */
@Serializable
data class SlotStyle(
    val z: Int = 0,
    val opacity: Float = 1f,
    val cornerRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Long = 0xFFFFFFFF,
) {
    fun applyTo(layer: Layer): Layer = layer.copy(
        z = z,
        opacity = opacity,
        cornerRadius = cornerRadius,
        borderWidth = borderWidth,
        borderColor = borderColor,
    )

    companion object {
        fun of(layer: Layer) = SlotStyle(layer.z, layer.opacity, layer.cornerRadius, layer.borderWidth, layer.borderColor)
    }
}

@Serializable
private data class TemplateFile(val version: Int = 1, val templates: List<LayoutTemplate>)

object LayoutTemplates {
    /** Template id of the automatic grid, which adapts to the number of tracks and their shape. */
    const val AUTO = "auto"

    private const val RESOURCE = "/io/github/higor0227/selfband/core/model/layout-templates.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Built-in templates (1, 2, 3, 4, 6 and 9 slots), from the bundled JSON. */
    val builtIn: List<LayoutTemplate> by lazy {
        val text = LayoutTemplates::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing $RESOURCE")
        parse(text)
    }

    fun parse(text: String): List<LayoutTemplate> = json.decodeFromString<TemplateFile>(text).templates

    /** The same file format as the built-in templates, used for the user's saved ones. */
    fun encode(templates: List<LayoutTemplate>): String =
        prettyJson.encodeToString(TemplateFile.serializer(), TemplateFile(templates = templates))

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = false }

    fun find(id: String?): LayoutTemplate? = builtIn.firstOrNull { it.id == id }

    /** Longest name of a saved template. */
    const val MAX_NAME_LENGTH = 40

    /**
     * The layout [project] shows now, as a template: one slot per visible video layer, in track
     * order, with each layer's look. Null when no layer is visible (nothing to save).
     */
    fun fromProject(id: String, name: String, project: Project, templates: List<LayoutTemplate> = builtIn): LayoutTemplate? {
        val layers = CanvasLayout.effectiveLayers(project, templates).associateBy { it.trackId }
        val visible = project.tracks.mapNotNull { layers[it.id] }.filter { it.visible }
        if (visible.isEmpty()) return null
        return LayoutTemplate(
            id = id,
            slots = mapOf(LayoutTemplate.DEFAULT to visible.map { it.frame }),
            name = name.trim().take(MAX_NAME_LENGTH),
            styles = visible.map(SlotStyle::of),
        )
    }
}
