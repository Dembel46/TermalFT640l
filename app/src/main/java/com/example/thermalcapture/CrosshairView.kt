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

    private val negSize = 120
    private val totalNegPixels = negSize * negSize

    private val opticalNegativeBitmap = Bitmap.createBitmap(negSize, negSize, Bitmap.Config.ARGB_8888)
    private val opticalRawPixels = IntArray(totalNegPixels)
    private val opticalNegPixels = IntArray(totalNegPixels)
    private val opticalShader = BitmapShader(opticalNegativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val opticalMatrix = Matrix()

    private val thermalRawBitmap = Bitmap.createBitmap(negSize, negSize, Bitmap.Config.ARGB_8888)
    private val thermalNegativeBitmap = Bitmap.createBitmap(negSize, negSize, Bitmap.Config.ARGB_8888)
    private val thermalRawPixels = IntArray(totalNegPixels)
    private val thermalNegPixels = IntArray(totalNegPixels)
    private val thermalShader = BitmapShader(thermalNegativeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val thermalMatrix = Matrix()

    private val reticlePaint = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Кисти для рамок целей машинного зрения
    private val boxMovingPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        isAntiAlias = true
    }

    private val boxStaticPaint = Paint().apply {
        color = Color.parseColor("#FF9900") // Янтарный для неподвижных горячих точек
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val taperPath = Path()

    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var thermalRect = RectF()
    var opticalRect = RectF()

    var phoneCameraView: PreviewView? = null
    var thermalCameraView: AspectRatioSurfaceView? = null

    // Список обнаруженных целей от ThermalVisionEngine
    var detectedTargets: List<DetectedTarget> = emptyList()

    private var isOpticalReady = false
    private var isThermalReady = false

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            updateOpticalFast()
            updateThermalFast()
            invalidate()
            if (isSamplingActive) {
                sampleHandler.postDelayed(this, 33)
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

    private fun updateOpticalFast() {
        val pView = phoneCameraView ?: return
        if (pView.width <= 0 || pView.height <= 0) return

        val fullBitmap = pView.bitmap ?: return

        try {
            val scaleX = fullBitmap.width.toFloat() / pView.width
            val scaleY = fullBitmap.height.toFloat() / pView.height

            val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
            val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y

            val centerBmpX = (cx * scaleX).roundToInt()
            val centerBmpY = (cy * scaleY).roundToInt()

            val maxLeft = (fullBitmap.width - negSize).coerceAtLeast(0)
            val maxTop = (fullBitmap.height - negSize).coerceAtLeast(0)

            val leftPx = (centerBmpX - negSize / 2).coerceIn(0, maxLeft)
            val topPx = (centerBmpY - negSize / 2).coerceIn(0, maxTop)

            fullBitmap.getPixels(opticalRawPixels, 0, negSize, leftPx, topPx, negSize, negSize)
            fullBitmap.recycle()

            for (i in 0 until totalNegPixels) {
                val p = opticalRawPixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8

                val rawNeg = 255 - luma
                val contrastNeg = if (rawNeg > 128) {
                    (128 + (rawNeg - 128) * 1.4f).toInt().coerceIn(0, 255)
                } else {
                    (128 - (128 - rawNeg) * 1.4f).toInt().coerceIn(0, 255)
                }

                opticalNegPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
            }

            opticalNegativeBitmap.setPixels(opticalNegPixels, 0, negSize, 0, 0, negSize, negSize)

            val screenLeft = leftPx / scaleX + pView.x
            val screenTop = topPx / scaleY + pView.y

            opticalMatrix.reset()
            opticalMatrix.setScale(1f / scaleX, 1f / scaleY)
            opticalMatrix.postTranslate(screenLeft, screenTop)
            opticalShader.setLocalMatrix(opticalMatrix)

            isOpticalReady = true
        } catch (e: Exception) {
            fullBitmap.recycle()
        }
    }

    private fun updateThermalFast() {
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
        val half = negSize / 2

        val srcRect = Rect(
            (surfCx - half).coerceIn(0, (frame.width() - negSize).coerceAtLeast(0)),
            (surfCy - half).coerceIn(0, (frame.height() - negSize).coerceAtLeast(0)),
            (surfCx + half).coerceIn(negSize, frame.width()),
            (surfCy + half).coerceIn(negSize, frame.height())
        )

        try {
            PixelCopy.request(tView, srcRect, thermalRawBitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thermalRawBitmap.getPixels(thermalRawPixels, 0, negSize, 0, 0, negSize, negSize)

                    for (i in 0 until totalNegPixels) {
                        val luma = thermalRawPixels[i] and 0xFF
                        val rawNeg = 255 - luma
                        val contrastNeg = if (rawNeg > 128) {
                            (128 + (rawNeg - 128) * 1.4f).toInt().coerceIn(0, 255)
                        } else {
                            (128 - (128 - rawNeg) * 1.4f).toInt().coerceIn(0, 255)
                        }
                        thermalNegPixels[i] = 0xFF000000.toInt() or (contrastNeg shl 16) or (contrastNeg shl 8) or contrastNeg
                    }

                    thermalNegativeBitmap.setPixels(thermalNegPixels, 0, negSize, 0, 0, negSize, negSize)

                    val screenLeft = (srcRect.left / scaleX) + tView.x
                    val screenTop = (srcRect.top / scaleY) + tView.y

                    thermalMatrix.reset()
                    thermalMatrix.setScale(1f / scaleX, 1f / scaleY)
                    thermalMatrix.postTranslate(screenLeft, screenTop)
                    thermalShader.setLocalMatrix(thermalMatrix)

                    isThermalReady = true
                }
            }, sampleHandler)
        } catch (e: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Отрисовка рамок целей машинного зрения (на активном окне камеры)
        val activeRect = if (thermalRect.width() > 0 && (thermalCameraView?.holder?.surface?.isValid == true)) {
            thermalRect
        } else {
            opticalRect
        }

        if (activeRect.width() > 0 && activeRect.height() > 0 && detectedTargets.isNotEmpty()) {
            for (target in detectedTargets) {
                val left = activeRect.left + target.bounds.left * activeRect.width()
                val top = activeRect.top + target.bounds.top * activeRect.height()
                val right = activeRect.left + target.bounds.right * activeRect.width()
                val bottom = activeRect.top + target.bounds.bottom * activeRect.height()

                val boxRect = RectF(left - 4f * density, top - 4f * density, right + 4f * density, bottom + 4f * density)
                val paint = if (target.isMoving) boxMovingPaint else boxStaticPaint

                // Рамка цели с угловыми засечками
                canvas.drawRoundRect(boxRect, 4f * density, 4f * density, paint)

                // Текстовая плашка с температурой: [+37.2°C]
                val sign = if (target.estimatedTemp >= 0) "+" else ""
                val label = String.format("%s%.1f°C", sign, target.estimatedTemp)
                val textW = textPaint.measureText(label)
                val textH = textPaint.textSize

                val tagRect = RectF(left - 4f * density, top - textH - 8f * density, left + textW + 4f * density, top - 2f * density)
                canvas.drawRect(tagRect, textBgPaint)
                canvas.drawText(label, left - 2f * density, top - 4f * density, textPaint)
            }
        }

        // 2. Прицел тепловизора
        if (thermalRect.width() > 0 && thermalRect.height() > 0) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawFastReticle(canvas, cx, cy, if (isThermalReady) thermalShader else null)
        }

        // 3. Прицел оптической камеры
        if (opticalRect.width() > 0 && opticalRect.height() > 0) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawFastReticle(canvas, cx, cy, if (isOpticalReady) opticalShader else null)
        }
    }

    private fun drawFastReticle(canvas: Canvas, cx: Float, cy: Float, shader: BitmapShader?) {
        if (shader != null) {
            reticlePaint.shader = shader
            fillPaint.shader = shader
        } else {
            reticlePaint.shader = null
            reticlePaint.color = Color.DKGRAY
            fillPaint.shader = null
            fillPaint.color = Color.DKGRAY
        }

        val halfThick = thickWidth / 2f
        val halfThin = thinWidth / 2f

        canvas.drawCircle(cx, cy, dotRadius, fillPaint)

        // Левый луч
        reticlePaint.strokeWidth = thickWidth
        canvas.drawLine(cx - reticleLength, cy, cx - taperStart, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - taperStart, cy - halfThick)
        taperPath.lineTo(cx - centerGap, cy - halfThin)
        taperPath.lineTo(cx - centerGap, cy + halfThin)
        taperPath.lineTo(cx - taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // Правый луч
        canvas.drawLine(cx + taperStart, cy, cx + reticleLength, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx + taperStart, cy - halfThick)
        taperPath.lineTo(cx + centerGap, cy - halfThin)
        taperPath.lineTo(cx + centerGap, cy + halfThin)
        taperPath.lineTo(cx + taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // Верхний луч
        canvas.drawLine(cx, cy - reticleLength, cx, cy - taperStart, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy - taperStart)
        taperPath.lineTo(cx - halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThick, cy - taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        // Нижний луч
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
