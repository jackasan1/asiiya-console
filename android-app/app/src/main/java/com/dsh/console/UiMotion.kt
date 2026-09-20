package com.dsh.console

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.text.DecimalFormat

/**
 * Lumen Soft · 动效工具集
 *
 * 设计原则：
 *  1. 全部使用平台原生 API —— 不引入 androidx.dynamicanimation 等新依赖
 *  2. 弹性手感用 OvershootInterpolator 近似弹簧，避免依赖 SpringAnimation
 *  3. 尊重系统「动画缩放 = 0」（无障碍 / 开发者选项），关闭动画时直接落终态
 *  4. 时长统一：按压 90/260ms、入场 260ms（错峰 60ms）、状态切换 160ms、数字滚动 480ms
 */
object UiMotion {

    const val PRESS_DOWN_MS = 90L
    const val PRESS_UP_MS = 260L
    const val ENTER_MS = 260L
    const val ENTER_STAGGER_MS = 60L
    const val ROLL_MS = 480L
    const val STATE_TWEEN_MS = 160L

    /** 系统是否关闭了动画 */
    fun animationsDisabled(v: View): Boolean = try {
        Settings.Global.getFloat(
            v.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

    /**
     * 按压反馈：按下缩到 [scale]（默认 0.96），松手用 Overshoot 回弹到 1。
     * 返回 false 以保证不吞掉 click 事件。
     */
    fun pressable(v: View, scale: Float = 0.96f) {
        if (animationsDisabled(v)) return
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(scale).scaleY(scale)
                    .setDuration(PRESS_DOWN_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(PRESS_UP_MS)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
            }
            false
        }
    }

    /**
     * 卡片错峰入场：自下方 14dp 上浮 + 淡入。
     * @param index 卡片序号（0 起），实际延迟 = index * 60ms
     */
    fun enterStagger(v: View, index: Int) {
        if (animationsDisabled(v)) return
        val d = v.resources.displayMetrics.density
        v.alpha = 0f
        v.translationY = 14f * d
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * ENTER_STAGGER_MS)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * 金额滚动：从当前显示值缓动到 [to]。
     * 配合等宽数字（fontFeatureSettings="tnum"）可避免刷新时宽度抖动。
     */
    fun rollMoney(tv: TextView, to: Double, prefix: String = "¥", decimals: Int = 0) {
        // decimals = 0 -> 沿用 money() 的规则：小于 1 元显示 4 位，否则 2 位
        val dec = if (decimals > 0) decimals else if (to < 1.0) 4 else 2
        val pattern = "0." + "0".repeat(dec)
        if (animationsDisabled(tv)) {
            tv.text = prefix + DecimalFormat(pattern).format(to)
            return
        }
        val fmt = DecimalFormat(pattern)
        val from = tv.text?.toString()?.removePrefix(prefix)?.trim()?.toDoubleOrNull() ?: 0.0
        ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = ROLL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a -> tv.text = prefix + fmt.format((a.animatedValue as Float).toDouble()) }
            start()
        }
    }

    /** 状态色缓动：用于状态胶囊底色 / 文字色的切换 */
    fun tweenColor(from: Int, to: Int, onUpdate: (Int) -> Unit) {
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = STATE_TWEEN_MS
            addUpdateListener { a -> onUpdate(a.animatedValue as Int) }
            start()
        }
    }
}
