package com.example.thermalcapture

import android.os.Handler
import android.os.HandlerThread
import androidx.appcompat.app.AppCompatActivity

class RecordingController(
    private val activity: AppCompatActivity,
    private val storageManager: StorageManager,
    private val videoRecorder: InternalVideoRecorder,
    private val ui: MainActivity.RecordUi
) {
    private var captureHandler: Handler? = null
    private var captureThread: HandlerThread? = null

    fun start() {
        val file = storageManager.makeTempMovieFile()
        if (videoRecorder.start(file)) {
            captureThread = HandlerThread("RecordThread").apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            captureHandler?.post(frameLoop)
        }
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!videoRecorder.isRecording) return
            // Аппаратный захват (TextureView.bitmap)
            val tBmp = ui.thermalView?.bitmap
            val oBmp = ui.opticalView?.bitmap
            videoRecorder.processFrame(tBmp, oBmp, ui.getDisplayMode(), ui.getDetectedTargets())
            captureHandler?.postDelayed(this, 50)
        }
    }

    fun stop() {
        videoRecorder.stop()
        captureThread?.quitSafely()
    }
}
