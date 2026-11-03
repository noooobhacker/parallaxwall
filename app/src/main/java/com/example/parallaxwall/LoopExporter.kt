package com.example.parallaxwall

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders a seamless, silent MP4 loop with an automatic parallax sway, using the SAME scene
 * (fit-to-screen + blurred fill + your zoom/position) as the preview and the wallpaper.
 * Photo -> 8 second loop. Video -> the video itself (first 15 seconds) with the sway on top.
 */
class LoopExporter {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun export(
        srcPath: String,
        isVideo: Boolean,
        sensitivity: Int,
        zoom: Float,
        panX: Float,
        panY: Float,
        outW: Int,
        outH: Int,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        var egl: EglCore? = null
        var inputSurface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            inputSurface = surface
            enc.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val core = EglCore(surface, true)
            egl = core

            if (isVideo) {
                renderVideo(core, srcPath, sensitivity, zoom, panX, panY, outW, outH, onProgress)
            } else {
                renderImage(core, srcPath, sensitivity, zoom, panX, panY, outW, outH, onProgress)
            }
            drain(true)
        } finally {
            try {
                egl?.release()
            } catch (e: Exception) {
            }
            try {
                encoder?.stop()
            } catch (e: Exception) {
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            try {
                inputSurface?.release()
            } catch (e: Exception) {
            }
        }
    }

    // ---------------- photo ----------------

    private fun renderImage(
        egl: EglCore, srcPath: String, sens: Int, zoom: Float, panX: Float, panY: Float,
        outW: Int, outH: Int, onProgress: (Int) -> Unit
    ) {
        val bmp = MediaUtil.loadBitmap(srcPath, 2560) ?: throw IOException("Cannot read the photo")
        val cw = bmp.width
        val ch = bmp.height
        val scene = SceneDrawer.image(bmp)
        bmp.recycle()
        try {
            val f = Cover.MAX_SHIFT * sens / 100f
            val total = FPS * IMAGE_SECONDS
            for (i in 0 until total) {
                val phase = 2.0 * PI * i / total
                val nx = sin(phase).toFloat()
                val ny = (0.5 * sin(2.0 * phase)).toFloat()
                scene.draw(outW, outH, cw, ch, nx * f * 2f, ny * f * 2f, zoom, panX, panY, identity)
                egl.setPresentationTime(i * 1_000_000_000L / FPS)
                egl.swap()
                drain(false)
                onProgress((i + 1) * 100 / total)
            }
        } finally {
            scene.release()
        }
    }

    // ---------------- video ----------------

    private fun probeVideo(path: String): Triple<Int, Int, Long> {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            return Triple(w, h, durMs * 1000L)
        } finally {
            mmr.release()
        }
    }

    private fun renderVideo(
        egl: EglCore, srcPath: String, sens: Int, zoom: Float, panX: Float, panY: Float,
        outW: Int, outH: Int, onProgress: (Int) -> Unit
    ) {
        val (cw, ch, durationUs) = probeVideo(srcPath)
        val capUs = MAX_VIDEO_SECONDS * 1_000_000L
        val loopUs = if (durationUs in 1 until capUs) durationUs else capUs

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var scene: SceneDrawer? = null
        var st: SurfaceTexture? = null
        var decSurface: Surface? = null
        val cbThread = HandlerThread("frame-callback").also { it.start() }
        try {
            extractor.setDataSource(srcPath)
            var trackIdx = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    trackIdx = i
                    inFormat = f
                    break
                }
            }
            if (trackIdx < 0 || inFormat == null) throw IOException("No video track found")
            extractor.selectTrack(trackIdx)

            val sd = SceneDrawer.video()
            scene = sd
            val texture = SurfaceTexture(sd.fgTex)
            st = texture
            val frameSignal = Semaphore(0)
            texture.setOnFrameAvailableListener(
                SurfaceTexture.OnFrameAvailableListener { frameSignal.release() },
                Handler(cbThread.looper)
            )
            val dsurface = Surface(texture)
            decSurface = dsurface

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inFormat, dsurface, null, 0)
            dec.start()

            val f = Cover.MAX_SHIFT * sens / 100f
            val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            val baseUs = extractor.sampleTime.coerceAtLeast(0L)
            val decInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                var progressed = false

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx) ?: throw IOException("Decoder buffer missing")
                        val size = extractor.readSampleData(buf, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts - baseUs > loopUs) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    progressed = true
                    val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val render = decInfo.size != 0
                    dec.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        if (!frameSignal.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                            throw IOException("Timed out waiting for a video frame")
                        }
                        texture.updateTexImage()
                        texture.getTransformMatrix(stMatrix)

                        val relUs = (decInfo.presentationTimeUs - baseUs).coerceAtLeast(0L)
                        val phase = 2.0 * PI * relUs.toDouble() / loopUs.toDouble()
                        val nx = sin(phase).toFloat()
                        val ny = (0.5 * sin(2.0 * phase)).toFloat()
                        sd.draw(outW, outH, cw, ch, nx * f * 2f, ny * f * 2f, zoom, panX, panY, stMatrix)
                        egl.setPresentationTime(relUs * 1000L)
                        egl.swap()
                        drain(false)
                        onProgress(min(100L, relUs * 100L / loopUs).toInt())
                    }
                    if (eos) outputDone = true
                } else if (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    progressed = true // format / buffers changed
                }

                if (progressed) idle = 0 else idle++
                if (idle > 400) throw IOException("Video decoding stalled")
            }
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
            }
            try {
                decoder?.release()
            } catch (e: Exception) {
            }
            try {
                st?.setOnFrameAvailableListener(null)
                st?.release()
            } catch (e: Exception) {
            }
            try {
                decSurface?.release()
            } catch (e: Exception) {
            }
            try {
                scene?.release()
            } catch (e: Exception) {
            }
            try {
                extractor.release()
            } catch (e: Exception) {
            }
            cbThread.quitSafely()
        }
    }

    // ---------------- encoder output ----------------

    private fun drain(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        if (endOfStream) enc.signalEndOfInputStream()
        var idleTries = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, TIMEOUT_US)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                idleTries++
                if (idleTries > 500) throw IOException("Encoder timed out")
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw IllegalStateException("Format changed twice")
                trackIndex = mux.addTrack(enc.outputFormat)
                mux.start()
                muxerStarted = true
            } else if (idx >= 0) {
                idleTries = 0
                val buf = enc.getOutputBuffer(idx) ?: throw IOException("Encoder buffer missing")
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (info.size != 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    mux.writeSampleData(trackIndex, buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    companion object {
        private const val FPS = 30
        private const val IMAGE_SECONDS = 8
        private const val MAX_VIDEO_SECONDS = 15
        private const val TIMEOUT_US = 10_000L
    }
}
