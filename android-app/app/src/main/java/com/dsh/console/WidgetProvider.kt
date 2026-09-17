package com.dsh.console

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/** 桌面小组件：状态灯 + 启动 / 停止 / 打开 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { paint(ctx, mgr, it) }
    }

    private fun paint(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_status)
        v.setTextViewText(R.id.wState, ctx.getString(R.string.state_loading))
        v.setTextColor(R.id.wDot, ContextCompat.getColor(ctx, R.color.dim))
        mgr.updateAppWidget(id, v)

        val pending = goAsync()
        Thread {
            val port = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("port", 3080)
            val code = try {
                val c = java.net.URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                val r = c.responseCode; c.disconnect(); r
            } catch (e: Exception) { 0 }

            val up = code == 401 || code == 200
            val v2 = RemoteViews(ctx.packageName, R.layout.widget_status)
            v2.setTextViewText(R.id.wState, ctx.getString(if (up) R.string.state_running else R.string.state_stopped))
            v2.setTextColor(R.id.wDot, ContextCompat.getColor(ctx, if (up) R.color.ok else R.color.bad))
            v2.setOnClickPendingIntent(R.id.wStart, act(ctx, "start"))
            v2.setOnClickPendingIntent(R.id.wStop, act(ctx, "stop"))
            v2.setOnClickPendingIntent(R.id.wOpen, act(ctx, "open"))
            mgr.updateAppWidget(id, v2)
            pending.finish()
        }.start()
    }

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
