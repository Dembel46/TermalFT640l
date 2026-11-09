package com.example.thermalcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class RecordButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }

    private val rectF = RectF()

    var isRecording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = minOf(width, height) / 2f - ringPaint.strokeWidth / 2f - 1f
        val innerCircleRadius = outerRadius * 0.72f
        val squareHalf = outerRadius * 0.5f
        val corner = 6f * density

        canvas.drawCircle(cx, cy, outerRadius, outerPaint)
        canvas.drawCircle(cx, cy, outerRadius, ringPaint)

        if (isRecording) {
            canvas.drawCircle(cx, cy, innerCircleRadius, innerPaint)
        } else {
            rectF.set(cx - squareHalf, cy - squareHalf, cx + squareHalf, cy + squareHalf)
            canvas.drawRoundRect(rectF, corner, corner, innerPaint)
        }
    }
}
