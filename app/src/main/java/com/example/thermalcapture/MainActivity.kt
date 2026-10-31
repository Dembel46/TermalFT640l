
package com.example.thermalcapture

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.widget.AspectRatioSurfaceView

class MainActivity : AppCompatActivity() {

    private lateinit var cameraView: AspectRatioSurfaceView
    private lateinit var tvStatus: TextView
    private var cameraHelper: ICameraHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Держим экран всегда включенным и во весь экран
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        cameraView = findViewById(R.id.svUvcCamera)
        tvStatus = findViewById(R.id.tvStatus)

        initViews()
        checkPermissionsAndInit()
    }

    private fun checkPermissionsAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
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
            initCameraHelper()
        } else {
            Toast.makeText(this, "Требуется разрешение на камеру", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initViews() {
        // Устанавливаем базовое соотношение сторон аналогового видео (PAL 4:3 = 720x576)
        cameraView.setAspectRatio(720, 576)

        cameraView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cameraHelper?.addSurface(holder.surface, false)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.surface)
            }
        })
    }

    private fun initCameraHelper() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Плата найдена: ${device.deviceName}. Подключение..."
                    }
                    // Запрашиваем доступ к найденному USB-устройству
                    selectDevice(device)
                }

                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                    runOnUiThread {
                        tvStatus.text = "Устройство открыто. Запуск потока..."
                    }
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Сигнал принимается (FT640L)"
                    }
                    // Запуск превью
                    startPreview()

                    // Если SurfaceView уже создан, передаем его
                    cameraView.holder.surface?.let { surface ->
                        if (surface.isValid) {
                            addSurface(surface, false)
                        }
                    }
                }

                override fun onCameraClose(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Камера закрыта"
                    }
                }

                override fun onDeviceClose(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Устройство отключено"
                    }
                }

                override fun onDetach(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Плата видеозахвата извлечена"
                    }
                }

                override fun onCancel(device: UsbDevice) {
                    runOnUiThread {
                        tvStatus.text = "Подключение отменено пользователем"
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Обязательно освобождаем ресурсы USB при выходе
        cameraHelper?.release()
    }
}
