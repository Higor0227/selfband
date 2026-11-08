// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

/** What to export (SPEC 2.7). Video comes in a later milestone. */
data class ExportOptions(
    /** The stereo mix (same graph as playback, metronome excluded) as WAV. */
    val mixWav: Boolean = true,
    /** One WAV per track: dry (as recorded), aligned and trimmed, all the same length. */
    val stems: Boolean = false,
    val wavFormat: WavFormat = WavFormat.PCM_24,
) {
    val isEmpty: Boolean get() = !mixWav && !stems
}

enum class ExportStep { MIX, STEMS }

enum class ExportFailure {
    /** Nothing to export: no recorded audio, or an empty trim. */
    EMPTY_PROJECT,
    /** A take file is missing or unreadable. */
    UNREADABLE_TAKE,
    /** The output could not be written (storage full, MediaStore refused). */
    WRITE_FAILED,
}

/** A saved file, as MediaStore sees it. */
data class ExportOutput(val uri: String, val displayName: String, val mimeType: String)

sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val projectId: String,
        val projectName: String,
        val step: ExportStep,
        /** 1-based stem being rendered while [step] is STEMS. */
        val stemIndex: Int = 0,
        val stemCount: Int = 0,
        /** 0..1 over the whole export. */
        val progress: Float = 0f,
    ) : ExportState

    data class Done(
        val projectId: String,
        val projectName: String,
        /** Where the files went, e.g. "Music/Selfband/My song/". */
        val folder: String,
        val outputs: List<ExportOutput>,
    ) : ExportState

    data class Failed(val projectId: String, val reason: ExportFailure, val detail: String? = null) : ExportState

    data class Cancelled(val projectId: String) : ExportState
}

object ExportNames {
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** A name safe for a file or folder: forbidden characters replaced, trimmed, at most 60 chars. */
    fun sanitize(name: String, fallback: String = "Selfband"): String {
        val cleaned = name.replace(forbidden, "_").trim().trim('.').take(60).trim()
        return cleaned.ifEmpty { fallback }
    }
}
