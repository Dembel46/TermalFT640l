package com.example.thermalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
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
    private val dotRadius = 1.5f * density

    // Размер области под негатив (квадрат, полностью покрывающий прицел)
    private val cropSize = (reticleLength * 2 + 10f * density).roundToInt().coerceIn(160, 360)
    private val cropHalf = cropSize / 2

    // Кисти прицела (рисуют негативным шейдером)
    private val reticlePaint = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
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

    var phoneCameraView: PreviewView? = null
    var thermalCameraView: AspectRatioSurfaceView? = null

    // Буферы для создания негатива (выделены 1 раз)
    private val opticalRawBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
    private val opticalNegativeBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
    private val thermalRawBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
    private val thermalNegativeBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

    private val rawPixels = IntArray(cropSize * cropSize)
    private val negPixels = IntArray(cropSize * cropSize)

    // Шейдеры негатива
    private var opticalShader: BitmapShader? = null
    private val opticalMatrix = Matrix()

    private var thermalShader: BitmapShader? = null
    private val thermalMatrix = Matrix()

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    // Захват негатива 12 раз в секунду (плавно и без нагрузки на GPU)
    private val sampleRunnable = object : Runnable {
        override fun run() {
            updateOpticalNegative()
            updateThermalNegative()
            invalidate()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 80)
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
     * Создание точного ч/б негатива под оптическим прицелом
     */
    private fun updateOpticalNegative() {
        val pView = phoneCameraView ?: return
        val surfaceView = pView.getChildAt(0) as? SurfaceView ?: return
        val surface = surfaceView.holder.surface ?: return
        if (!surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) return

        val frame = surfaceView.holder.surfaceFrame
        val scaleX = frame.width().toFloat() / surfaceView.width
        val scaleY = frame.height().toFloat() / surfaceView.height

        val cx = (opticalRect.centerX() + opticalOffsetX) - surfaceView.x
        val cy = (opticalRect.centerY() + opticalOffsetY) - surfaceView.y

        val surfCx = (cx * scaleX).roundToInt()
        val surfCy = (cy * scaleY).roundToInt()

        val halfW = (cropHalf * scaleX).roundToInt()
        val halfH = (cropHalf * scaleY).roundToInt()

        val srcRect = Rect(
            (surfCx - halfW).coerceIn(0, frame.width() - (cropSize * scaleX).roundToInt().coerceAtLeast(1)),
            (surfCy - halfH).coerceIn(0, frame.height() - (cropSize * scaleY).roundToInt().coerceAtLeast(1)),
            (surfCx + halfW).coerceIn((cropSize * scaleX).roundToInt().coerceAtLeast(1), frame.width()),
            (surfCy + halfH).coerceIn((cropSize * scaleY).roundToInt().coerceAtLeast(1), frame.height())
        )

        try {
            PixelCopy.request(surfaceView, srcRect, opticalRawBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    opticalRawBitmap.getPixels(rawPixels, 0, cropSize, 0, 0, cropSize, cropSize)

                    // Превращение каждого пикселя в высококонтрастный ч/б негатив
                    for (i in 0 until cropSize * cropSize) {
                        val p = rawPixels[i]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) shr 8

                        // Формула ч/б негатива с усилением контраста
                        val rawNeg = 255 - luma
                        val contrastNeg = if (rawNeg > 128) {
                            (128 + (rawNeg - 128) * 1.35f).roundToInt().coerceIn(0, 255)
                        } else {
                            (128 - (128 - rawNeg) * 1.35f).roundToInt().coerceIn(0, 255)
                        }

                        negPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
                    }

                    opticalNegativeBitmap.setPixels(negPixels, 0, cropSize, 0, 0, cropSize, cropSize)

                    val globalCx = opticalRect.centerX() + opticalOffsetX
                    val globalCy = opticalRect.centerY() + opticalOffsetY

                    opticalShader = BitmapShader(opticalNegativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    opticalMatrix.setTranslate(globalCx - cropHalf, globalCy - cropHalf)
                    opticalShader?.setLocalMatrix(opticalMatrix)
                }
            }, sampleHandler)
        } catch (e: Exception) {}
    }

    /**
     * Создание точного ч/б негатива под тепловизионным прицелом (CVBS)
     */
    private fun updateThermalNegative() {
        val tView = thermalCameraView ?: return
        val surface = tView.holder.surface ?: return
        if (!surface.isValid || tView.width <= 0 || tView.height <= 0) return

        val frame = tView.holder.surfaceFrame
        val scaleX = frame.width().toFloat() / tView.width
        val scaleY = frame.height().toFloat() / tView.height

        val cx = thermalRect.centerX() - tView.x
        val cy = thermalRect.centerY() - tView.y

        val surfCx = (cx * scaleX).roundToInt()
        val surfCy = (cy * scaleY).roundToInt()

        val halfW = (cropHalf * scaleX).roundToInt()
        val halfH = (cropHalf * scaleY).roundToInt()

        val srcRect = Rect(
            (surfCx - halfW).coerceIn(0, frame.width() - (cropSize * scaleX).roundToInt().coerceAtLeast(1)),
            (surfCy - halfH).coerceIn(0, frame.height() - (cropSize * scaleY).roundToInt().coerceAtLeast(1)),
            (surfCx + halfW).coerceIn((cropSize * scaleX).roundToInt().coerceAtLeast(1), frame.width()),
            (surfCy + halfH).coerceIn((cropSize * scaleY).roundToInt().coerceAtLeast(1), frame.height())
        )

        try {
            PixelCopy.request(tView, srcRect, thermalRawBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thermalRawBitmap.getPixels(rawPixels, 0, cropSize, 0, 0, cropSize, cropSize)

                    for (i in 0 until cropSize * cropSize) {
                        val luma = rawPixels[i] and 0xFF
                        val rawNeg = 255 - luma
                        val contrastNeg = if (rawNeg > 128) {
                            (128 + (rawNeg - 128) * 1.35f).roundToInt().coerceIn(0, 255)
                        } else {
                            (128 - (128 - rawNeg) * 1.35f).roundToInt().coerceIn(0, 255)
                        }

                        negPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
                    }

                    thermalNegativeBitmap.setPixels(negPixels, 0, cropSize, 0, 0, cropSize, cropSize)

                    val globalCx = thermalRect.centerX()
                    val globalCy = thermalRect.centerY()

                    thermalShader = BitmapShader(thermalNegativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    thermalMatrix.setTranslate(globalCx - cropHalf, globalCy - cropHalf)
                    thermalShader?.setLocalMatrix(thermalMatrix)
                }
            }, sampleHandler)
        } catch (e: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Отрисовка негативного прицела тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawNegativeReticle(canvas, cx, cy, thermalShader)
        }

        // 2. Отрисовка негативного прицела оптической камеры (с учетом "ТЯНИ")
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawNegativeReticle(canvas, cx, cy, opticalShader)
        }
    }

    /**
     * Нанесение геометрии сетки, залитой живым негативом фона
     */
    private fun drawNegativeReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        negativeShader: BitmapShader?
    ) {
        if (negativeShader != null) {
            reticlePaint.shader = negativeShader
            fillPaint.shader = negativeShader
        } else {
            reticlePaint.shader = null
            reticlePaint.color = Color.WHITE
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
        }

        val halfThick = thickWidth / 2f
        val halfThin = thinWidth / 2f

        // 1. Центральная микро-марка
        canvas.drawCircle(cx, cy, dotRadius, fillPaint)

        // 2. Левый луч
        reticlePaint.strokeWidth = thickWidth
        canvas.drawLine(cx - reticleLength, cy, cx - taperStart, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - taperStart, cy - halfThick)
        taperPath.lineTo(cx - centerGap, cy - halfThin)
        taperPath.lineTo(cx - centerGap, cy + halfThin)
        taperPath.lineTo(cx - taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // 3. Правый луч
        canvas.drawLine(cx + taperStart, cy, cx + reticleLength, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx + taperStart, cy - halfThick)
        taperPath.lineTo(cx + centerGap, cy - halfThin)
        taperPath.lineTo(cx + centerGap, cy + halfThin)
        taperPath.lineTo(cx + taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // 4. Верхний луч
        canvas.drawLine(cx, cy - reticleLength, cx, cy - taperStart, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy - taperStart)
        taperPath.lineTo(cx - halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThick, cy - taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // 5. Нижний луч
        canvas.drawLine(cx, cy + taperStart, cx, cy + reticleLength, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy + taperStart)
        taperPath.lineTo(cx - halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThick, cy + taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)
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
