package com.example.parallaxwall

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One renderer used by BOTH the in-app preview and the live wallpaper, so what you see in the
 * preview is exactly what you get. Everything GL happens on its own thread.
 */
class ParallaxRenderer(context: Context) {

    private val thread = HandlerThread("parallax-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val tilt = TiltTracker()

    @Volatile var sensitivity = 50
    @Volatile var userZoom = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    private var surface: Surface? = null
    private var ownsSurface = false
    private var viewW = 0
    private var viewH = 0
    private var egl: EglCore? = null

    private var mediaFile: File? = null
    private var mediaIsVideo = false
    private var layerBg: File? = null
    private var layerFg: File? = null
    private var scene: SceneDrawer? = null
    private var contentW = 0
    private var contentH = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var player: MediaPlayer? = null
    @Volatile private var newFrame = false
    private var hasFirstFrame = false
    @Volatile private var active = false

    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (egl == null) return
            tilt.update(event.values)
            draw()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---------------- public API (any thread) ----------------

    fun setSurface(s: Surface, w: Int, h: Int, owns: Boolean) {
        val posted = handler.post {
            teardownEgl()
            releaseSurface()
            surface = s
            ownsSurface = owns
            viewW = w
            viewH = h
            buildEgl()
        }
        if (!posted && owns) s.release()
    }

    fun surfaceDestroyed() {
        runOnRenderThread {
            teardownEgl()
            releaseSurface()
        }
    }

    fun setMedia(file: File?, isVideo: Boolean, bgLayer: File? = null, fgLayer: File? = null) {
        handler.post {
            mediaFile = file
            mediaIsVideo = isVideo
            layerBg = bgLayer
            layerFg = fgLayer
            if (egl != null) {
                loadMedia()
                startIfActive()
            }
        }
    }

    fun setActive(a: Boolean) {
        active = a
        handler.post { if (a) startIfActive() else stopActive() }
    }

    fun requestDraw() {
        handler.post { draw() }
    }

    fun destroy() {
        runOnRenderThread {
            stopActive()
            teardownEgl()
            releaseSurface()
        }
        thread.quitSafely()
    }

    // ---------------- render thread ----------------

    private fun runOnRenderThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, TimeUnit.SECONDS)
    }

    private fun buildEgl() {
        val s = surface ?: return
        if (viewW <= 0 || viewH <= 0) return
        try {
            egl = EglCore(s, false)
            loadMedia()
            startIfActive()
        } catch (e: Exception) {
            Log.e(TAG, "EGL setup failed", e)
            teardownEgl()
        }
    }

    private fun loadMedia() {
        releaseContent()
        val file = mediaFile
        if (file == null || !file.exists()) {
            draw()
            return
        }
        tilt.reset()
        try {
            if (mediaIsVideo) setupVideo(file) else setupImage(file)
        } catch (e: Exception) {
            Log.e(TAG, "load media failed", e)
            releaseContent()
        }
        draw()
    }

    private fun setupImage(file: File) {
        val lb = layerBg
        val lf = layerFg
        if (lb != null && lf != null && lb.exists() && lf.exists()) {
            val bgBmp = BitmapFactory.decodeFile(lb.absolutePath)
            val cutBmp = BitmapFactory.decodeFile(lf.absolutePath)
            if (bgBmp != null && cutBmp != null) {
                contentW = bgBmp.width
                contentH = bgBmp.height
                scene = SceneDrawer.layered(bgBmp, cutBmp)
                bgBmp.recycle()
                cutBmp.recycle()
                return
            }
            bgBmp?.recycle()
            cutBmp?.recycle()
        }
        val bmp = MediaUtil.loadBitmap(file.absolutePath, 2560) ?: return
        contentW = bmp.width
        contentH = bmp.height
        scene = SceneDrawer.image(bmp)
        bmp.recycle()
    }

    private fun setupVideo(file: File) {
        val sd = SceneDrawer.video()
        scene = sd
        hasFirstFrame = false
        val st = SurfaceTexture(sd.fgTex)
        st.setOnFrameAvailableListener(
            SurfaceTexture.OnFrameAvailableListener {
                newFrame = true
                draw()
            },
            handler
        )
        surfaceTexture = st
        val s = Surface(st)
        videoSurface = s

        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setSurface(s)
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnVideoSizeChangedListener { _, w, h ->
            contentW = w
            contentH = h
        }
        mp.setOnPreparedListener { m ->
            if (active) {
                try {
                    m.start()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "start failed", e)
                }
            }
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error $what/$extra")
            true
        }
        mp.prepareAsync()
    }

    private fun startIfActive() {
        if (!active || egl == null) return
        tilt.reset()
        sensorManager.unregisterListener(sensorListener)
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) {
            sensorManager.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        try {
            player?.start()
        } catch (e: IllegalStateException) {
            // not prepared yet: the prepared-listener starts it
        }
        draw()
    }

    private fun stopActive() {
        sensorManager.unregisterListener(sensorListener)
        try {
            player?.pause()
        } catch (e: IllegalStateException) {
            // ignore
        }
    }

    private fun draw() {
        val e = egl ?: return
        if (viewW <= 0 || viewH <= 0) return
        try {
            val sc = scene
            val st = surfaceTexture
            if (sc == null || (st != null && !hasFirstFrame && !newFrame)) {
                GLES20.glViewport(0, 0, viewW, viewH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                e.swap()
                return
            }
            if (st != null && newFrame) {
                newFrame = false
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasFirstFrame = true
            }
            val f = Cover.MAX_SHIFT * sensitivity / 100f
            sc.draw(
                viewW, viewH, contentW, contentH,
                tilt.x * f * 2f, -tilt.y * f * 2f,
                userZoom, panX, panY,
                if (st != null) stMatrix else identity
            )
            e.swap()
        } catch (ex: Exception) {
            Log.e(TAG, "draw failed", ex)
        }
    }

    private fun releaseContent() {
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "player release", e)
        }
        player = null
        surfaceTexture?.let {
            try {
                it.setOnFrameAvailableListener(null)
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "texture release", e)
            }
        }
        surfaceTexture = null
        try {
            videoSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "surface release", e)
        }
        videoSurface = null
        try {
            scene?.release()
        } catch (e: Exception) {
            Log.w(TAG, "scene release", e)
        }
        scene = null
        contentW = 0
        contentH = 0
        newFrame = false
        hasFirstFrame = false
    }

    private fun teardownEgl() {
        sensorManager.unregisterListener(sensorListener)
        releaseContent()
        try {
            egl?.release()
        } catch (e: Exception) {
            Log.w(TAG, "egl release", e)
        }
        egl = null
    }

    private fun releaseSurface() {
        if (ownsSurface) {
            try {
                surface?.release()
            } catch (e: Exception) {
                Log.w(TAG, "surface release", e)
            }
        }
        surface = null
        ownsSurface = false
    }

    companion object {
        private const val TAG = "ParallaxRenderer"
    }
}
