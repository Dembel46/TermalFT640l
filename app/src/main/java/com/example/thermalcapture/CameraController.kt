package com.example.thermalcapture

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Обёртка над CameraX (оптика) + CameraHelper (UVC тепловизор) + поиск COM-порта.
 *
 * ВАЖНО: все колбэки [Callbacks] приходят из потоков UVC-библиотеки,
 * не из main. Вся работа с UI (Toast, runOnUiThread) — на стороне MainActivity.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val phonePreviewView: PreviewView,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onThermalStreamingChanged(active: Boolean, message: String)
        fun onSerialDeviceAttached(device: UsbDevice)
    }

    private var cameraHelper: ICameraHelper? = null

    fun startPhoneCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(phonePreviewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview
                )
            } catch (e: Exception) {
                Log.e("CameraController", "Phone camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun initThermalCamera() {
        // Освобождаем предыдущий helper, если был
        try { cameraHelper?.release() } catch (_: Exception) {}
        cameraHelper = CameraHelper().apply {
            setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) {
                    if (isVideoDevice(device)) {
                        callbacks.onThermalStreamingChanged(
                            false, "Подключение к плате: ${device.deviceName}"
                        )
                        selectDevice(device)
                    } else {
                        // Не UVC-видео — вероятно, это UART-адаптер
                        callbacks.onSerialDeviceAttached(device)
                    }
                }

                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                    callbacks.onThermalStreamingChanged(false, "Плата открыта. Запуск потока...")
                    openCamera()
                }

                override fun onCameraOpen(device: UsbDevice) {
                    callbacks.onThermalStreamingChanged(true, "FT640L: Поток активен")
                    startPreview()
                }

                override fun onCameraClose(device: UsbDevice) {
                    callbacks.onThermalStreamingChanged(false, "Тепловизор закрыт")
                }

                override fun onDeviceClose(device: UsbDevice) {
                    callbacks.onThermalStreamingChanged(false, "Плата отключена")
                }

                override fun onDetach(device: UsbDevice) {
                    callbacks.onThermalStreamingChanged(false, "Плата захвата извлечена")
                }

                override fun onCancel(device: UsbDevice) {
                    callbacks.onThermalStreamingChanged(false, "Подключение отменено")
                }
            })
        }
    }

    fun addSurface(surface: Surface) {
        try {
            cameraHelper?.addSurface(surface, false)
        } catch (e: Exception) {
            Log.e("CameraController", "addSurface error: ${e.message}")
        }
    }

    fun removeSurface(surface: Surface) {
        try {
            cameraHelper?.removeSurface(surface)
        } catch (e: Exception) {
            Log.e("CameraController", "removeSurface error: ${e.message}")
        }
    }

    fun release() {
        try { cameraHelper?.release() } catch (_: Exception) {}
        cameraHelper = null
    }

    /** Найти первый USB-UART драйвер (для FT640SerialController.connect). */
    fun findFirstSerialDevice(): UsbDevice? {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            drivers.firstOrNull()?.device
        } catch (e: Exception) {
            Log.e("CameraController", "findFirstSerialDevice error: ${e.message}")
            null
        }
    }

    private fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
