package com.example.thermalcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.widget.EditText
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
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.widget.AspectRatioSurfaceView

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraView: AspectRatioSurfaceView
    private lateinit var phoneCameraView: PreviewView
    private lateinit var crosshairOverlay: CrosshairView
    private lateinit var tvStatus: TextView
    private lateinit var btnMode: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnSound: Button
    private lateinit var btnFlash: Button
    private lateinit var btnCalibTemp: Button
    private lateinit var btnRatio: Button
    private lateinit var btnRes: Button

    private var cameraHelper: ICameraHelper? = null

    private val visionEngine = ThermalVisionEngine()
    private lateinit var alertManager: AlertManager

    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisionRunning = true

    // 0 - Split, 1 - PiP, 2 - Только тепловизор
    private var displayMode = 0

    // Пропорции экрана в режиме СПЛИТ: 0: 50/50, 1: 70/30 (тепло больше), 2: 30/70, 3: 80/20
    private var splitRatioIndex = 0
    private val splitRatios = arrayOf(
        Pair(0.5f, 0.5f),
        Pair(0.7f, 0.3f),
        Pair(0.3f, 0.7f),
        Pair(0.8f, 0.2f)
    )
    private val splitRatioLabels = arrayOf("ЭКРАН: 50/50", "ЭКРАН: 70/30", "ЭКРАН: 30/70", "ЭКРАН: 80/20")

    // Буфер для захвата кадра тепловизора в машинном зрении
    private val visionThermalBitmap = Bitmap.createBitmap(160, 128, Bitmap.Config.ARGB_8888)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        cameraView = findViewById(R.id.svUvcCamera)
        phoneCameraView = findViewById(R.id.pvPhoneCamera)
        crosshairOverlay = findViewById(R.id.crosshairOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        btnMode = findViewById(R.id.btnMode)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnSound = findViewById(R.id.btnSound)
        btnFlash = findViewById(R.id.btnFlash)
        btnCalibTemp = findViewById(R.id.btnCalibTemp)
        btnRatio = findViewById(R.id.btnRatio)
        btnRes = findViewById(R.id.btnRes)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        alertManager = AlertManager(this)

        initViews()
        initButtons()
        initVisionPipeline()
        checkPermissionsAndInit()
    }

    private fun initViews() {
        // Устанавливаем родное разрешение сенсора FT640L: 640x512 (5:4)
        cameraView.setAspectRatio(640, 512)

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

    /**
     * Надежный конвейер машинного зрения: гарантированная передача кадра без null-ошибок
     */
    private fun initVisionPipeline() {
        visionThread = HandlerThread("VisionWorkerThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        val visionLoop = object : Runnable {
            override fun run() {
                if (!isVisionRunning) return

                val isThermalActive = cameraView.holder.surface?.isValid == true && cameraView.width > 0

                if (isThermalActive) {
                    // Захват кадра тепловизора через PixelCopy напрямую в фоновый поток
                    try {
                        PixelCopy.request(cameraView, visionThermalBitmap, { result ->
                            if (result == PixelCopy.SUCCESS) {
                                visionHandler.post {
                                    val targets = visionEngine.processFrame(visionThermalBitmap)
                                    handleVisionResults(targets, isThermal = true)
                                }
                            }
                        }, visionHandler)
                    } catch (e: Exception) {}
                } else {
                    // Если тепловизор не подключен — анализируем штатную камеру
                    val bmp = phoneCameraView.bitmap
                    if (bmp != null) {
                        visionHandler.post {
                            val targets = visionEngine.processFrame(bmp)
                            bmp.recycle()
                            handleVisionResults(targets, isThermal = false)
                        }
                    }
                }

                mainHandler.postDelayed(this, 100) // Анализ 10 раз в секунду
            }
        }

        mainHandler.postDelayed(visionLoop, 800)
    }

    private fun handleVisionResults(targets: List<DetectedTarget>, isThermal: Boolean) {
        val hasMotion = targets.any { it.isMoving }
        if (hasMotion) {
            runOnUiThread { alertManager.triggerMotionAlert() }
        }

        runOnUiThread {
            crosshairOverlay.isTargetFromThermal = isThermal
            crosshairOverlay.detectedTargets = targets
            crosshairOverlay.invalidate()

            if (targets.isNotEmpty()) {
                val maxTarget = targets.maxByOrNull { it.maxLuma }
                val sign = if ((maxTarget?.estimatedTemp ?: 0f) >= 0) "+" else ""
                tvStatus.text = String.format("Целей: %d | Пик: %s%.1f°C | %s",
                    targets.size,
                    sign,
                    maxTarget?.estimatedTemp ?: 0f,
                    if (isThermal) "FT640L" else "Оптика"
                )
            } else {
                tvStatus.text = if (isThermal) "FT640L (640x512): Поиск целей" else "Оптика: Поиск целей"
            }
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
        // Режимы экрана: Сплит -> PiP -> Только тепловизор
        btnMode.setOnClickListener {
            displayMode = (displayMode + 1) % 3
            applyDisplayMode(displayMode)
        }

        // Переключение пропорций экрана в режиме СПЛИТ (50/50 -> 70/30 -> 30/70 -> 80/20)
        btnRatio.setOnClickListener {
            splitRatioIndex = (splitRatioIndex + 1) % splitRatios.size
            btnRatio.text = splitRatioLabels[splitRatioIndex]
            applySplitRatio()
        }

        // Выбор разрешения для тепловизора FT640L
        btnRes.setOnClickListener {
            showResolutionDialog()
        }

        btnCalibrate.setOnClickListener {
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "ПРИЦЕЛ: ТЯНИ"
                btnCalibrate.setBackgroundColor(0xAAFF8800.toInt())
            } else {
                btnCalibrate.text = "ПРИЦЕЛ: ФИКС"
                btnCalibrate.setBackgroundColor(0xFF333333.toInt())
            }
        }

        btnSound.setOnClickListener {
            alertManager.isSoundEnabled = !alertManager.isSoundEnabled
            if (alertManager.isSoundEnabled) {
                btnSound.text = "ЗВУК: ВКЛ"
                btnSound.setBackgroundColor(0xFF008800.toInt())
            } else {
                btnSound.text = "ЗВУК: ВЫКЛ"
                btnSound.setBackgroundColor(0xFF333333.toInt())
            }
        }

        btnFlash.setOnClickListener {
            alertManager.isFlashEnabled = !alertManager.isFlashEnabled
            if (alertManager.isFlashEnabled) {
                btnFlash.text = "СТРОБ: ВКЛ"
                btnFlash.setBackgroundColor(0xFFFF8800.toInt())
            } else {
                btnFlash.text = "СТРОБ: ВЫКЛ"
                btn
