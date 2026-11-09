package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
        private const val FRAME_INTERVAL_MS = 50L   // 20 FPS
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

    private var lastOpticalFrame: Bitmap? = null

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
        lastOpticalFrame = null
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
        lastOpticalFrame = null

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
