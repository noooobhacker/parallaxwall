package com.example.parallaxwall

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object MediaUtil {

    fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Cannot store file")
    }

    /** Decodes an image so its longest side is at most maxDim, with EXIF rotation applied. */
    fun loadBitmap(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val big = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (big / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return null

        val curMax = max(bmp.width, bmp.height)
        if (curMax > maxDim) {
            val s = maxDim.toFloat() / curMax
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * s).roundToInt()),
                max(1, (bmp.height * s).roundToInt()),
                true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(-90f)
                m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** Copies an MP4 into the phone gallery (Movies/ParallaxWall). */
    fun saveVideoToGallery(context: Context, file: File) {
        val name = "ParallaxLoop_" + System.currentTimeMillis() + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ParallaxWall")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Gallery insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot write to gallery")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val dir = File(movies, "ParallaxWall")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create folder")
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
        }
    }
}
