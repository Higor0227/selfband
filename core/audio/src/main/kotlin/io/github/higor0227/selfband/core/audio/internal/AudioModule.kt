// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.AudioProbe
import io.github.higor0227.selfband.core.audio.Waveforms

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AudioModule {
    @Binds
    abstract fun bindDeviceMonitor(impl: AndroidAudioDeviceMonitor): AudioDeviceMonitor

    @Binds
    abstract fun bindAudioProbe(impl: NativeAudioProbe): AudioProbe

    @Binds
    abstract fun bindAudioEngine(impl: NativeAudioEngine): AudioEngine

    @Binds
    abstract fun bindWaveforms(impl: FileWaveforms): Waveforms
}
