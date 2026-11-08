// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws one layer (SPEC 3.5): a quad placed on the canvas, sampling the video through a UV
 * crop rectangle with the display rotation applied, with rounded corners, border and
 * opacity computed in the fragment shader. Use on the GL thread only.
 */
internal class LayerProgram {
    private val program: Int = GlPrograms.link(VERTEX, FRAGMENT)
    private val quad: Int
    private val vao: Int
    private val uFrame = GLES30.glGetUniformLocation(program, "uFrame")
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uCrop = GLES30.glGetUniformLocation(program, "uCrop")
    private val uRotation = GLES30.glGetUniformLocation(program, "uRotation")
    private val uSizePx = GLES30.glGetUniformLocation(program, "uSizePx")
    private val uRadiusPx = GLES30.glGetUniformLocation(program, "uRadiusPx")
    private val uBorderPx = GLES30.glGetUniformLocation(program, "uBorderPx")
    private val uBorderColor = GLES30.glGetUniformLocation(program, "uBorderColor")
    private val uOpacity = GLES30.glGetUniformLocation(program, "uOpacity")
    private val uHasTexture = GLES30.glGetUniformLocation(program, "uHasTexture")
    private val uPlaceholder = GLES30.glGetUniformLocation(program, "uPlaceholder")
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

    /** Parameters of one draw; reused between frames to avoid allocations. */
    class Params {
        /** Placement on the canvas, normalized, y down: x, y, width, height. */
        val frame = FloatArray(4)
        /** SurfaceTexture transform. */
        val texMatrix = FloatArray(16)
        /** Crop in displayed-video coordinates: x, y, width, height. */
        val crop = FloatArray(4)
        var quarterTurns = 0
        var widthPx = 0f
        var heightPx = 0f
        var radiusPx = 0f
        var borderPx = 0f
        val borderColor = FloatArray(4)
        var opacity = 1f
        /** External texture; without one, [placeholder] is drawn (no frame decoded yet). */
        var texture = 0
        var hasTexture = false
        val placeholder = floatArrayOf(0.15f, 0.15f, 0.15f, 1f)
    }

    fun draw(p: Params) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glUniform4fv(uFrame, 1, p.frame, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, p.texMatrix, 0)
        GLES30.glUniform4fv(uCrop, 1, p.crop, 0)
        GLES30.glUniform1i(uRotation, p.quarterTurns)
        GLES30.glUniform2f(uSizePx, p.widthPx, p.heightPx)
        GLES30.glUniform1f(uRadiusPx, p.radiusPx)
        GLES30.glUniform1f(uBorderPx, p.borderPx)
        GLES30.glUniform4fv(uBorderColor, 1, p.borderColor, 0)
        GLES30.glUniform1f(uOpacity, p.opacity)
        GLES30.glUniform4fv(uPlaceholder, 1, p.placeholder, 0)
        GLES30.glUniform1i(uHasTexture, if (p.hasTexture) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, if (p.hasTexture) p.texture else 0)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    companion object {
        /** New external (video) texture for a SurfaceTexture. */
        fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private const val VERTEX = """#version 300 es
in vec2 aCorner;
uniform vec4 uFrame;
out vec2 vLocal;
void main() {
    vLocal = aCorner;
    vec2 p = uFrame.xy + aCorner * uFrame.zw;
    gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec4 uCrop;
uniform int uRotation;
uniform vec2 uSizePx;
uniform float uRadiusPx;
uniform float uBorderPx;
uniform vec4 uBorderColor;
uniform float uOpacity;
uniform bool uHasTexture;
uniform vec4 uPlaceholder;
in vec2 vLocal;
out vec4 outColor;

float roundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    // Displayed-video coordinates (y down) -> encoded-video coordinates.
    vec2 uv = uCrop.xy + vLocal * uCrop.zw;
    vec2 e;
    if (uRotation == 1) e = vec2(uv.y, 1.0 - uv.x);
    else if (uRotation == 2) e = vec2(1.0 - uv.x, 1.0 - uv.y);
    else if (uRotation == 3) e = vec2(1.0 - uv.y, uv.x);
    else e = uv;
    vec2 st = (uTexMatrix * vec4(e.x, 1.0 - e.y, 0.0, 1.0)).xy;
    vec4 color = uHasTexture ? texture(uTex, st) : uPlaceholder;

    vec2 halfSize = 0.5 * uSizePx;
    vec2 p = (vLocal - 0.5) * uSizePx;
    float r = min(uRadiusPx, min(halfSize.x, halfSize.y));
    float d = roundedBox(p, halfSize, r);
    float alpha = clamp(0.5 - d, 0.0, 1.0);
    if (uBorderPx > 0.0) {
        color = mix(uBorderColor, color, clamp(0.5 - (d + uBorderPx), 0.0, 1.0));
    }
    outColor = vec4(color.rgb, color.a * alpha * uOpacity);
}
"""
    }
}
