// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A canvas layout template (SPEC 2.6): data, not code. It only places the layers; everything
 * stays editable afterwards. [slots] maps an [AspectRatio] name, or "default", to the slots
 * for that format, filled by the video tracks in track order.
 */
@Serializable
data class LayoutTemplate(
    val id: String,
    val slots: Map<String, List<NormRect>>,
) {
    fun slotsFor(aspectRatio: AspectRatio): List<NormRect> =
        slots[aspectRatio.name] ?: slots[DEFAULT].orEmpty()

    companion object {
        const val DEFAULT = "default"
    }
}

@Serializable
private data class TemplateFile(val version: Int, val templates: List<LayoutTemplate>)

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

    fun find(id: String?): LayoutTemplate? = builtIn.firstOrNull { it.id == id }
}
