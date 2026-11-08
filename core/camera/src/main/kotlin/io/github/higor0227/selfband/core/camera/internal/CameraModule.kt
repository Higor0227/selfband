// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.camera.VideoCamera

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CameraModule {
    @Binds
    abstract fun bindVideoCamera(impl: Camera2VideoCamera): VideoCamera
}
