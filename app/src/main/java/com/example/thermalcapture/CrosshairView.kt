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

    // Аппаратные микро-буферы (выделяются 1 раз, 0 байт нагрузки на сборщик мусора)
    private val sampleSize = 100
    private val opticalSampleBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)
    private val thermalBitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888)

    private val sampleHandler = Handler(Looper.getMainLooper())
    private var isSamplingActive = false

    // Оптимальный тактовый интервал 10 Гц (100 мс)
    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleOpticalHardware()
            sampleThermalHardware()
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
     * Аппаратный захват оптической камеры через GPU без выделения памяти (<0.2 мс)
     */
    private fun sampleOpticalHardware() {
        val pView = phoneCameraView ?: return
        val textureView = pView.getChildAt(0) as? TextureView ?: return
        if (textureView.width <= 0 || textureView.height <= 0) return

        // Аппаратный GPU-блиттинг прямо в готовый буфер (0 байт мусора в RAM!)
        textureView.getBitmap(opticalSampleBitmap)

        val cx = (opticalRect.centerX() + opticalOffsetX) - pView.x
        val cy = (opticalRect.centerY() + opticalOffsetY) - pView.y

        // Нормализация координат прицела (0.0..1.0) с учетом "ТЯНИ"
        val nx = (cx / pView.width).coerceIn(0f, 1f)
        val ny = (cy / pView.height).coerceIn(0f, 1f)

        val bx = (nx * (sampleSize - 1)).roundToInt()
        val by = (ny * (sampleSize - 1)).roundToInt()

        val rayDistX = ((reticleLength * 0.65f / pView.width) * sampleSize).roundToInt().coerceAtLeast(3)
        val thinDistX = ((centerGap * 1.8f / pView.width) * sampleSize).roundToInt().coerceAtLeast(1)
        val rayDistY = ((reticleLength * 0.65f / pView.height) * sampleSize).roundToInt().coerceAtLeast(3)
        val thinDistY = ((centerGap * 1.8f / pView.height) * sampleSize).roundToInt().coerceAtLeast(1)

        fun getLuma(x: Int, y: Int): Float {
            val px = x.coerceIn(0, sampleSize - 1)
            val py = y.coerceIn(0, sampleSize - 1)
            val p = opticalSampleBitmap.getPixel(px, py)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        val cLuma = getLuma(bx, by)
        val lLuma = getLuma(bx - rayDistX, by) * 0.6f + getLuma(bx - thinDistX, by) * 0.4f
        val rLuma = getLuma(bx + rayDistX, by) * 0.6f + getLuma(bx + thinDistX, by) * 0.4f
        val tLuma = getLuma(bx, by - rayDistY) * 0.6f + getLuma(bx, by - thinDistY) * 0.4f
        val bLuma = getLuma(bx, by + rayDistY) * 0.6f + getLuma(bx, by + thinDistY) * 0.4f

        fun toTone(luma: Float): Float = (255f - luma).coerceIn(0f, 255f)

        optToneCenter = optToneCenter * 0.65f + toTone(cLuma) * 0.35f
        optToneLeft = optToneLeft * 0.65f + toTone(lLuma) * 0.35f
        optToneRight = optToneRight * 0.65f + toTone(rLuma) * 0.35f
        optToneTop = optToneTop * 0.65f + toTone(tLuma) * 0.35f
        optToneBottom = optToneBottom * 0.65f + toTone(bLuma) * 0.35f
    }

    /**
     * Аппаратный захват тепловизора CVBS через SurfaceView
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
                    fun getLuma(x: Int, y: Int): Float {
                        val px = x.coerceIn(0, sampleSize - 1)
                        val py = y.coerceIn(0, sampleSize - 1)
                        val p = thermalBitmap.getPixel(px, py)
                        return (p and 0xFF).toFloat()
                    }

                    val dist = (reticleLength * 0.5f * scaleX).roundToInt().coerceIn(8, half - 5)
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
                }
            }, sampleHandler)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
