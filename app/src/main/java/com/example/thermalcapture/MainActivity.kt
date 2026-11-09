package com.example.thermalcapture

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val actionUsbPermission = "com.example.thermalcapture.USB_SERIAL_PERMISSION"

    // ---------- Views ----------
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var thermalContainer: FrameLayout
    private lateinit var opticalContainer: FrameLayout
    private lateinit var cameraView: TextureView
    private lateinit var phoneCameraView: PreviewView
    private lateinit var crosshairOverlay: CrosshairView
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordIndicator: TextView
    private lateinit var sidebarContent: LinearLayout
    private lateinit var btnToggleSidebar: Button
    private lateinit var btnMode: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnTracking: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSettings: Button
    private lateinit var btnThermalFullscreen: Button
    private lateinit var btnHybrid: Button
    private lateinit var btnZoom: Button
    private lateinit var btnContrastType: Button

    // ---------- Controllers ----------
    private lateinit var storageManager: StorageManager
    private lateinit var settingsDialogs: SettingsDialogs
    private lateinit var cameraController: CameraController
    private lateinit var recordingController: RecordingController
    private lateinit var batteryStatus: BatteryStatusView
    private lateinit var alertManager: AlertManager

    // ---------- Serial / Vision ----------
    private val serialController = FT640SerialController()
    private val visionEngine = ThermalVisionEngine()

    private lateinit var visionThread: HandlerThread
    private lateinit var visionHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVisionRunning = true
    private var isProcessingFrame = false

    private var isTrackingEnabled = false
    private var isThermalStreaming = false
    private var thermalSurface: Surface? = null

    private val thermalAnalyzeBmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

    // ---------- UI state ----------
    private var displayMode = 1
    private var isThermalFullscreen = true
    private var isHybridModeActive = false
    private var isSidebarExpanded = true
    private var currentZoomIndex = 0
    private var thermalAspectW = 4
    private var thermalAspectH = 3

    private val sidebarHandler = Handler(Looper.getMainLooper())
    private val hideSidebarRunnable = Runnable { collapseSidebar() }

    private var isRecording = false
    private var recordDurationSec = 0

    private var lastStatusMessage = "Ожидание подключения..."
    private var lastBatteryText = ""

    // ================================================================
    // Жизненный цикл
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        storageManager = StorageManager(this)
        alertManager = AlertManager(this)

        crosshairOverlay.phoneCameraView = phoneCameraView
        crosshairOverlay.thermalCameraView = cameraView

        val prefs = getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE)
        crosshairOverlay.opticalOffsetX = prefs.getFloat("opticalOffsetX", 0f)
        crosshairOverlay.opticalOffsetY = prefs.getFloat("opticalOffsetY", 0f)
        crosshairOverlay.onHybridAlphaChanged = { alpha ->
            if (isHybridModeActive) cameraView.alpha = alpha
        }

        initControllers()
        initVisionPipeline()
        initViews()
        initButtons()
        checkPermissionsAndInit()

        registerUsbReceiver()
        updateRecordUiState()

        rootLayout.post {
            setSplitRatio(1.0f, 0.0f, "ТЕПЛО 100%")
            btnThermalFullscreen.text = "⤡"
            resetSidebarTimer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isVisionRunning = false
        try { visionThread.quitSafely() } catch (_: Exception) {}
        sidebarHandler.removeCallbacks(hideSidebarRunnable)
        recordingController.release()
        alertManager.release()
        batteryStatus.stop()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        serialController.disconnect()
        thermalSurface?.release()
        cameraController.release()
    }

    // ================================================================
    // Инициализация
    // ================================================================

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        cameraContainer = findViewById(R.id.cameraContainer)
        thermalContainer = findViewById(R.id.thermalContainer)
        opticalContainer = findViewById(R.id.opticalContainer)
        cameraView = findViewById(R.id.svUvcCamera)
        phoneCameraView = findViewById(R.id.pvPhoneCamera)
        crosshairOverlay = findViewById(R.id.crosshairOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        tvRecordIndicator = findViewById(R.id.tvRecordIndicator)
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
    }

    private fun initControllers() {
        // ---- CameraController ----
        cameraController = CameraController(this, this, phoneCameraView,
            object : CameraController.Callbacks {
                override fun onThermalStreamingChanged(active: Boolean, message: String) {
                    isThermalStreaming = active
                    runOnUiThread { tvStatus.text = message }
                    if (active) {
                        thermalSurface?.let { if (it.isValid) cameraController.addSurface(it) }
                    }
                }

                override fun onSerialDeviceAttached(device: UsbDevice) {
                    if (serialController.connect(this@MainActivity, device)) {
                        Toast.makeText(this@MainActivity,
                            "UART: ${serialController.connectedDeviceName}", Toast.LENGTH_SHORT).show()
                    }
                }
            })

        // ---- SettingsDialogs ----
        settingsDialogs = SettingsDialogs(this, alertManager, storageManager, serialController,
            object : SettingsDialogs.Callbacks {
                override fun onAspectRatioChanged(w: Int, h: Int) {
                    thermalAspectW = w
                    thermalAspectH = h
                    rootLayout.post { updateOverlayBounds() }
                }

                override fun onPaletteChanged(code: Int, shortName: String) {
                    btnContrastType.text = "КОНТРАСТ: $shortName"
                }

                override fun onRescanUsb() { rescanUsb() }

                override fun onStorageConfiguredThen(action: (() -> Unit)?) { action?.invoke() }

                override fun onRequestUsbPermission(device: UsbDevice) { requestUsbPermission(device) }
            })

        // ---- RecordingController ----
        recordingController = RecordingController(this, storageManager,
            InternalVideoRecorder(
                width = 1280,
                height = 720,
                frameRate = 20,
                opticalRotationDegrees = 90   // поворот только слоя оптики
            ),
            object : RecordingController.Ui {
                override val thermalView: TextureView? get() = cameraView
                override val opticalView: PreviewView? get() = phoneCameraView
                override fun isThermalStreaming(): Boolean = this@MainActivity.isThermalStreaming
                override fun getDisplayMode(): Int = displayMode
                override fun isHybridActive(): Boolean = isHybridModeActive
                override fun getHybridAlpha(): Float = crosshairOverlay.hybridAlpha

                override fun getOpticalOffsetNorm(): Pair<Float, Float> {
                    val nx = if (phoneCameraView.width > 0)
                        crosshairOverlay.opticalOffsetX / phoneCameraView.width else 0f
                    val ny = if (phoneCameraView.height > 0)
                        crosshairOverlay.opticalOffsetY / phoneCameraView.height else 0f
                    return nx to ny
                }

                override fun getDetectedTargets(): List<DetectedTarget> =
                    crosshairOverlay.detectedTargets

                override fun onRecordingStateChanged(recording: Boolean) {
                    isRecording = recording
                    updateRecordUiState()
                }

                override fun onRecordingTick(durationSec: Int) {
                    recordDurationSec = durationSec
                    val min = durationSec / 60
                    val sec = durationSec % 60
                    val label = if (recordingController.isLoopRecording) "ЦИКЛ 3М" else "НЕПРЕРЫВ"
                    tvRecordIndicator.text =
                        String.format(Locale.US, "● REC [%s] %02d:%02d", label, min, sec)
                }

                override fun onRecordingError(message: String) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            })

        // ---- BatteryStatusView ----
        batteryStatus = BatteryStatusView(this).apply {
            onStatusChanged = { text ->
                runOnUiThread { updateStatusBar(text) }
            }
            start()
        }
    }

    // ================================================================
    // Статус-бар
    // ================================================================

    private fun updateStatusBar(batteryText: String) {
        lastBatteryText = batteryText
        tvStatus.text = if (batteryText.isNotEmpty()) "$lastStatusMessage | $batteryText" else lastStatusMessage
    }

    private fun setStatus(msg: String) {
        lastStatusMessage = msg
        updateStatusBar(lastBatteryText)
    }

    // ================================================================
    // Views / Buttons
    // ================================================================

    private fun initViews() {
        cameraView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                thermalSurface = surface
                cameraController.addSurface(surface)
                updateOverlayBounds()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updateOverlayBounds()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                thermalSurface?.let { cameraController.removeSurface(it) }
                thermalSurface?.release()
                thermalSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        phoneCameraView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOverlayBounds()
        }
    }

    private fun initButtons() {
        btnToggleSidebar.setOnClickListener {
            if (isSidebarExpanded) collapseSidebar() else expandSidebar()
        }
        btnSettings.setOnClickListener { resetSidebarTimer(); settingsDialogs.showMain() }
        btnPhoto.setOnClickListener { resetSidebarTimer(); ensureStorage { takeScreenshotPhoto() } }
        btnRecord.setOnClickListener { resetSidebarTimer(); ensureStorage { toggleRecording() } }
        btnRecord.setOnLongClickListener { resetSidebarTimer(); showRecordModeDialog(); true }
        btnHybrid.setOnClickListener { resetSidebarTimer(); toggleHybrid() }
        btnTracking.setOnClickListener { resetSidebarTimer(); toggleTracking() }
        btnThermalFullscreen.setOnClickListener { resetSidebarTimer(); toggleFullscreen() }
        btnMode.setOnClickListener { resetSidebarTimer(); cycleDisplayMode() }
        btnCalibrate.setOnClickListener { resetSidebarTimer(); toggleCalibration() }
        btnZoom.setOnClickListener { resetSidebarTimer(); cycleZoom() }
        btnContrastType.setOnClickListener { resetSidebarTimer(); settingsDialogs.showPaletteDialog() }
    }

    // ================================================================
    // Vision pipeline
    // ================================================================

    private fun initVisionPipeline() {
        visionThread = HandlerThread("VisionProcessingThread").apply { start() }
        visionHandler = Handler(visionThread.looper)

        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isVisionRunning) return
                if (!isProcessingFrame && isTrackingEnabled) {
                    val active = isThermalStreaming && cameraView.width > 0 && cameraView.isAvailable
                    if (active) {
                        try {
                            val bmp = cameraView.getBitmap(thermalAnalyzeBmp)
                            if (bmp != null) {
                                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                dispatchFrameToVision(copy)
                            } else isProcessingFrame = false
                        } catch (_: Exception) { isProcessingFrame = false }
                    } else {
                        if (crosshairOverlay.detectedTargets.isNotEmpty()) {
                            crosshairOverlay.detectedTargets = emptyList()
                        }
                        setStatus("Ожидание подключения тепловизора...")
                    }
                } else if (!isTrackingEnabled) {
                    if (crosshairOverlay.detectedTargets.isNotEmpty()) {
                        crosshairOverlay.detectedTargets = emptyList()
                    }
                    setStatus("Трекинг: ВЫКЛЮЧЕН")
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
                if (hasMotion && isTrackingEnabled) alertManager.triggerMotionAlert()
                mainHandler.post {
                    crosshairOverlay.detectedTargets = targets
                    when {
                        !isTrackingEnabled -> setStatus("Трекинг: ВЫКЛЮЧЕН")
                        targets.isNotEmpty() -> {
                            val moving = targets.count { it.type == TargetType.MOVING }
                            setStatus("Целей: ${targets.size} (Движение: $moving)")
                        }
                        else -> setStatus("Поиск целей: АКТИВЕН")
                    }
                }
            } catch (_: Exception) {
            } finally {
                isProcessingFrame = false
            }
        }
    }

    // ================================================================
    // Permissions / Cameras
    // ================================================================

    private fun checkPermissionsAndInit() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else startCameras()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameras()
        }
    }

    private fun startCameras() {
        cameraController.startPhoneCamera()
        cameraController.initThermalCamera()
    }

    // ================================================================
    // Overlay / Layout
    // ================================================================

    private fun updateOverlayBounds() {
        adjustThermalViewBounds()

        if (thermalContainer.visibility == View.VISIBLE &&
            cameraView.width > 0 && cameraView.height > 0) {
            val locT = IntArray(2); cameraView.getLocationOnScreen(locT)
            val locR = IntArray(2); rootLayout.getLocationOnScreen(locR)
            crosshairOverlay.thermalRect = RectF(
                (locT[0] - locR[0]).toFloat(),
                (locT[1] - locR[1]).toFloat(),
                (locT[0] - locR[0] + cameraView.width).toFloat(),
                (locT[1] - locR[1] + cameraView.height).toFloat()
            )
        } else crosshairOverlay.thermalRect = RectF()

        if (opticalContainer.visibility == View.VISIBLE &&
            phoneCameraView.width > 0 && phoneCameraView.height > 0) {
            val locO = IntArray(2); phoneCameraView.getLocationOnScreen(locO)
            val locR = IntArray(2); rootLayout.getLocationOnScreen(locR)
            crosshairOverlay.opticalRect = RectF(
                (locO[0] - locR[0]).toFloat(),
                (locO[1] - locR[1]).toFloat(),
                (locO[0] - locR[0] + phoneCameraView.width).toFloat(),
                (locO[1] - locR[1] + phoneCameraView.height).toFloat()
            )
        } else crosshairOverlay.opticalRect = RectF()

        crosshairOverlay.invalidate()
    }

    private fun adjustThermalViewBounds() {
        val cw = thermalContainer.width
        val ch = thermalContainer.height
        if (cw <= 0 || ch <= 0) return
        val ratio = thermalAspectW.toFloat() / thermalAspectH.toFloat()
        val fitW: Int
        val fitH: Int
        if (isHybridModeActive) {
            fitH = (ch * 0.9f).toInt()
            fitW = (fitH * ratio).toInt().coerceAtMost(cw)
        } else {
            val cr = cw.toFloat() / ch.toFloat()
            if (cr > ratio) {
                fitH = ch
                fitW = (ch * ratio).toInt().coerceAtMost(cw)
            } else {
                fitW = cw
                fitH = (fitW / ratio).toInt().coerceAtMost(ch)
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

    private fun resetSidebarTimer() {
        sidebarHandler.removeCallbacks(hideSidebarRunnable)
        if (isSidebarExpanded) sidebarHandler.postDelayed(hideSidebarRunnable, 7000)
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

    // ================================================================
    // Buttons
    // ================================================================

    private fun toggleHybrid() {
        isHybridModeActive = !isHybridModeActive
        crosshairOverlay.isHybridMode = isHybridModeActive
        if (isHybridModeActive) {
            btnHybrid.text = "ГИБРИД: ВКЛ"
            btnHybrid.setBackgroundColor(0xFF0099CC.toInt())
            applyHybridMode()
            Toast.makeText(this,
                "Свайп вверх/вниз слева — прозрачность", Toast.LENGTH_SHORT).show()
        } else {
            btnHybrid.text = "ГИБРИД"
            btnHybrid.setBackgroundColor(0xFF444444.toInt())
            cameraView.alpha = 1f
            setSplitRatio(1f, 0f, "ТЕПЛО 100%")
        }
    }

    private fun toggleTracking() {
        isTrackingEnabled = !isTrackingEnabled
        if (isTrackingEnabled) {
            btnTracking.text = "ТРЕК: ВКЛ"
            btnTracking.setBackgroundColor(0xFF008800.toInt())
        } else {
            btnTracking.text = "ТРЕК: ВЫКЛ"
            btnTracking.setBackgroundColor(0xFF444444.toInt())
            crosshairOverlay.detectedTargets = emptyList()
            setStatus("Трекинг: ВЫКЛЮЧЕН")
        }
    }

    private fun toggleFullscreen() {
        if (isHybridModeActive) {
            isHybridModeActive = false
            crosshairOverlay.isHybridMode = false
            btnHybrid.text = "ГИБРИД"
            btnHybrid.setBackgroundColor(0xFF444444.toInt())
            cameraView.alpha = 1f
        }
        if (!isThermalFullscreen) {
            isThermalFullscreen = true
            btnThermalFullscreen.text = "⤡"
            setSplitRatio(1f, 0f, "ТЕПЛО 100%")
        } else {
            isThermalFullscreen = false
            btnThermalFullscreen.text = "⛶"
            setSplitRatio(0.7f, 0.3f, "70/30")
        }
    }

    private fun cycleDisplayMode() {
        if (isHybridModeActive) {
            isHybridModeActive = false
            crosshairOverlay.isHybridMode = false
            btnHybrid.text = "ГИБРИД"
            btnHybrid.setBackgroundColor(0xFF444444.toInt())
            cameraView.alpha = 1f
        }
        displayMode = (displayMode + 1) % 3
        isThermalFullscreen = (displayMode == 1)
        btnThermalFullscreen.text = if (isThermalFullscreen) "⤡" else "⛶"
        when (displayMode) {
            0 -> setSplitRatio(0.7f, 0.3f, "70/30")
            1 -> setSplitRatio(1f, 0f, "ТЕПЛО 100%")
            2 -> setSplitRatio(0f, 1f, "ОПТИКА 100%")
        }
    }

    private fun toggleCalibration() {
        crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
        if (crosshairOverlay.isCalibrationMode) {
            btnCalibrate.text = "СОХРАНИТЬ"
            btnCalibrate.setBackgroundColor(0xFFCC0000.toInt())
            Toast.makeText(this, "Перемещайте оптический прицел пальцем", Toast.LENGTH_SHORT).show()
        } else {
            btnCalibrate.text = "ЮСТИРОВКА"
            btnCalibrate.setBackgroundColor(0xFF333333.toInt())
            getSharedPreferences("ThermalCapturePrefs", Context.MODE_PRIVATE).edit()
                .putFloat("opticalOffsetX", crosshairOverlay.opticalOffsetX)
                .putFloat("opticalOffsetY", crosshairOverlay.opticalOffsetY)
                .apply()
            Toast.makeText(this, "Позиция юстировки сохранена", Toast.LENGTH_SHORT).show()
        }
        crosshairOverlay.invalidate()
    }

    private fun cycleZoom() {
        currentZoomIndex = (currentZoomIndex + 1) % 4
        val zoom = when (currentZoomIndex) { 0 -> 1f; 1 -> 2f; 2 -> 3f; else -> 4f }
        btnZoom.text = "ЗУМ: ${currentZoomIndex}x"
        cameraView.scaleX = zoom
        cameraView.scaleY = zoom
        serialController.setElectronicZoom(zoom)
    }

    // ================================================================
    // Recording
    // ================================================================

    private fun toggleRecording() {
        if (!isRecording) recordingController.start() else recordingController.stop()
    }

    private fun ensureStorage(action: () -> Unit) {
        if (storageManager.getStorageLocation() == null)
            settingsDialogs.showStorageDialog(action)
        else action()
    }

    private fun takeScreenshotPhoto() {
        val w = rootLayout.width
        val h = rootLayout.height
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) storageManager.savePhoto(bmp)
                else {
                    bmp.recycle()
                    Toast.makeText(this, "Не удалось создать снимок", Toast.LENGTH_SHORT).show()
                }
            }, mainHandler)
        } catch (_: Exception) {
            bmp.recycle()
        }
    }

    private fun showRecordModeDialog() {
        val modes = arrayOf("Циклическая запись (по 3 мин)", "Непрерывная запись")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Режим видеорегистратора:")
            .setItems(modes) { _, which ->
                recordingController.isLoopRecording = (which == 0)
                updateRecordUiState()
                Toast.makeText(this,
                    if (which == 0) "Включен циклический режим" else "Включен непрерывный режим",
                    Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateRecordUiState() {
        if (isRecording) {
            btnRecord.text = "СТОП ⏹"
            btnRecord.setBackgroundColor(0xFF00C853.toInt())
            tvRecordIndicator.visibility = View.VISIBLE
        } else {
            btnRecord.text = "ЗАПИСЬ ⏺"
            btnRecord.setBackgroundColor(0xFFB71C1C.toInt())
            val label = if (recordingController.isLoopRecording) "ЦИКЛ (3м)" else "НЕПРЕРЫВ"
            tvRecordIndicator.text = "РЕЖИМ: $label"
            tvRecordIndicator.visibility = View.VISIBLE
        }
    }

    // ================================================================
    // Layout modes
    // ================================================================

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

    private fun setSplitRatio(weightThermal: Float, weightOptical: Float, label: String) {
        cameraView.alpha = 1f
        val sw = cameraContainer.width
        val sh = cameraContainer.height
        if (sw <= 0 || sh <= 0) {
            rootLayout.post { setSplitRatio(weightThermal, weightOptical, label) }
            return
        }
        val lpT = thermalContainer.layoutParams as FrameLayout.LayoutParams
        val lpO = opticalContainer.layoutParams as FrameLayout.LayoutParams

        if (weightThermal > 0f && weightOptical > 0f) {
            val tw = (sw * weightThermal).toInt()
            val ow = sw - tw
            lpT.width = tw
            lpT.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpT.gravity = Gravity.START
            thermalContainer.visibility = View.VISIBLE
            lpO.width = ow
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
        btnMode.text = label
        cameraContainer.requestLayout()
        rootLayout.post { updateOverlayBounds() }
    }

    // ================================================================
    // USB
    // ================================================================

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let {
                        if (serialController.connect(this@MainActivity, it)) {
                            Toast.makeText(this@MainActivity,
                                "UART подключен: ${serialController.connectedDeviceName}",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun rescanUsb() {
        Toast.makeText(this, "Повторный опрос USB...", Toast.LENGTH_SHORT).show()
        try {
            cameraController.initThermalCamera()
            cameraController.findFirstSerialDevice()?.let { device ->
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(device)) serialController.connect(this, device)
                else requestUsbPermission(device)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val intent = Intent(actionUsbPermission).apply { setPackage(packageName) }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }
}
