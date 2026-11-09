package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import java.io.File

class InternalVideoRecorder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 25,
    private val bitRate: Int = 4_000_000
) {

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false

    private var recordThread: HandlerThread? = null
    private var recordHandler: Handler? = null
    var isRecording = false
        private set

    private val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas = Canvas(compositeBitmap)
    private val clearPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hybridPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val argbPixels = IntArray(width * height)
    private val yuvBuffer = ByteArray(width * height * 3 / 2)
    private var frameIndex = 0L

    fun start(
        outputFile: File,
        frameSupplier: () -> RecordFrameData?
    ): Boolean {
        if (isRecording) return false

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Ключевой кадр каждую секунду для регистратора
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
            videoTrackIndex = -1
            frameIndex = 0L
            isRecording = true

            recordThread = HandlerThread("InternalRecorderThread").apply { start() }
            recordHandler = Handler(recordThread!!.looper)

            val frameIntervalMs = 1000L / frameRate
            recordHandler?.post(object : Runnable {
                override fun run() {
                    if (!isRecording) return
                    encodeNextFrame(frameSupplier)
                    drainCodec(false)
                    recordHandler?.postDelayed(this, frameIntervalMs)
                }
            })

            return true
        } catch (e: Exception) {
            stop()
            return false
        }
    }

    private fun encodeNextFrame(frameSupplier: () -> RecordFrameData?) {
        val codec = mediaCodec ?: return
        val data = frameSupplier() ?: return

        // 1. Отрисовка композитного кадра ровно по текущему режиму приложения
        compositeCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)

        when {
            data.isHybrid -> {
                // ГИБРИД: Оптика на весь экран, тепловизор центрирован 4:3 с прозрачностью
                data.opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                }
                data.thermalBmp?.let {
                    val tH = (height * 0.9f).toInt()
                    val tW = (tH * 4f / 3f).toInt().coerceAtMost(width)
                    val left = (width - tW) / 2
                    val top = (height - tH) / 2
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(left, top, left + tW, top + tH)
                    hybridPaint.alpha = (data.hybridAlpha * 255).toInt().coerceIn(0, 255)
                    compositeCanvas.drawBitmap(it, src, dst, hybridPaint)
                }
            }
            data.displayMode == 0 -> {
                // 70 / 30: Тепловизор слева 70%, оптика справа 30%
                val splitX = (width * 0.7f).toInt()
                data.thermalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, splitX, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                }
                data.opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(splitX, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                }
            }
            data.displayMode == 1 -> {
                // ТЕПЛО 100%
                data.thermalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                }
            }
            data.displayMode == 2 -> {
                // ОПТИКА 100%
                data.opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                }
            }
        }

        // 2. Быстрый перевод RGB в NV12
        compositeBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        convertArgbToNv12(argbPixels, yuvBuffer, width, height)

        // 3. Отправка кадра в аппаратный энкодер
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(yuvBuffer)
            val pts = frameIndex * (1_000_000L / frameRate)
            frameIndex++
            codec.queueInputBuffer(inputIndex, 0, yuvBuffer.size, pts, 0)
        }
    }

    private fun drainCodec(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) continue
                val newFormat = codec.outputFormat
                videoTrackIndex = muxer.addTrack(newFormat)
                muxer.start()
                isMuxerStarted = true
            } else if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && isMuxerStarted && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        recordHandler?.removeCallbacksAndMessages(null)
        recordThread?.quitSafely()
        recordThread = null
        recordHandler = null

        try {
            drainCodec(true)
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {}
        mediaMuxer = null
        isMuxerStarted = false
        videoTrackIndex = -1
    }

    private fun convertArgbToNv12(argb: IntArray, nv12: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        var yIdx = 0
        var uvIdx = frameSize

        for (j in 0 until h) {
            val rowOffset = j * w
            val isUvRow = (j and 1) == 0

            for (i in 0 until w) {
                val p = argb[rowOffset + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[yIdx++] = (if (y > 255) 255 else if (y < 16) 16 else y).toByte()

                if (isUvRow && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIdx++] = (if (u > 255) 255 else if (u < 0) 0 else u).toByte()
                    nv12[uvIdx++] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()
                }
            }
        }
    }

    data class RecordFrameData(
        val thermalBmp: Bitmap?,
        val opticalBmp: Bitmap?,
        val displayMode: Int,
        val isHybrid: Boolean,
        val hybridAlpha: Float
    )
}
