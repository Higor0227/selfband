// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioProbe

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AudioModule {
    @Binds
    abstract fun bindDeviceMonitor(impl: AndroidAudioDeviceMonitor): AudioDeviceMonitor

    @Binds
    abstract fun bindAudioProbe(impl: NativeAudioProbe): AudioProbe
}
