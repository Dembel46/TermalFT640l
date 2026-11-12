package com.example.thermalcapture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class AlertManager(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
        } catch (_: Exception) {}
    }

    /**
     * ✅ 4.4: можно вызывать из любого потока.
     * Если мы не на main — перекидываем на main-looper.
     */
    fun triggerMotionAlert() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { triggerMotionAlertInternal() }
            return
        }
        triggerMotionAlertInternal()
    }

    private fun triggerMotionAlertInternal() {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) return
        lastAlertTime = now

        if (isSoundEnabled) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            } catch (_: Exception) {}
        }

        if (isFlashEnabled) {
            val id = torchCameraId ?: return
            try {
                cameraManager.setTorchMode(id, true)
                handler.postDelayed({
                    try {
                        cameraManager.setTorchMode(id, false)
                    } catch (_: Exception) {}
                }, 120)
            } catch (_: Exception) {}
        }
    }

    fun release() {
        // Гарантируем, что release выполнится на main
        val doRelease = {
            try { toneGenerator?.release() } catch (_: Exception) {}
            toneGenerator = null
            val id = torchCameraId
            if (id != null) {
                try { cameraManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
            handler.removeCallbacksAndMessages(null)
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(doRelease)
        } else {
            doRelease()
        }
    }
}
