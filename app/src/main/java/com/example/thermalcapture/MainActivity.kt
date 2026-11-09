package com.example.thermalcapture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val actionUsbPermission = "com.example.thermalcapture.USB_SERIAL_PERMISSION"

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var thermalContainer: FrameLayout
    private lateinit var opticalContainer: FrameLayout
    private lateinit var cameraView: TextureView
    private lateinit var phoneCameraView: PreviewView
    private lateinit var crosshairOverlay: CrosshairView
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordIndicator: TextView

    // Левый выдвижной сайдбар
    private lateinit var leftSidebar: LinearLayout
    private lateinit var sidebarContent: LinearLayout
    private lateinit var btnToggleSidebar: Button
    private lateinit var btnMode: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnTracking: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnRecord: Button

    // Верхняя и нижняя панели
    private lateinit var btnSettings: Button
    private lateinit var btnThermalFullscreen: Button
    private lateinit var btnHybrid: Button
    private lateinit var btnZoom: Button
    private lateinit var btnContrastType: Button

    private var cameraHelper: ICameraHelper? = null
    private var thermalSurface: Surface? = null

    private val serialController = FT640SerialController()
    private val visionEngine = ThermalVisionEngine()
    private lateinit var alertManager: AlertManager

    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisionRunning = true
    private var isProcessingFrame = false

    private var isTrackingEnabled = false
    private var isThermalStreaming = false

    private val thermalAnalyzeBmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

    // Режимы: 0 - 70/30, 1 - ТЕПЛО 100%, 2 - ОПТИКА 100%
    private var displayMode = 0
    private var isThermalFullscreen = false
    private var isHybridModeActive = false
    private var isSidebarExpanded = true

    private var currentZoomIndex = 0
    private var thermalAspectW = 4
    private var thermalAspectH = 3

    // Автоскрытие сайдбара через 7 секунд
    private val sidebarHandler = Handler(Looper.getMainLooper())
    private val hideSidebarRunnable = Runnable { collapseSidebar() }

    // НАСТОЯЩИЙ ВНУТРЕННИЙ РЕКОРДЕР
    private val videoRecorder = InternalVideoRecorder(width = 1280, height = 720, frameRate = 25)
    private var isRecording = false
    private var isLoopRecordMode = true
    private var recordDurationSec = 0
    private var currentRecordFile: File? = null

    private val recordFrameLoopRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val tBmp = if (isThermalStreaming && cameraView.isAvailable) cameraView.bitmap else null
                val oBmp = if (phoneCameraView.visibility == View.VISIBLE) phoneCameraView.bitmap else null

                videoRecorder.queueFrame(
                    thermalBmp = tBmp,
                    opticalBmp = oBmp,
                    displayMode = displayMode,
                    isHybrid = isHybridModeActive,
                    hybridAlpha = crosshairOverlay.hybridAlpha
                )
                mainHandler.postDelayed(this, 40)
            }
        }
    }

    private val recordTimerHandler = Handler(Looper.getMainLooper())
    private val recordTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                recordDurationSec++
                val min = recordDurationSec / 60
                val sec = recordDurationSec % 60
                val modeLabel = if (isLoopRecordMode) "ЦИКЛ 3М" else "НЕПРЕРЫВ"
                tvRecordIndicator.text = String.format(Locale.US, "● REC [%s] %02d:%02d", modeLabel, min, sec)

                if (isLoopRecordMode && recordDurationSec >= 180) {
                    restartRecordSegment()
                }

                recordTimerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            if (serialController.connect(this@MainActivity, it)) {
                                Toast.makeText(this@MainActivity, "UART подключен: " + serialController.connectedDeviceName, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

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
        tvRecordIndicator = findViewById(R.id.tvRecordIndicator)

        leftSidebar = findViewById(R.id.leftSidebar)
        sidebarContent = findViewById(R.id.sidebarContent)
        btnToggleSidebar = findViewById(R.id.btnToggleSidebar)
        btnMode = findViewById(R.id.btnMode)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnTracking = findViewById(R.id.btnTracking)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnRecord = findViewById(R.id.btnRecord)

        btnSettings = findViewById(R.id.btnSettings)
        btnThermalFullscreen = findViewById(R.id.btnThermalFullscreen)
        btnHybrid = findViewById(R.id.btnHybrid)
        btnZoom = findViewById(R.id.btnZoom)
        btnContrastType = findViewById(R.id.btnContrastType)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        crosshairOverlay.opticalOffsetX = prefs.getFloat("opticalOffsetX", 0f)
        crosshairOverlay.opticalOffsetY = prefs.getFloat("opticalOffsetY", 0f)

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

        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        updateRecordUiState()

        rootLayout.post {
            setSplitRatio(0.7f, 0.3f, "70/30")
            resetSidebarTimer()
        }
    }

    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
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
                } else if (!isTrackingEnabled) {
                    if (crosshairOverlay.detectedTargets.isNotEmpty()) {
                        crosshairOverlay.detectedTargets = emptyList()
                        crosshairOverlay.invalidate()
                    }
                    tvStatus.text = "Трекинг: ВЫКЛЮЧЕН"
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
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
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

    private fun adjustThermalViewBounds() {
        val containerW = thermalContainer.width
        val containerH = thermalContainer.height
        if (containerW <= 0 || containerH <= 0) return

        val targetRatio = thermalAspectW.toFloat() / thermalAspectH.toFloat()
        val fitW: Int
        val fitH: Int

        if (isHybridModeActive) {
            fitH = (containerH * 0.9f).toInt()
            fitW = (fitH * targetRatio).toInt().coerceAtMost(containerW)
        } else {
            val containerRatio = containerW.toFloat() / containerH.toFloat()
            if (containerRatio > targetRatio) {
                fitH = containerH
                fitW = (containerH * targetRatio).toInt().coerceAtMost(containerW)
            } else {
                fitW = containerW
                fitH = ((fitW * (1f / targetRatio))).toInt().coerceAtMost(containerH)
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

    private fun resetSidebarTimer() {
        sidebarHandler.removeCallbacks(hideSidebarRunnable)
        if (isSidebarExpanded) {
            sidebarHandler.postDelayed(hideSidebarRunnable, 7000)
        }
    }

    private fun collapseSidebar() {
        isSidebarExpanded = false
        sidebarContent.visibility = View.GONE
        btnToggleSidebar.text = "▶"
        sidebarHandler.removeCallbacks(hideSidebarRunnable)
    }

    private fun expandSidebar() {
        isSidebarExpanded = true
        sidebarContent.visibility = View.VISIBLE
        btnToggleSidebar.text = "◀"
        resetSidebarTimer()
    }

    private fun initButtons() {
        btnToggleSidebar.setOnClickListener {
            if (isSidebarExpanded) collapseSidebar() else expandSidebar()
        }

        btnSettings.setOnClickListener {
            resetSidebarTimer()
            showSettingsDialog()
        }

        btnPhoto.setOnClickListener {
            resetSidebarTimer()
            ensureStorageConfigured {
                takeScreenshotPhoto()
            }
        }

        btnRecord.setOnClickListener {
            resetSidebarTimer()
            ensureStorageConfigured {
                toggleVideoRecording()
            }
        }

        btnRecord.setOnLongClickListener {
            resetSidebarTimer()
            showRecordModeDialog()
            true
        }

        btnHybrid.setOnClickListener {
            resetSidebarTimer()
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
            resetSidebarTimer()
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
            resetSidebarTimer()
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
            resetSidebarTimer()
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

        btnCalibrate.setOnClickListener {
            resetSidebarTimer()
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "СОХРАНИТЬ"
                btnCalibrate.setBackgroundColor(0xFFCC0000.toInt())
                Toast.makeText(this, "Перемещайте оптический прицел пальцем", Toast.LENGTH_SHORT).show()
            } else {
                btnCalibrate.text = "ЮСТИРОВКА"
                btnCalibrate.setBackgroundColor(0xFF333333.toInt())

                val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putFloat("opticalOffsetX", crosshairOverlay.opticalOffsetX)
                    .putFloat("opticalOffsetY", crosshairOverlay.opticalOffsetY)
                    .apply()

                Toast.makeText(this, "Позиция юстировки сохранена", Toast.LENGTH_SHORT).show()
            }
            crosshairOverlay.invalidate()
        }

        btnZoom.setOnClickListener {
            currentZoomIndex = (currentZoomIndex + 1) % 4
            val zoomFactor = when (currentZoomIndex) {
                0 -> 1.0f
                1 -> 2.0f
                2 -> 3.0f
                else -> 4.0f
            }
            btnZoom.text = "ЗУМ: " + currentZoomIndex + "x"
            cameraView.scaleX = zoomFactor
            cameraView.scaleY = zoomFactor
            serialController.setElectronicZoom(zoomFactor)
        }

        btnContrastType.setOnClickListener {
            showPaletteDialog()
        }
    }

    private fun showSettingsDialog() {
        val soundState = if (alertManager.isSoundEnabled) "ВКЛ" else "ВЫКЛ"
        val flashState = if (alertManager.isFlashEnabled) "ВКЛ" else "ВЫКЛ"

        val items = arrayOf(
            "Пропорции тепловизора (4:3, 5:4, 16:9)",
            "Звуковая тревога: $soundState",
            "Световой стробоскоп: $flashState",
            "USB - управление камерой",
            "Откалибровать шторку (NUC / Shutter)",
            "Повторный опрос USB устройств",
            "Папка сохранения фото и видео",
            "ⓘ Инструкция и описание"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙ Настройки прибора")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showResolutionDialog()
                    1 -> {
                        alertManager.isSoundEnabled = !alertManager.isSoundEnabled
                        Toast.makeText(this, "Звук: " + if (alertManager.isSoundEnabled) "ВКЛ" else "ВЫКЛ", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        alertManager.isFlashEnabled = !alertManager.isFlashEnabled
                        Toast.makeText(this, "Строб: " + if (alertManager.isFlashEnabled) "ВКЛ" else "ВЫКЛ", Toast.LENGTH_SHORT).show()
                    }
                    3 -> showUartSelectionDialog()
                    4 -> {
                        serialController.triggerShutter()
                        Toast.makeText(this, "Калибровка шторки отправлена", Toast.LENGTH_SHORT).show()
                    }
                    5 -> rescanUsbDevices()
                    6 -> showStorageChoiceDialog(null)
                    7 -> showHelpManualDialog()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showHelpManualDialog() {
        val message = """
            ТЕПЛОВИЗИОННЫЙ ПРИЦЕЛЬНЫЙ КОМПЛЕКС FT640L
            
            1. БОКОВОЙ ВЫДВИЖНОЙ БАР (СЛЕВА):
            • [◀ / ▶] — свернуть / открыть панель. Автоматически скрывается через 7 секунд покоя.
            • РЕЖИМ — переключение: 70/30 (тепло + оптика), 100% Тепловизор, 100% Оптика.
            • ЮСТИРОВКА — калибровка параллакса двух камер. Перемещайте красный прицел пальцем, нажмите "СОХРАНИТЬ".
            • ТРЕК — вкл/выкл компьютерного зрения. Цели >9x9 берутся в контур силуэта, ≤9x9 — в квадрат. Движущиеся цели подсвечиваются КРАСНЫМ на 3 секунды.
            • ФОТО 📷 — снимок экрана в текущем режиме отображения.
            • ЗАПИСЬ ⏺ — внутренний видеорегистратор (без запросов системы). Клик: старт/стоп. Зажатие: выбор циклического (по 3 мин) или непрерывного режима.

            2. ОПЕРАТИВНАЯ НИЖНЯЯ ПАНЕЛЬ:
            • ЗУМ — ступенчатое приближение 0x, 1x, 2x, 3x.
            • КОНТРАСТ — выбор палитр (Белый горячий, Черный горячий, Железо, Радуга, Красный).
            • ГИБРИД — наложение 4:3 тепловизора поверх полноэкранной дневной камеры. Проведите пальцем вверх/вниз у левого края экрана для изменения прозрачности.

            3. МЕНЮ НАСТРОЕК (ШЕСТЕРЕНКА ⚙):
            • Пропорции, звук, стробоскоп вспышкой, повторный опрос устройств и подключение UART управления камерой.
        """.trimIndent()

        val scrollView = ScrollView(this).apply {
            setPadding(40, 20, 40, 20)
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 12f
                setTextColor(0xFFDDDDDD.toInt())
                setLineSpacing(0f, 1.25f)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("ⓘ Инструкция по эксплуатации")
            .setView(scrollView)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun ensureStorageConfigured(onReady: () -> Unit) {
        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        val location = prefs.getString("storage_location", null)

        if (location == null) {
            showStorageChoiceDialog(onReady)
        } else {
            onReady()
        }
    }

    private fun showStorageChoiceDialog(onReady: (() -> Unit)?) {
        val options = arrayOf(
            "Штатные папки устройства (Галерея: DCIM / Movies)",
            "Своя папка в корне устройства (/ThermalCapture)"
        )

        AlertDialog.Builder(this)
            .setTitle("Куда сохранять фото и видео?")
            .setItems(options) { _, which ->
                val choice = if (which == 0) "standard" else "root"
                getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("storage_location", choice)
                    .apply()

                if (choice == "root" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {}
                    }
                }

                Toast.makeText(this, "Место сохранения: " + options[which], Toast.LENGTH_SHORT).show()
                onReady?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    private fun takeScreenshotPhoto() {
        val w = rootLayout.width
        val h = rootLayout.height
        if (w <= 0 || h <= 0) return

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    savePhotoOutput(bmp)
                } else {
                    bmp.recycle()
                    Toast.makeText(this, "Не удалось создать снимок", Toast.LENGTH_SHORT).show()
                }
            }, mainHandler)
        } catch (e: Exception) {
            bmp.recycle()
        }
    }

    private fun savePhotoOutput(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        val location = prefs.getString("storage_location", "standard")

        if (location == "root" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val rootDir = File(Environment.getExternalStorageDirectory(), "ThermalCapture/Photos").apply { mkdirs() }
            val file = File(rootDir, fileName)
            try {
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                Toast.makeText(this, "Фото сохранено: " + file.name, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения фото", Toast.LENGTH_SHORT).show()
            } finally {
                bitmap.recycle()
            }
        } else {
            // Сохранение через MediaStore (100% совместимо с Галереей без ошибок прав)
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ThermalCapture")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    Toast.makeText(this, "Фото сохранено в Галерею: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения в галерею", Toast.LENGTH_SHORT).show()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun toggleVideoRecording() {
        if (!isRecording) {
            startInternalVideoRecording()
        } else {
            stopVideoRecording()
        }
    }

    private fun startInternalVideoRecording() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        currentRecordFile = File(tempDir, "REC_$timeStamp.mp4")

        val started = videoRecorder.start(currentRecordFile!!)
        if (started) {
            isRecording = true
            recordDurationSec = 0
            updateRecordUiState()
            mainHandler.post(recordFrameLoopRunnable)
            recordTimerHandler.post(recordTimerRunnable)
        } else {
            Toast.makeText(this, "Не удалось запустить видеозапись", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartRecordSegment() {
        val oldFile = currentRecordFile
        videoRecorder.stop {
            oldFile?.let { exportFinishedVideo(it) }
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        currentRecordFile = File(tempDir, "REC_$timeStamp.mp4")

        val started = videoRecorder.start(currentRecordFile!!)
        if (started) {
            recordDurationSec = 0
        } else {
            stopVideoRecording()
        }
    }

    private fun stopVideoRecording() {
        isRecording = false
        mainHandler.removeCallbacks(recordFrameLoopRunnable)
        recordTimerHandler.removeCallbacks(recordTimerRunnable)

        btnRecord.text = "СОХРАНЕНИЕ..."
        btnRecord.setBackgroundColor(0xFFFFA000.toInt()) // Желтый цвет сохранения

        val fileToExport = currentRecordFile
        videoRecorder.stop {
            mainHandler.post {
                fileToExport?.let { exportFinishedVideo(it) }
                updateRecordUiState()
            }
        }
    }

    /**
     * Экспорт видеоролика в выбранную папку (MediaStore Галереи или корень устройства)
     */
    private fun exportFinishedVideo(sourceFile: File) {
        if (!sourceFile.exists() || sourceFile.length() <= 0) return

        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        val location = prefs.getString("storage_location", "standard")
        val fileName = sourceFile.name

        if (location == "root" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val rootDir = File(Environment.getExternalStorageDirectory(), "ThermalCapture/Videos").apply { mkdirs() }
            val destFile = File(rootDir, fileName)
            try {
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                sourceFile.delete()
                MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
                Toast.makeText(this, "Видео сохранено: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка экспорта видео", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ThermalCapture")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    sourceFile.delete()
                    Toast.makeText(this, "Видео сохранено в Галерею: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка экспорта в галерею", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRecordUiState() {
        if (isRecording) {
            btnRecord.text = "СТОП ⏹"
            btnRecord.setBackgroundColor(0xFF00C853.toInt())
            tvRecordIndicator.visibility = View.VISIBLE
        } else {
            btnRecord.text = "ЗАПИСЬ ⏺"
            btnRecord.setBackgroundColor(0xFFB71C1C.toInt())
            val modeLabel = if (isLoopRecordMode) "ЦИКЛ (3м)" else "НЕПРЕРЫВ"
            tvRecordIndicator.text = "РЕЖИМ: $modeLabel"
            tvRecordIndicator.visibility = View.VISIBLE
        }
    }

    private fun showRecordModeDialog() {
        val modes = arrayOf("Циклическая запись (по 3 мин)", "Непрерывная запись")
        AlertDialog.Builder(this)
            .setTitle("Режим видеорегистратора:")
            .setItems(modes) { _, which ->
                isLoopRecordMode = (which == 0)
                updateRecordUiState()
                val text = if (isLoopRecordMode) "Включен циклический режим (по 3 мин)" else "Включен непрерывный режим"
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun rescanUsbDevices() {
        Toast.makeText(this, "Повторный опрос USB устройств...", Toast.LENGTH_SHORT).show()
        try {
            initCameraHelper()
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isNotEmpty()) {
                val device = drivers[0].device
                if (usbManager.hasPermission(device)) {
                    serialController.connect(this, device)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка опроса: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUartSelectionDialog() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            val allUsbDevices = usbManager.deviceList.values.toList()
            val msg = if (allUsbDevices.isEmpty()) {
                "USB-устройства не обнаружены. Проверьте OTG-кабель и хаб."
            } else {
                val names = allUsbDevices.joinToString("\n") {
                    String.format(Locale.US, "%s (VID:%04X PID:%04X)", it.deviceName, it.vendorId, it.productId)
                }
                "COM-порт не распознан. Подключенные USB:\n$names"
            }
            AlertDialog.Builder(this)
                .setTitle("USB - управление камерой")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = Array(drivers.size) { i ->
            val d = drivers[i].device
            val friendly = serialController.getFriendlyName(d)
            val hasPerm = if (usbManager.hasPermission(d)) "[Доступ есть]" else "[Запросить доступ]"
            String.format(Locale.US, "%s %s (VID:%04X)", friendly, hasPerm, d.vendorId)
        }

        AlertDialog.Builder(this)
            .setTitle("USB - управление камерой:")
            .setItems(items) { _, which ->
                val selectedDriver = drivers[which]
                val device = selectedDriver.device

                if (usbManager.hasPermission(device)) {
                    if (serialController.connectWithDriver(this, selectedDriver)) {
                        Toast.makeText(this, "Подключено: " + serialController.connectedDeviceName + " (115200 8N1)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Не удалось открыть порт", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    requestUsbPermission(device)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val intent = Intent(actionUsbPermission).apply {
            setPackage(packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

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
                btnContrastType.text = "КОНТРАСТ: " + paletteShortNames[which]
                serialController.setPalette(paletteCodes[which])
                Toast.makeText(this, "UART: Палитра " + paletteShortNames[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applyHybridMode() {
        val lpO = opticalContainer.layoutParams as FrameLayout.LayoutParams
        lpO.width = FrameLayout.LayoutParams.MATCH_PARENT
        lpO.height = FrameLayout.LayoutParams.MATCH_PARENT
        lpO.gravity = Gravity.NO_GRAVITY
        opticalContainer.visibility = View.VISIBLE
        opticalContainer.layoutParams = lpO

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
            lpT.width = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.gravity = Gravity.CENTER
            thermalContainer.visibility = View.VISIBLE
            opticalContainer.visibility = View.GONE
        } else {
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
        val sizes = arrayOf(
            Pair(4, 3),
            Pair(5, 4),
            Pair(16, 9)
        )

        AlertDialog.Builder(this)
            .setTitle("Пропорции тепловизора:")
            .setItems(resolutions) { _, which ->
                val chosen = sizes[which]
                thermalAspectW = chosen.first
                thermalAspectH = chosen.second
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
                    if (isVideoDevice(device)) {
                        runOnUiThread { tvStatus.text = "Подключение к плате: " + device.deviceName }
                        selectDevice(device)
                    }
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
        sidebarHandler.removeCallbacks(hideSidebarRunnable)
        mainHandler.removeCallbacks(recordFrameLoopRunnable)
        recordTimerHandler.removeCallbacks(recordTimerRunnable)
        videoRecorder.stop(null)
        alertManager.release()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
        serialController.disconnect()
        thermalSurface?.release()
        cameraHelper?.release()
    }
}
