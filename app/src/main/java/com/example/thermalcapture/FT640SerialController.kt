package com.example.thermalcapture

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class FT640SerialController {

    private var serialPort: UsbSerialPort? = null
    var isConnected = false
        private set

    /**
     * Поиск и подключение к USB-UART адаптеру на скорости 115200 8N1
     */
    fun connect(context: Context, device: UsbDevice? = null): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            isConnected = false
            return false
        }

        val driver = if (device != null) {
            availableDrivers.firstOrNull { it.device == device } ?: availableDrivers[0]
        } else {
            availableDrivers[0]
        }

        val connection = usbManager.openDevice(driver.device) ?: return false
        val port = driver.ports[0]

        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            isConnected = true
            return true
        } catch (e: Exception) {
            disconnect()
            return false
        }
    }

    fun disconnect() {
        try {
            serialPort?.close()
        } catch (e: Exception) {}
        serialPort = null
        isConnected = false
    }

    /**
     * Сборка и отправка заводского пакета InfiRay XCore:
     * 0xAA + Length + CW0 + CW1 + OW + Params + CheckSum + 0xEB + 0xAA
     */
    private fun sendCommand(cw0: Int, cw1: Int, ow: Int, params: ByteArray = byteArrayOf()) {
        val length = 2 + 1 + params.size + 1
        val totalSize = 1 + 1 + length + 2
        val packet = ByteArray(totalSize)

        packet[0] = 0xAA.toByte()
        packet[1] = length.toByte()
        packet[2] = cw0.toByte()
        packet[3] = cw1.toByte()
        packet[4] = ow.toByte()

        var checksum = 0xAA + length + cw0 + cw1 + ow
        for (i in params.indices) {
            val b = params[i]
            packet[5 + i] = b
            checksum += (b.toInt() and 0xFF)
        }

        val scIndex = 5 + params.size
        packet[scIndex] = (checksum and 0xFF).toByte()
        packet[scIndex + 1] = 0xEB.toByte()
        packet[scIndex + 2] = 0xAA.toByte()

        try {
            serialPort?.write(packet, 500)
        } catch (e: Exception) {}
    }

    /**
     * 1. Переключение палитры ядра (Команда 0x42)
     */
    fun setPalette(paletteCode: Int) {
        sendCommand(0x01, 0x42, 0x01, byteArrayOf(paletteCode.toByte()))
    }

    /**
     * 2. Переключение авто-контраста AGC ядра (Команда 0x1F)
     * 0x01 - Auto, 0x00 - Manual
     */
    fun setAutoContrast(isAuto: Boolean) {
        val mode = if (isAuto) 0x01 else 0x00
        sendCommand(0x01, 0x1F, 0x01, byteArrayOf(mode.toByte()))
    }

    /**
     * 3. Аппаратный электронный зум ядра 640x512 (Команда 0x40)
     */
    fun setElectronicZoom(zoomFactor: Float) {
        val z = zoomFactor.coerceIn(1.0f, 4.0f)
        val fullW = 640
        val fullH = 512

        val winW = (fullW / z).toInt().coerceAtLeast(160)
        val winH = (fullH / z).toInt().coerceAtLeast(128)

        val x0 = (fullW - winW) / 2
        val y0 = (fullH - winH) / 2
        val x1 = x0 + winW
        val y1 = y0 + winH

        val params = byteArrayOf(
            (x0 and 0xFF).toByte(), ((x0 shr 8) and 0xFF).toByte(),
            (y0 and 0xFF).toByte(), ((y0 shr 8) and 0xFF).toByte(),
            (x1 and 0xFF).toByte(), ((x1 shr 8) and 0xFF).toByte(),
            (y1 and 0xFF).toByte(), ((y1 shr 8) and 0xFF).toByte()
        )
        sendCommand(0x01, 0x40, 0x02, params)
    }

    /**
     * 4. Калибровка шторки матрицы (NUC / Shutter, Команда 0x02)
     */
    fun triggerShutter() {
        sendCommand(0x01, 0x02, 0x02, byteArrayOf(0x01, 0x01))
    }
}
