package com.example.thermalcapture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class AlertManager(context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var torchCameraId: String? = null

    var isSoundEnabled: Boolean = true
    var isFlashEnabled: Boolean = false

    private var lastAlertTime = 0L
    private val alertCooldownMs = 1200L

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 85)
            torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {}
    }

    fun triggerMotionAlert() {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) return
        lastAlertTime = now

        if (isSoundEnabled) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            } catch (e: Exception) {}
        }

        if (isFlashEnabled && torchCameraId != null) {
            try {
                cameraManager.setTorchMode(torchCameraId!!, true)
                handler.postDelayed({
                    try {
                        cameraManager.setTorchMode(torchCameraId!!, false)
                    } catch (e: Exception) {}
                }, 120)
            } catch (e: Exception) {}
        }
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
        if (torchCameraId != null) {
            try {
                cameraManager.setTorchMode(torchCameraId!!, false)
            } catch (e: Exception) {}
        }
    }
}
