package com.example.parallaxwall

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Layout maths shared by the preview, the live wallpaper and the loop-video exporter. */
object Cover {
    const val MAX_SHIFT = 0.10f   // foreground shift at sensitivity 100 (fraction of the screen)
    const val BG_ZOOM = 1.15f     // background is slightly larger so tilting never shows black edges
    const val BG_SHIFT = 0.5f     // background moves less than the foreground = depth
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 3.0f

    private fun aspects(viewW: Int, viewH: Int, cw: Int, ch: Int): Pair<Float, Float> {
        val va = viewW.toFloat() / viewH.toFloat()
        val ca = if (cw > 0 && ch > 0) cw.toFloat() / ch.toFloat() else va
        return Pair(va, ca)
    }

    /** Whole picture visible, exactly fitted to the screen (no crop, no zoom at zoom = 1). */
    fun fitMvp(
        out: FloatArray, viewW: Int, viewH: Int, cw: Int, ch: Int,
        zoom: Float, tx: Float, ty: Float
    ) {
        val (va, ca) = aspects(viewW, viewH, cw, ch)
        var sx = 1f
        var sy = 1f
        if (ca > va) sy = va / ca else sx = ca / va
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx, ty, 0f)
        Matrix.scaleM(out, 0, sx * zoom, sy * zoom, 1f)
    }

    /** Picture covers the whole screen (used for the blurred background). */
    fun coverMvp(
        out: FloatArray, viewW: Int, viewH: Int, cw: Int, ch: Int,
        zoom: Float, tx: Float, ty: Float
    ) {
        val (va, ca) = aspects(viewW, viewH, cw, ch)
        var sx = 1f
        var sy = 1f
        if (ca > va) sx = ca / va else sy = va / ca
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx, ty, 0f)
        Matrix.scaleM(out, 0, sx * zoom, sy * zoom, 1f)
    }
}

object GlUtil {
    fun createTexture(external: Boolean): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun uploadBitmap(tex: Int, bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun deleteTexture(tex: Int) {
        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

class EglCore(surface: Surface, recordable: Boolean) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("No EGL display")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("EGL init failed")

            val base = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT
            )
            val attribs = if (recordable) {
                base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            } else {
                base + intArrayOf(EGL14.EGL_NONE)
            }
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
                throw RuntimeException("No EGL config")
            }
            val config = configs[0] ?: throw RuntimeException("No EGL config")

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("EGL context failed")

            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("EGL surface failed")

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        } catch (e: RuntimeException) {
            release()
            throw e
        }
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swap() {
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // eglTerminate is intentionally NOT called: the display is shared by the whole process.
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

class QuadProgram(private val external: Boolean, blur: Boolean) {
    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uMvp: Int
    private val uSt: Int
    private val uTex: Int
    private val buffer: FloatBuffer

    init {
        val vs = """
            uniform mat4 uMvp;
            uniform mat4 uSt;
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = (uSt * vec4(aTex, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val header = if (external) {
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n"
        } else {
            "precision mediump float;\n"
        }
        val sampler = if (external) "samplerExternalOES" else "sampler2D"
        val body = if (blur) {
            """
            varying vec2 vTex;
            uniform $sampler uTex;
            void main() {
                vec3 sum = vec3(0.0);
                for (int i = 0; i < 24; i++) {
                    float fi = float(i);
                    float ang = fi * 2.399963;
                    float rad = 0.06 * sqrt((fi + 0.5) / 24.0);
                    sum += texture2D(uTex, vTex + vec2(cos(ang), sin(ang)) * rad).rgb;
                }
                gl_FragColor = vec4(sum / 24.0, 1.0);
            }
            """.trimIndent()
        } else {
            """
            varying vec2 vTex;
            uniform $sampler uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
            """.trimIndent()
        }
        val fs = header + body

        val vsId = compile(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vsId)
        GLES20.glAttachShader(p, fsId)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vsId)
        GLES20.glDeleteShader(fsId)
        program = p
        aPos = GLES20.glGetAttribLocation(p, "aPos")
        aTex = GLES20.glGetAttribLocation(p, "aTex")
        uMvp = GLES20.glGetUniformLocation(p, "uMvp")
        uSt = GLES20.glGetUniformLocation(p, "uSt")
        uTex = GLES20.glGetUniformLocation(p, "uTex")

        // x, y, s, t  (external textures use the SurfaceTexture matrix, 2D textures need a flip)
        val data = if (external) {
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
        } else {
            floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
        }
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data)
        fb.position(0)
        buffer = fb
    }

    fun draw(textureId: Int, mvp: FloatArray, st: FloatArray) {
        val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(aPos)
        buffer.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindTexture(target, 0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }
}

/**
 * Draws one frame: a blurred, edge-extended background (fills whatever the picture does not cover)
 * plus the sharp picture fitted exactly to the screen.
 */
class SceneDrawer private constructor(video: Boolean) {
    private val fg = QuadProgram(video, false)
    private val bg = QuadProgram(video, true)
    var fgTex = 0
        private set
    private var bgTex = 0
    private val mvp = FloatArray(16)

    fun draw(
        viewW: Int, viewH: Int, cw: Int, ch: Int,
        shiftX: Float, shiftY: Float,
        zoom: Float, panX: Float, panY: Float,
        st: FloatArray
    ) {
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        Cover.coverMvp(
            mvp, viewW, viewH, cw, ch, Cover.BG_ZOOM,
            shiftX * Cover.BG_SHIFT, shiftY * Cover.BG_SHIFT
        )
        bg.draw(bgTex, mvp, st)

        Cover.fitMvp(mvp, viewW, viewH, cw, ch, zoom, panX + shiftX, panY + shiftY)
        fg.draw(fgTex, mvp, st)
    }

    fun release() {
        fg.release()
        bg.release()
        GlUtil.deleteTexture(fgTex)
        if (bgTex != fgTex) GlUtil.deleteTexture(bgTex)
        fgTex = 0
        bgTex = 0
    }

    companion object {
        fun image(bmp: Bitmap): SceneDrawer {
            val d = SceneDrawer(false)
            d.fgTex = GlUtil.createTexture(false)
            GlUtil.uploadBitmap(d.fgTex, bmp)
            d.bgTex = GlUtil.createTexture(false)
            val big = max(bmp.width, bmp.height)
            if (big > 256) {
                val s = 256f / big
                val small = Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * s).roundToInt()),
                    max(1, (bmp.height * s).roundToInt()),
                    true
                )
                GlUtil.uploadBitmap(d.bgTex, small)
                if (small !== bmp) small.recycle()
            } else {
                GlUtil.uploadBitmap(d.bgTex, bmp)
            }
            return d
        }

        fun video(): SceneDrawer {
            val d = SceneDrawer(true)
            d.fgTex = GlUtil.createTexture(true)
            d.bgTex = d.fgTex
            return d
        }
    }
}
