package com.example.thermalcapture

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
import kotlin.math.atan2

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraContainer: LinearLayout
    private lateinit var thermalContainer: FrameLayout
    private lateinit var opticalContainer: FrameLayout
    private lateinit var cameraView: SurfaceView
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
    private lateinit var btnAgc: Button
    private lateinit var btnZoom: Button
    private lateinit var btnContrastType: Button

    private var cameraHelper: ICameraHelper? = null

    // Контроллер протокола FT640L
    private val serialController = FT640SerialController()

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

    // Режимы экрана: 0 - 70/30 (по умолчанию), 1 - ТЕПЛО 100%, 2 - ОПТИКА 100%
    private var displayMode = 0
    private var isThermalFullscreen = false

    // Настройки тепловизора FT640L
    private var isAutoAgc = true
    private var currentPaletteIdx = 0
    private var currentZoomIndex = 0
    private var currentContinuousZoom = 1.0f

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
        btnAgc = findViewById(R.id.btnAgc)
        btnZoom = findViewById(R.id.btnZoom)
        btnContrastType = findViewById(R.id.btnContrastType)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        crosshairOverlay.opticalOffsetX = prefs.getFloat("opticalOffsetX", 0f)
        crosshairOverlay.opticalOffsetY = prefs.getFloat("opticalOffsetY", 0f)

        alertManager = AlertManager(this)

        initVisionPipeline()
        initViews()
        initButtons()
        setupZoomGestures()
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
                        } catch (_: Exception) {
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
            } catch (_: Exception) {
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

    private fun adjustThermalViewBounds() {
        val containerW = thermalContainer.width
        val containerH = thermalContainer.height
        if (containerW <= 0 || containerH <= 0) return

        val targetRatio = 4f / 3f
        val containerRatio = containerW.toFloat() / containerH.toFloat()

        val fitW: Int
        val fitH: Int

        if (containerRatio > targetRatio) {
            fitH = containerH
            fitW = (containerH * targetRatio).toInt().coerceAtMost(containerW)
        } else {
            fitW = containerW
            fitH = (containerW / targetRatio).toInt().coerceAtMost(containerH)
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
        // 1. ТРИГГЕР АВТО/РУЧНОЙ КОНТРАСТ (справа сверху)
        btnAgc.setOnClickListener {
            isAutoAgc = !isAutoAgc
            if (isAutoAgc) {
                btnAgc.text = "АВТО КОНТРАСТ"
                btnAgc.setBackgroundColor(0xFF008800.toInt())
                serialController.setAutoContrast(true)
                Toast.makeText(this, "Контраст: АВТО", Toast.LENGTH_SHORT).show()
            } else {
                btnAgc.text = "РУЧН КОНТРАСТ"
                btnAgc.setBackgroundColor(0xFFCC7000.toInt())
                serialController.setAutoContrast(false)
                Toast.makeText(this, "Контраст: РУЧНОЙ (Зафиксирован)", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. ВЫБОР ТИПА КОНТРАСТА (ПАЛИТРЫ)
        btnContrastType.setOnClickListener {
            showPaletteDialog()
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
     * Меню выбора палитры (Тип контраста)
     */
    private fun showPaletteDialog() {
        val palettes = arrayOf(
            "Горячий белый (White Hot)",
            "Горячий черный (Black Hot)",
            "Железо (Ironbow)",
            "Радуга (Rainbow)",
            "Горячий красный (Warning Red)"
        )
        val paletteCodes = intArrayOf(0x00, 0x01, 0x04, 0x02, 0x0B)
        val paletteShortNames = arrayOf("БЕЛЫЙ", "ЧЕРНЫЙ", "ЖЕЛЕЗО", "РАДУГА", "КРАСНЫЙ")

        AlertDialog.Builder(this)
            .setTitle("Тип контраста (Палитра):")
            .setItems(palettes) { _, which ->
                currentPaletteIdx = which
                btnContrastType.text = "КОНТРАСТ: " + paletteShortNames[which]
                serialController.setPalette(paletteCodes[which])
                Toast.makeText(this, "Палитра: " + palettes[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Управление зумом: короткий клик (0x->1x->2x->3x) и зажатие кругового нониуса
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomGestures() {
        val longPressHandler = Handler(Looper.getMainLooper())
        var isLongPressTriggered = false
        var downTime = 0L
        var lastTouchAngle = 0.0

        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            crosshairOverlay.isZoomDialVisible = true
            crosshairOverlay.currentZoomValue = currentContinuousZoom
            crosshairOverlay.invalidate()
        }

        btnZoom.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    isLongPressTriggered = false
                    longPressHandler.postDelayed(longPressRunnable, 400) // Зажатие через 400 мс
                    lastTouchAngle = 0.0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLongPressTriggered) {
                        // Расчет угла жеста относительно центра экрана
                        val cx = rootLayout.width / 2.0
                        val cy = rootLayout.height / 2.0
                        val currentAngle = Math.toDegrees(atan2(event.rawY - cy, event.rawX - cx))

                        if (lastTouchAngle != 0.0) {
                            var delta = currentAngle - lastTouchAngle
                            if (delta > 180) delta -= 360
                            if (delta < -180) delta += 360

                            // По часовой стрелке -> zoom++, против -> zoom--
                            val zoomStep = (delta / 40.0).toFloat()
                            currentContinuousZoom = (currentContinuousZoom + zoomStep).coerceIn(1.0f, 4.0f)
                            crosshairOverlay.currentZoomValue = currentContinuousZoom
                            crosshairOverlay.invalidate()

                            applyVisualZoom(currentContinuousZoom)
                        }
                        lastTouchAngle = currentAngle
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressTriggered) {
                        // Отпускание после вращения нониуса
                        crosshairOverlay.isZoomDialVisible = false
                        crosshairOverlay.invalidate()
                        btnZoom.text = String.format("ЗУМ: %.1fx", currentContinuousZoom)
                        serialController.setElectronicZoom(currentContinuousZoom)
             } else {
                        // Короткий клик: переключение 0x -> 1x -> 2x -> 3x
                        if (System.currentTimeMillis() - downTime < 400) {
                            currentZoomIndex = (currentZoomIndex + 1) % 4
                            currentContinuousZoom = when (currentZoomIndex) {
                                0 -> 1.0f // Базовый 0x/1x
                                1 -> 2.0f
                                2 -> 3.0f
                                else -> 4.0f
                            }
                            btnZoom.text = "ЗУМ: " + currentZoomIndex + "x"
                            applyVisualZoom(currentContinuousZoom)
                            serialController.setElectronicZoom(currentContinuousZoom)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyVisualZoom(zoom: Float) {
        cameraView.scaleX = zoom
        cameraView.scaleY = zoom
    }

    private fun setSplitRatio(weightThermal: Float, weightOptical: Float, modeLabel: String) {
        val lpT = thermalContainer.layoutParams as LinearLayout.LayoutParams
        val lpO = opticalContainer.layoutParams as LinearLayout.LayoutParams

        lpT.width = 0
        lpO.width = 0
        lpT.weight = weightThermal
        lpO.weight = weightOptical

        thermalContainer.visibility = if (weightThermal > 0f) View.VISIBLE else View.GONE
        opticalContainer.visibility = if (weightOptical > 0f) View.VISIBLE else View.GONE

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
