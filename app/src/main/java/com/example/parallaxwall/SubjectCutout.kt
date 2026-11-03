package com.example.parallaxwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Splits a photo into two layers with Google ML Kit subject segmentation:
 *  - the cut-out object (transparent everywhere else)
 *  - the background with the object area filled in from its surroundings
 * Call on a background thread.
 */
object SubjectCutout {
    private const val TAG = "SubjectCutout"
    private const val MAX_DIM = 2048

    fun createLayers(
        context: Context, photo: File, outBg: File, outFg: File, onStatus: (String) -> Unit
    ): Boolean {
        return try {
            val src = MediaUtil.loadBitmap(photo.absolutePath, MAX_DIM) ?: return false
            onStatus("Separating the object… (first time downloads the AI model)")
            val raw = segment(src) ?: return false
            val cut = if (raw.width != src.width || raw.height != src.height) {
                Bitmap.createScaledBitmap(raw, src.width, src.height, true)
            } else {
                raw
            }

            val cov = coverage(cut)
            if (cov < 0.01f || cov > 0.9f) {
                Log.w(TAG, "subject coverage $cov, skipping")
                return false
            }

            onStatus("Building the background…")
            val bg = buildBackground(src, cut) ?: return false

            FileOutputStream(outFg).use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileOutputStream(outBg).use { bg.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "createLayers failed", e)
            false
        }
    }

    /** Runs ML Kit; retries while Google Play services is still downloading the model. */
    private fun segment(src: Bitmap): Bitmap? {
        val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            var lastError: Exception? = null
            for (attempt in 0 until 20) {
                try {
                    val task = segmenter.process(InputImage.fromBitmap(src, 0))
                    val result = Tasks.await(task, 120, TimeUnit.SECONDS)
                    return result.foregroundBitmap
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "segment attempt $attempt failed: ${e.message}")
                    Thread.sleep(4000)
                }
            }
            Log.e(TAG, "segmentation gave up", lastError)
            return null
        } finally {
            segmenter.close()
        }
    }

    private fun coverage(cut: Bitmap): Float {
        val s = 64f / max(cut.width, cut.height)
        val w = max(1, (cut.width * s).roundToInt())
        val h = max(1, (cut.height * s).roundToInt())
        val small = Bitmap.createScaledBitmap(cut, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0
        for (c in px) if (((c ushr 24) and 0xFF) > 128) n++
        return n.toFloat() / px.size
    }

    private fun dilate(a: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = a.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (a[i]) continue
                if ((x > 0 && a[i - 1]) || (x < w - 1 && a[i + 1]) ||
                    (y > 0 && a[i - w]) || (y < h - 1 && a[i + w])
                ) {
                    o[i] = true
                }
            }
        }
        return o
    }

    /** Fills the object area with colours diffused in from the surrounding background. */
    private fun buildBackground(src: Bitmap, cut: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val s = 96f / max(w, h)
        val sw = max(8, (w * s).roundToInt())
        val sh = max(8, (h * s).roundToInt())

        val smallImg = Bitmap.createScaledBitmap(src, sw, sh, true)
        val smallCut = Bitmap.createScaledBitmap(cut, sw, sh, true)
        val n = sw * sh
        val img = IntArray(n)
        val msk = IntArray(n)
        smallImg.getPixels(img, 0, sw, 0, 0, sw, sh)
        smallCut.getPixels(msk, 0, sw, 0, 0, sw, sh)

        var unknown = BooleanArray(n) { ((msk[it] ushr 24) and 0xFF) > 20 }
        repeat(2) { unknown = dilate(unknown, sw, sh) }

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        var known = 0
        var sr = 0f
        var sg = 0f
        var sb = 0f
        for (i in 0 until n) {
            if (!unknown[i]) {
                val c = img[i]
                r[i] = ((c shr 16) and 0xFF).toFloat()
                g[i] = ((c shr 8) and 0xFF).toFloat()
                b[i] = (c and 0xFF).toFloat()
                sr += r[i]
                sg += g[i]
                sb += b[i]
                known++
            }
        }
        if (known < n / 50) return null // nothing around the object to learn from

        val mr = sr / known
        val mg = sg / known
        val mb = sb / known
        for (i in 0 until n) {
            if (unknown[i]) {
                r[i] = mr
                g[i] = mg
                b[i] = mb
            }
        }

        repeat(max(sw, sh) * 4) {
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    val i = y * sw + x
                    if (!unknown[i]) continue
                    var cr = 0f
                    var cg = 0f
                    var cb = 0f
                    var cnt = 0
                    if (x > 0) { cr += r[i - 1]; cg += g[i - 1]; cb += b[i - 1]; cnt++ }
                    if (x < sw - 1) { cr += r[i + 1]; cg += g[i + 1]; cb += b[i + 1]; cnt++ }
                    if (y > 0) { cr += r[i - sw]; cg += g[i - sw]; cb += b[i - sw]; cnt++ }
                    if (y < sh - 1) { cr += r[i + sw]; cg += g[i + sw]; cb += b[i + sw]; cnt++ }
                    if (cnt > 0) {
                        r[i] = cr / cnt
                        g[i] = cg / cnt
                        b[i] = cb / cnt
                    }
                }
            }
        }

        val out = IntArray(n) {
            (0xFF shl 24) or
                (r[it].toInt().coerceIn(0, 255) shl 16) or
                (g[it].toInt().coerceIn(0, 255) shl 8) or
                b[it].toInt().coerceIn(0, 255)
        }
        val fillSmall = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        val fillUp = Bitmap.createScaledBitmap(fillSmall, w, h, true)

        // keep the fill only where the object is (alpha boosted so soft edges are fully covered)
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(fillUp, 0f, 0f, null)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, 4f, 0f
                    )
                )
            )
        }
        lc.drawBitmap(cut, 0f, 0f, paint)

        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(layer, 0f, 0f, null)

        smallImg.recycle()
        smallCut.recycle()
        fillSmall.recycle()
        if (fillUp !== fillSmall) fillUp.recycle()
        layer.recycle()
        return result
    }
}
