package com.example.thermalcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.widget.AspectRatioSurfaceView

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraContainer: LinearLayout
    private lateinit var cameraView: AspectRatioSurfaceView
    private lateinit var phoneCameraView: PreviewView
    private lateinit var crosshairOverlay: CrosshairView
    private lateinit var tvStatus: TextView
    private lateinit var btnMode: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnSound: Button
    private lateinit var btnFlash: Button
    private lateinit var btnResolution: Button
    private lateinit var btnTracking: Button
    private lateinit var btnThermalFullscreen: Button

    private var cameraHelper: ICameraHelper? = null

    private val visionEngine = ThermalVisionEngine()
    private lateinit var alertManager: AlertManager

    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisionRunning = true
    private var isProcessingFrame = false

    private var isTrackingEnabled = true
    private var isThermalStreaming = false

    private val thermalAnalyzeBmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

    // Режимы: 0 - 70/30 (по умолчанию), 1 - ТЕПЛО 100%, 2 - ОПТИКА 100%
    private var displayMode = 0
    private var isThermalFullscreen = false

    // Стандартное недеформированное соотношение аналогового CVBS
    private var thermalAspectW = 4
    private var thermalAspectH = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        cameraContainer = findViewById(R.id.cameraContainer)
        cameraView = findViewById(R.id.svUvcCamera)
        phoneCameraView = findViewById(R.id.pvPhoneCamera)
        crosshairOverlay = findViewById(R.id.crosshairOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        btnMode = findViewById(R.id.btnMode)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnSound = findViewById(R.id.btnSound)
        btnFlash = findViewById(R.id.btnFlash)
        btnResolution = findViewById(R.id.btnResolution)
        btnTracking = findViewById(R.id.btnTracking)
        btnThermalFullscreen = findViewById(R.id.btnThermalFullscreen)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        // Загрузка сохраненных координат юстировки
        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        crosshairOverlay.opticalOffsetX = prefs.getFloat("opticalOffsetX", 0f)
        crosshairOverlay.opticalOffsetY = prefs.getFloat("opticalOffsetY", 0f)

        alertManager = AlertManager(this)

        initThermalResolution(4, 3)
        initVisionPipeline()
        initViews()
        initButtons()
        checkPermissionsAndInit()

        // По умолчанию 70/30
        rootLayout.post {
            setSplitRatio(0.7f, 0.3f, "70/30")
        }
    }

    private fun initThermalResolution(w: Int, h: Int) {
        thermalAspectW = w
        thermalAspectH = h
        cameraView.setAspectRatio(thermalAspectW, thermalAspectH)
        btnResolution.text = "" + w + ":" + h
    }

    private fun initVisionPipeline() {
        visionThread = HandlerThread("VisionProcessingThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isVisionRunning) return

                if (!isProcessingFrame && isTrackingEnabled) {
                    val isThermalActive = isThermalStreaming && cameraView.width > 0 && cameraView.holder.surface?.isValid == true

                    if (isThermalActive) {
                        val frame = cameraView.holder.surfaceFrame
                        val srcRect = Rect(0, 0, frame.width(), frame.height())
                        try {
                            PixelCopy.request(cameraView, srcRect, thermalAnalyzeBmp, { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    val copy = thermalAnalyzeBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    dispatchFrameToVision(copy)
                                } else {
                                    isProcessingFrame = false
                                }
                            }, mainHandler)
                        } catch (e: Exception) {
                            isProcessingFrame = false
                        }
                    }
                }

                mainHandler.postDelayed(this, 90)
            }
        })
    }

    private fun dispatchFrameToVision(bmp: Bitmap) {
        isProcessingFrame = true
        visionHandler.post {
            try {
                val targets = if (isTrackingEnabled) visionEngine.processFrame(bmp) else emptyList()
                bmp.recycle()

                val hasMotion = targets.any { it.type == TargetType.MOVING }
                if (hasMotion && isTrackingEnabled) {
                    alertManager.triggerMotionAlert()
                }

                mainHandler.post {
                    crosshairOverlay.detectedTargets = targets
                    crosshairOverlay.invalidate()

                    if (!isTrackingEnabled) {
                        tvStatus.text = "Трекинг: ВЫКЛЮЧЕН"
                    } else if (targets.isNotEmpty()) {
                        val movingCount = targets.count { it.type == TargetType.MOVING }
                        tvStatus.text = "Целей: " + targets.size + " (Движение: " + movingCount + ")"
                    } else {
                        tvStatus.text = "Поиск целей: АКТИВЕН"
                    }
                }
            } catch (e: Exception) {
            } finally {
                isProcessingFrame = false
            }
        }
    }

    private fun checkPermissionsAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            startPhoneCamera()
            initCameraHelper()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPhoneCamera()
            initCameraHelper()
        }
    }

    private fun initViews() {
        cameraView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraHelper?.addSurface(holder.surface, false)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                updateOverlayBounds()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.surface)
            }
        })

        phoneCameraView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOverlayBounds()
        }
    }

    private fun updateOverlayBounds() {
        val thermalRect = RectF(
            cameraView.x, cameraView.y,
            cameraView.x + cameraView.width, cameraView.y + cameraView.height
        )
        val opticalRect = RectF(
            phoneCameraView.x, phoneCameraView.y,
            phoneCameraView.x + phoneCameraView.width, phoneCameraView.y + phoneCameraView.height
        )

        crosshairOverlay.thermalRect = thermalRect
        crosshairOverlay.opticalRect = opticalRect
        crosshairOverlay.invalidate()
    }

    private fun initButtons() {
        btnTracking.setOnClickListener {
            isTrackingEnabled = !isTrackingEnabled
            if (isTrackingEnabled) {
                btnTracking.text = "ТРЕК: ВКЛ"
                btnTracking.setBackgroundColor(0xFF008800.toInt())
            } else {
                btnTracking.text = "ТРЕК: ВЫКЛ"
                btnTracking.setBackgroundColor(0xFF444444.toInt())
                crosshairOverlay.detectedTargets = emptyList()
                crosshairOverlay.invalidate()
                tvStatus.text = "Трекинг: ВЫКЛЮЧЕН"
            }
        }

        // Кнопка "Во весь экран"
        btnThermalFullscreen.setOnClickListener {
            if (!isThermalFullscreen) {
                isThermalFullscreen = true
                btnThermalFullscreen.text = "⤡"
                setSplitRatio(1.0f, 0.0f, "ТЕПЛО 100%")
            } else {
                isThermalFullscreen = false
                btnThermalFullscreen.text = "⛶"
                setSplitRatio(0.7f, 0.3f, "70/30")
            }
        }

        // Переключение режимов: 70/30 -> ТЕПЛО 100% -> ОПТИКА 100%
        btnMode.setOnClickListener {
            displayMode = (displayMode + 1) % 3
            isThermalFullscreen = (displayMode == 1)
            btnThermalFullscreen.text = if (isThermalFullscreen) "⤡" else "⛶"

            when (displayMode) {
                0 -> setSplitRatio(0.7f, 0.3f, "70/30")
                1 -> setSplitRatio(1.0f, 0.0f, "ТЕПЛО 100%")
                2 -> setSplitRatio(0.0f, 1.0f, "ОПТИКА 100%")
            }
        }

        btnResolution.setOnClickListener {
            showResolutionDialog()
        }

        // Кнопка ЮСТИРОВКА
        btnCalibrate.setOnClickListener {
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "СОХРАНИТЬ"
                btnCalibrate.setBackgroundColor(0xFFCC0000.toInt())
            } else {
                btnCalibrate.text = "ЮСТИРОВКА"
                btnCalibrate.setBackgroundColor(0xFF444444.toInt())

                // Сохранение в память
                val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putFloat("opticalOffsetX", crosshairOverlay.opticalOffsetX)
                    .putFloat("opticalOffsetY", crosshairOverlay.opticalOffsetY)
                    .apply()

                Toast.makeText(this, "Позиция юстировки сохранена", Toast.LENGTH_SHORT).show()
            }
            crosshairOverlay.invalidate()
        }

        btnSound.setOnClickListener {
            alertManager.isSoundEnabled = !alertManager.isSoundEnabled
            if (alertManager.isSoundEnabled) {
                btnSound.text = "ЗВУК: ВКЛ"
                btnSound.setBackgroundColor(0xFF008800.toInt())
            } else {
                btnSound.text = "ЗВУК: ВЫКЛ"
                btnSound.setBackgroundColor(0xFF444444.toInt())
            }
        }

        btnFlash.setOnClickListener {
            alertManager.isFlashEnabled = !alertManager.isFlashEnabled
            if (alertManager.isFlashEnabled) {
                btnFlash.text = "СТРОБ: ВКЛ"
                btnFlash.setBackgroundColor(0xFFFF8800.toInt())
            } else {
                btnFlash.text = "СТРОБ: ВЫКЛ"
                btnFlash.setBackgroundColor(0xFF444444.toInt())
            }
        }
    }

    /**
     * Переключение пропорций: 70/30, 100% Тепло, 100% Оптика
     */
    private fun setSplitRatio(weightThermal: Float, weightOptical: Float, modeLabel: String) {
        val lpT = cameraView.layoutParams as LinearLayout.LayoutParams
        val lpO = phoneCameraView.layoutParams as LinearLayout.LayoutParams

        lpT.weight = weightThermal
        lpO.weight = weightOptical

        cameraView.visibility = if (weightThermal > 0f) View.VISIBLE else View.GONE
        phoneCameraView.visibility = if (weightOptical > 0f) View.VISIBLE else View.GONE

        cameraView.layoutParams = lpT
        phoneCameraView.layoutParams = lpO

        btnMode.text = modeLabel

        cameraContainer.requestLayout()
        rootLayout.post { updateOverlayBounds() }
    }

    private fun showResolutionDialog() {
        val resolutions = arrayOf(
            "4 : 3 (Стандарт аналогового видео)",
            "5 : 4 (640 x 512 FT640L)",
            "16 : 9 (Широкоформатный)"
        )
        val sizes = arrayOf(
            Pair(4, 3),
            Pair(5, 4),
            Pair(16, 9)
        )

        AlertDialog.Builder(this)
            .setTitle("Пропорции тепловизора:")
            .setItems(resolutions) { _, which ->
                val chosen = sizes[which]
                initThermalResolution(chosen.first, chosen.second)
                Toast.makeText(this, "Установлено: " + resolutions[which], Toast.LENGTH_SHORT).show()
                rootLayout.post { updateOverlayBounds() }
            }
            .show()
    }

    private fun startPhoneCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(phoneCameraView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (exc: Exception) {
                Toast.makeText(this, "Ошибка камеры: " + (exc.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initCameraHelper() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "Подключение к плате: " + device.deviceName }
                    selectDevice(device)
                }

                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                    runOnUiThread { tvStatus.text = "Плата открыта. Запуск потока..." }
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice) {
                    isThermalStreaming = true
                    runOnUiThread { tvStatus.text = "FT640L: Поток активен" }
                    startPreview()
                    cameraView.holder.surface?.let { surface ->
                        if (surface.isValid) {
                            addSurface(surface, false)
                        }
                    }
                }

                override fun onCameraClose(device: UsbDevice) {
                    isThermalStreaming = false
                    runOnUiThread { tvStatus.text = "Тепловизор закрыт" }
                }

                override fun onDeviceClose(device: UsbDevice) {
                    isThermalStreaming = false
                    runOnUiThread { tvStatus.text = "Плата отключена" }
                }

                override fun onDetach(device: UsbDevice) {
                    isThermalStreaming = false
                    runOnUiThread { tvStatus.text = "Плата захвата извлечена" }
                }

                override fun onCancel(device: UsbDevice) {
                    isThermalStreaming = false
                    runOnUiThread { tvStatus.text = "Подключение отменено" }
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isVisionRunning = false
        visionThread.quitSafely()
        alertManager.release()
        cameraHelper?.release()
    }
}
