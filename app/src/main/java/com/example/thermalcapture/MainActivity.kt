package com.example.thermalcapture

import android.Manifest
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
    private lateinit var btnResolution: Button

    private var cameraHelper: ICameraHelper? = null

    private val visionEngine = ThermalVisionEngine()
    private lateinit var alertManager: AlertManager

    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisionRunning = true
    private var isProcessingFrame = false

    private var isThermalStreaming = false
    private var forceAnalysisSource = 0 // 0 - Авто, 1 - Оптика, 2 - Тепло

    private val thermalAnalyzeBmp = Bitmap.createBitmap(320, 256, Bitmap.Config.ARGB_8888)

    // Режимы экрана: 0 - 50/50, 1 - 70/30, 2 - 30/70, 3 - PiP, 4 - Тепло 100%, 5 - Оптика 100%
    private var displayMode = 0
    private var thermalWidth = 640
    private var thermalHeight = 512

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
        btnResolution = findViewById(R.id.btnResolution)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        alertManager = AlertManager(this)

        initThermalResolution(640, 512)
        initVisionPipeline()
        initViews()
        initButtons()
        checkPermissionsAndInit()
    }

    private fun initThermalResolution(w: Int, h: Int) {
        thermalWidth = w
        thermalHeight = h
        cameraView.setAspectRatio(thermalWidth, thermalHeight)
        btnResolution.text = "" + w + "x" + h
    }

    private fun initVisionPipeline() {
        visionThread = HandlerThread("VisionProcessingThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isVisionRunning) return

                if (!isProcessingFrame) {
                    val useThermal = when (forceAnalysisSource) {
                        1 -> false
                        2 -> true
                        else -> isThermalStreaming
                    }

                    if (useThermal && cameraView.width > 0 && cameraView.holder.surface?.isValid == true) {
                        val frame = cameraView.holder.surfaceFrame
                        val srcRect = Rect(0, 0, frame.width(), frame.height())
                        try {
                            PixelCopy.request(cameraView, srcRect, thermalAnalyzeBmp, { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    val copy = thermalAnalyzeBmp.copy(Bitmap.Config.ARGB_8888, false)
                                    dispatchFrameToVision(copy, true, "Тепловизор")
                                } else {
                                    analyzeOpticalFallback()
                                }
                            }, mainHandler)
                        } catch (e: Exception) {
                            analyzeOpticalFallback()
                        }
                    } else {
                        analyzeOpticalFallback()
                    }
                }

                mainHandler.postDelayed(this, 90)
            }
        })
    }

    private fun analyzeOpticalFallback() {
        val opticalBmp = phoneCameraView.bitmap
        if (opticalBmp != null) {
            dispatchFrameToVision(opticalBmp, false, "Оптика")
        }
    }

    private fun dispatchFrameToVision(bmp: Bitmap, isThermal: Boolean, sourceName: String) {
        isProcessingFrame = true
        visionHandler.post {
            try {
                val targets = visionEngine.processFrame(bmp, isThermal)
                bmp.recycle()

                val hasMotion = targets.any { it.isMoving }
                if (hasMotion) {
                    alertManager.triggerMotionAlert()
                }

                mainHandler.post {
                    crosshairOverlay.detectedTargets = targets
                    crosshairOverlay.invalidate()

                    if (targets.isNotEmpty()) {
                        val maxTarget = targets.maxByOrNull { it.contrastScore }
                        val score = maxTarget?.contrastScore?.toInt() ?: 0
                        tvStatus.text = "Движущихся целей: " + targets.size + " (Контраст: " + score + ") [" + sourceName + "]"
                    } else {
                        tvStatus.text = "Поиск движущихся целей: АКТИВЕН [" + sourceName + "]"
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
        btnMode.setOnClickListener {
            displayMode = (displayMode + 1) % 6
            applyDisplayMode(displayMode)
        }

        btnResolution.setOnClickListener {
            showResolutionDialog()
        }

        btnCalibrate.setOnClickListener {
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "ТЯНИ: ВКЛ"
                btnCalibrate.setBackgroundColor(0xAAFF8800.toInt())
            } else {
                btnCalibrate.text = "ТЯНИ: ФИКС"
                btnCalibrate.setBackgroundColor(0xFF444444.toInt())
            }
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

        tvStatus.setOnClickListener {
            forceAnalysisSource = (forceAnalysisSource + 1) % 3
            val name = when (forceAnalysisSource) {
                1 -> "ПРИНУДИТЕЛЬНО ОПТИКА"
                2 -> "ПРИНУДИТЕЛЬНО ТЕПЛОВИЗОР"
                else -> "АВТОВЫБОР"
            }
            Toast.makeText(this, "Анализ: " + name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResolutionDialog() {
        val resolutions = arrayOf(
            "640 x 512 (FT640L Native)",
            "720 x 576 (PAL CVBS)",
            "720 x 480 (NTSC CVBS)",
            "640 x 480 (VGA 4:3)"
        )
        val sizes = arrayOf(
            Pair(640, 512),
            Pair(720, 576),
            Pair(720, 480),
            Pair(640, 480)
        )

        AlertDialog.Builder(this)
            .setTitle("Разрешение тепловизора:")
            .setItems(resolutions) { _, which ->
                val chosen = sizes[which]
                initThermalResolution(chosen.first, chosen.second)
                Toast.makeText(this, "Установлено: " + resolutions[which], Toast.LENGTH_SHORT).show()
                rootLayout.post { updateOverlayBounds() }
            }
            .show()
    }

    private fun applyDisplayMode(mode: Int) {
        val set = ConstraintSet()
        set.clone(rootLayout)

        when (mode) {
            0 -> { // 50 / 50
                btnMode.text = "50/50"
                cameraView.visibility = View.VISIBLE
                phoneCameraView.visibility = View.VISIBLE
                set.setGuidelinePercent(R.id.splitGuideline, 0.5f)

                set.connect(R.id.pvPhoneCamera, ConstraintSet.START, R.id.splitGuideline, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 0)
                set.constrainHeight(R.id.pvPhoneCamera, 0)
            }
            1 -> { // 70 / 30
                btnMode.text = "70/30"
                cameraView.visibility = View.VISIBLE
                phoneCameraView.visibility = View.VISIBLE
                set.setGuidelinePercent(R.id.splitGuideline, 0.7f)

                set.connect(R.id.pvPhoneCamera, ConstraintSet.START, R.id.splitGuideline, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 0)
                set.constrainHeight(R.id.pvPhoneCamera, 0)
            }
            2 -> { // 30 / 70
                btnMode.text = "30/70"
                cameraView.visibility = View.VISIBLE
                phoneCameraView.visibility = View.VISIBLE
                set.setGuidelinePercent(R.id.splitGuideline, 0.3f)

                set.connect(R.id.pvPhoneCamera, ConstraintSet.START, R.id.splitGuideline, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 0)
                set.constrainHeight(R.id.pvPhoneCamera, 0)
            }
            3 -> { // PiP
                btnMode.text = "PiP"
                cameraView.visibility = View.VISIBLE
                phoneCameraView.visibility = View.VISIBLE
                set.setGuidelinePercent(R.id.splitGuideline, 1.0f)

                set.clear(R.id.pvPhoneCamera, ConstraintSet.START)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.clear(R.id.pvPhoneCamera, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 360)
                set.constrainHeight(R.id.pvPhoneCamera, 270)
            }
            4 -> { // ТЕПЛО 100%
                btnMode.text = "ТЕПЛО 100%"
                cameraView.visibility = View.VISIBLE
                phoneCameraView.visibility = View.GONE
                set.setGuidelinePercent(R.id.splitGuideline, 1.0f)
            }
            5 -> { // ОПТИКА 100%
                btnMode.text = "ОПТИКА 100%"
                cameraView.visibility = View.GONE
                phoneCameraView.visibility = View.VISIBLE
                set.setGuidelinePercent(R.id.splitGuideline, 0.0f)

                set.connect(R.id.pvPhoneCamera, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 0)
                set.constrainHeight(R.id.pvPhoneCamera, 0)
            }
        }

        set.applyTo(rootLayout)
        rootLayout.post { updateOverlayBounds() }
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
                    runOnUiThread { tvStatus.text = "FT640L (" + thermalWidth + "x" + thermalHeight + "): Поток активен" }
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
