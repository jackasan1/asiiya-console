package com.dsh.console

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Lumen Soft · 柔和分层阴影容器
 *
 * 为什么需要它：Android 的 XML 表达不了「上亮下暗」的双向柔和阴影，
 * 而 View.elevation 只能给单色系统投影、且深色模式下几乎不可见。
 * 这里用「多层圆角矩形 + 平方衰减透明度」手绘出近似高斯模糊的软阴影：
 *   · 零第三方依赖
 *   · 兼容 API 26（minSdk）
 *   · 硬件加速下开销可忽略（仅 12 次 drawRoundRect）
 *
 * 用法：
 *   <com.dsh.console.ShadowLayout
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content"
 *       android:layout_marginTop="14dp"
 *       app:shadowRadius="@dimen/card_radius"
 *       app:shadowBlur="@dimen/shadow_blur"
 *       app:shadowDy="@dimen/shadow_dy"
 *       app:shadowColor="@color/shadow_key">
 *       <include layout="@layout/card_harness" />
 *   </com.dsh.console.ShadowLayout>
 *
 * 注意：子视图会被自动内缩（padding），内缩量即阴影所需空间，
 * 因此 **不要在 ShadowLayout 之外再算阴影留白**。
 */
class ShadowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var cornerRadius: Float
    private var blur: Float
    private var dy: Float
    private var shadowColor: Int

    /** 层数越多越平滑；12 层在手机上已看不出台阶 */
    private val steps = 12

    init {
        val d = resources.displayMetrics.density
        cornerRadius = 28f * d
        blur = 10f * d
        dy = 6f * d
        shadowColor = 0x38000000

        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.ShadowLayout)
            cornerRadius = ta.getDimension(R.styleable.ShadowLayout_shadowRadius, cornerRadius)
            blur = ta.getDimension(R.styleable.ShadowLayout_shadowBlur, blur)
            dy = ta.getDimension(R.styleable.ShadowLayout_shadowDy, dy)
            shadowColor = ta.getColor(R.styleable.ShadowLayout_shadowColor, shadowColor)
            ta.recycle()
        }

        // 为阴影预留绘制空间：左右 = blur，上方 = blur - dy，下方 = blur + dy
        val side = blur.toInt()
        setPadding(
            side,
            max(0f, blur - dy).toInt(),
            side,
            (blur + max(0f, dy)).toInt()
        )
        setWillNotDraw(false)
    }

    override fun draw(canvas: Canvas) {
        if (childCount > 0) drawSoftShadow(canvas)
        super.draw(canvas)
    }

    private fun drawSoftShadow(canvas: Canvas) {
        val c: View = getChildAt(0)
        if (blur <= 0f || c.width <= 0 || c.height <= 0) return

        // 阴影几何 = 子视图矩形整体下移 dy
        rect.set(
            c.left.toFloat(),
            c.top.toFloat() + dy,
            c.right.toFloat(),
            c.bottom.toFloat() + dy
        )

        val baseA = Color.alpha(shadowColor)
        paint.color = shadowColor

        // 由外向内绘制：越靠内层透明度越高，叠加后形成由深到浅的扩散
        for (i in steps - 1 downTo 0) {
            val f = i / (steps - 1f)              // 0 = 最紧, 1 = 最散
            val spread = blur * f
            val a = (baseA * (1f - f) * (1f - f) * 0.20f).toInt()
            if (a <= 0) continue
            paint.alpha = a
            val r = cornerRadius + spread
            canvas.drawRoundRect(
                rect.left - spread,
                rect.top - spread,
                rect.right + spread,
                rect.bottom + spread,
                r, r, paint
            )
        }
    }
}
