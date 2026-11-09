package com.example.thermalcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PhotoButtonView @JvmOverloads constructor(
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
    private val cameraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }

    private val bodyRect = RectF()
    private val lensRect = RectF()
    private val bodyPath = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = minOf(width, height) / 2f - ringPaint.strokeWidth / 2f - 1f

        canvas.drawCircle(cx, cy, outerRadius, outerPaint)
        canvas.drawCircle(cx, cy, outerRadius, ringPaint)

        // Корпус фотоаппарата
        val bodyW = outerRadius * 1.15f
        val bodyH = outerRadius * 0.75f
        val bodyLeft = cx - bodyW / 2f
        val bodyTop = cy - bodyH / 2f + outerRadius * 0.08f
        val corner = 3f * density
        bodyRect.set(bodyLeft, bodyTop, bodyLeft + bodyW, bodyTop + bodyH)

        // Кнопка сверху
        val bumpW = outerRadius * 0.32f
        val bumpH = outerRadius * 0.18f
        val bumpLeft = cx - bodyW * 0.28f
        val bumpTop = bodyTop - bumpH * 0.6f
        val bumpRect = RectF(bumpLeft, bumpTop, bumpLeft + bumpW, bodyTop)

        bodyPath.reset()
        bodyPath.addRoundRect(bumpRect, corner, corner, Path.Direction.CW)
        bodyPath.addRoundRect(bodyRect, corner, corner, Path.Direction.CW)
        canvas.drawPath(bodyPath, cameraPaint)

        // Объектив
        val lensR = outerRadius * 0.28f
        lensRect.set(cx - lensR, cy - lensR, cx + lensR, cy + lensR)
        canvas.drawCircle(cx, cy + outerRadius * 0.08f, lensR, cameraPaint)
    }
}
