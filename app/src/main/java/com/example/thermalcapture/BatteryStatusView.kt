package com.example.thermalcapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Читает уровень/температуру батареи и отдаёт строку вида "🔋 78% | 🌡 41°C".
 */
class BatteryStatusView(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""

    var onStatusChanged: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            if (pct >= 0) {
                val text = String.format("🔋 %d%% | 🌡 %.0f°C", pct, tempC)
                if (text != lastText) {
                    lastText = text
                    onStatusChanged?.invoke(text)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
