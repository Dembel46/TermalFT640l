package com.example.thermalcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.camera.view.PreviewView
import kotlin.math.roundToInt

class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private val reticleLength = 70f * density
    private val centerGap = 7f * density
    private val taperStart = 24f * density
    private val thickWidth = 3.6f * density
    private val thinWidth = 1.2f * density
    private val dotRadius = 1.5f * density

    // === НОВОЕ: сегментация для правильного контраста ===
    private val SEGMENTS_PER_RAY = 10
    private val GRAY_MIN = 140f
    private val SAMPLE_RADIUS = 2

    private val optTones = Array(4) { FloatArray(SEGMENTS_PER_RAY) { 255f } }
    private val thmTones = Array(4) { FloatArray(SEGMENTS_PER_RAY) { 255f } }
    private var optToneCenter = 255f
    private var thmToneCenter = 255f

    private val sampleSize = 200
    private val opticalSampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val thermalSampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val opticalPixels = IntArray(sampleSize * sampleSize)
    private val thermalPixels = IntArray(sampleSize * sampleSize)

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
    private val colorHumanVehicle = Color.parseColor("#FFE082")
    private val colorOther = Color.parseColor("#A5D6A7")

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

    private val sliderTrackPaint = Paint().apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.FILL
    }

    private val sliderProgressPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val sliderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f * density
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val taperPath = Path()

    var opticalOffsetX = 0f
    var opticalOffsetY = 0f
    var isCalibrationMode = false

    var isHybridMode = false
    var hybridAlpha = 0.5f
    var onHybridAlphaChanged: ((Float) -> Unit)? = null

    var thermalRect = RectF()
    var opticalRect = RectF()
    var thermalRotation: Int = 0 // 0, 90, 180, 270

    var phoneCameraView: PreviewView? = null
    var thermalCameraView: TextureView? = null

    var detectedTargets: List<DetectedTarget> = emptyList()
        set(value) { field = value; invalidate() }

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
                sampleHandler.postDelayed(this, 100) // 10 Hz достаточно для сэмплов
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

    private fun avgLuma(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int): Float {
        var sum = 0f
        var count = 0
        val r = SAMPLE_RADIUS
        for (dy in -r..r) {
            for (dx in -r..r) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val y = (cy + dy).coerceIn(0, h - 1)
                val p = pixels[y * w + x]
                sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                count++
            }
        }
        return if (count > 0) sum / count else 128f
    }

    private fun toTone(luma: Float): Float {
        val t = (luma / 255f).coerceIn(0f, 1f)
        return 255f - t * (255f - GRAY_MIN)
    }

    private fun updateOpticalFast() {
        val pView = phoneCameraView ?: return
        if (pView.visibility != View.VISIBLE || pView.width <= 0 || pView.height <= 0) return
        val textureView = pView.getChildAt(0) as? TextureView ?: return
        if (!textureView.isAvailable) return

        textureView.getBitmap(opticalSampleBitmap)
        opticalSampleBitmap.getPixels(opticalPixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

        val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
        val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y
        val nx = (cx / pView.width).coerceIn(0f, 1f)
        val ny = (cy / pView.height).coerceIn(0f, 1f)
        val bx = (nx * (sampleSize - 1)).roundToInt()
        val by = (ny * (sampleSize - 1)).roundToInt()

        val segPxX = ((reticleLength - centerGap) / SEGMENTS_PER_RAY / pView.width * sampleSize).coerceAtLeast(1f)
        val segPxY = ((reticleLength - centerGap) / SEGMENTS_PER_RAY / pView.height * sampleSize).coerceAtLeast(1f)
        val gapPxX = (centerGap / pView.width * sampleSize).coerceAtLeast(1f)
        val gapPxY = (centerGap / pView.height * sampleSize).coerceAtLeast(1f)

        optToneCenter = optToneCenter * 0.65f + toTone(avgLuma(opticalPixels, sampleSize, sampleSize, bx, by)) * 0.35f

        for (i in 0 until SEGMENTS_PER_RAY) {
            val offsetX = (gapPxX + segPxX * (i + 0.5f)).roundToInt()
            val lx = (bx - offsetX).coerceIn(0, sampleSize - 1)
            val rx = (bx + offsetX).coerceIn(0, sampleSize - 1)
            optTones[0][i] = optTones[0][i] * 0.65f + toTone(avgLuma(opticalPixels, sampleSize, sampleSize, lx, by)) * 0.35f
            optTones[1][i] = optTones[1][i] * 0.65f + toTone(avgLuma(opticalPixels, sampleSize, sampleSize, rx, by)) * 0.35f

            val offsetY = (gapPxY + segPxY * (i + 0.5f)).roundToInt()
            val ty = (by - offsetY).coerceIn(0, sampleSize - 1)
            val byy = (by + offsetY).coerceIn(0, sampleSize - 1)
            optTones[2][i] = optTones[2][i] * 0.65f + toTone(avgLuma(opticalPixels, sampleSize, sampleSize, bx, ty)) * 0.35f
            optTones[3][i] = optTones[3][i] * 0.65f + toTone(avgLuma(opticalPixels, sampleSize, sampleSize, bx, byy)) * 0.35f
        }
        isOpticalReady = true
    }

    private fun updateThermalFast() {
        val tView = thermalCameraView ?: return
        if (tView.visibility != View.VISIBLE || !tView.isAvailable || tView.width <= 0 || tView.height <= 0) return

        try {
            if (tView.getBitmap(thermalSampleBitmap) == null) {
                isThermalReady = false
                return
            }
            thermalSampleBitmap.getPixels(thermalPixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

            val cx = thermalRect.centerX() - tView.x
            val cy = thermalRect.centerY() - tView.y
            val nx = (cx / tView.width).coerceIn(0f, 1f)
            val ny = (cy / tView.height).coerceIn(0f, 1f)
            val bx = (nx * (sampleSize - 1)).roundToInt()
            val by = (ny * (sampleSize - 1)).roundToInt()

            val segPxX = ((reticleLength - centerGap) / SEGMENTS_PER_RAY / tView.width * sampleSize).coerceAtLeast(1f)
            val segPxY = ((reticleLength - centerGap) / SEGMENTS_PER_RAY / tView.height * sampleSize).coerceAtLeast(1f)
            val gapPxX = (centerGap / tView.width * sampleSize).coerceAtLeast(1f)
            val gapPxY = (centerGap / tView.height * sampleSize).coerceAtLeast(1f)

            thmToneCenter = thmToneCenter * 0.65f + toTone(avgLuma(thermalPixels, sampleSize, sampleSize, bx, by)) * 0.35f

            for (i in 0 until SEGMENTS_PER_RAY) {
                val offsetX = (gapPxX + segPxX * (i + 0.5f)).roundToInt()
                val lx = (bx - offsetX).coerceIn(0, sampleSize - 1)
                val rx = (bx + offsetX).coerceIn(0, sampleSize - 1)
                thmTones[0][i] = thmTones[0][i] * 0.65f + toTone(avgLuma(thermalPixels, sampleSize, sampleSize, lx, by)) * 0.35f
                thmTones[1][i] = thmTones[1][i] * 0.65f + toTone(avgLuma(thermalPixels, sampleSize, sampleSize, rx, by)) * 0.35f

                val offsetY = (gapPxY + segPxY * (i + 0.5f)).roundToInt()
                val ty = (by - offsetY).coerceIn(0, sampleSize - 1)
                val byy = (by + offsetY).coerceIn(0, sampleSize - 1)
                thmTones[2][i] = thmTones[2][i] * 0.65f + toTone(avgLuma(thermalPixels, sampleSize, sampleSize, bx, ty)) * 0.35f
                thmTones[3][i] = thmTones[3][i] * 0.65f + toTone(avgLuma(thermalPixels, sampleSize, sampleSize, bx, byy)) * 0.35f
            }
            isThermalReady = true
        } catch (_: Exception) {
            isThermalReady = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val isThermalVisible = thermalCameraView?.visibility == View.VISIBLE && thermalRect.width() > 0 && thermalRect.height() > 0
        val isOpticalVisible = phoneCameraView?.visibility == View.VISIBLE && opticalRect.width() > 0 && opticalRect.height() > 0

        if (isThermalVisible && detectedTargets.isNotEmpty()) {
            val minSensorW = 9f / 640f
            val minSensorH = 9f / 512f
            for (target in detectedTargets) {
                val isLarge = target.bounds.width() > minSensorW || target.bounds.height() > minSensorH
                targetPaint.color = when (target.type) {
                    TargetType.MOVING -> colorMoving
                    TargetType.HUMAN_VEHICLE -> colorHumanVehicle
                    TargetType.OTHER -> colorOther
                }
                if (isLarge && target.silhouettePoints.isNotEmpty()) {
                    val path = Path()
                    var first = true
                    for (pt in target.silhouettePoints) {
                        val px = thermalRect.left + pt.x * thermalRect.width()
                        val py = thermalRect.top + pt.y * thermalRect.height()
                        if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                    }
                    path.close()
                    targetPaint.strokeWidth = 2.4f * density
                    canvas.drawPath(path, targetPaint)
                } else {
                    val cx = thermalRect.left + target.bounds.centerX() * thermalRect.width()
                    val cy = thermalRect.top + target.bounds.centerY() * thermalRect.height()
                    val halfBox = 7.5f * density
                    targetPaint.strokeWidth = 2f * density
                    canvas.drawRect(RectF(cx - halfBox, cy - halfBox, cx + halfBox, cy + halfBox), targetPaint)
                    canvas.drawCircle(cx, cy, 1.5f * density, targetPaint)
                }
            }
        }

        canvas.save()
        // Компенсируем поворот вокруг центра thermalRect
        if (thermalRotation != 0) {
            canvas.rotate(thermalRotation.toFloat(), thermalRect.centerX(), thermalRect.centerY())
        }
        if (isThermalVisible) {
            drawSegmentedReticle(canvas, thermalRect.centerX(), thermalRect.centerY(), thmToneCenter, thmTones)
        }
        canvas.restore()

        if (isOpticalVisible && !isHybridMode) {
            val cx = opticalRect.centerX() + opticalOffsetX
            val cy = opticalRect.centerY() + opticalOffsetY
            drawSegmentedReticle(canvas, cx, cy, optToneCenter, optTones)

            if (isCalibrationMode) {
                val distR = opticalRect.right - cx - 10f * density
                val distL = cx - opticalRect.left - 10f * density
                val distT = cy - opticalRect.top - 20f * density
                val distB = opticalRect.bottom - cy - 10f * density
                val maxRadius = minOf(distR, distL, distT, distB).coerceAtLeast(15f * density)
                val boxHalf = minOf(reticleLength + 8f * density, maxRadius)
                val arm = (boxHalf * 0.4f).coerceAtLeast(8f * density)

                canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf + arm, cy - boxHalf, justCornersPaint)
                canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf, cy - boxHalf + arm, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf - arm, cy - boxHalf, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf, cy - boxHalf + arm, justCornersPaint)
                canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf + arm, cy + boxHalf, justCornersPaint)
                canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf, cy + boxHalf - arm, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf - arm, cy + boxHalf, justCornersPaint)
                canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf, cy + boxHalf - arm, justCornersPaint)

                val text = "ПЕРЕМЕЩАЙ"
                val tW = justTextPaint.measureText(text)
                val tH = justTextPaint.textSize
                val tagY = (cy - boxHalf - 8f * density).coerceAtLeast(opticalRect.top + tH + 6f * density)
                val tagX = (cx - tW / 2f).coerceIn(opticalRect.left + 6f * density, opticalRect.right - tW - 6f * density)
                canvas.drawRect(tagX - 5f * density, tagY - tH - 3f * density, tagX + tW + 5f * density, tagY + 3f * density, justBgPaint)
                canvas.drawText(text, tagX, tagY, justTextPaint)
            }
        }

        if (isHybridMode) {
            val trackW = 8f * density
            val trackH = 180f * density
            // Смещаем правее, чтобы не наезжать на гамбургер
            val trackLeft = 70f * density
            val trackTop = (height - trackH) / 2f
            val trackBottom = trackTop + trackH
            canvas.drawRoundRect(RectF(trackLeft, trackTop, trackLeft + trackW, trackBottom), 4f * density, 4f * density, sliderTrackPaint)
            val fillHeight = trackH * hybridAlpha
            canvas.drawRoundRect(RectF(trackLeft, trackBottom - fillHeight, trackLeft + trackW, trackBottom), 4f * density, 4f * density, sliderProgressPaint)
            val percentText = String.format("ТЕПЛО %d%%", (hybridAlpha * 100).roundToInt())
            canvas.drawText(percentText, trackLeft, trackTop - 8f * density, sliderTextPaint)
        }
    }

    private fun drawSegmentedReticle(canvas: Canvas, cx: Float, cy: Float, toneCenter: Float, tones: Array<FloatArray>) {
        val halfThick = thickWidth / 2f
        val halfThin = thinWidth / 2f

        fillPaint.color = Color.rgb(toneCenter.roundToInt().coerceIn(0, 255), toneCenter.roundToInt().coerceIn(0, 255), toneCenter.roundToInt().coerceIn(0, 255))
        canvas.drawCircle(cx, cy, dotRadius, fillPaint)

        drawTaperZone(canvas, cx, cy, taperStart, halfThick, halfThin, tones[0][0], tones[1][0], tones[2][0], tones[3][0])

        val totalLen = reticleLength - taperStart
        val segLen = totalLen / SEGMENTS_PER_RAY

        for (i in 0 until SEGMENTS_PER_RAY) {
            val start = taperStart + i * segLen
            val end = start + segLen
            reticlePaint.strokeWidth = thickWidth

            reticlePaint.color = getGrayColor(tones[0][i]); canvas.drawLine(cx - start, cy, cx - end, cy, reticlePaint)
            reticlePaint.color = getGrayColor(tones[1][i]); canvas.drawLine(cx + start, cy, cx + end, cy, reticlePaint)
            reticlePaint.color = getGrayColor(tones[2][i]); canvas.drawLine(cx, cy - start, cx, cy - end, reticlePaint)
            reticlePaint.color = getGrayColor(tones[3][i]); canvas.drawLine(cx, cy + start, cx, cy + end, reticlePaint)
        }
    }

    private fun drawTaperZone(canvas: Canvas, cx: Float, cy: Float, start: Float, halfThick: Float, halfThin: Float, toneL: Float, toneR: Float, toneT: Float, toneB: Float) {
        fillPaint.color = getGrayColor(toneL)
        taperPath.reset()
        taperPath.moveTo(cx - start, cy - halfThick)
        taperPath.lineTo(cx - centerGap, cy - halfThin)
        taperPath.lineTo(cx - centerGap, cy + halfThin)
        taperPath.lineTo(cx - start, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        fillPaint.color = getGrayColor(toneR)
        taperPath.reset()
        taperPath.moveTo(cx + start, cy - halfThick)
        taperPath.lineTo(cx + centerGap, cy - halfThin)
        taperPath.lineTo(cx + centerGap, cy + halfThin)
        taperPath.lineTo(cx + start, cy + halfThick)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        fillPaint.color = getGrayColor(toneT)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy - start)
        taperPath.lineTo(cx - halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThin, cy - centerGap)
        taperPath.lineTo(cx + halfThick, cy - start)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)

        fillPaint.color = getGrayColor(toneB)
        taperPath.reset()
        taperPath.moveTo(cx - halfThick, cy + start)
        taperPath.lineTo(cx - halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThin, cy + centerGap)
        taperPath.lineTo(cx + halfThick, cy + start)
        taperPath.close()
        canvas.drawPath(taperPath, fillPaint)
    }

    private fun getGrayColor(tone: Float): Int {
        val v = tone.roundToInt().coerceIn(0, 255)
        return Color.rgb(v, v, v)
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingSlider = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isHybridMode && !isCalibrationMode) {
            // Обновляем зону захвата ползунка (сдвинуто правее)
            val touchAreaLeft = 60f * density
            val touchAreaRight = 90f * density
            when (event.action) {
                MotionEvent.ACTION_DOWN -> if (event.x in touchAreaLeft..touchAreaRight) {
                    isDraggingSlider = true
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (isDraggingSlider) {
                    val dy = lastTouchY - event.y
                    lastTouchY = event.y
                    hybridAlpha = (hybridAlpha + dy * 0.005f).coerceIn(0f, 1f)
                    onHybridAlphaChanged?.invoke(hybridAlpha)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDraggingSlider = false
            }
        }
        if (isCalibrationMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y; return true }
                MotionEvent.ACTION_MOVE -> {
                    opticalOffsetX += event.x - lastTouchX
                    opticalOffsetY += event.y - lastTouchY
                    lastTouchX = event.x; lastTouchY = event.y
                    invalidate(); return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
