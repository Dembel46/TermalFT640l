package com.example.thermalcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var thermalContainer: FrameLayout
    private lateinit var opticalContainer: FrameLayout
    private lateinit var cameraView: TextureView
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
    private lateinit var btnHybrid: Button

    private var cameraHelper: ICameraHelper? = null
    private var thermalSurface: Surface? = null

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

    // Режимы: 0 - 70/30, 1 - ТЕПЛО 100%, 2 - ОПТИКА 100%
    private var displayMode = 0
    private var isThermalFullscreen = false
    private var isHybridModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        cameraContainer = findViewById(R.id.cameraContainer)
        thermalContainer = findViewById(R.id.thermalContainer)
        opticalContainer = findViewById(R.id.opticalContainer)
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
        btnHybrid = findViewById(R.id.btnHybrid)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        // Загрузка сохраненных координат юстировки
        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        crosshairOverlay.opticalOffsetX = prefs.getFloat("opticalOffsetX", 0f)
        crosshairOverlay.opticalOffsetY = prefs.getFloat("opticalOffsetY", 0f)

        // Связка вертикального регулятора прозрачности гибрида
        crosshairOverlay.onHybridAlphaChanged = { alpha ->
            if (isHybridModeActive) {
                cameraView.alpha = alpha
            }
        }

        alertManager = AlertManager(this)

        initVisionPipeline()
        initViews()
        initButtons()
        checkPermissionsAndInit()

        // Старт строго в режиме 70/30
        rootLayout.post {
            setSplitRatio(0.7f, 0.3f, "70/30")
        }
    }

    private fun initVisionPipeline() {
        visionThread = HandlerThread("VisionProcessingThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isVisionRunning) return

                if (!isProcessingFrame && isTrackingEnabled) {
                    val isThermalActive = isThermalStreaming && cameraView.width > 0 && cameraView.isAvailable

                    if (isThermalActive) {
                        try {
                            val bmp = cameraView.getBitmap(thermalAnalyzeBmp)
                            if (bmp != null) {
                                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                dispatchFrameToVision(copy)
                            } else {
                                isProcessingFrame = false
                            }
                        } catch (e: Exception) {
                            isProcessingFrame = false
                        }
                    } else {
                        if (crosshairOverlay.detectedTargets.isNotEmpty()) {
                            crosshairOverlay.detectedTargets = emptyList()
                            crosshairOverlay.invalidate()
                        }
                        tvStatus.text = "Ожидание подключения тепловизора..."
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
        cameraView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(surfaceTexture)
                thermalSurface = surface
                cameraHelper?.addSurface(surface, false)
                updateOverlayBounds()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                updateOverlayBounds()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                thermalSurface?.let { cameraHelper?.removeSurface(it) }
                thermalSurface?.release()
                thermalSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        phoneCameraView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOverlayBounds()
        }
    }

    /**
     * Расчет 4:3 кропа тепловизора строго по центру
     */
    private fun adjustThermalViewBounds() {
        val containerW = thermalContainer.width
        val containerH = thermalContainer.height
        if (containerW <= 0 || containerH <= 0) return

        val targetRatio = 4f / 3f
        val fitW: Int
        val fitH: Int

        if (isHybridModeActive) {
            // В режиме ГИБРИД: тепловизор занимает 85% высоты экрана с пропорцией 4:3 по центру
            fitH = (containerH * 0.9f).toInt()
            fitW = (fitH * targetRatio).toInt().coerceAtMost(containerW)
        } else {
            val containerRatio = containerW.toFloat() / containerH.toFloat()
            if (containerRatio > targetRatio) {
                fitH = containerH
                fitW = (containerH * targetRatio).toInt().coerceAtMost(containerW)
            } else {
                fitW = containerW
                fitH = ((fitW * 3f) / 4f).toInt().coerceAtMost(containerH)
            }
        }

        val lp = cameraView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != fitW || lp.height != fitH) {
            lp.width = fitW
            lp.height = fitH
            lp.gravity = Gravity.CENTER
            cameraView.layoutParams = lp
        }
    }

    private fun updateOverlayBounds() {
        adjustThermalViewBounds()

        if (thermalContainer.visibility == View.VISIBLE && cameraView.width > 0 && cameraView.height > 0) {
            val locT = IntArray(2)
            cameraView.getLocationOnScreen(locT)
            val locR = IntArray(2)
            rootLayout.getLocationOnScreen(locR)

            val leftT = (locT[0] - locR[0]).toFloat()
            val topT = (locT[1] - locR[1]).toFloat()

            crosshairOverlay.thermalRect = RectF(
                leftT, topT,
                leftT + cameraView.width, topT + cameraView.height
            )
        } else {
            crosshairOverlay.thermalRect = RectF(0f, 0f, 0f, 0f)
        }

        if (opticalContainer.visibility == View.VISIBLE && phoneCameraView.width > 0 && phoneCameraView.height > 0) {
            val locO = IntArray(2)
            phoneCameraView.getLocationOnScreen(locO)
            val locR = IntArray(2)
            rootLayout.getLocationOnScreen(locR)

            val leftO = (locO[0] - locR[0]).toFloat()
            val topO = (locO[1] - locR[1]).toFloat()

            crosshairOverlay.opticalRect = RectF(
                leftO, topO,
                leftO + phoneCameraView.width, topO + phoneCameraView.height
            )
        } else {
            crosshairOverlay.opticalRect = RectF(0f, 0f, 0f, 0f)
        }

        crosshairOverlay.invalidate()
    }

    private fun initButtons() {
        // КНОПКА "ГИБРИД"
        btnHybrid.setOnClickListener {
            isHybridModeActive = !isHybridModeActive
            crosshairOverlay.isHybridMode = isHybridModeActive

            if (isHybridModeActive) {
                btnHybrid.text = "ГИБРИД: ВКЛ"
                btnHybrid.setBackgroundColor(0xFF0099CC.toInt())
                applyHybridMode()
                Toast.makeText(this, "Проведите пальцем вверх/вниз слева для настройки прозрачности", Toast.LENGTH_SHORT).show()
            } else {
                btnHybrid.text = "ГИБРИД"
                btnHybrid.setBackgroundColor(0xFF444444.toInt())
                cameraView.alpha = 1.0f
                setSplitRatio(0.7f, 0.3f, "70/30")
            }
        }

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

        btnThermalFullscreen.setOnClickListener {
            if (isHybridModeActive) {
                isHybridModeActive = false
                crosshairOverlay.isHybridMode = false
                btnHybrid.text = "ГИБРИД"
                btnHybrid.setBackgroundColor(0xFF444444.toInt())
                cameraView.alpha = 1.0f
            }

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

        btnMode.setOnClickListener {
            if (isHybridModeActive) {
                isHybridModeActive = false
                crosshairOverlay.isHybridMode = false
                btnHybrid.text = "ГИБРИД"
                btnHybrid.setBackgroundColor(0xFF444444.toInt())
                cameraView.alpha = 1.0f
            }

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
                Toast.makeText(this, "Перемещайте оптический прицел пальцем", Toast.LENGTH_SHORT).show()
            } else {
                btnCalibrate.text = "ЮСТИРОВКА"
                btnCalibrate.setBackgroundColor(0xFF444444.toInt())

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
     * Режим ГИБРИД: оптика на весь экран, тепловизор по центру с настраиваемой прозрачностью
     */
    private fun applyHybridMode() {
        val screenW = cameraContainer.width
        val screenH = cameraContainer.height

        // Оптика во весь экран на заднем плане
        val lpO = opticalContainer.layoutParams as FrameLayout.LayoutParams
        lpO.width = FrameLayout.LayoutParams.MATCH_PARENT
        lpO.height = FrameLayout.LayoutParams.MATCH_PARENT
        lpO.gravity = Gravity.NO_GRAVITY
        opticalContainer.visibility = View.VISIBLE
        opticalContainer.layoutParams = lpO

        // Тепловизор поверх оптики по центру
        val lpT = thermalContainer.layoutParams as FrameLayout.LayoutParams
        lpT.width = FrameLayout.LayoutParams.MATCH_PARENT
        lpT.height = FrameLayout.LayoutParams.MATCH_PARENT
        lpT.gravity = Gravity.CENTER
        thermalContainer.visibility = View.VISIBLE
        thermalContainer.layoutParams = lpT

        cameraView.alpha = crosshairOverlay.hybridAlpha

        cameraContainer.requestLayout()
        rootLayout.post { updateOverlayBounds() }
    }

    /**
     * Режимы с разделением экрана (70/30, 100% Тепло, 100% Оптика)
     */
    private fun setSplitRatio(weightThermal: Float, weightOptical: Float, modeLabel: String) {
        cameraView.alpha = 1.0f

        val screenW = cameraContainer.width
        val screenH = cameraContainer.height
        if (screenW <= 0 || screenH <= 0) {
            rootLayout.post { setSplitRatio(weightThermal, weightOptical, modeLabel) }
            return
        }

        val lpT = thermalContainer.layoutParams as FrameLayout.LayoutParams
        val lpO = opticalContainer.layoutParams as FrameLayout.LayoutParams

        if (weightThermal > 0f && weightOptical > 0f) {
            // Режим 70/30: тепловизор слева, оптика справа (без наложений)
            val thermalW = (screenW * weightThermal).toInt()
            val opticalW = screenW - thermalW

            lpT.width = thermalW
            lpT.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.gravity = Gravity.START
            thermalContainer.visibility = View.VISIBLE

            lpO.width = opticalW
            lpO.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpO.gravity = Gravity.END
            opticalContainer.visibility = View.VISIBLE
        } else if (weightThermal > 0f) {
            // ТЕПЛО 100%
            lpT.width = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.gravity = Gravity.CENTER
            thermalContainer.visibility = View.VISIBLE
            opticalContainer.visibility = View.GONE
        } else {
            // ОПТИКА 100%
            lpO.width = FrameLayout.LayoutParams.MATCH_PARENT
            lpO.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpO.gravity = Gravity.CENTER
            opticalContainer.visibility = View.VISIBLE
            thermalContainer.visibility = View.GONE
        }

        thermalContainer.layoutParams = lpT
        opticalContainer.layoutParams = lpO

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

        AlertDialog.Builder(this)
            .setTitle("Пропорции тепловизора:")
            .setItems(resolutions) { _, which ->
                btnResolution.text = when(which) {
                    0 -> "4:3"
                    1 -> "5:4"
                    else -> "16:9"
                }
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
                    thermalSurface?.let { surface ->
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
        thermalSurface?.release()
        cameraHelper?.release()
    }
}
