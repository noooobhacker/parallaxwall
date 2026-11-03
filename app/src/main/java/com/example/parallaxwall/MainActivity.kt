package com.example.parallaxwall

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.View
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

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var content: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var hint: TextView
    private lateinit var sensLabel: TextView

    private var player: ExoPlayer? = null
    private var sensitivity = 50

    // tilt state
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var baseRoll = 0f
    private var basePitch = 0f
    private var hasBase = false
    private var curX = 0f
    private var curY = 0f

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) showMedia(uri)
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

        findViewById<Button>(R.id.pickBtn).setOnClickListener {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        findViewById<SeekBar>(R.id.sensSeek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    sensitivity = p
                    sensLabel.text = "Sensitivity: $p"
                    applyTransform()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        findViewById<Button>(R.id.setBtn).setOnClickListener {
            Toast.makeText(this, "Coming in step 2", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            Toast.makeText(this, "Coming in step 3", Toast.LENGTH_SHORT).show()
        }

        applyTransform()
    }

    private fun showMedia(uri: Uri) {
        hint.visibility = View.GONE
        releasePlayer()
        hasBase = false

        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video")) {
            imageView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            val p = ExoPlayer.Builder(this).build()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.volume = 0f
            p.prepare()
            p.playWhenReady = true
            playerView.player = p
            player = p
        } else {
            playerView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            imageView.setImageURI(uri)
        }
        applyTransform()
    }

    // Maps sensitivity (0..100) to the maximum shift as a fraction of view size.
    private fun shiftFraction(): Float = 0.10f * (sensitivity / 100f)

    private fun applyTransform() {
        val f = shiftFraction()
        val scale = 1f + 2f * f + 0.02f
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = curX * content.width * f
        content.translationY = curY * content.height * f
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        SensorManager.getOrientation(rotMatrix, orientation)
        val pitch = orientation[1]
        val roll = orientation[2]
        if (!hasBase) {
            basePitch = pitch
            baseRoll = roll
            hasBase = true
        }
        val range = 0.5f // about 30 degrees of tilt = full shift
        val tx = ((roll - baseRoll) / range).coerceIn(-1f, 1f)
        val ty = ((pitch - basePitch) / range).coerceIn(-1f, 1f)
        // smooth + move opposite to the tilt (flip the signs if it feels wrong)
        curX += (-tx - curX) * 0.15f
        curY += (ty - curY) * 0.15f
        applyTransform()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) {
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Toast.makeText(this, "No motion sensor found", Toast.LENGTH_LONG).show()
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
}
