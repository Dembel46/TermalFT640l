package com.example.thermalcapture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Геометрия прицела (Duplex / German #4)
    private val reticleLength = 70f * density    // Полная длина луча
    private val centerGap = 8f * density         // Зазор у центра (уменьшен)
    private val taperStart = 24f * density       // Точка перехода (1/3 сужение, 2/3 толстая)
    private val thickWidth = 3.6f * density      // Толщина внешних 2/3
    private val thinWidth = 1.2f * density       // Толщина нити у центра
    private val dotRadius = 1.4f * density       // Микро-марка в центре

    // Кисти для рисования
    private val thickPaint = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val taperPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Трапециевидный путь для плавного сужения к центру
    private val taperPath = Path()

    // Смещение юстировки (функция "ТЯНИ")
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var thermalRect = RectF()
    var opticalRect = RectF()

    // Зона считывания фона (вокруг прицела)
    private val sampleSize = (reticleLength * 2.2f).roundToInt().coerceIn(120, 320)
    private val sampleHalf = sampleSize / 2

    private val sampleBitmapOptical = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val sampleBitmapThermal = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val rawPixels = IntArray(sampleSize * sampleSize)

    // Сглаженные значения тонов (0 - черный, 255 - белый) для устранения дрожания и моргания
    private var optToneCenter = 255f
    private var optToneLeft = 255f
    private var optToneRight = 255f
    private var optToneTop = 255f
    private var optToneBottom = 255f

    private var thmToneCenter = 255f
    private var thmToneLeft = 255f
    private var thmToneRight = 255f
    private var thmToneTop = 255f
    private var thmToneBottom = 255f

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleSurfaces()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 60)
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

    private fun sampleSurfaces() {
        val activity = context as? Activity ?: return
        val window = activity.window ?: return
        if (width <= 0 || height <= 0) return

        // 1. Оптическая камера с учетом "ТЯНИ"
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            probeTones(window, cx, cy, sampleBitmapOptical) { c, l, r, t, b ->
                // Фильтр инерции (EMA 70/30) - плавный переход без дрожания
                optToneCenter = optToneCenter * 0.7f + c * 0.3f
                optToneLeft = optToneLeft * 0.7f + l * 0.3f
                optToneRight = optToneRight * 0.7f + r * 0.3f
                optToneTop = optToneTop * 0.7f + t * 0.3f
                optToneBottom = optToneBottom * 0.7f + b * 0.3f
                invalidate()
            }
        }

        // 2. Тепловизор (CVBS поток)
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            probeTones(window, cx, cy, sampleBitmapThermal) { c, l, r, t, b ->
                thmToneCenter = thmToneCenter * 0.7f + c * 0.3f
                thmToneLeft = thmToneLeft * 0.7f + l * 0.3f
                thmToneRight = thmToneRight * 0.7f + r * 0.3f
                thmToneTop = thmToneTop * 0.7f + t * 0.3f
                thmToneBottom = thmToneBottom * 0.7f + b * 0.3f
                invalidate()
            }
        }
    }

    /**
     * Считывание реального фона без самозахвата линий прицела
     */
    private fun probeTones(
        window: android.view.Window,
        viewCx: Float,
        viewCy: Float,
        bitmap: Bitmap,
        onReady: (center: Float, left: Float, right: Float, top: Float, bottom: Float) -> Unit
    ) {
        val loc = IntArray(2)
        getLocationInWindow(loc)

        val winCx = loc[0] + viewCx.roundToInt()
        val winCy = loc[1] + viewCy.roundToInt()

        val decorView = window.decorView
        val winW = decorView.width.coerceAtLeast(sampleSize)
        val winH = decorView.height.coerceAtLeast(sampleSize)

        val winLeft = (winCx - sampleHalf).coerceIn(0, winW - sampleSize)
        val winTop = (winCy - sampleHalf).coerceIn(0, winH - sampleSize)
        val srcRect = Rect(winLeft, winTop, winLeft + sampleSize, winTop + sampleSize)

        try {
            PixelCopy.request(window, srcRect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    bitmap.getPixels(rawPixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

                    val mid = sampleHalf
                    val probeDist = (reticleLength * 0.55f).roundToInt().coerceIn(15, sampleHalf - 10)
                    val offset = (8f * density).roundToInt() // Отступ вбок от линии во избежание самосчитывания

                    fun getLuma(x: Int, y: Int): Float {
                        val px = x.coerceIn(0, sampleSize - 1)
                        val py = y.coerceIn(0, sampleSize - 1)
                        val p = rawPixels[py * sampleSize + px]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        return 0.299f * r + 0.587f * g + 0.114f * b
                    }

                    // Замер центра (вокруг микро-точки)
                    val cLuma = (getLuma(mid - offset, mid) + getLuma(mid + offset, mid) +
                                 getLuma(mid, mid - offset) + getLuma(mid, mid + offset)) / 4f

                    // Замер лучей: среднее по бокам от луча (строго чистый фон)
                    val lLuma = (getLuma(mid - probeDist, mid - offset) + getLuma(mid - probeDist, mid + offset)) / 2f
                    val rLuma = (getLuma(mid + probeDist, mid - offset) + getLuma(mid + probeDist, mid + offset)) / 2f
                    val tLuma = (getLuma(mid - offset, mid - probeDist) + getLuma(mid + offset, mid - probeDist)) / 2f
                    val bLuma = (getLuma(mid - offset, mid + probeDist) + getLuma(mid + offset, mid + probeDist)) / 2f

                    // Плавная непрерывная инверсия: 255 - Luma (через серый)
                    val cTone = (255f - cLuma).coerceIn(0f, 255f)
                    val lTone = (255f - lLuma).coerceIn(0f, 255f)
                    val rTone = (255f - rLuma).coerceIn(0f, 255f)
                    val tTone = (255f - tLuma).coerceIn(0f, 255f)
                    val bTone = (255f - bLuma).coerceIn(0f, 255f)

                    post { onReady(cTone, lTone, rTone, tTone, bTone) }
                }
            }, sampleHandler)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Прицел тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawTaperedReticle(canvas, cx, cy, thmToneCenter, thmToneLeft, thmToneRight, thmToneTop, thmToneBottom)
        }

        // 2. Прицел оптической камеры (с учетом "ТЯНИ")
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawTaperedReticle(canvas, cx, cy, optToneCenter, optToneLeft, optToneRight, optToneTop, optToneBottom)
        }
    }

    /**
     * Отрисовка тактического прицела Duplex с сужением к центру
     */
    private fun drawTaperedReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        toneCenter: Float,
        toneLeft: Float,
        toneRight: Float,
        toneTop: Float,
        toneBottom: Float
    ) {
        val halfThick = thickWidth / 2f
        val halfThin = thinWidth / 2f

        // 1. Центральная микро-марка
        val cVal = toneCenter.roundToInt().coerceIn(0, 255)
        dotPaint.color = Color.rgb(cVal, cVal, cVal)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)

        // 2. Левый луч
        val lVal = toneLeft.roundToInt().coerceIn(0, 255)
        val lColor = Color.rgb(lVal, lVal, lVal)
        thickPaint.color = lColor
        taperPaint.color = lColor
        thickPaint.strokeWidth = thickWidth
        // Внешние 2/3
        canvas.drawLine(cx - reticleLength, cy, cx - taperStart, cy, thickPaint)
        // Внутреннее сужение 1/3 к центру
        taperPath.reset()
        taperPath.moveTo(cx - taperStart, cy - halfThick)
        taperPath.lineTo(cx - centerGap, cy - halfThin)
        taperPath.lineTo(cx - centerGap, cy + halfThin)
        taperPath.lineTo(cx - taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, taperPaint)

        // 3. Правый луч
        val rVal = toneRight.roundToInt().coerceIn(0, 255)
        val rColor = Color.rgb(rVal, rVal, rVal)
        thickPaint.color = rColor
        taperPaint.color = rColor
        canvas.drawLine(cx + taperStart, cy, cx + reticleLength, cy, thickPaint)
        taperPath.reset()
        taperPath.moveTo(cx + taperStart, cy - halfThick)
        taperPath.lineTo(cx + centerGap, cy - halfThin)
        taperPath.lineTo(cx + centerGap, cy + halfThin)
        taperPath.lineTo(cx + taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, taperPaint)

        // 4. Верхний луч
        val tVal = toneTop.roundToInt().coerceIn(0, 255)
        val tColor = Color.rgb(tVal, tVal, tVal)
        thickPaint.color = tColor
        taperPaint.color = tColor
        canvas.drawLine(cx, cy - reticleLength, cx, cy - taperStart, thickPaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy - taperStart)
        taperPath.lineTo(cx - halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThick, cy - taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, taperPaint)

        // 5. Нижний луч
        val bVal = toneBottom.roundToInt().coerceIn(0, 255)
        val bColor = Color.rgb(bVal, bVal, bVal)
        thickPaint.color = bColor
        taperPaint.color = bColor
        canvas.drawLine(cx, cy + taperStart, cx, cy + reticleLength, thickPaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy + taperStart)
        taperPath.lineTo(cx - halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThick, cy + taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, taperPaint)
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
