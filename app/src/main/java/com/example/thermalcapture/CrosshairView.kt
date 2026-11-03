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

    private val targetPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val colorMoving = Color.RED
    private val colorHumanVehicle = Color.parseColor("#FFE082") // Бледно-желтый
    private val colorOther = Color.parseColor("#A5D6A7")        // Бледно-зеленый

    // Кисти для режима "ЮСТИРОВКА"
    private val justCornersPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isAntiAlias = true
    }

    private val justTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f * density
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val justBgPaint = Paint().apply {
        color = Color.parseColor("#D9CC0000")
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
        if (pView.visibility != View.VISIBLE || pView.width <= 0 || pView.height <= 0) return

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
        if (tView.visibility != View.VISIBLE || !surface.isValid || tView.width <= 0 || tView.height <= 0) return

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

        val isThermalVisible = thermalRect.width() > 0 && thermalRect.height() > 0
        val isOpticalVisible = phoneCameraView?.visibility == View.VISIBLE && opticalRect.width() > 0 && opticalRect.height() > 0

        // 1. Отрисовка рамок трекера строго на активном окне тепловизора
        if (isThermalVisible && detectedTargets.isNotEmpty()) {
            val minSensorW = 9f / 640f
            val minSensorH = 9f / 512f

            for (target in detectedTargets) {
                val targetWindow = if (target.isThermalSource) thermalRect else opticalRect
                val isTargetWindowVisible = if (target.isThermalSource) isThermalVisible else isOpticalVisible

                if (isTargetWindowVisible && targetWindow.width() > 0 && targetWindow.height() > 0) {
                    val isLarge = target.bounds.width() > minSensorW || target.bounds.height() > minSensorH

                    val targetColor = when (target.type) {
                        TargetType.MOVING -> colorMoving
                        TargetType.HUMAN_VEHICLE -> colorHumanVehicle
                        TargetType.OTHER -> colorOther
                    }
                    targetPaint.color = targetColor

                    if (isLarge && target.silhouettePoints.isNotEmpty()) {
                        val path = Path()
                        var first = true
                        for (pt in target.silhouettePoints) {
                            val px = targetWindow.left + pt.x * targetWindow.width()
                            val py = targetWindow.top + pt.y * targetWindow.height()
                            if (first) {
                                path.moveTo(px, py)
                                first = false
                            } else {
                                path.lineTo(px, py)
                            }
                        }
                        path.close()
                        targetPaint.strokeWidth = 2.4f * density
                        canvas.drawPath(path, targetPaint)
                    } else {
                        val cx = targetWindow.left + target.bounds.centerX() * targetWindow.width()
                        val cy = targetWindow.top + target.bounds.centerY() * targetWindow.height()

                        val halfBox = 7.5f * density
                        val boxRect = RectF(cx - halfBox, cy - halfBox, cx + halfBox, cy + halfBox)

                        targetPaint.strokeWidth = 2f * density
                        canvas.drawRect(boxRect, targetPaint)
                        canvas.drawCircle(cx, cy, 1.5f * density, targetPaint)
                    }
                }
            }
        }

        // 2. Прицел тепловизора
        if (isThermalVisible) {
            val cx = thermalRect.centerX()
            val cy = thermalRect.centerY()
            drawFastReticle(canvas, cx, cy, if (isThermalReady) thermalShader else null)
        }

        // 3. Прицел оптической камеры (полностью скрыт, если режим "ТЕПЛО 100%")
        if (isOpticalVisible) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY

            drawFastReticle(canvas, cx, cy, if (isOpticalReady) opticalShader else null)

            // РЕЖИМ "ЮСТИРОВКА": аккуратные 4 угла без обрезки краев
            if (isCalibrationMode) {
                val maxAllowedHalf = (opticalRect.width() / 2f - 8f * density).coerceAtLeast(20f * density)
                val boxHalf = (reticleLength + 10f * density).coerceAtMost(maxAllowedHalf)
                val arm = 15f * density

                // Уголки
                canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf + arm, cy - boxHalf, justCornersPaint)
                canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf, cy - boxHalf + arm, justCornersPaint)

                canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf - arm, cy - boxHalf, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf - arm, cy - boxHalf, justCornersPaint)

                canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf + arm, cy + boxHalf, justCornersPaint)
                canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf, cy + boxHalf - arm, justCornersPaint)

                canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf - arm, cy + boxHalf, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf, cy + boxHalf - arm, justCornersPaint)

                // Плашка "ПЕРЕМЕЩАЙ"
                val text = "ПЕРЕМЕЩАЙ"
                val tW = justTextPaint.measureText(text)
                val tH = justTextPaint.textSize
                val tagY = (cy - boxHalf - 8f * density).coerceAtLeast(opticalRect.top + tH + 6f * density)
                val tagX = cx - tW / 2f

                canvas.drawRect(tagX - 6f * density, tagY - tH - 4f * density, tagX + tW + 6f * density, tagY + 3f * density, justBgPaint)
                canvas.drawText(text, tagX, tagY, justTextPaint)
            }
        }
    }

    private fun drawFastReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        shader: BitmapShader?
    ) {
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

        reticlePaint.strokeWidth = thickWidth
        canvas.drawLine(cx - reticleLength, cy, cx - taperStart, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - taperStart, cy - halfThick)
        taperPath.lineTo(cx - centerGap, cy - halfThin)
        taperPath.lineTo(cx - centerGap, cy + halfThin)
        taperPath.lineTo(cx - taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        canvas.drawLine(cx + taperStart, cy, cx + reticleLength, cy, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx + taperStart, cy - halfThick)
        taperPath.lineTo(cx + centerGap, cy - halfThin)
        taperPath.lineTo(cx + centerGap, cy + halfThin)
        taperPath.lineTo(cx + taperStart, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        canvas.drawLine(cx, cy - reticleLength, cx, cy - taperStart, reticlePaint)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy - taperStart)
        taperPath.lineTo(cx - halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThick, cy - taperStart)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

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
