package com.example.thermalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.TextureView
import android.view.View
import androidx.camera.view.PreviewView
import com.serenegiant.widget.AspectRatioSurfaceView
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Количество сегментов на каждом из 4 лучей
    private val numSegments = 10

    // Геометрия Duplex (German #4)
    private val reticleLength = 70f * density
    private val centerGap = 7f * density
    private val taperStart = 24f * density
    private val thickWidth = 3.6f * density
    private val thinWidth = 1.2f * density
    private val dotRadius = 1.4f * density

    // Кисть для отрисовки сегментов
    private val segmentPaint = Paint().apply {
        strokeCap = Paint.Cap.BUTT
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Смещение юстировки ("ТЯНИ")
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var thermalRect = RectF()
    var opticalRect = RectF()

    var phoneCameraView: PreviewView? = null
    var thermalCameraView: AspectRatioSurfaceView? = null

    // Тона сегментов (по 10 на каждый луч + 1 на центр)
    private val optToneLeft = FloatArray(numSegments) { 255f }
    private val optToneRight = FloatArray(numSegments) { 255f }
    private val optToneTop = FloatArray(numSegments) { 255f }
    private val optToneBottom = FloatArray(numSegments) { 255f }
    private var optToneCenter = 255f

    private val thmToneLeft = FloatArray(numSegments) { 255f }
    private val thmToneRight = FloatArray(numSegments) { 255f }
    private val thmToneTop = FloatArray(numSegments) { 255f }
    private val thmToneBottom = FloatArray(numSegments) { 255f }
    private var thmToneCenter = 255f

    // Расширенная зона сканирования (300x300 пикселей)
    private val sampleSize = 300
    private val opticalSampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val thermalBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleOpticalHardware()
            sampleThermalHardware()
            invalidate()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 90)
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

    /**
     * Замер яркости квадрата 3x3 пикселя вокруг заданной координаты
     */
    private fun getAverageLuma3x3(bitmap: Bitmap, cx: Int, cy: Int): Float {
        var sum = 0f
        for (dy in -1..1) {
            val y = (cy + dy).coerceIn(0, sampleSize - 1)
            for (dx in -1..1) {
                val x = (cx + dx).coerceIn(0, sampleSize - 1)
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return sum / 9f
    }

    /**
     * Аппаратный сегментный замер оптической камеры
     */
    private fun sampleOpticalHardware() {
        val pView = phoneCameraView ?: return
        val textureView = pView.getChildAt(0) as? TextureView ?: return
        if (textureView.width <= 0 || textureView.height <= 0) return

        textureView.getBitmap(opticalSampleBitmap)

        val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
        val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y

        val nx = (cx / pView.width).coerceIn(0f, 1f)
        val ny = (cy / pView.height).coerceIn(0f, 1f)

        val bx = (nx * (sampleSize - 1)).roundToInt()
        val by = (ny * (sampleSize - 1)).roundToInt()

        val scaleX = sampleSize.toFloat() / pView.width
        val scaleY = sampleSize.toFloat() / pView.height

        // Замер центральной марки 3x3
        val cLuma = getAverageLuma3x3(opticalSampleBitmap, bx, by)
        val cTone = (255f - cLuma).coerceIn(0f, 255f)
        optToneCenter = optToneCenter * 0.65f + cTone * 0.35f

        // Посегментный замер 10 участков для каждого из 4 лучей
        val segStep = (reticleLength - centerGap) / numSegments

        for (i in 0 until numSegments) {
            val segDist = centerGap + (i + 0.5f) * segStep
            val dx = (segDist * scaleX).roundToInt()
            val dy = (segDist * scaleY).roundToInt()

            // Левый луч
            val lLuma = getAverageLuma3x3(opticalSampleBitmap, bx - dx, by)
            val lTone = (255f - lLuma).coerceIn(0f, 255f)
            optToneLeft[i] = optToneLeft[i] * 0.65f + lTone * 0.35f

            // Правый луч
            val rLuma = getAverageLuma3x3(opticalSampleBitmap, bx + dx, by)
            val rTone = (255f - rLuma).coerceIn(0f, 255f)
            optToneRight[i] = optToneRight[i] * 0.65f + rTone * 0.35f

            // Верхний луч
            val tLuma = getAverageLuma3x3(opticalSampleBitmap, bx, by - dy)
            val tTone = (255f - tLuma).coerceIn(0f, 255f)
            optToneTop[i] = optToneTop[i] * 0.65f + tTone * 0.35f

            // Нижний луч
            val bLuma = getAverageLuma3x3(opticalSampleBitmap, bx, by + dy)
            val bTone = (255f - bLuma).coerceIn(0f, 255f)
            optToneBottom[i] = optToneBottom[i] * 0.65f + bTone * 0.35f
        }
    }

    /**
     * Аппаратный сегментный замер CVBS тепловизора
     */
    private fun sampleThermalHardware() {
        val tView = thermalCameraView ?: return
        val surface = tView.holder.surface ?: return
        if (!surface.isValid || tView.width <= 0 || tView.height <= 0) return

        val frame = tView.holder.surfaceFrame
        val scaleX = frame.width().toFloat() / tView.width
        val scaleY = frame.height().toFloat() / tView.height

        val cx = thermalRect.centerX() - tView.x
        val cy = thermalRect.centerY() - tView.y

        val surfX = (cx * scaleX).roundToInt()
        val surfY = (cy * scaleY).roundToInt()
        val half = sampleSize / 2

        val srcRect = Rect(
            (surfX - half).coerceIn(0, frame.width() - sampleSize),
            (surfY - half).coerceIn(0, frame.height() - sampleSize),
            (surfX + half).coerceIn(sampleSize, frame.width()),
            (surfY + half).coerceIn(sampleSize, frame.height())
        )

        try {
            PixelCopy.request(tView, srcRect, thermalBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val mid = half

                    val cLuma = getAverageLuma3x3(thermalBitmap, mid, mid)
                    val cTone = (255f - cLuma).coerceIn(0f, 255f)
                    thmToneCenter = thmToneCenter * 0.65f + cTone * 0.35f

                    val segStep = (reticleLength - centerGap) / numSegments
                    for (i in 0 until numSegments) {
                        val segDist = ((centerGap + (i + 0.5f) * segStep) * scaleX).roundToInt()

                        val lLuma = getAverageLuma3x3(thermalBitmap, mid - segDist, mid)
                        val lTone = (255f - lLuma).coerceIn(0f, 255f)
                        thmToneLeft[i] = thmToneLeft[i] * 0.65f + lTone * 0.35f

                        val rLuma = getAverageLuma3x3(thermalBitmap, mid + segDist, mid)
                        val rTone = (255f - rLuma).coerceIn(0f, 255f)
                        thmToneRight[i] = thmToneRight[i] * 0.65f + rTone * 0.35f

                        val tLuma = getAverageLuma3x3(thermalBitmap, mid, mid - segDist)
                        val tTone = (255f - tLuma).coerceIn(0f, 255f)
                        thmToneTop[i] = thmToneTop[i] * 0.65f + tTone * 0.35f

                        val bLuma = getAverageLuma3x3(thermalBitmap, mid, mid + segDist)
                        val bTone = (255f - bLuma).coerceIn(0f, 255f)
                        thmToneBottom[i] = thmToneBottom[i] * 0.65f + bTone * 0.35f
                    }
                }
            }, sampleHandler)
        } catch (e: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Прицел тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawSegmentedReticle(canvas, cx, cy, thmToneCenter, thmToneLeft, thmToneRight, thmToneTop, thmToneBottom)
        }

        // 2. Прицел оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawSegmentedReticle(canvas, cx, cy, optToneCenter, optToneLeft, optToneRight, optToneTop, optToneBottom)
        }
    }

    /**
     * Толщина линии в зависимости от расстояния от центра (сужение к центру)
     */
    private fun getStrokeWidthAt(r: Float): Float {
        return if (r <= taperStart) {
            thinWidth + (thickWidth - thinWidth) * ((r - centerGap) / (taperStart - centerGap)).coerceIn(0f, 1f)
        } else {
            thickWidth
        }
    }

    /**
     * Посегментная отрисовка тактического прицела
     */
    private fun drawSegmentedReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        toneCenter: Float,
        toneLeft: FloatArray,
        toneRight: FloatArray,
        toneTop: FloatArray,
        toneBottom: FloatArray
    ) {
        // 1. Центральная микро-марка
        val cVal = toneCenter.roundToInt().coerceIn(0, 255)
        dotPaint.color = Color.rgb(cVal, cVal, cVal)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)

        val segStep = (reticleLength - centerGap) / numSegments

        // 2. Отрисовка 10 сегментов каждого луча со своей контрастностью и толщиной
        for (i in 0 until numSegments) {
            val r1 = centerGap + i * segStep
            val r2 = centerGap + (i + 1) * segStep
            val avgW = (getStrokeWidthAt(r1) + getStrokeWidthAt(r2)) / 2f
            segmentPaint.strokeWidth = avgW

            // Левый луч
            val lVal = toneLeft[i].roundToInt().coerceIn(0, 255)
            segmentPaint.color = Color.rgb(lVal, lVal, lVal)
            canvas.drawLine(cx - r2, cy, cx - r1, cy, segmentPaint)

            // Правый луч
            val rVal = toneRight[i].roundToInt().coerceIn(0, 255)
            segmentPaint.color = Color.rgb(rVal, rVal, rVal)
            canvas.drawLine(cx + r1, cy, cx + r2, cy, segmentPaint)

            // Верхний луч
            val tVal = toneTop[i].roundToInt().coerceIn(0, 255)
            segmentPaint.color = Color.rgb(tVal, tVal, tVal)
            canvas.drawLine(cx, cy - r2, cx, cy - r1, segmentPaint)

            // Нижний луч
            val bVal = toneBottom[i].roundToInt().coerceIn(0, 255)
            segmentPaint.color = Color.rgb(bVal, bVal, bVal)
            canvas.drawLine(cx, cy + r1, cx, cy + r2, segmentPaint)
        }
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
