package com.example.thermalcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Черный контур для контраста на ярких/белых объектах
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 7.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Внутренняя неоново-зеленая сердцевина (Тепловизор)
    private val thermalCorePaint = Paint().apply {
        color = Color.parseColor("#00FF66") // Неоновый зеленый
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Внутренняя неоново-красная сердцевина (Оптическая камера)
    private val opticalCorePaint = Paint().apply {
        color = Color.parseColor("#FF3333") // Ярко-красный
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Заливка для центральной точки
    private val dotFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Смещение прицела оптической камеры относительно центра (юстировка параллакса)
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    // Границы окон (задаются из MainActivity)
    var thermalRect = RectF()
    var opticalRect = RectF()

    // Геометрические размеры тактического прицела (увеличены для лучшего обзора)
    private val reticleLength = 75f     // Длина основных лучей (было 40)
    private val centerGap = 16f         // Разрыв в центре, чтобы не закрывать микро-цели
    private val ringRadius = 52f        // Радиус внешнего кольца (было 30)
    private val tickLength = 10f        // Длина тактических рисок на лучах

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Прицел тепловизора (всегда строго по центру окна)
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawTacticalReticle(canvas, cx, cy, thermalCorePaint)
        }

        // 2. Прицел штатной камеры (со смещением юстировки)
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawTacticalReticle(canvas, cx, cy, opticalCorePaint)
        }
    }

    /**
     * Отрисовка двухслойного прицела: сначала черный контур, затем яркая сердцевина
     */
    private fun drawTacticalReticle(canvas: Canvas, cx: Float, cy: Float, corePaint: Paint) {
        // Шаг 1: Отрисовка черной обводки (для контраста на ярком фоне)
        drawReticleGeometry(canvas, cx, cy, outlinePaint)

        // Шаг 2: Отрисовка яркой сердцевины (для контраста на темном фоне)
        drawReticleGeometry(canvas, cx, cy, corePaint)

        // Шаг 3: Центральная марка (точка 3.5px с черным ободком)
        canvas.drawCircle(cx, cy, 3.5f + 2f, outlinePaint)
        dotFillPaint.color = corePaint.color
        canvas.drawCircle(cx, cy, 3.5f, dotFillPaint)
    }

    /**
     * Геометрия тактической сетки: кольцо, 4 луча с разрывом и дальномерные риски
     */
    private fun drawReticleGeometry(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        // Внешнее прицельное кольцо
        canvas.drawCircle(cx, cy, ringRadius, paint)

        // Горизонтальный левый луч
        canvas.drawLine(cx - reticleLength, cy, cx - centerGap, cy, paint)
        // Риска слева
        canvas.drawLine(cx - ringRadius, cy - tickLength / 2, cx - ringRadius, cy + tickLength / 2, paint)

        // Горизонтальный правый луч
        canvas.drawLine(cx + centerGap, cy, cx + reticleLength, cy, paint)
        // Риска справа
        canvas.drawLine(cx + ringRadius, cy - tickLength / 2, cx + ringRadius, cy + tickLength / 2, paint)

        // Вертикальный верхний луч
        canvas.drawLine(cx, cy - reticleLength, cx, cy - centerGap, paint)
        // Риска сверху
        canvas.drawLine(cx - tickLength / 2, cy - ringRadius, cx + tickLength / 2, cy - ringRadius, paint)

        // Вертикальный нижний луч
        canvas.drawLine(cx, cy + centerGap, cx, cy + reticleLength, paint)
        // Риска снизу
        canvas.drawLine(cx - tickLength / 2, cy + ringRadius, cx + tickLength / 2, cy + ringRadius, paint)
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
