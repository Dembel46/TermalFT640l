package com.example.thermalcapture

import java.io.OutputStream

class FT640SerialController {

    var outputStream: OutputStream? = null

    /**
     * Сборка пакета по протоколу InfiRay XCore FT II:
     * Header(0xAA) + Length + CW0 + CW1 + OW + Params + CheckSum + Tail(0xEB, 0xAA)
     */
    fun buildPacket(cw0: Int, cw1: Int, ow: Int, params: ByteArray = byteArrayOf()): ByteArray {
        val length = 2 + 1 + params.size + 1 // CW0, CW1, OW, params, SC
        val packet = ArrayList<Int>()

        packet.add(0xAA)
        packet.add(length and 0xFF)
        packet.add(cw0 and 0xFF)
        packet.add(cw1 and 0xFF)
        packet.add(ow and 0xFF)
        for (b in params) {
            packet.add(b.toInt() and 0xFF)
        }

        // Контрольная сумма: сумма всех байт пакета до SC по модулю 256
        var sum = 0
        for (b in packet) {
            sum += b
        }
        val sc = sum and 0xFF
        packet.add(sc)

        packet.add(0xEB)
        packet.add(0xAA)

        val result = ByteArray(packet.size)
        for (i in packet.indices) {
            result[i] = packet[i].toByte()
        }
        return result
    }

    fun sendPacket(packet: ByteArray) {
        try {
            outputStream?.write(packet)
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    /**
     * 1. Переключение цветовой палитры (Тип контраста)
     * 0x00 - White Hot, 0x01 - Black Hot, 0x02 - Rainbow, 0x04 - Iron, 0x0B - Warning Red
     */
    fun setPalette(paletteCode: Int) {
        val packet = buildPacket(0x01, 0x42, 0x02, byteArrayOf(paletteCode.toByte()))
        sendPacket(packet)
    }

    /**
     * 2. Переключение режима авто-контраста (AGC)
     * 0x01 - Авто, 0x00 - Ручной
     */
    fun setAutoContrast(isAuto: Boolean) {
        val mode = if (isAuto) 0x01 else 0x00
        val packet = buildPacket(0x01, 0x01, 0x01, byteArrayOf(mode.toByte()))
        sendPacket(packet)
    }

    /**
     * 3. Установка электронного цифрового зума (1.0x .. 4.0x) для матрицы 640x512
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

        // 8 байт параметров: x0_low, x0_high, y0_low, y0_high, x1_low, x1_high, y1_low, y1_high
        val params = byteArrayOf(
            (x0 and 0xFF).toByte(), ((x0 shr 8) and 0xFF).toByte(),
            (y0 and 0xFF).toByte(), ((y0 shr 8) and 0xFF).toByte(),
            (x1 and 0xFF).toByte(), ((x1 shr 8) and 0xFF).toByte(),
            (y1 and 0xFF).toByte(), ((y1 shr 8) and 0xFF).toByte()
        )

        val packet = buildPacket(0x01, 0x40, 0x02, params)
        sendPacket(packet)
    }

    /**
     * Калибровка шторки (NUC / Shutter)
     */
    fun triggerShutter() {
        val packet = buildPacket(0x01, 0x02, 0x02, byteArrayOf(0x01, 0x01))
        sendPacket(packet)
    }
}
