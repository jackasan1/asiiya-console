package com.dsh.console

import android.content.Context
import android.util.Base64

/** Termux 侧 dsh-ctl.sh 返回的 JSON 状态 */
data class Status(
    val service: Boolean,
    val watchdog: Boolean,
    val port: Boolean,
    val portCode: String,
    val dshVersion: String,
    val installing: Boolean,
    val ctlVersion: String,
    val url: String
)

object DshApi {

    /** 与 assets/dsh-ctl.sh 里的 CTL_VER 保持一致 */
    const val CTL_VERSION = "3"

    /** 每次调用前先把最新版 dsh-ctl.sh 落盘（幂等，约 3KB） */
    private fun bootstrap(ctx: Context): String {
        val b64 = Base64.encodeToString(
            ctx.assets.open("dsh-ctl.sh").readBytes(), Base64.NO_WRAP
        )
        return "mkdir -p ~/dsh && printf '%s' '$b64' | base64 -d > ~/dsh/dsh-ctl.sh && chmod +x ~/dsh/dsh-ctl.sh && "
    }

    fun cmd(ctx: Context, vararg args: String): String =
        bootstrap(ctx) + "bash ~/dsh/dsh-ctl.sh " + args.joinToString(" ")

    fun parseStatus(raw: String): Status? {
        val i = raw.indexOf('{')
        if (i < 0) return null
        return try {
            val o = org.json.JSONObject(raw.substring(i, raw.lastIndexOf('}') + 1))
            Status(
                service = o.optString("service") == "UP",
                watchdog = o.optString("watchdog") == "UP",
                port = o.optString("port") == "UP",
                portCode = o.optString("portCode", "000"),
                dshVersion = o.optString("dshVersion", ""),
                installing = o.optString("install") == "RUNNING",
                ctlVersion = o.optString("ctlVersion", ""),
                url = o.optString("url", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseUrl(raw: String): String? =
        raw.lineSequence().firstOrNull { it.startsWith("URL=") }?.removePrefix("URL=")?.trim()
            ?.takeIf { it.isNotEmpty() }
}
