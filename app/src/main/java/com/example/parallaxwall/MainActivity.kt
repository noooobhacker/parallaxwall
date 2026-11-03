package com.example.parallaxwall

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var content: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var hint: TextView
    private lateinit var sensLabel: TextView
    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private lateinit var pickBtn: Button
    private lateinit var setBtn: Button
    private lateinit var saveBtn: Button

    private var player: ExoPlayer? = null
    private var sensitivity = 50
    private var hasMedia = false
    private var currentIsVideo = false
    private var busy = false
    private val tilt = TiltTracker()

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

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        content = findViewById(R.id.content)
        imageView = findViewById(R.id.imageView)
        playerView = findViewById(R.id.playerView)
        hint = findViewById(R.id.hint)
        sensLabel = findViewById(R.id.sensLabel)
        status = findViewById(R.id.status)
        seek = findViewById(R.id.sensSeek)
        pickBtn = findViewById(R.id.pickBtn)
        setBtn = findViewById(R.id.setBtn)
        saveBtn = findViewById(R.id.saveBtn)

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        sensitivity = prefs.getInt(Prefs.KEY_SENS, 50)
        seek.progress = sensitivity
        sensLabel.text = "Sensitivity: $sensitivity"

        pickBtn.setOnClickListener {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                sensitivity = p
                sensLabel.text = "Sensitivity: $p"
                applyTransform()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        setBtn.setOnClickListener { onSetWallpaper() }
        saveBtn.setOnClickListener { onSaveLoop() }

        // restore the last picked media (e.g. after the activity was recreated)
        val last = File(filesDir, Prefs.CURRENT_FILE)
        if (last.exists()) showCurrent(prefs.getBoolean(Prefs.KEY_CURRENT_VIDEO, false))

        applyTransform()
    }

    // ---------------- picking ----------------

    private fun onPicked(uri: Uri) {
        val isVideo = (contentResolver.getType(uri) ?: "").startsWith("video")
        setBusy(true, "Loading…")
        Thread {
            try {
                MediaUtil.copyUriToFile(this, uri, File(filesDir, Prefs.CURRENT_FILE))
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Prefs.KEY_CURRENT_VIDEO, isVideo).commit()
                runOnUiThread {
                    setBusy(false)
                    showCurrent(isVideo)
                }
            } catch (e: Exception) {
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
        releasePlayer()
        tilt.reset()
        if (isVideo) {
            imageView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            val p = ExoPlayer.Builder(this).build()
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.volume = 0f
            p.prepare()
            p.playWhenReady = true
            playerView.player = p
            player = p
        } else {
            playerView.visibility = View.GONE
            val bmp = MediaUtil.loadBitmap(file.absolutePath, 2048)
            if (bmp == null) {
                hasMedia = false
                toast("Could not read that photo")
                return
            }
            imageView.setImageBitmap(bmp)
            imageView.visibility = View.VISIBLE
        }
        hasMedia = true
        currentIsVideo = isVideo
        hint.visibility = View.GONE
        applyTransform()
    }

    // ---------------- set wallpaper ----------------

    private fun onSetWallpaper() {
        if (!hasMedia) {
            toast("Choose a photo or video first")
            return
        }
        val isVideo = currentIsVideo
        val sens = sensitivity
        setBusy(true, "Preparing wallpaper…")
        Thread {
            try {
                val src = File(filesDir, Prefs.CURRENT_FILE)
                val dst = File(filesDir, Prefs.WALLPAPER_FILE)
                src.copyTo(dst, overwrite = true)
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Prefs.KEY_IS_VIDEO, isVideo)
                    .putInt(Prefs.KEY_SENS, sens)
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
        val (w, h) = outputSize()
        val src = File(filesDir, Prefs.CURRENT_FILE)
        setBusy(true, "Rendering loop video… 0%")
        Thread {
            val tmp = File(cacheDir, "loop_tmp.mp4")
            try {
                if (tmp.exists()) tmp.delete()
                LoopExporter().export(src.absolutePath, isVideo, sens, w, h, tmp) { pct ->
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

    // ---------------- preview transform / sensors ----------------

    private fun applyTransform() {
        val f = Cover.MAX_SHIFT * sensitivity / 100f
        // the zoom is FIXED: the slider only changes how far the picture moves
        content.scaleX = Cover.ZOOM
        content.scaleY = Cover.ZOOM
        content.translationX = tilt.x * content.width * f
        content.translationY = tilt.y * content.height * f
    }

    override fun onSensorChanged(e: SensorEvent) {
        tilt.update(e.values)
        applyTransform()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) {
            tilt.reset()
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        } else {
            toast("No motion sensor found")
        }
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
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
