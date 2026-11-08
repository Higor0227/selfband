// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** Canvas width / height. */
val AspectRatio.value: Float
    get() = when (this) {
        AspectRatio.PORTRAIT_9_16 -> 9f / 16f
        AspectRatio.LANDSCAPE_16_9 -> 16f / 9f
        AspectRatio.SQUARE_1_1 -> 1f
    }

/** Rectangle in normalized coordinates: (0, 0) = top left, (1, 1) = bottom right. */
@Serializable
data class NormRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Placement of one track's video on the canvas (SPEC 2.6). Coordinates are normalized so
 * they survive a format change (SPEC 3.5).
 */
@Serializable
data class Layer(
    val trackId: String,
    /** Where the layer goes, normalized to the canvas. */
    val frame: NormRect,
    /** Visible part of the video, normalized to the displayed (rotated) video. */
    val crop: NormRect = NormRect.FULL,
    /** Higher draws on top. */
    val z: Int = 0,
    val opacity: Float = 1f,
    /** Corner radius as a fraction of the layer's shorter side (0..0.5). */
    val cornerRadius: Float = 0f,
    /** Border thickness as a fraction of the canvas's shorter side. */
    val borderWidth: Float = 0f,
    /** ARGB. */
    val borderColor: Long = 0xFFFFFFFF,
    val visible: Boolean = true,
)

/** Canvas of a project: background and layers (SPEC 2.6, 3.6). */
@Serializable
data class CanvasSettings(
    /** Solid background color, ARGB. */
    val backgroundColor: Long = 0xFF000000,
    /** Placements chosen by the user; tracks without one get an automatic grid cell. */
    val layers: List<Layer> = emptyList(),
)
