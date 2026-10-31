package com.example.thermalcapture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Основная кисть прицела (чистая линия без контура)
    private val reticlePaint = Paint().apply {
        strokeWidth = 4.0f // Четкая тактическая толщина линии
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Центральная марка (точка)
    private val dotFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var thermalRect = RectF()
    var opticalRect = RectF()

    // Геометрические параметры сетки
    private val reticleLength = 75f
    private val centerGap = 16f
    private val ringRadius = 52f
    private val tickLength = 10f

    // Размер зоны локального анализа
    private val patchSize = 160
    private val patchHalf = patchSize / 2

    // Буферы для анализа фона
    private val sampleBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val reticleBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)

    private val sampleBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val reticleBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)

    // Шейдеры зональной черно-белой инверсии
    private var shaderThermal: BitmapShader? = null
    private val matrixThermal = Matrix()

    private var shaderOptical: BitmapShader? = null
    private val matrixOptical = Matrix()

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    // Частота зонального анализа (~15 кадров/сек)
    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleBackground()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 70)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isSamplingActive = true
        sampleHandler.post(sampleRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isSamplingActive = false
        sampleHandler.removeCallbacks(sampleRunnable)
    }

    private fun sampleBackground() {
        val activity = context as? Activity ?: return
        val window = activity.window ?: return
        if (width <= 0 || height <= 0) return

        // 1. Зональный анализ тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            probeAndBuildShader(window, cx, cy, sampleBitmapThermal, reticleBitmapThermal) {
                shaderThermal = BitmapShader(reticleBitmapThermal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixThermal.setTranslate(cx - patchHalf, cy - patchHalf)
                shaderThermal?.setLocalMatrix(matrixThermal)
                invalidate()
            }
        }

        // 2. Зональный анализ оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            probeAndBuildShader(window, cx, cy, sampleBitmapOptical, reticleBitmapOptical) {
                shaderOptical = BitmapShader(reticleBitmapOptical, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixOptical.setTranslate(cx - patchHalf, cy - patchHalf)
                shaderOptical?.setLocalMatrix(matrixOptical)
                invalidate()
            }
        }
    }

    /**
     * Попиксельная генерация монохромной инверсии (от ярко-белого до глубокого черного)
     */
    private fun probeAndBuildShader(
        window: android.view.Window,
        cx: Float,
        cy: Float,
        sampleBitmap: Bitmap,
        reticleBitmap: Bitmap,
        onReady: () -> Unit
    ) {
        val px = cx.roundToInt().coerceIn(patchHalf, width - patchHalf)
        val py = cy.roundToInt().coerceIn(patchHalf, height - patchHalf)
        val srcRect = Rect(px - patchHalf, py - patchHalf, px + patchHalf, py + patchHalf)

        try {
            PixelCopy.request(window, srcRect, sampleBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val rawPixels = IntArray(patchSize * patchSize)
                    sampleBitmap.getPixels(rawPixels, 0, patchSize, 0, 0, patchSize, patchSize)
                    val outputPixels = IntArray(patchSize * patchSize)

                    val blackColor = 0xFF000000.toInt() // Насыщенный черный
                    val whiteColor = 0xFFFFFFFF.toInt() // Ярко-белый

                    val center = patchHalf

                    for (y in 0 until patchSize) {
                        for (x in 0 until patchSize) {
                            // Фильтр от самосчитывания линии
                            var sx = x
                            var sy = y
                            if (abs(x - center) <= 3) sx = (x + 5).coerceAtMost(patchSize - 1)
                            if (abs(y - center) <= 3) sy = (y + 5).coerceAtMost(patchSize - 1)

                            val dx = sx - center
                            val dy = sy - center
                            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (abs(dist - ringRadius) <= 3.5f && dist > 0f) {
                                val factor = (dist + 5f) / dist
                                sx = (center + dx * factor).roundToInt().coerceIn(0, patchSize - 1)
                                sy = (center + dy * factor).roundToInt().coerceIn(0, patchSize - 1)
                            }

                            val p = rawPixels[sy * patchSize + sx]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            val luma = (r * 77 + g * 150 + b * 29) shr 8

                            val idx = y * patchSize + x
                            // На светлом фоне (>128) пиксель ЧЕРНЫЙ, на темном (<=128) БЕЛЫЙ
                            outputPixels[idx] = if (luma > 128) blackColor else whiteColor
                        }
                    }

                    reticleBitmap.setPixels(outputPixels, 0, patchSize, 0, 0, patchSize, patchSize)
                    post { onReady() }
                }
            }, sampleHandler)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Отрисовка прицела тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawZonalReticle(canvas, cx, cy, shaderThermal)
        }

        // 2. Отрисовка прицела оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawZonalReticle(canvas, cx, cy, shaderOptical)
        }
    }

    private fun drawZonalReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        shader: BitmapShader?
    ) {
        if (shader != null) {
            reticlePaint.shader = shader
        } else {
            reticlePaint.shader = null
            reticlePaint.color = Color.BLACK // Базовый цвет до первого кадра
        }

        // Отрисовка геометрии чистой одинарной линией
        drawReticleGeometry(canvas, cx, cy, reticlePaint)

        // Центральная марка
        dotFillPaint.shader = reticlePaint.shader
        if (reticlePaint.shader == null) dotFillPaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 3.5f, dotFillPaint)
    }

    private fun drawReticleGeometry(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        canvas.drawCircle(cx, cy, ringRadius, paint)

        // Горизонтальный луч (левый и правый)
        canvas.drawLine(cx - reticleLength, cy, cx - centerGap, cy, paint)
        canvas.drawLine(cx + centerGap, cy, cx + reticleLength, cy, paint)

        // Горизонтальные риски
        canvas.drawLine(cx - ringRadius, cy - tickLength / 2, cx - ringRadius, cy + tickLength / 2, paint)
        canvas.drawLine(cx + ringRadius, cy - tickLength / 2, cx + ringRadius, cy + tickLength / 2, paint)

        // Вертикальный луч (верхний и нижний)
        canvas.drawLine(cx, cy - reticleLength, cx, cy - centerGap, paint)
        canvas.drawLine(cx, cy + centerGap, cx, cy + reticleLength, paint)

        // Вертикальные риски
        canvas.drawLine(cx - tickLength / 2, cy - ringRadius, cx + tickLength / 2, cy - ringRadius, paint)
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
