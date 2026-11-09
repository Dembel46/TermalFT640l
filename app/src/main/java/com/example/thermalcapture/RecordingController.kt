package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Управляет циклом записи, захватывает кадры с обеих камер в фоновом потоке
 * и передаёт их в InternalVideoRecorder. Устраняет фризы UI.
 */
class RecordingController(
    private val activity: AppCompatActivity,
    private val storageManager: StorageManager,
    private val videoRecorder: InternalVideoRecorder,
    private val ui: Ui
) {

    interface Ui {
        val thermalView: TextureView?
        val opticalView: PreviewView?
        fun isThermalStreaming(): Boolean
        fun getDisplayMode(): Int
        fun isHybridActive(): Boolean
        fun getHybridAlpha(): Float
        fun getOpticalOffsetNorm(): Pair<Float, Float>
        fun getDetectedTargets(): List<DetectedTarget>
        fun getOpticalRotationDegrees(): Int
        fun onRecordingStateChanged(recording: Boolean)
        fun onRecordingTick(durationSec: Int)
        fun onRecordingError(message: String)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 40L // 25 FPS
        private const val LOOP_SEGMENT_SEC = 180
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val opticalBmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    private val thermalBmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    private val opticalCanvas = Canvas(opticalBmp)
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, opticalBmp.width, opticalBmp.height)

    @Volatile private var isRecording = false
    private var durationSec = 0
    private var isLoopMode = true
    private var currentFile: File? = null
    private var nextTickMs = 0L

    var isLoopRecording: Boolean
        get() = isLoopMode
        set(value) { isLoopMode = value }

    fun start() {
        if (isRecording) return

        currentFile = storageManager.makeTempMovieFile()
        val file = currentFile ?: return

        if (!videoRecorder.start(file)) {
            ui.onRecordingError("Не удалось запустить видеозапись")
            return
        }

        isRecording = true
        durationSec = 0
        nextTickMs = SystemClock.uptimeMillis() + 1000L
        ui.onRecordingStateChanged(true)

        captureThread = HandlerThread("RecordCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        captureHandler?.post(frameLoop)
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        val fileToExport = currentFile
        ui.onRecordingStateChanged(false)
        videoRecorder.stop {
            mainHandler.post {
                fileToExport?.let { storageManager.exportVideo(it) }
            }
        }
    }

    private fun restartSegment() {
        val oldFile = currentFile
        videoRecorder.stop {
            mainHandler.post { oldFile?.let { storageManager.exportVideo(it) } }
        }

        currentFile = storageManager.makeTempMovieFile()
        val file = currentFile ?: run {
            stop()
            return
        }
        if (!videoRecorder.start(file)) {
            ui.onRecordingError("Не удалось перезапустить запись")
            stop()
            return
        }
        durationSec = 0
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!isRecording) return

            val now = SystemClock.uptimeMillis()
            if (isLoopMode && durationSec >= LOOP_SEGMENT_SEC) {
                restartSegment()
            }

            if (now >= nextTickMs) {
                durationSec++
                nextTickMs = now + 1000L
                mainHandler.post { ui.onRecordingTick(durationSec) }
            }

            val thermalView = ui.thermalView
            val opticalView = ui.opticalView

            // Тепловизор: getBitmap(Bitmap) возвращает Bitmap? — проверяем на null
            val tBmp: Bitmap? = if (ui.isThermalStreaming() && thermalView != null && thermalView.isAvailable) {
                try {
                    val result = thermalView.getBitmap(thermalBmp)
                    if (result != null) thermalBmp else null
                } catch (_: Exception) {
                    null
                }
            } else null

            // Оптика: PreviewView.getBitmap() на main + CountDownLatch с таймаутом
            val oBmp: Bitmap? = if (opticalView != null && opticalView.visibility == View.VISIBLE) {
                var result: Bitmap? = null
                val latch = CountDownLatch(1)
                mainHandler.post {
                    try {
                        val b = opticalView.bitmap
                        if (b != null) {
                            srcRect.set(0, 0, b.width, b.height)
                            opticalCanvas.drawBitmap(b, srcRect, dstRect, null)
                            result = opticalBmp
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
                try {
                    latch.await(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
                result
            } else null

            val (optNormX, optNormY) = ui.getOpticalOffsetNorm()

            videoRecorder.queueFrame(
                thermalBmp = tBmp,
                opticalBmp = oBmp,
                displayMode = ui.getDisplayMode(),
                isHybrid = ui.isHybridActive(),
                hybridAlpha = ui.getHybridAlpha(),
                opticalOffsetNormX = optNormX,
                opticalOffsetNormY = optNormY,
                targets = ui.getDetectedTargets(),
                opticalRotationDegrees = ui.getOpticalRotationDegrees()
            )

            captureHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun release() {
        if (isRecording) stop()
    }
}
