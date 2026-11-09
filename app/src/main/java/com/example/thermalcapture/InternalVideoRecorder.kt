package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import kotlin.math.roundToInt

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
    private var hasWrittenKeyFrame = false
    private var sampleCount = 0

    private var recordThread: HandlerThread? = null
    private var recordHandler: Handler? = null

    @Volatile
    var isRecording = false
        private set

    @Volatile
    private var isBusyEncoding = false

    private val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas = Canvas(compositeBitmap)
    private val clearPaint = Paint().apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hybridPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Кисти для впечатывания прицелов и рамок целей в видеозапись
    private val reticlePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val targetBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val argbPixels = IntArray(width * height)
    private val yuvBuffer = ByteArray(width * height * 3 / 2)

    // Реальные таймкоды для исключения ускоренного воспроизведения
    private var startPtsUs = -1L
    private var lastPtsUs = 0L

    fun start(outputFile: File): Boolean {
        if (isRecording) return false

        try {
            outputFile.parentFile?.mkdirs()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec

            try {
                val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
                codec.setParameters(params)
            } catch (e: Exception) {}

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
            videoTrackIndex = -1
            hasWrittenKeyFrame = false
            sampleCount = 0
            startPtsUs = -1L
            lastPtsUs = 0L
            isBusyEncoding = false
            isRecording = true

            recordThread = HandlerThread("InternalEncoderThread").apply { start() }
            recordHandler = Handler(recordThread!!.looper)

            return true
        } catch (t: Throwable) {
            stop(null)
            return false
        }
    }

    fun queueFrame(
        thermalBmp: Bitmap?,
        opticalBmp: Bitmap?,
        displayMode: Int,
        isHybrid: Boolean,
        hybridAlpha: Float,
        opticalOffsetNormX: Float,
        opticalOffsetNormY: Float,
        targets: List<DetectedTarget>
    ) {
        if (!isRecording || isBusyEncoding) return

        isBusyEncoding = true
        recordHandler?.post {
            try {
                processCompositeFrame(
                    thermalBmp, opticalBmp, displayMode, isHybrid, hybridAlpha,
                    opticalOffsetNormX, opticalOffsetNormY, targets
                )
                drainCodec(false)
            } catch (t: Throwable) {
            } finally {
                isBusyEncoding = false
            }
        }
    }

    private fun processCompositeFrame(
        thermalBmp: Bitmap?,
        opticalBmp: Bitmap?,
        displayMode: Int,
        isHybrid: Boolean,
        hybridAlpha: Float,
        opticalOffsetNormX: Float,
        opticalOffsetNormY: Float,
        targets: List<DetectedTarget>
    ) {
        val codec = mediaCodec ?: return

        compositeCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)

        val thermalArea = RectF()
        val opticalArea = RectF()

        when {
            isHybrid -> {
                opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                    opticalArea.set(0f, 0f, width.toFloat(), height.toFloat())
                }
                thermalBmp?.let {
                    val tH = (height * 0.9f).toInt()
                    val tW = (tH * 4f / 3f).toInt().coerceAtMost(width)
                    val left = (width - tW) / 2f
                    val top = (height - tH) / 2f
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(left.toInt(), top.toInt(), (left + tW).toInt(), (top + tH).toInt())
                    hybridPaint.alpha = (hybridAlpha * 255).toInt().coerceIn(0, 255)
                    compositeCanvas.drawBitmap(it, src, dst, hybridPaint)
                    thermalArea.set(left, top, left + tW, top + tH)
                }
            }
            displayMode == 0 -> {
                // 70 / 30
                val splitX = (width * 0.7f).toInt()
                thermalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, splitX, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                    thermalArea.set(0f, 0f, splitX.toFloat(), height.toFloat())
                }
                opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(splitX, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                    opticalArea.set(splitX.toFloat(), 0f, width.toFloat(), height.toFloat())
                }
            }
            displayMode == 1 -> {
                // ТЕПЛО 100%
                thermalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                    thermalArea.set(0f, 0f, width.toFloat(), height.toFloat())
                }
            }
            displayMode == 2 -> {
                // ОПТИКА 100%
                opticalBmp?.let {
                    val src = Rect(0, 0, it.width, it.height)
                    val dst = Rect(0, 0, width, height)
                    compositeCanvas.drawBitmap(it, src, dst, bitmapPaint)
                    opticalArea.set(0f, 0f, width.toFloat(), height.toFloat())
                }
            }
        }

        // Впечатывание рамок трекера на тепловизоре
        if (thermalArea.width() > 0 && targets.isNotEmpty()) {
            for (t in targets) {
                targetBoxPaint.color = when (t.type) {
                    TargetType.MOVING -> Color.RED
                    TargetType.HUMAN_VEHICLE -> Color.parseColor("#FFE082")
                    TargetType.OTHER -> Color.parseColor("#A5D6A7")
                }

                if (t.silhouettePoints.isNotEmpty() && (t.widthPx > 9 || t.heightPx > 9)) {
                    val path = Path()
                    var first = true
                    for (pt in t.silhouettePoints) {
                        val px = thermalArea.left + pt.x * thermalArea.width()
                        val py = thermalArea.top + pt.y * thermalArea.height()
                        if (first) {
                            path.moveTo(px, py)
                            first = false
                        } else {
                            path.lineTo(px, py)
                        }
                    }
                    path.close()
                    compositeCanvas.drawPath(path, targetBoxPaint)
                } else {
                    val cx = thermalArea.left + t.bounds.centerX() * thermalArea.width()
                    val cy = thermalArea.top + t.bounds.centerY() * thermalArea.height()
                    val half = 8f
                    compositeCanvas.drawRect(RectF(cx - half, cy - half, cx + half, cy + half), targetBoxPaint)
                }
            }
        }

        // Впечатывание прицела тепловизора
        if (thermalArea.width() > 0) {
            val cx = thermalArea.centerX()
            val cy = thermalArea.centerY()
            drawReticleMarks(compositeCanvas, cx, cy, 38f)
        }

        // Впечатывание прицела оптики (с учетом юстировки)
        if (opticalArea.width() > 0 && !isHybrid) {
            val cx = opticalArea.centerX() + opticalOffsetNormX * opticalArea.width()
            val cy = opticalArea.centerY() + opticalOffsetNormY * opticalArea.height()
            drawReticleMarks(compositeCanvas, cx, cy, 38f)
        }

        compositeBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        convertArgbToNv12(argbPixels, yuvBuffer, width, height)

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                val bytesToCopy = minOf(yuvBuffer.size, inputBuffer.capacity())
                inputBuffer.put(yuvBuffer, 0, bytesToCopy)

                // ТОЧНЫЙ РАСЧЕТ ВРЕМЕНИ PTS ПО СИСТЕМНЫМ ЧАСАМ (1.0x скорость воспроизведения)
                val nowUs = System.nanoTime() / 1000L
                if (startPtsUs < 0L) {
                    startPtsUs = nowUs
                }
                var pts = nowUs - startPtsUs
                if (pts <= lastPtsUs) {
                    pts = lastPtsUs + 1000L
                }
                lastPtsUs = pts

                codec.queueInputBuffer(inputIndex, 0, bytesToCopy, pts, 0)
            }
        }
    }

    private fun drawReticleMarks(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val gap = 6f
        canvas.drawLine(cx - size, cy, cx - gap, cy, reticlePaint)
        canvas.drawLine(cx + gap, cy, cx + size, cy, reticlePaint)
        canvas.drawLine(cx, cy - size, cx, cy - gap, reticlePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + size, reticlePaint)
        canvas.drawCircle(cx, cy, 2.5f, reticlePaint)
    }

    private fun drainCodec(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var retryCount = 0

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++retryCount > 10) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!isMuxerStarted) {
                    videoTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    isMuxerStarted = true
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && isMuxerStarted && bufferInfo.size > 0) {
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    if (!hasWrittenKeyFrame) {
                        if (isKeyFrame) hasWrittenKeyFrame = true
                    }

                    if (hasWrittenKeyFrame) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        sampleCount++
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun stop(onCompleted: (() -> Unit)?) {
        if (!isRecording) {
            onCompleted?.invoke()
            return
        }
        isRecording = false

        val handler = recordHandler
        val thread = recordThread

        if (handler != null && thread != null) {
            handler.post {
                try {
                    mediaCodec?.let { codec ->
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val pts = lastPtsUs + 1000L
                            codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                    drainCodec(true)
                } catch (e: Exception) {}

                try {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                } catch (e: Exception) {}
                mediaCodec = null

                try {
                    if (isMuxerStarted && sampleCount > 0) {
                        mediaMuxer?.stop()
                    }
                    mediaMuxer?.release()
                } catch (e: Exception) {}
                mediaMuxer = null
                isMuxerStarted = false
                videoTrackIndex = -1

                thread.quitSafely()
                recordThread = null
                recordHandler = null

                onCompleted?.invoke()
            }
        } else {
            onCompleted?.invoke()
        }
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
}
