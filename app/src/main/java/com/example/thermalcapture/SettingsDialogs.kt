package com.example.thermalcapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Все диалоги настроек. Действия передаются в колбэках, чтобы не тащить сюда MainActivity.
 */
class SettingsDialogs(
    private val activity: AppCompatActivity,
    private val alertManager: AlertManager,
    private val storageManager: StorageManager,
    private val serialController: FT640SerialController,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onAspectRatioChanged(w: Int, h: Int)
        fun onPaletteChanged(code: Int, shortName: String)
        fun onRescanUsb()
        fun onStorageConfiguredThen(action: (() -> Unit)?)
        fun onRequestUsbPermission(device: android.hardware.usb.UsbDevice)
    }

    fun showMain() {
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
        AlertDialog.Builder(activity)
            .setTitle("⚙ Настройки прибора")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAspectRatioDialog()
                    1 -> {
                        alertManager.isSoundEnabled = !alertManager.isSoundEnabled
                        Toast.makeText(activity, "Звук: ${if (alertManager.isSoundEnabled) "ВКЛ" else "ВЫКЛ"}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        alertManager.isFlashEnabled = !alertManager.isFlashEnabled
                        Toast.makeText(activity, "Строб: ${if (alertManager.isFlashEnabled) "ВКЛ" else "ВЫКЛ"}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> callbacks.onRescanUsb() // упрощённо: повторный опрос + выбор
                    4 -> {
                        serialController.triggerShutter()
                        Toast.makeText(activity, "Калибровка шторки отправлена", Toast.LENGTH_SHORT).show()
                    }
                    5 -> callbacks.onRescanUsb()
                    6 -> showStorageDialog(null)
                    7 -> showHelp()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    fun showStorageDialog(onReady: (() -> Unit)?) {
        val options = arrayOf(
            "Штатные папки (Галерея: DCIM / Movies)",
            "Своя папка в корне (/ThermalCapture)"
        )
        AlertDialog.Builder(activity)
            .setTitle("Куда сохранять фото и видео?")
            .setItems(options) { _, which ->
                val choice = if (which == 0) StorageManager.LOCATION_STANDARD else StorageManager.LOCATION_ROOT
                storageManager.setStorageLocation(choice)
                if (choice == StorageManager.LOCATION_ROOT) {
                    storageManager.requestAllFilesPermissionIfNeeded()
                }
                Toast.makeText(activity, "Место сохранения: ${options[which]}", Toast.LENGTH_SHORT).show()
                onReady?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    fun showAspectRatioDialog() {
        val resolutions = arrayOf(
            "4 : 3 (Стандарт аналогового видео)",
            "5 : 4 (640 x 512 FT640L)",
            "16 : 9 (Широкоформатный)"
        )
        val sizes = arrayOf(Pair(4, 3), Pair(5, 4), Pair(16, 9))
        AlertDialog.Builder(activity)
            .setTitle("Пропорции тепловизора:")
            .setItems(resolutions) { _, which ->
                val chosen = sizes[which]
                callbacks.onAspectRatioChanged(chosen.first, chosen.second)
            }
            .show()
    }

    fun showPaletteDialog() {
        val palettes = arrayOf(
            "Горячий белый (White Hot)",
            "Горячий черный (Black Hot)",
            "Железо (Ironbow)",
            "Радуга (Rainbow)",
            "Горячий красный (Warning Red)"
        )
        val codes = intArrayOf(0x00, 0x01, 0x04, 0x02, 0x0B)
        val shortNames = arrayOf("БЕЛЫЙ", "ЧЕРНЫЙ", "ЖЕЛЕЗО", "РАДУГА", "КРАСНЫЙ")
        AlertDialog.Builder(activity)
            .setTitle("Тип контраста (Палитра):")
            .setItems(palettes) { _, which ->
                serialController.setPalette(codes[which])
                callbacks.onPaletteChanged(codes[which], shortNames[which])
                Toast.makeText(activity, "UART: Палитра ${shortNames[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun showHelp() {
        val message = """
            ТЕПЛОВИЗИОННЫЙ ПРИЦЕЛЬНЫЙ КОМПЛЕКС FT640L
            
            1. БОКОВОЙ БАР (СЛЕВА):
            • [◀ / ▶] — свернуть / открыть. Автоскрытие через 7 сек.
            • РЕЖИМ — 70/30, 100% Тепло, 100% Оптика.
            • ЮСТИРОВКА — калибровка параллакса. Перемещайте прицел пальцем.
            • ТРЕК — CV-детекция. Движущиеся цели — КРАСНЫЙ, крупные — ЖЁЛТЫЙ, прочие — ЗЕЛЁНЫЙ.
            • ФОТО 📷 — снимок экрана.
            • ЗАПИСЬ ⏺ — клик: старт/стоп. Зажатие: режим (цикл 3 мин / непрерывный).
            
            2. НИЖНЯЯ ПАНЕЛЬ:
            • ЗУМ — 0x…3x.
            • КОНТРАСТ — палитры.
            • ГИБРИД — наложение тепла на оптику. Свайп вверх/вниз у левого края — прозрачность.
            
            3. НАСТРОЙКИ (⚙): пропорции, звук, строб, UART, папка сохранения.
        """.trimIndent()

        val scroll = ScrollView(activity).apply {
            setPadding(40, 20, 40, 20)
            addView(TextView(activity).apply {
                text = message
                textSize = 12f
                setTextColor(0xFFDDDDDD.toInt())
                setLineSpacing(0f, 1.25f)
            })
        }
        AlertDialog.Builder(activity)
            .setTitle("ⓘ Инструкция по эксплуатации")
            .setView(scroll)
            .setPositiveButton("Понятно", null)
            .show()
    }
}
