package com.example.thermalcapture

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
import androidx.camera.view.PreviewView
import com.serenegiant.widget.AspectRatioSurfaceView
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Геометрия Duplex (German #4)
    private val reticleLength = 70f * density
    private val centerGap = 7f * density
    private val taperStart = 24f * density
    private val thickWidth = 3.6f * density
    private val thinWidth = 1.2f * density
    private val dotRadius = 1.4f * density

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

    private val taperPath = Path()

    // Смещение юстировки ("ТЯНИ")
    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var thermalRect = RectF()
    var opticalRect = RectF()

    // Прямые ссылки на видеоисточники для считывания чистого видеопотока
    var phoneCameraView: PreviewView? = null
    var thermalCameraView: AspectRatioSurfaceView? = null

    // Тона лучей и марки (255 = белый, 0 = черный)
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

    // Буфер для замера CVBS тепловизора
    private val thermalPatchSize = 120
    private val thermalBitmap = Bitmap.createBitmap(thermalPatchSize, thermalPatchSize, Bitmap.Config.ARGB_8888)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleOpticalCleanFrame()
            sampleThermalCleanFrame()
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

    /**
     * Замер оптической камеры: берем ЧИСТЫЙ кадр сенсора без единого пикселя наложенного прицела!
     */
    private fun sampleOpticalCleanFrame() {
        val pView = phoneCameraView ?: return
        if (pView.width <= 0 || pView.height <= 0) return

        // Чистый кадр камеры прямо из видеобуфера
        val bitmap = pView.bitmap ?: return

        val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
        val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y

        val scaleX = bitmap.width.toFloat() / pView.width
        val scaleY = bitmap.height.toFloat() / pView.height

        val bx = (cx * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val by = (cy * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)

        val thickDistX = (reticleLength * 0.65f * scaleX).roundToInt()
        val thinDistX = (centerGap * 1.8f * scaleX).roundToInt()
        val thickDistY = (reticleLength * 0.65f * scaleY).roundToInt()
        val thinDistY = (centerGap * 1.8f * scaleY).roundToInt()

        fun getLuma(x: Int, y: Int): Float {
            val px = x.coerceIn(0, bitmap.width - 1)
            val py = y.coerceIn(0, bitmap.height - 1)
            val p = bitmap.getPixel(px, py)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Замер СТРОГО по марке и по линиям из чистого кадра
        val cLuma = getLuma(bx, by)
        val lLuma = getLuma(bx - thickDistX, by) * 0.6f + getLuma(bx - thinDistX, by) * 0.4f
        val rLuma = getLuma(bx + thickDistX, by) * 0.6f + getLuma(bx + thinDistX, by) * 0.4f
        val tLuma = getLuma(bx, by - thickDistY) * 0.6f + getLuma(bx, by - thinDistY) * 0.4f
        val bLuma = getLuma(bx, by + thickDistY) * 0.6f + getLuma(bx, by + thinDistY) * 0.4f

        fun toTone(luma: Float): Float = (255f - luma).coerceIn(0f, 255f)

        // Плавный отклик без мерцания
        optToneCenter = optToneCenter * 0.65f + toTone(cLuma) * 0.35f
        optToneLeft = optToneLeft * 0.65f + toTone(lLuma) * 0.35f
        optToneRight = optToneRight * 0.65f + toTone(rLuma) * 0.35f
        optToneTop = optToneTop * 0.65f + toTone(tLuma) * 0.35f
        optToneBottom = optToneBottom * 0.65f + toTone(bLuma) * 0.35f

        invalidate()
    }

    /**
     * Замер тепловизора CVBS: замеряем чистый аппаратный SurfaceView видеозахвата
     */
    private fun sampleThermalCleanFrame() {
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
        val half = thermalPatchSize / 2

        val srcRect = Rect(
            (surfX - half).coerceIn(0, frame.width() - thermalPatchSize),
            (surfY - half).coerceIn(0, frame.height() - thermalPatchSize),
            (surfX + half).coerceIn(thermalPatchSize, frame.width()),
            (surfY + half).coerceIn(thermalPatchSize, frame.height())
        )

        try {
            PixelCopy.request(tView, srcRect, thermalBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val mid = half
                    fun getLuma(x: Int, y: Int): Float {
                        val px = x.coerceIn(0, thermalPatchSize - 1)
                        val py = y.coerceIn(0, thermalPatchSize - 1)
                        val p = thermalBitmap.getPixel(px, py)
                        return (p and 0xFF).toFloat() // Монохромный CVBS тепловизор
                    }

                    val dist = (reticleLength * 0.5f * scaleX).roundToInt().coerceIn(10, half - 5)
                    val cLuma = getLuma(mid, mid)
                    val lLuma = getLuma(mid - dist, mid)
                    val rLuma = getLuma(mid + dist, mid)
                    val tLuma = getLuma(mid, mid - dist)
                    val bLuma = getLuma(mid, mid + dist)

                    fun toTone(luma: Float): Float = (255f - luma).coerceIn(0f, 255f)

                    thmToneCenter = thmToneCenter * 0.65f + toTone(cLuma) * 0.35f
                    thmToneLeft = thmToneLeft * 0.65f + toTone(lLuma) * 0.35f
                    thmToneRight = thmToneRight * 0.65f + toTone(rLuma) * 0.35f
                    thmToneTop = thmToneTop * 0.65f + toTone(tLuma) * 0.35f
                    thmToneBottom = thmToneBottom * 0.65f + toTone(bLuma) * 0.35f

                    post { invalidate() }
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
        canvas.drawLine(cx - reticleLength, cy, cx - taperStart, cy, thickPaint)
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
