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

    // Сторона квадрата негатива
    private val cropSizeDp = 160f * density
    private val cropHalfDp = cropSizeDp / 2f

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

    // Шейдеры негатива
    private var opticalShader: BitmapShader? = null
    private val opticalMatrix = Matrix()

    private var thermalShader: BitmapShader? = null
    private val thermalMatrix = Matrix()

    // Буфер для тепловизора (CVBS)
    private val thermalCropPx = 140
    private val thermalRawBitmap = Bitmap.createBitmap(thermalCropPx, thermalCropPx, Bitmap.Config.ARGB_8888)
    private val thermalNegativeBitmap = Bitmap.createBitmap(thermalCropPx, thermalCropPx, Bitmap.Config.ARGB_8888)
    private val thermalRawPixels = IntArray(thermalCropPx * thermalCropPx)
    private val thermalNegPixels = IntArray(thermalCropPx * thermalCropPx)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    // Захват негатива 10 раз в секунду (без лагов и нагрева)
    private val sampleRunnable = object : Runnable {
        override fun run() {
            updateOpticalNegative()
            updateThermalNegative()
            invalidate()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 100)
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
     * Превращение области под оптическим прицелом в чистый ч/б негатив
     */
    private fun updateOpticalNegative() {
        val pView = phoneCameraView ?: return
        if (pView.width <= 0 || pView.height <= 0) return

        // 1. Берем чистый кадр камеры
        val fullBitmap = pView.bitmap ?: return

        try {
            val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
            val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y

            val scaleX = fullBitmap.width.toFloat() / pView.width
            val scaleY = fullBitmap.height.toFloat() / pView.height

            val cropWPx = (cropSizeDp * scaleX).roundToInt().coerceIn(40, fullBitmap.width)
            val cropHPx = (cropSizeDp * scaleY).roundToInt().coerceIn(40, fullBitmap.height)

            val leftPx = (cx * scaleX - cropWPx / 2f).roundToInt().coerceIn(0, fullBitmap.width - cropWPx)
            val topPx = (cy * scaleY - cropHPx / 2f).roundToInt().coerceIn(0, fullBitmap.height - cropHPx)

            // 2. Вырезаем только микро-область под прицелом
            val cropBitmap = Bitmap.createBitmap(fullBitmap, leftPx, topPx, cropWPx, cropHPx)

            // 3. МГНОВЕННО освобождаем 8 МБ тяжелого кадра (видео не тормозит!)
            fullBitmap.recycle()

            val pixels = IntArray(cropWPx * cropHPx)
            cropBitmap.getPixels(pixels, 0, cropWPx, 0, 0, cropWPx, cropHPx)
            val negPixels = IntArray(cropWPx * cropHPx)

            // 4. Попиксельный математический ч/б негатив с контрастным бустом
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8

                val rawNeg = 255 - luma
                val contrastNeg = if (rawNeg > 128) {
                    (128 + (rawNeg - 128) * 1.4f).roundToInt().coerceIn(0, 255)
                } else {
                    (128 - (128 - rawNeg) * 1.4f).roundToInt().coerceIn(0, 255)
                }

                negPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
            }

            val negativeBitmap = Bitmap.createBitmap(cropWPx, cropHPx, Bitmap.Config.ARGB_8888)
            negativeBitmap.setPixels(negPixels, 0, cropWPx, 0, 0, cropWPx, cropHPx)
            cropBitmap.recycle()

            // 5. Создаем шейдер и масштабируем в координаты экрана
            val shader = BitmapShader(negativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

            val screenLeft = cx + pView.x - (cropWPx / scaleX) / 2f
            val screenTop = cy + pView.y - (cropHPx / scaleY) / 2f

            opticalMatrix.reset()
            opticalMatrix.setScale(1f / scaleX, 1f / scaleY)
            opticalMatrix.postTranslate(screenLeft, screenTop)
            shader.setLocalMatrix(opticalMatrix)

            opticalShader = shader
        } catch (e: Exception) {
            fullBitmap.recycle()
        }
    }

    /**
     * Создание ч/б негатива для тепловизора (CVBS через аппаратный PixelCopy)
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
        val half = thermalCropPx / 2

        val srcRect = Rect(
            (surfCx - half).coerceIn(0, frame.width() - thermalCropPx),
            (surfCy - half).coerceIn(0, frame.height() - thermalCropPx),
            (surfCx + half).coerceIn(thermalCropPx, frame.width()),
            (surfCy + half).coerceIn(thermalCropPx, frame.height())
        )

        try {
            PixelCopy.request(tView, srcRect, thermalRawBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thermalRawBitmap.getPixels(thermalRawPixels, 0, thermalCropPx, 0, 0, thermalCropPx, thermalCropPx)

                    for (i in 0 until thermalCropPx * thermalCropPx) {
                        val luma = thermalRawPixels[i] and 0xFF
                        val rawNeg = 255 - luma
                        val contrastNeg = if (rawNeg > 128) {
                            (128 + (rawNeg - 128) * 1.4f).roundToInt().coerceIn(0, 255)
                        } else {
                            (128 - (128 - rawNeg) * 1.4f).roundToInt().coerceIn(0, 255)
                        }
                        thermalNegPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
                    }

                    thermalNegativeBitmap.setPixels(thermalNegPixels, 0, thermalCropPx, 0, 0, thermalCropPx, thermalCropPx)

                    val shader = BitmapShader(thermalNegativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

                    val screenLeft = thermalRect.centerX() - (half / scaleX)
                    val screenTop = thermalRect.centerY() - (half / scaleY)

                    thermalMatrix.reset()
                    thermalMatrix.setScale(1f / scaleX, 1f / scaleY)
                    thermalMatrix.postTranslate(screenLeft, screenTop)
                    shader.setLocalMatrix(thermalMatrix)

                    thermalShader = shader
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
            drawNegativeReticle(canvas, cx, cy, thermalShader)
        }

        // 2. Прицел оптической камеры (с учетом "ТЯНИ")
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawNegativeReticle(canvas, cx, cy, opticalShader)
        }
    }

    /**
     * Отрисовка геометрии прицела, заполненной живым негативом
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
            reticlePaint.color = Color.DKGRAY
            fillPaint.shader = null
            fillPaint.color = Color.DKGRAY
        }

        val halfThick = thickWidth / 2f
        val halfThin = thinWidth / 2f

        // 1. Центральная марка
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
