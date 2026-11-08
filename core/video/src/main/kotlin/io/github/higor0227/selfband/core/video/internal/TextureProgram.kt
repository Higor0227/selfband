// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a 2D texture into a rectangle of the current viewport, either as is or as one pass of a
 * separable Gaussian blur. Used for the canvas background: the picture, and the blurred video.
 * Use on the GL thread only.
 */
internal class TextureProgram {
    private val program: Int = GlPrograms.link(VERTEX, FRAGMENT)
    private val quad: Int
    private val vao: Int
    private val uFrame = GLES30.glGetUniformLocation(program, "uFrame")
    private val uCrop = GLES30.glGetUniformLocation(program, "uCrop")
    private val uFlipY = GLES30.glGetUniformLocation(program, "uFlipY")
    private val uStep = GLES30.glGetUniformLocation(program, "uStep")
    private val uDim = GLES30.glGetUniformLocation(program, "uDim")
    private val uTex = GLES30.glGetUniformLocation(program, "uTex")

    init {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buffer = ByteBuffer.allocateDirect(corners.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(corners).also { it.position(0) }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Draws [texture] into [frame] (normalized to the viewport, y down: x, y, w, h), sampling
     * [crop] (texture coordinates, y down). [flipY]: the texture's first row is its bottom (a
     * framebuffer) rather than its top (an uploaded bitmap). [stepX], [stepY]: blur direction in
     * texture units per tap, zero for a plain copy. [dim] multiplies the color.
     */
    fun draw(
        texture: Int,
        frame: FloatArray,
        crop: FloatArray,
        flipY: Boolean,
        stepX: Float = 0f,
        stepY: Float = 0f,
        dim: Float = 1f,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glUniform4fv(uFrame, 1, frame, 0)
        GLES30.glUniform4fv(uCrop, 1, crop, 0)
        GLES30.glUniform1i(uFlipY, if (flipY) 1 else 0)
        GLES30.glUniform2f(uStep, stepX, stepY)
        GLES30.glUniform1f(uDim, dim)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    private companion object {
        const val VERTEX = """#version 300 es
in vec2 aCorner;
uniform vec4 uFrame;
out vec2 vLocal;
void main() {
    vLocal = aCorner;
    vec2 p = uFrame.xy + aCorner * uFrame.zw;
    gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
}
"""

        // 9-tap Gaussian done with 5 bilinear samples (offsets/weights for sigma ~ 2 taps).
        const val FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uCrop;
uniform bool uFlipY;
uniform vec2 uStep;
uniform float uDim;
in vec2 vLocal;
out vec4 outColor;
void main() {
    vec2 uv = uCrop.xy + vLocal * uCrop.zw;
    if (uFlipY) uv.y = 1.0 - uv.y;
    vec3 c;
    if (uStep.x == 0.0 && uStep.y == 0.0) {
        c = texture(uTex, uv).rgb;
    } else {
        c = texture(uTex, uv).rgb * 0.2270270270;
        c += (texture(uTex, uv + uStep * 1.3846153846).rgb + texture(uTex, uv - uStep * 1.3846153846).rgb) * 0.3162162162;
        c += (texture(uTex, uv + uStep * 3.2307692308).rgb + texture(uTex, uv - uStep * 3.2307692308).rgb) * 0.0702702703;
    }
    outColor = vec4(c * uDim, 1.0);
}
"""
    }
}
