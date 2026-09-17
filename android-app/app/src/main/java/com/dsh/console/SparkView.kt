package com.dsh.console

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** 近 N 天费用迷你柱状图（最后一根高亮为今天） */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var values: FloatArray = FloatArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var accent = 0
    private var normal = 0

    init {
        accent = ContextCompat.getColor(context, R.color.accent)
        normal = ContextCompat.getColor(context, R.color.ring_track)
    }

    fun setData(v: FloatArray) {
        values = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = (w * 0.05f) / n
        val bw = (w - gap * (n - 1)) / n
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
        for (i in 0 until n) {
            val v = values[i]
            var bh = (h - 2f) * (v / max)
            if (bh < 1.5f) bh = 1.5f          // 零值也给个小底座，视觉连续
            val left = i * (bw + gap)
            val top = h - bh
            paint.color = if (i == n - 1) accent else normal
            canvas.drawRoundRect(RectF(left, top, left + bw, h), bw * 0.3f, bw * 0.3f, paint)
        }
    }
}
