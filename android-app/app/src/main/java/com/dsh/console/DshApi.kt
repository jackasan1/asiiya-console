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
    val url: String,
    val pid: String,
    val procs: String,
    val runtime: String,
    val model: String
)

object DshApi {

    /** 与 assets/dsh-ctl.sh 里的 CTL_VER 保持一致 */
    const val CTL_VERSION = "5"

    /** 每次调用前先把最新版 dsh-ctl.sh 落盘（幂等，约 4KB） */
    private fun bootstrap(ctx: Context): String {
        val b64 = Base64.encodeToString(
            ctx.assets.open("dsh-ctl.sh").readBytes(), Base64.NO_WRAP
        )
        return "mkdir -p ~/dsh && printf '%s' '$b64' | base64 -d > ~/dsh/dsh-ctl.sh && chmod +x ~/dsh/dsh-ctl.sh && "
    }

    /**
     * 安装脚本体积较大（约 25KB → base64 34KB），只在缺失时才落盘，
     * 避免每次调用都把 34KB 塞进 Intent。
     */
    private fun bootstrapInstallScript(ctx: Context): String {
        val b64 = Base64.encodeToString(
            ctx.assets.open("dsh-oneclick.sh").readBytes(), Base64.NO_WRAP
        )
        return "if [ ! -s ~/dsh/dsh-oneclick.sh ]; then " +
               "printf '%s' '$b64' | base64 -d > ~/dsh/dsh-oneclick.sh && " +
               "chmod +x ~/dsh/dsh-oneclick.sh; fi && "
    }

    fun cmd(ctx: Context, vararg args: String): String =
        bootstrap(ctx) + "bash ~/dsh/dsh-ctl.sh " + args.joinToString(" ")

    /** 安装命令：额外确保 dsh-oneclick.sh 已就位 */
    fun installCmd(ctx: Context): String =
        bootstrap(ctx) + bootstrapInstallScript(ctx) + "bash ~/dsh/dsh-ctl.sh install"

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
                url = o.optString("url", ""),
                pid = o.optString("pid", ""),
                procs = o.optString("procs", "0"),
                runtime = o.optString("runtime", ""),
                model = o.optString("model", "MISSING")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseUrl(raw: String): String? =
        raw.lineSequence().firstOrNull { it.startsWith("URL=") }?.removePrefix("URL=")?.trim()
            ?.takeIf { it.isNotEmpty() }
}
