package com.example.thermalcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val thermalPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val opticalPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Смещение прицела оптической камеры относительно центра (юстировка параллакса)
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    // Границы окон (задаются из MainActivity)
    var thermalRect = android.graphics.RectF()
    var opticalRect = android.graphics.RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Прицел на тепловизоре (зеленый, всегда строго по центру экрана тепловизора)
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawTacticalReticle(canvas, cx, cy, thermalPaint, 40f)
        }

        // 2. Прицел на штатной камере (красный, со смещением для юстировки)
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawTacticalReticle(canvas, cx, cy, opticalPaint, 40f)
        }
    }

    private fun drawTacticalReticle(canvas: Canvas, cx: Float, cy: Float, paint: Paint, size: Float) {
        // Линии перекрестия с разрывом в центре
        val gap = 12f
        canvas.drawLine(cx - size, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + size, cy, paint)
        canvas.drawLine(cx, cy - size, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + size, paint)

        // Центральная марка-точка и внешний круг
        canvas.drawCircle(cx, cy, 3f, paint)
        canvas.drawCircle(cx, cy, size * 0.75f, paint)
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrationMode) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                opticalOffsetX += dx
                opticalOffsetY += dy
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
