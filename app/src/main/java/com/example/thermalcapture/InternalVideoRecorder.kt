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
import android.os.Bundle
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class InternalVideoRecorder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 20,
    private val bitRate: Int = 4_000_000
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var frameIndex = 0L
    
    @Volatile var isRecording = false
    
    private val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas = Canvas(compositeBitmap)
    private val clearPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    
    private val yuvBuffer = ByteArray(width * height * 3 / 2)

    fun start(outputFile: File): Boolean {
        try {
            outputFile.parentFile?.mkdirs()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
            frameIndex = 0L
            isRecording = true
            return true
        } catch (e: Exception) { return false }
    }

    fun processFrame(thermalBmp: Bitmap?, opticalBmp: Bitmap?, displayMode: Int) {
        if (!isRecording) return
        
        compositeCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)

        // Логика отрисовки: используем Rect(left, top, right, bottom) для предотвращения сплющивания
        when (displayMode) {
            0 -> { // 70/30
                val split = (width * 0.7f).toInt()
                thermalBmp?.let { compositeCanvas.drawBitmap(it, null, Rect(0, 0, split, height), bitmapPaint) }
                opticalBmp?.let { compositeCanvas.drawBitmap(it, null, Rect(split, 0, width, height), bitmapPaint) }
            }
            1 -> { // ТЕПЛО 100%
                thermalBmp?.let { compositeCanvas.drawBitmap(it, null, Rect(0, 0, width, height), bitmapPaint) }
            }
            2 -> { // ОПТИКА 100%
                opticalBmp?.let { compositeCanvas.drawBitmap(it, null, Rect(0, 0, width, height), bitmapPaint) }
            }
        }

        val argbPixels = IntArray(width * height)
        compositeBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        
        val codec = mediaCodec ?: return
        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            convertArgbToNv12(argbPixels, yuvBuffer, width, height)
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(yuvBuffer)
            codec.queueInputBuffer(inputIndex, 0, yuvBuffer.size, frameIndex * 50_000, 0)
            frameIndex++
        }
        drainCodec()
    }

    private fun drainCodec() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val info = MediaCodec.BufferInfo()
        var idx = codec.dequeueOutputBuffer(info, 0)
        while (idx >= 0) {
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                isMuxerStarted = true
            } else if (idx >= 0 && isMuxerStarted) {
                muxer.writeSampleData(videoTrackIndex, codec.getOutputBuffer(idx)!!, info)
            }
            codec.releaseOutputBuffer(idx, false)
            idx = codec.dequeueOutputBuffer(info, 0)
        }
    }

    fun stop() {
        isRecording = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaMuxer?.stop()
            mediaMuxer?.release()
        } catch (e: Exception) {}
    }

    private fun convertArgbToNv12(argb: IntArray, nv12: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        var yIdx = 0
        var uvIdx = frameSize
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[yIdx++] = y.toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIdx++] = u.toByte()
                    nv12[uvIdx++] = v.toByte()
                }
            }
        }
    }
}
