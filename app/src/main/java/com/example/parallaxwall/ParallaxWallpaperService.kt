package com.example.parallaxwall

import android.content.Context
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
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine(), SensorEventListener {

        private val thread = HandlerThread("parallax-wallpaper").also { it.start() }
        private val handler = Handler(thread.looper)
        private val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val tilt = TiltTracker()

        @Volatile private var surfaceW = 0
        @Volatile private var surfaceH = 0
        @Volatile private var hasSurface = false
        @Volatile private var isVisibleNow = false

        private var loadedVersion = -1L
        private var sensitivity = 50
        private var contentW = 0
        private var contentH = 0

        private var egl: EglCore? = null
        private var program: QuadProgram? = null
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var videoSurface: Surface? = null
        private var player: MediaPlayer? = null
        @Volatile private var newFrame = false

        private val mvp = FloatArray(16)
        private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // ---------- engine callbacks (main thread) ----------

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            hasSurface = true
            handler.post { rebuild(holder) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            hasSurface = false
            runOnRenderThread { teardown() }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisibleNow = visible
            handler.post { if (visible) onShown() else onHidden() }
        }

        override fun onDestroy() {
            super.onDestroy()
            isVisibleNow = false
            runOnRenderThread { teardown() }
            thread.quitSafely()
        }

        // ---------- render thread ----------

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

        private fun rebuild(holder: SurfaceHolder) {
            teardown()
            if (!hasSurface || surfaceW <= 0 || surfaceH <= 0) return
            try {
                egl = EglCore(holder.surface, false)
                loadContent()
                startIfVisible()
            } catch (e: Exception) {
                Log.e(TAG, "rebuild failed", e)
                teardown()
            }
        }

        private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        private fun loadContent() {
            val p = prefs()
            sensitivity = p.getInt(Prefs.KEY_SENS, 50)
            loadedVersion = p.getLong(Prefs.KEY_VERSION, 0L)
            val isVideo = p.getBoolean(Prefs.KEY_IS_VIDEO, false)
            val file = File(filesDir, Prefs.WALLPAPER_FILE)
            releaseContent()
            if (!file.exists()) {
                clearBlack()
                return
            }
            if (isVideo) setupVideo(file) else setupImage(file)
        }

        private fun setupImage(file: File) {
            val bmp = MediaUtil.loadBitmap(file.absolutePath, 2560)
            if (bmp == null) {
                clearBlack()
                return
            }
            contentW = bmp.width
            contentH = bmp.height
            program = QuadProgram(false)
            textureId = GlUtil.createTexture(false)
            GlUtil.uploadBitmap(textureId, bmp)
            bmp.recycle()
            drawFrame()
        }

        private fun setupVideo(file: File) {
            program = QuadProgram(true)
            textureId = GlUtil.createTexture(true)
            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener(
                SurfaceTexture.OnFrameAvailableListener {
                    newFrame = true
                    drawFrame()
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
                if (isVisibleNow) {
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

        private fun onShown() {
            if (egl == null) return
            val v = prefs().getLong(Prefs.KEY_VERSION, 0L)
            if (v != loadedVersion) {
                try {
                    loadContent()
                } catch (e: Exception) {
                    Log.e(TAG, "reload failed", e)
                }
            }
            startIfVisible()
        }

        private fun startIfVisible() {
            if (!isVisibleNow || egl == null) return
            tilt.reset()
            sensorManager.unregisterListener(this)
            val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (s != null) {
                sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            try {
                player?.start()
            } catch (e: IllegalStateException) {
                // not prepared yet; the prepared-listener starts it
            }
            drawFrame()
        }

        private fun onHidden() {
            sensorManager.unregisterListener(this)
            try {
                player?.pause()
            } catch (e: IllegalStateException) {
                // ignore
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (egl == null) return
            tilt.update(event.values)
            drawFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun clearBlack() {
            val e = egl ?: return
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            e.swap()
        }

        private fun drawFrame() {
            val e = egl ?: return
            val p = program ?: return
            if (surfaceW <= 0 || surfaceH <= 0) return
            try {
                val st = surfaceTexture
                if (st != null && newFrame) {
                    newFrame = false
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix)
                }
                GLES20.glViewport(0, 0, surfaceW, surfaceH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val f = Cover.MAX_SHIFT * sensitivity / 100f
                val tx = tilt.x * f * 2f
                val ty = -tilt.y * f * 2f
                Cover.mvp(mvp, surfaceW, surfaceH, contentW, contentH, tx, ty)
                p.draw(textureId, mvp, if (st != null) stMatrix else identity)
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
                program?.release()
            } catch (e: Exception) {
                Log.w(TAG, "program release", e)
            }
            program = null
            try {
                GlUtil.deleteTexture(textureId)
            } catch (e: Exception) {
                Log.w(TAG, "texture delete", e)
            }
            textureId = 0
            contentW = 0
            contentH = 0
            newFrame = false
        }

        private fun teardown() {
            sensorManager.unregisterListener(this)
            releaseContent()
            try {
                egl?.release()
            } catch (e: Exception) {
                Log.w(TAG, "egl release", e)
            }
            egl = null
        }
    }

    companion object {
        private const val TAG = "ParallaxWallpaper"
    }
}
