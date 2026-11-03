package com.example.parallaxwall

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var renderer: ParallaxRenderer
    private lateinit var stage: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var hint: TextView
    private lateinit var sensLabel: TextView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var pickBtn: Button
    private lateinit var setBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var scaleDetector: ScaleGestureDetector

    private var sensitivity = 50
    private var hasMedia = false
    private var currentIsVideo = false
    private var busy = false
    private var lastX = 0f
    private var lastY = 0f

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onPicked(uri)
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startExport() else toast("Storage permission is needed to save to the gallery")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stage = findViewById(R.id.stage)
        preview = findViewById(R.id.preview)
        hint = findViewById(R.id.hint)
        sensLabel = findViewById(R.id.sensLabel)
        status = findViewById(R.id.status)
        seek = findViewById(R.id.sensSeek)
        pickBtn = findViewById(R.id.pickBtn)
        setBtn = findViewById(R.id.setBtn)
        saveBtn = findViewById(R.id.saveBtn)
        resetBtn = findViewById(R.id.resetBtn)

        renderer = ParallaxRenderer(this)

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        sensitivity = prefs.getInt(Prefs.KEY_SENS, 50)
        renderer.sensitivity = sensitivity
        seek.progress = sensitivity
        sensLabel.text = "Sensitivity: $sensitivity"

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                renderer.setSurface(Surface(st), w, h, true)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                renderer.setSurface(Surface(st), w, h, true)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                renderer.surfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        // The preview has the same shape as the phone screen, so what you see is what you get.
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    renderer.userZoom =
                        (renderer.userZoom * d.scaleFactor).coerceIn(Cover.MIN_ZOOM, Cover.MAX_ZOOM)
                    renderer.requestDraw()
                    return true
                }
            }
        )

        preview.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastX = ev.getX(ev.actionIndex)
                    lastY = ev.getY(ev.actionIndex)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val keep = if (ev.actionIndex == 0) 1 else 0
                    if (keep < ev.pointerCount) {
                        lastX = ev.getX(keep)
                        lastY = ev.getY(keep)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && ev.pointerCount == 1 && v.width > 0 && v.height > 0) {
                        val dx = ev.x - lastX
                        val dy = ev.y - lastY
                        renderer.panX = (renderer.panX + 2f * dx / v.width).coerceIn(-2f, 2f)
                        renderer.panY = (renderer.panY - 2f * dy / v.height).coerceIn(-2f, 2f)
                        renderer.requestDraw()
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            true
        }

        pickBtn.setOnClickListener {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                sensitivity = p
                sensLabel.text = "Sensitivity: $p"
                renderer.sensitivity = p
                renderer.requestDraw()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        resetBtn.setOnClickListener {
            renderer.userZoom = 1f
            renderer.panX = 0f
            renderer.panY = 0f
            renderer.requestDraw()
        }
        setBtn.setOnClickListener { onSetWallpaper() }
        saveBtn.setOnClickListener { onSaveLoop() }

        // restore the last picked media (e.g. after the activity was recreated)
        val last = File(filesDir, Prefs.CURRENT_FILE)
        if (last.exists()) showCurrent(prefs.getBoolean(Prefs.KEY_CURRENT_VIDEO, false))
    }

    private fun fitPreview() {
        val aw = stage.width
        val ah = stage.height
        if (aw <= 0 || ah <= 0) return
        val dm = resources.displayMetrics
        val sw = min(dm.widthPixels, dm.heightPixels).toFloat()
        val sh = max(dm.widthPixels, dm.heightPixels).toFloat()
        val aspect = sw / sh
        var w = aw.toFloat()
        var h = w / aspect
        if (h > ah) {
            h = ah.toFloat()
            w = h * aspect
        }
        val nw = w.toInt()
        val nh = h.toInt()
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            lp.gravity = Gravity.CENTER
            preview.layoutParams = lp
        }
    }

    // ---------------- picking ----------------

    private fun onPicked(uri: Uri) {
        val isVideo = (contentResolver.getType(uri) ?: "").startsWith("video")
        setBusy(true, "Loading…")
        Thread {
            try {
                val cur = File(filesDir, Prefs.CURRENT_FILE)
                val bgF = File(filesDir, Prefs.CURRENT_BG)
                val fgF = File(filesDir, Prefs.CURRENT_FG)
                bgF.delete()
                fgF.delete()
                MediaUtil.copyUriToFile(this, uri, cur)
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Prefs.KEY_CURRENT_VIDEO, isVideo).commit()
                var separated = true
                if (!isVideo) {
                    separated = SubjectCutout.createLayers(this, cur, bgF, fgF) { msg ->
                        runOnUiThread { status.text = msg }
                    }
                    if (!separated) {
                        bgF.delete()
                        fgF.delete()
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    renderer.userZoom = 1f
                    renderer.panX = 0f
                    renderer.panY = 0f
                    showCurrent(isVideo)
                    if (!isVideo && !separated) {
                        toast("Could not separate the object (needs Google Play services and internet once). Using the whole picture.")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "pick failed", e)
                runOnUiThread {
                    setBusy(false)
                    toast("Could not open that file")
                }
            }
        }.start()
    }

    private fun showCurrent(isVideo: Boolean) {
        val file = File(filesDir, Prefs.CURRENT_FILE)
        if (!isVideo) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                hasMedia = false
                toast("Could not read that photo")
                return
            }
        }
        hasMedia = true
        currentIsVideo = isVideo
        hint.visibility = View.GONE
        val bgF = File(filesDir, Prefs.CURRENT_BG)
        val fgF = File(filesDir, Prefs.CURRENT_FG)
        val layered = !isVideo && bgF.exists() && fgF.exists()
        renderer.setMedia(file, isVideo, if (layered) bgF else null, if (layered) fgF else null)
    }

    // ---------------- set wallpaper ----------------

    private fun onSetWallpaper() {
        if (!hasMedia) {
            toast("Choose a photo or video first")
            return
        }
        val isVideo = currentIsVideo
        val sens = sensitivity
        val zoom = renderer.userZoom
        val px = renderer.panX
        val py = renderer.panY
        setBusy(true, "Preparing wallpaper…")
        Thread {
            try {
                val src = File(filesDir, Prefs.CURRENT_FILE)
                val dst = File(filesDir, Prefs.WALLPAPER_FILE)
                src.copyTo(dst, overwrite = true)
                val wbg = File(filesDir, Prefs.WALLPAPER_BG)
                val wfg = File(filesDir, Prefs.WALLPAPER_FG)
                wbg.delete()
                wfg.delete()
                val cbg = File(filesDir, Prefs.CURRENT_BG)
                val cfg = File(filesDir, Prefs.CURRENT_FG)
                if (!isVideo && cbg.exists() && cfg.exists()) {
                    cbg.copyTo(wbg, overwrite = true)
                    cfg.copyTo(wfg, overwrite = true)
                }
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Prefs.KEY_IS_VIDEO, isVideo)
                    .putInt(Prefs.KEY_SENS, sens)
                    .putFloat(Prefs.KEY_ZOOM, zoom)
                    .putFloat(Prefs.KEY_PANX, px)
                    .putFloat(Prefs.KEY_PANY, py)
                    .putLong(Prefs.KEY_VERSION, System.currentTimeMillis())
                    .commit()
                runOnUiThread {
                    setBusy(false)
                    openWallpaperScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "set wallpaper failed", e)
                runOnUiThread {
                    setBusy(false)
                    toast("Could not prepare the wallpaper")
                }
            }
        }.start()
    }

    private fun openWallpaperScreen() {
        val comp = ComponentName(this, ParallaxWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                toast("Pick “Parallax Wall” from the list")
            } catch (e2: Exception) {
                toast("Cannot open the wallpaper settings on this phone")
            }
        }
    }

    // ---------------- save loop video ----------------

    private fun onSaveLoop() {
        if (!hasMedia) {
            toast("Choose a photo or video first")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startExport()
    }

    private fun outputSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        var w = min(dm.widthPixels, dm.heightPixels).toFloat()
        var h = max(dm.widthPixels, dm.heightPixels).toFloat()
        val scale = min(1f, 1920f / h)
        w *= scale
        h *= scale
        val ow = max(16, (w.toInt() / 16) * 16)
        val oh = max(16, (h.toInt() / 16) * 16)
        return Pair(ow, oh)
    }

    private fun startExport() {
        if (busy || !hasMedia) return
        val isVideo = currentIsVideo
        val sens = sensitivity
        val zoom = renderer.userZoom
        val px = renderer.panX
        val py = renderer.panY
        val (w, h) = outputSize()
        val src = File(filesDir, Prefs.CURRENT_FILE)
        val cbg = File(filesDir, Prefs.CURRENT_BG)
        val cfg = File(filesDir, Prefs.CURRENT_FG)
        val layered = !isVideo && cbg.exists() && cfg.exists()
        setBusy(true, "Rendering loop video… 0%")
        Thread {
            val tmp = File(cacheDir, "loop_tmp.mp4")
            try {
                if (tmp.exists()) tmp.delete()
                LoopExporter().export(
                    src.absolutePath, isVideo, sens, zoom, px, py, w, h, tmp,
                    if (layered) cbg else null, if (layered) cfg else null
                ) { pct ->
                    runOnUiThread { status.text = "Rendering loop video… $pct%" }
                }
                runOnUiThread { status.text = "Saving to gallery…" }
                MediaUtil.saveVideoToGallery(this, tmp)
                runOnUiThread {
                    setBusy(false)
                    toast("Saved to gallery (Movies/ParallaxWall)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "export failed", e)
                runOnUiThread {
                    setBusy(false)
                    toast("Save failed: ${e.message}")
                }
            } finally {
                tmp.delete()
            }
        }.start()
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        renderer.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        renderer.setActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.destroy()
    }

    private fun setBusy(b: Boolean, msg: String = "") {
        busy = b
        pickBtn.isEnabled = !b
        setBtn.isEnabled = !b
        saveBtn.isEnabled = !b
        status.text = msg
        status.visibility = if (b) View.VISIBLE else View.GONE
        if (b) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ParallaxWall"
    }
}
