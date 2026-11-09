package com.example.thermalcapture

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import java.io.File

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

    /** Для циклического режима: закрыть текущий сегмент и открыть новый без остановки UI. */
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

            // Тик каждые 1000 мс — обновление индикатора
            if (now >= nextTickMs) {
                durationSec++
                nextTickMs = now + 1000L
                mainHandler.post { ui.onRecordingTick(durationSec) }
            }

            val thermalView = ui.thermalView
            val opticalView = ui.opticalView

            val tBmp: Bitmap? = if (ui.isThermalStreaming() && thermalView?.isAvailable == true) {
                if (thermalView.getBitmap(thermalBmp)) thermalBmp else null
            } else null

            val oBmp: Bitmap? = if (opticalView?.visibility == View.VISIBLE) {
                // PreviewView.getBitmap() должен вызываться на main. Переносим задачу туда.
                var result: Bitmap? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        val b = opticalView.bitmap
                        if (b != null) {
                            // Копируем в наш буфер, чтобы не держать внутренний
                            val src = android.graphics.Rect(0, 0, b.width, b.height)
                            val dst = android.graphics.Rect(0, 0, opticalBmp.width, opticalBmp.height)
                            val c = android.graphics.Canvas(opticalBmp)
                            c.drawBitmap(b, src, dst, null)
                            result = opticalBmp
                        }
                    } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
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
