package com.example.thermalcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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

    private var cameraHelper: ICameraHelper? = null

    // Аналитика и оповещения
    private val visionEngine = ThermalVisionEngine()
    private lateinit var alertManager: AlertManager

    // Фоновый поток для компьютерного зрения (не тормозит экран и видео)
    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private var isVisionRunning = true

    private var displayMode = 0

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

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        alertManager = AlertManager(this)

        initVisionThread()
        initViews()
        initButtons()
        checkPermissionsAndInit()
    }

    private fun initVisionThread() {
        visionThread = HandlerThread("ThermalVisionThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        // Цикл аналитики (10 раз в секунду в фоновом потоке)
        visionHandler.post(object : Runnable {
            override fun run() {
                if (!isVisionRunning) return

                var frameToAnalyze: Bitmap? = null

                // Если активен тепловизор — анализируем его поток, иначе берем штатную камеру
                runOnUiThread {
                    frameToAnalyze = phoneCameraView.bitmap
                }

                frameToAnalyze?.let { bmp ->
                    val targets = visionEngine.processFrame(bmp)
                    bmp.recycle()

                    // Проверка движения и подача сигнала тревоги
                    val hasMotion = targets.any { it.isMoving }
                    if (hasMotion) {
                        alertManager.triggerMotionAlert()
                    }

                    // Передаем цели на отрисовку в Overlay
                    runOnUiThread {
                        crosshairOverlay.detectedTargets = targets
                        if (targets.isNotEmpty()) {
                            val maxTarget = targets.maxByOrNull { it.maxLuma }
                            tvStatus.text = String.format("Целей: %d | Пик: %s%.1f°C",
                                targets.size,
                                if ((maxTarget?.estimatedTemp ?: 0f) >= 0) "+" else "",
                                maxTarget?.estimatedTemp ?: 0f
                            )
                        } else {
                            tvStatus.text = "Поиск тепловых целей: АКТИВЕН"
                        }
                    }
                }

                visionHandler.postDelayed(this, 100)
            }
        })
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
        cameraView.setAspectRatio(720, 576)

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
            displayMode = (displayMode + 1) % 3
            applyDisplayMode(displayMode)
        }

        btnCalibrate.setOnClickListener {
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "ПРИЦЕЛ: ТЯНИ"
                btnCalibrate.setBackgroundColor(0xAAFF8800.toInt())
            } else {
                btnCalibrate.text = "ПРИЦЕЛ: ФИКС"
                btnCalibrate.setBackgroundColor(0xFF444444.toInt())
            }
        }

        // Переключение звуковой тревоги
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

        // Переключение светового стробоскопа вспышкой
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

        // Диалог быстрой 2-точечной калибровки температуры
        btnCalibTemp.setOnClickListener {
            showTemperatureCalibrationDialog()
        }
    }

    private fun showTemperatureCalibrationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val etTemp1 = EditText(this).apply {
            hint = "Темп. 1 опорной точки (°C) [напр: 0]"
            setText("0")
        }
        val etTemp2 = EditText(this).apply {
            hint = "Темп. 2 опорной точки (°C) [напр: 60]"
            setText("60")
        }

        layout.addView(etTemp1)
        layout.addView(etTemp2)

        AlertDialog.Builder(this)
            .setTitle("Калибровка температуры")
            .setMessage("Наведите камеру/тепловизор на опорные объекты с известной температурой:")
            .setView(layout)
            .setPositiveButton("Применить") { _, _ ->
                val t1 = etTemp1.text.toString().toFloatOrNull() ?: 0f
                val t2 = etTemp2.text.toString().toFloatOrNull() ?: 60f
                // Калибровка по базовым опорным точкам (Luma 30 -> t1, Luma 220 -> t2)
                visionEngine.calibrate(30, t1, 220, t2)
                Toast.makeText(this, "Калибровка температуры сохранена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyDisplayMode(mode: Int) {
        val set = ConstraintSet()
        set.clone(rootLayout)

        when (mode) {
            0 -> {
                btnMode.text = "РЕЖИМ: 50/50"
                phoneCameraView.visibility = View.VISIBLE
                set.connect(R.id.svUvcCamera, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                set.connect(R.id.svUvcCamera, ConstraintSet.END, R.id.pvPhoneCamera, ConstraintSet.START)
                set.constrainWidth(R.id.svUvcCamera, 0)
                set.constrainHeight(R.id.svUvcCamera, ConstraintSet.MATCH_CONSTRAINT)

                set.connect(R.id.pvPhoneCamera, ConstraintSet.START, R.id.svUvcCamera, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 0)
                set.constrainHeight(R.id.pvPhoneCamera, ConstraintSet.MATCH_CONSTRAINT)
            }
            1 -> {
                btnMode.text = "РЕЖИМ: PiP"
                phoneCameraView.visibility = View.VISIBLE
                set.connect(R.id.svUvcCamera, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                set.connect(R.id.svUvcCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.constrainWidth(R.id.svUvcCamera, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(R.id.svUvcCamera, ConstraintSet.MATCH_CONSTRAINT)

                set.clear(R.id.pvPhoneCamera, ConstraintSet.START)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(R.id.pvPhoneCamera, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.clear(R.id.pvPhoneCamera, ConstraintSet.BOTTOM)
                set.constrainWidth(R.id.pvPhoneCamera, 400)
                set.constrainHeight(R.id.pvPhoneCamera, 300)
            }
            2 -> {
                btnMode.text = "РЕЖИМ: ТЕПЛО"
                phoneCameraView.visibility = View.GONE
                set.connect(R.id.svUvcCamera, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                set.connect(R.id.svUvcCamera, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.constrainWidth(R.id.svUvcCamera, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(R.id.svUvcCamera, ConstraintSet.MATCH_CONSTRAINT)
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
                Toast.makeText(this, "Ошибка камеры: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initCameraHelper() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "Подключение к плате: ${device.deviceName}..." }
                    selectDevice(device)
                }

                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                    runOnUiThread { tvStatus.text = "Плата открыта. Запуск потока..." }
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "FT640L: Поток активен" }
                    startPreview()
                    cameraView.holder.surface?.let { surface ->
                        if (surface.isValid) {
                            addSurface(surface, false)
                        }
                    }
                }

                override fun onCameraClose(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "Тепловизор закрыт" }
                }

                override fun onDeviceClose(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "Плата отключена" }
                }

                override fun onDetach(device: UsbDevice) {
                    runOnUiThread { tvStatus.text = "Плата захвата извлечена" }
                }

                override fun onCancel(device: UsbDevice) {
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
