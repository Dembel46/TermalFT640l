package com.example.thermalcapture

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import java.io.File

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
        fun getThermalRotationDegrees(): Int
        fun onRecordingStateChanged(recording: Boolean)
        fun onRecordingTick(durationSec: Int)
        fun onRecordingError(message: String)
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 66L   // 15 FPS (быстро и стабильно)
        private const val LOOP_SEGMENT_SEC = 180    // Циклическая запись 3 минуты
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Готовые аппаратные буферы — 0 байт выделения памяти в цикле!
    private var opticalBmp: Bitmap? = null
    private var thermalBmp: Bitmap? = null

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
        val file = currentFile ?: run { stop(); return }
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
            if (isLoopMode && durationSec >= LOOP_SEGMENT_SEC) restartSegment()
            if (now >= nextTickMs) {
                durationSec++
                nextTickMs = now + 1000L
                mainHandler.post { ui.onRecordingTick(durationSec) }
            }

            // 1. Быстрый аппаратный захват тепловизора (напрямую из памяти видеокарты)
            val thermalTextureView = ui.thermalView
            var readyThermal: Bitmap? = null
            if (ui.isThermalStreaming() && thermalTextureView != null && thermalTextureView.isAvailable) {
                val tw = thermalTextureView.width
                val th = thermalTextureView.height
                if (tw > 0 && th > 0) {
                    if (thermalBmp == null || thermalBmp!!.width != tw || thermalBmp!!.height != th) {
                        thermalBmp?.recycle()
                        thermalBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    }
                    try {
                        thermalTextureView.getBitmap(thermalBmp!!)
                        readyThermal = thermalBmp
                    } catch (_: Exception) {}
                }
            }

            // 2. Быстрый аппаратный захват оптики (БЕЗ использования тяжелого PreviewView.bitmap)
            val opticalTextureView = ui.opticalView?.getChildAt(0) as? TextureView
            var readyOptical: Bitmap? = null
            if (opticalTextureView != null && opticalTextureView.isAvailable && ui.opticalView?.visibility == View.VISIBLE) {
                val ow = opticalTextureView.width
                val oh = opticalTextureView.height
                if (ow > 0 && oh > 0) {
                    if (opticalBmp == null || opticalBmp!!.width != ow || opticalBmp!!.height != oh) {
                        opticalBmp?.recycle()
                        opticalBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                    }
                    try {
                        opticalTextureView.getBitmap(opticalBmp!!)
                        readyOptical = opticalBmp
                    } catch (_: Exception) {}
                }
            }

            val (optNormX, optNormY) = ui.getOpticalOffsetNorm()

            videoRecorder.queueFrame(
                thermalBmp = readyThermal,
                opticalBmp = readyOptical,
                displayMode = ui.getDisplayMode(),
                isHybrid = ui.isHybridActive(),
                hybridAlpha = ui.getHybridAlpha(),
                opticalOffsetNormX = optNormX,
                opticalOffsetNormY = optNormY,
                targets = ui.getDetectedTargets(),
                opticalRotationDegrees = ui.getOpticalRotationDegrees(),
                thermalRotationDegrees = ui.getThermalRotationDegrees()
            )

            captureHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun release() {
        if (isRecording) stop()
    }
}
