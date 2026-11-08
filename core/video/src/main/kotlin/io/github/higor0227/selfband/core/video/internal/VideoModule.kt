// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.video.CanvasPreviewFactory
import io.github.higor0227.selfband.core.video.CanvasRendererFactory

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VideoModule {
    @Binds
    abstract fun bindCanvasPreviewFactory(impl: GlCanvasPreviewFactory): CanvasPreviewFactory

    @Binds
    abstract fun bindCanvasRendererFactory(impl: GlCanvasRendererFactory): CanvasRendererFactory
}
