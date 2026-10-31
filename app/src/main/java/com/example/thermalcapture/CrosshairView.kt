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

    // Кисть для контура (обводки)
    private val outlinePaint = Paint().apply {
        strokeWidth = 7.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Кисть для сердцевины лучей
    private val corePaint = Paint().apply {
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    // Центральная точка
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

    // Размер зоны локального анализа (160x160 пикселей)
    private val patchSize = 160
    private val patchHalf = patchSize / 2

    // Буферы для снятия фона и генерации зональных масок
    private val sampleBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val coreBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val outlineBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)

    private val sampleBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val coreBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val outlineBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)

    // Шейдеры зональной инверсии
    private var coreShaderThermal: BitmapShader? = null
    private var outlineShaderThermal: BitmapShader? = null
    private val matrixThermal = Matrix()

    private var coreShaderOptical: BitmapShader? = null
    private var outlineShaderOptical: BitmapShader? = null
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
            probeAndBuildShader(window, cx, cy, sampleBitmapThermal, coreBitmapThermal, outlineBitmapThermal, isThermal = true) {
                coreShaderThermal = BitmapShader(coreBitmapThermal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                outlineShaderThermal = BitmapShader(outlineBitmapThermal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixThermal.setTranslate(cx - patchHalf, cy - patchHalf)
                coreShaderThermal?.setLocalMatrix(matrixThermal)
                outlineShaderThermal?.setLocalMatrix(matrixThermal)
                invalidate()
            }
        }

        // 2. Зональный анализ оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            probeAndBuildShader(window, cx, cy, sampleBitmapOptical, coreBitmapOptical, outlineBitmapOptical, isThermal = false) {
                coreShaderOptical = BitmapShader(coreBitmapOptical, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                outlineShaderOptical = BitmapShader(outlineBitmapOptical, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixOptical.setTranslate(cx - patchHalf, cy - patchHalf)
                coreShaderOptical?.setLocalMatrix(matrixOptical)
                outlineShaderOptical?.setLocalMatrix(matrixOptical)
                invalidate()
            }
        }
    }

    /**
     * Захват кадра и попиксельный расчет зональной инверсии
     */
    private fun probeAndBuildShader(
        window: android.view.Window,
        cx: Float,
        cy: Float,
        sampleBitmap: Bitmap,
        coreBitmap: Bitmap,
        outlineBitmap: Bitmap,
        isThermal: Boolean,
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

                    val corePixels = IntArray(patchSize * patchSize)
                    val outlinePixels = IntArray(patchSize * patchSize)

                    val darkCoreColor = if (isThermal) 0xFF00FF66.toInt() else 0xFFFF3333.toInt() // Неоновый зеленый / красный
                    val brightCoreColor = 0xFF050505.toInt() // Глубокий черный
                    val darkOutlineColor = 0xFF000000.toInt() // Черный контур
                    val brightOutlineColor = 0xFFFFFFFF.toInt() // Белый светящийся контур

                    val center = patchHalf

                    for (y in 0 until patchSize) {
                        for (x in 0 until patchSize) {
                            // Фильтр защиты от самосчитывания линии
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
                            val luma = (r * 77 + g * 150 + b * 29) shr 8 // Быстрый целочисленный расчет яркости

                            val idx = y * patchSize + x
                            if (luma > 130) {
                                // Фон светлый -> пиксель луча ЧЕРНЫЙ, контур БЕЛЫЙ
                                corePixels[idx] = brightCoreColor
                                outlinePixels[idx] = brightOutlineColor
                            } else {
                                // Фон темный -> пиксель луча ЯРКИЙ, контур ЧЕРНЫЙ
                                corePixels[idx] = darkCoreColor
                                outlinePixels[idx] = darkOutlineColor
                            }
                        }
                    }

                    coreBitmap.setPixels(corePixels, 0, patchSize, 0, 0, patchSize, patchSize)
                    outlineBitmap.setPixels(outlinePixels, 0, patchSize, 0, 0, patchSize, patchSize)
                    post { onReady() }
                }
            }, sampleHandler)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Отрисовка зонального прицела тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawZonalReticle(canvas, cx, cy, coreShaderThermal, outlineShaderThermal, 0xFF00FF66.toInt())
        }

        // 2. Отрисовка зонального прицела оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawZonalReticle(canvas, cx, cy, coreShaderOptical, outlineShaderOptical, 0xFFFF3333.toInt())
        }
    }

    private fun drawZonalReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        coreShader: BitmapShader?,
        outlineShader: BitmapShader?,
        defaultCoreColor: Int
    ) {
        if (coreShader != null && outlineShader != null) {
            outlinePaint.shader = outlineShader
            corePaint.shader = coreShader
        } else {
            outlinePaint.shader = null
            outlinePaint.color = Color.BLACK
            corePaint.shader = null
            corePaint.color = defaultCoreColor
        }

        // Шаг 1: Контур
        drawReticleGeometry(canvas, cx, cy, outlinePaint)
        // Шаг 2: Сердцевина (зонально инвертированная)
        drawReticleGeometry(canvas, cx, cy, corePaint)

        // Шаг 3: Центральная марка
        canvas.drawCircle(cx, cy, 3.5f + 2f, outlinePaint)
        dotFillPaint.shader = corePaint.shader
        if (corePaint.shader == null) dotFillPaint.color = defaultCoreColor
        canvas.drawCircle(cx, cy, 3.5f, dotFillPaint)
    }

    private fun drawReticleGeometry(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        canvas.drawCircle(cx, cy, ringRadius, paint)

        // Горизонтальный луч
        canvas.drawLine(cx - reticleLength, cy, cx - centerGap, cy, paint)
        canvas.drawLine(cx - ringRadius, cy - tickLength / 2, cx - ringRadius, cy + tickLength / 2, paint)
        canvas.drawLine(cx + centerGap, cy, cx + reticleLength, paint)
        canvas.drawLine(cx + ringRadius, cy - tickLength / 2, cx + ringRadius, cy + tickLength / 2, paint)

        // Вертикальный луч
        canvas.drawLine(cx, cy - reticleLength, cx, cy - centerGap, paint)
        canvas.drawLine(cx - tickLength / 2, cy - ringRadius, cx + tickLength / 2, cy - ringRadius, paint)
        canvas.drawLine(cx, cy + centerGap, cx, cy + reticleLength, paint)
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
