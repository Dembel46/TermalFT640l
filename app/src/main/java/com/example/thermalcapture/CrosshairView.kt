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
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Плотность экрана для адаптации под любые разрешения смартфонов
    private val density = context.resources.displayMetrics.density

    // Геометрия прицела (в аппаратно-независимых DP)
    private val reticleLength = 70f * density
    private val centerGap = 16f * density
    private val ringRadius = 48f * density
    private val tickLength = 10f * density
    private val strokeWidthVal = 3.5f * density

    // Адаптивный размер зоны сканирования под прицел
    private val patchSize = (ringRadius * 2.4f).roundToInt().coerceIn(120, 360)
    private val patchHalf = patchSize / 2

    // Кисть прицела (чистая монолитная линия)
    private val reticlePaint = Paint().apply {
        strokeWidth = strokeWidthVal
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val dotFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Смещение юстировки (функция "ТЯНИ")
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    // Границы окон тепловизора (CVBS) и дневной камеры
    var thermalRect = RectF()
    var opticalRect = RectF()

    // Буферы без выделения памяти в цикле (предотвращает micro-stuttering)
    private val sampleBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val reticleBitmapOptical = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val sampleBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)
    private val reticleBitmapThermal = Bitmap.createBitmap(patchSize, patchSize, Bitmap.Config.ARGB_8888)

    // Рабочие массивы для быстрого пространственного анализа фона
    private val rawPixels = IntArray(patchSize * patchSize)
    private val lumaBuffer = IntArray(patchSize * patchSize)
    private val smoothLuma = IntArray(patchSize * patchSize)
    private val outPixels = IntArray(patchSize * patchSize)

    private var shaderOptical: BitmapShader? = null
    private val matrixOptical = Matrix()

    private var shaderThermal: BitmapShader? = null
    private val matrixThermal = Matrix()

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    // Частота анализа (~15 раз в секунду)
    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleSurfaces()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 65)
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

        // 1. Оптическая камера: замер строго с учетом смещения "ТЯНИ"
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            probeWindowPatch(window, cx, cy, sampleBitmapOptical, reticleBitmapOptical) { left, top ->
                shaderOptical = BitmapShader(reticleBitmapOptical, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixOptical.setTranslate(left.toFloat(), top.toFloat())
                shaderOptical?.setLocalMatrix(matrixOptical)
                invalidate()
            }
        }

        // 2. Тепловизор: CVBS аналоговый сигнал в границах кадра
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            probeWindowPatch(window, cx, cy, sampleBitmapThermal, reticleBitmapThermal) { left, top ->
                shaderThermal = BitmapShader(reticleBitmapThermal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrixThermal.setTranslate(left.toFloat(), top.toFloat())
                shaderThermal?.setLocalMatrix(matrixThermal)
                invalidate()
            }
        }
    }

    /**
     * Прецизионный замер фона под текущим положением прицела
     */
    private fun probeWindowPatch(
        window: android.view.Window,
        viewCx: Float,
        viewCy: Float,
        sampleBitmap: Bitmap,
        outputBitmap: Bitmap,
        onReady: (viewLeft: Int, viewTop: Int) -> Unit
    ) {
        val loc = IntArray(2)
        getLocationInWindow(loc)

        // Текущие абсолютные координаты центра прицела на экране
        val winCx = loc[0] + viewCx.roundToInt()
        val winCy = loc[1] + viewCy.roundToInt()

        val decorView = window.decorView
        val winW = decorView.width.coerceAtLeast(patchSize)
        val winH = decorView.height.coerceAtLeast(patchSize)

        // Зона измерений строго под перекрестием
        val winLeft = (winCx - patchHalf).coerceIn(0, winW - patchSize)
        val winTop = (winCy - patchHalf).coerceIn(0, winH - patchSize)
        val srcRect = Rect(winLeft, winTop, winLeft + patchSize, winTop + patchSize)

        try {
            PixelCopy.request(window, srcRect, sampleBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    sampleBitmap.getPixels(rawPixels, 0, patchSize, 0, 0, patchSize, patchSize)

                    // 1. Расчет первичной яркости каждого пикселя фона
                    for (i in 0 until patchSize * patchSize) {
                        val p = rawPixels[i]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        lumaBuffer[i] = (r * 77 + g * 150 + b * 29) shr 8
                    }

                    // 2. Пространственный 3x3 фильтр с подавлением шума и линий
                    val black = 0xFF000000.toInt()
                    val white = 0xFFFFFFFF.toInt()

                    for (y in 0 until patchSize) {
                        val row = y * patchSize
                        for (x in 0 until patchSize) {
                            if (x > 0 && x < patchSize - 1 && y > 0 && y < patchSize - 1) {
                                val sum = lumaBuffer[row - patchSize + x - 1] + lumaBuffer[row - patchSize + x] + lumaBuffer[row - patchSize + x + 1] +
                                          lumaBuffer[row + x - 1]             + (lumaBuffer[row + x] shl 1)     + lumaBuffer[row + x + 1] +
                                          lumaBuffer[row + patchSize + x - 1] + lumaBuffer[row + patchSize + x] + lumaBuffer[row + patchSize + x + 1]
                                smoothLuma[row + x] = sum / 10
                            } else {
                                smoothLuma[row + x] = lumaBuffer[row + x]
                            }

                            // Порог контрастности: светлый фон (>122) -> ЧЕРНЫЙ, темный (<=122) -> БЕЛЫЙ
                            outPixels[row + x] = if (smoothLuma[row + x] > 122) black else white
                        }
                    }

                    outputBitmap.setPixels(outPixels, 0, patchSize, 0, 0, patchSize, patchSize)

                    val localLeft = winLeft - loc[0]
                    val localTop = winTop - loc[1]
                    post { onReady(localLeft, localTop) }
                }
            }, sampleHandler)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Тепловизор (CVBS поток)
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawZonalReticle(canvas, cx, cy, shaderThermal)
        }

        // 2. Оптическая камера (с учетом поправки "ТЯНИ")
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
            reticlePaint.color = Color.BLACK
        }

        drawReticleGeometry(canvas, cx, cy, reticlePaint)

        dotFillPaint.shader = reticlePaint.shader
        if (reticlePaint.shader == null) dotFillPaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 3.5f * density, dotFillPaint)
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
