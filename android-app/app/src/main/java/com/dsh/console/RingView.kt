package com.dsh.console

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 双色渐变状态环：底环 + 扫掠渐变进度弧 + 刻度。
 * 中心内容由布局里的其它视图负责，本控件只画环。
 */
class RingView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    var sweep = 300f
        set(v) { field = v.coerceIn(0f, 360f); invalidate() }

    private val density = resources.displayMetrics.density
    private var strokeW = 14f * density
    private var tickLen = 6f * density
    private var gap = 9f * density

    private val primary = ContextCompat.getColor(ctx, R.color.accent)
    private val secondary = ContextCompat.getColor(ctx, R.color.accent2)
    private val trackColor = ContextCompat.getColor(ctx, R.color.ring_track)
    private val tickColor = ContextCompat.getColor(ctx, R.color.ring_tick)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        color = tickColor
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val inset = strokeW / 2f + tickLen + gap
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(rect.width(), rect.height()) / 2f

        // 底环
        ring.shader = null
        ring.color = trackColor
        canvas.drawCircle(cx, cy, r, ring)

        // 渐变进度弧
        if (sweep > 0f) {
            val colors = intArrayOf(primary, primary, secondary, secondary)
            val pos = floatArrayOf(0f, 0.42f, 0.58f, 1f)
            val g = SweepGradient(cx, cy, colors, pos)
            val m = Matrix().apply { setRotate(-90f, cx, cy) }
            g.setLocalMatrix(m)
            ring.shader = g
            canvas.drawArc(rect, -90f, sweep, false, ring)
        }

        // 刻度：绕环一周的短刻线（压在半透明层上）
        val rOut = r + strokeW / 2f - tickLen
        val rIn = r + strokeW / 2f
        var a = -90.0
        while (a < 270.0) {
            val rad = Math.toRadians(a)
            val x1 = cx + (rIn * cos(rad)).toFloat()
            val y1 = cy + (rIn * sin(rad)).toFloat()
            val x2 = cx + (rOut * cos(rad)).toFloat()
            val y2 = cy + (rOut * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tick)
            a += 7.5
        }
    }
}
