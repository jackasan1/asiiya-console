package com.dsh.console

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 桌面小组件：服务状态 + 今日费用 + 启动/停止/打开。
 *
 * 费用不发起任何请求 —— 直接复用 App 拉到的官方 JSON 缓存（"ui" 偏好里的 cost_json_cache），
 * 所以渲染是零成本的；App 每次刷新费用后会调用 companion 的 build() + updateAppWidget() 同步重绘。
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { paint(ctx, mgr, it) }
    }

    /**
     * 系统触发（每 30 分钟兜底 / 桌面重建）时的渲染：
     * 先用缓存里的费用出图，再异步探一次本地端口把状态刷新。
     */
    private fun paint(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val c0 = cachedCost(ctx)
        mgr.updateAppWidget(id, build(ctx, null, c0))

        val pending = goAsync()
        Thread {
            val c = cachedCost(ctx)
            val port = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("port", 3080)
            val code = try {
                val conn = java.net.URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val r = conn.responseCode
                conn.disconnect()
                r
            } catch (e: Exception) { 0 }
            val up = code == 401 || code == 200
            mgr.updateAppWidget(id, build(ctx, up, c))
            pending.finish()
        }.start()
    }

    companion object {

        /** 纯渲染：状态 + 费用 → RemoteViews（不发起任何请求） */
        fun build(ctx: Context, up: Boolean?, cost: Cost?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_status)
            v.setTextViewText(R.id.wState, ctx.getString(when (up) {
                true -> R.string.state_running
                false -> R.string.state_stopped
                else -> R.string.state_loading
            }))
            v.setTextColor(R.id.wDot, ContextCompat.getColor(ctx, when (up) {
                true -> R.color.widget_ok
                false -> R.color.widget_bad
                else -> R.color.widget_dim
            }))
            v.setTextViewText(R.id.wCost,
                ctx.getString(R.string.widget_today_fmt, cost?.let { money(it.today.cost) } ?: "—"))
            v.setTextViewText(R.id.wSub, ctx.getString(R.string.widget_sub_fmt,
                cost?.let { money(it.balance) } ?: "—", cost?.let { money(it.totalCost) } ?: "—"))
            v.setOnClickPendingIntent(R.id.wRoot, act(ctx, "refresh"))
            v.setOnClickPendingIntent(R.id.wCost, act(ctx, "cost"))
            v.setOnClickPendingIntent(R.id.wStart, act(ctx, "start"))
            v.setOnClickPendingIntent(R.id.wStop, act(ctx, "stop"))
            v.setOnClickPendingIntent(R.id.wOpen, act(ctx, "open"))
            return v
        }

        /** 上次 App 拉到的官方费用（含余额/累计），没有就到时显示「—」 */
        private fun cachedCost(ctx: Context): Cost? =
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getString("cost_json_cache", null)?.let { DshApi.parseCost(it) }

        /** 金额格式化：不足 1 元显示 4 位小数 */
        private fun money(v: Double): String =
            if (v < 1.0) String.format(Locale.US, "¥%.4f", v)
            else String.format(Locale.US, "¥%.2f", v)

        private fun act(ctx: Context, what: String): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("widget_action", what)
            return PendingIntent.getActivity(
                ctx, what.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
