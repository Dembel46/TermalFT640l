package com.example.thermalcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

    private var cameraHelper: ICameraHelper? = null

    // Режимы экрана: 0 - Split 50/50, 1 - PiP (Тепловизор большой, Телефон малый), 2 - Только тепловизор
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

        initViews()
        initButtons()
        checkPermissionsAndInit()
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
        } else {
            Toast.makeText(this, "Требуется доступ к камере", Toast.LENGTH_SHORT).show()
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
        // Переключение режимов: 50/50 -> PiP -> Тепловизор
        btnMode.setOnClickListener {
            displayMode = (displayMode + 1) % 3
            applyDisplayMode(displayMode)
        }

        // Включение/выключение режима юстировки параллакса пальцем
        btnCalibrate.setOnClickListener {
            crosshairOverlay.isCalibrationMode = !crosshairOverlay.isCalibrationMode
            if (crosshairOverlay.isCalibrationMode) {
                btnCalibrate.text = "ПРИЦЕЛ: ТЯНИ"
                btnCalibrate.setBackgroundColor(0xAAFF8800.toInt())
                Toast.makeText(this, "Тяните палец по экрану для юстировки оптического прицела", Toast.LENGTH_SHORT).show()
            } else {
                btnCalibrate.text = "ПРИЦЕЛ: ФИКС"
                btnCalibrate.setBackgroundColor(0xFF444444.toInt())
            }
        }
    }

    private fun applyDisplayMode(mode: Int) {
        val set = ConstraintSet()
        set.clone(rootLayout)

        when (mode) {
            0 -> { // Split 50/50
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
            1 -> { // PiP (Тепловизор большой, Телефон мини-окно в правом верхнем углу)
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
            2 -> { // Только тепловизор во весь экран
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

    /**
     * Запуск штатной камеры смартфона через Jetpack CameraX
     */
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

    /**
     * Запуск тепловизора через USB Host UVC
     */
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
                    runOnUiThread { tvStatus.text = "FT640L: Активен (Зеленый прицел)" }
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
        cameraHelper?.release()
    }
}
