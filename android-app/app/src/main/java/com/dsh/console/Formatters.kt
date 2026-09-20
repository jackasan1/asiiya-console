package com.dsh.console

import java.util.Locale

/**
 * 纯函数格式化工具。
 *
 * 从 MainActivity 抽出来有两个目的：
 *  1. 无 Android 依赖 → 可以直接被 JVM 单元测试覆盖
 *  2. 不让已经 2100 行的 Activity 继续膨胀
 *
 * 全部使用 Locale.US，保证任何语言环境下小数点与千分位行为一致。
 */
object Formatters {

    /**
     * 金额。
     *
     * 小于 1 元（且非 0）时用 4 位小数 —— 否则小额支出会被四舍五入成 ¥0.00，看不出差别；
     * 但 0 元本身必须显示为 ¥0.00，而不是 ¥0.0000（单测发现的显示瑕疵）。
     */
    fun money(v: Double): String =
        if (v > 0.0 && v < 1.0) String.format(Locale.US, "¥%.4f", v)
        else String.format(Locale.US, "¥%.2f", v)

    /** token 数：B / M / K 自适应缩写 */
    fun tokens(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.2fM", n / 1e6)
        n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }

    /** 剩余分钟 → 1h30m / 45m */
    fun fmtLeft(min: Int): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) String.format(Locale.US, "%dh%02dm", h, m)
        else String.format(Locale.US, "%dm", m)
    }
}
