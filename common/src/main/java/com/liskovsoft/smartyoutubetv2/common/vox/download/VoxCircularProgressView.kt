package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.liskovsoft.smartyoutubetv2.common.R

/**
 * Круговой индикатор прогресса (Circular Progress Ring) 64x64 dp по макету Prompt #028.
 *
 * Цвет кольца прогресса: #1890FF (акцент)
 * Цвет дорожки: #22334A
 * Толщина линии: 3.5 dp
 */
class VoxCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.vox_dl_ring_track)
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.vox_dl_accent)
        strokeCap = Paint.Cap.ROUND
    }

    private val boundsRect = RectF()
    private var progress: Int = 0
    private var strokeWidthPx: Float = dpToPx(3.5f)

    init {
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    fun setProgress(value: Int) {
        val clamped = value.coerceIn(0, 100)
        if (progress != clamped) {
            progress = clamped
            invalidate()
        }
    }

    fun getProgress(): Int = progress

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f + dpToPx(2f)
        boundsRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1. Дорожка кольца
        canvas.drawOval(boundsRect, trackPaint)

        // 2. Активная дуга прогресса
        if (progress > 0) {
            val sweepAngle = 360f * (progress / 100f)
            canvas.drawArc(boundsRect, -90f, sweepAngle, false, progressPaint)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
