// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.higor0227.selfband.core.audio.EngineStatus

/**
 * The engine status as it changes (~20 times a second: position, meters, limiter). The screen
 * state ([RecorderUiState.status]) leaves these values out, so they are read only by the
 * small pieces that show them, preferably while drawing (7b: the whole screen used to
 * recompose on every update).
 */
internal val LocalLiveStatus = staticCompositionLocalOf<State<EngineStatus>> { mutableStateOf(EngineStatus()) }

/** [EngineStatus] without the values that change all the time; equal while nothing else changes. */
internal fun EngineStatus.withoutLiveValues(): EngineStatus = copy(
    positionFrames = 0,
    peakLeft = 0f,
    peakRight = 0f,
    recordedFrames = 0,
    trackPeaks = emptyList(),
    masterPeakLeft = 0f,
    masterPeakRight = 0f,
    limiterGain = 1f,
    presentationClock = null,
    ioOffsetFrames = null,
    ioOffsetSpreadFrames = 0.0,
)
