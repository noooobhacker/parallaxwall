package com.example.parallaxwall

import android.content.Context
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine() {

        private val renderer = ParallaxRenderer(this@ParallaxWallpaperService)
        private var loadedVersion = -1L

        private fun syncFromPrefs() {
            val p = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            renderer.sensitivity = p.getInt(Prefs.KEY_SENS, 50)
            renderer.userZoom = p.getFloat(Prefs.KEY_ZOOM, 1f)
            renderer.panX = p.getFloat(Prefs.KEY_PANX, 0f)
            renderer.panY = p.getFloat(Prefs.KEY_PANY, 0f)
            val v = p.getLong(Prefs.KEY_VERSION, 0L)
            if (v != loadedVersion) {
                loadedVersion = v
                val file = File(filesDir, Prefs.WALLPAPER_FILE)
                val bg = File(filesDir, Prefs.WALLPAPER_BG)
                val fg = File(filesDir, Prefs.WALLPAPER_FG)
                val layered = bg.exists() && fg.exists()
                renderer.setMedia(
                    if (file.exists()) file else null,
                    p.getBoolean(Prefs.KEY_IS_VIDEO, false),
                    if (layered) bg else null,
                    if (layered) fg else null
                )
            }
            renderer.requestDraw()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            syncFromPrefs()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            syncFromPrefs()
            renderer.setSurface(holder.surface, width, height, false)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer.surfaceDestroyed()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) syncFromPrefs()
            renderer.setActive(visible)
        }

        override fun onDestroy() {
            super.onDestroy()
            renderer.destroy()
        }
    }
}
