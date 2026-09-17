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
    val model: String,
    val modelName: String,
    // ---- OpenList（原 AList）----
    val olService: Boolean,
    val olPortCode: String,
    val olPort: String,
    val olUrl: String,
    val olPid: String,
    val olRuntime: String,
    val olVersion: String,
    val olBoot: Boolean,
    val olInstalled: Boolean
)

/** dsh-cost.sh json 的输出：余额 + 各时段估算费用 */
data class Cost(
    val currency: String,
    val balance: Double,
    val granted: Double,
    val toppedUp: Double,
    val updatedAt: String,
    val error: String,
    val sessions: Int,
    val todayCost: Double,
    val todayTurns: Int,
    val weekCost: Double,
    val monthCost: Double,
    val monthTurns: Int,
    val monthHit: Long,
    val monthMiss: Long,
    val monthOut: Long,
    val totalCost: Double,
    val totalTurns: Int
)

object DshApi {

    /** 与 assets/dsh-ctl.sh 里的 CTL_VER 保持一致 */
    const val CTL_VERSION = "7"

    /** 与 assets/dsh-cost.sh 的版本对应：改脚本就让旧标记失效，重新落盘一次 */
    private const val COST_VER = "2"

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

    /**
     * 费用卡专用：确保 dsh-cost.sh 已落盘。
     *
     * 脚本约 13KB → base64 18KB，不能像 dsh-ctl.sh 那样每次调用都塞进 Intent，
     * 所以用版本标记文件：只有标记缺失（首次 / 脚本升级）时才下发一次。
     */
    private fun bootstrapCost(ctx: Context): String {
        val b64 = Base64.encodeToString(
            ctx.assets.open("dsh-cost.sh").readBytes(), Base64.NO_WRAP
        )
        return "if [ ! -f ~/dsh/.cost-ver" + COST_VER + " ]; then rm -f ~/dsh/.cost-ver*; " +
               "printf '%s' '" + b64 + "' | base64 -d > ~/dsh/dsh-cost.sh && " +
               "chmod +x ~/dsh/dsh-cost.sh && touch ~/dsh/.cost-ver" + COST_VER + "; fi && "
    }

    fun cmd(ctx: Context, vararg args: String): String =
        bootstrap(ctx) + "bash ~/dsh/dsh-ctl.sh " + args.joinToString(" ")

    /** 费用查询：余额（官方接口）+ 本地会话 token 用量估算 */
    fun costCmd(ctx: Context, vararg args: String): String =
        bootstrap(ctx) + bootstrapCost(ctx) + "bash ~/dsh/dsh-ctl.sh cost " + args.joinToString(" ")

    fun parseCost(raw: String): Cost? {
        val i = raw.indexOf('{')
        if (i < 0) return null
        // 只认费用 JSON：缺这两个键就不是（status 由 parseStatus 处理）
        if (!raw.contains("\"balance\"") || !raw.contains("\"today\"")) return null
        return try {
            val o = org.json.JSONObject(raw.substring(i, raw.lastIndexOf('}') + 1))
            val bal = o.optJSONObject("balance") ?: org.json.JSONObject()
            val today = o.optJSONObject("today") ?: org.json.JSONObject()
            val week = o.optJSONObject("week") ?: org.json.JSONObject()
            val month = o.optJSONObject("month") ?: org.json.JSONObject()
            val total = o.optJSONObject("total") ?: org.json.JSONObject()
            Cost(
                currency = bal.optString("currency", "CNY"),
                balance = bal.optDouble("total", 0.0),
                granted = bal.optDouble("granted", 0.0),
                toppedUp = bal.optDouble("topped_up", 0.0),
                updatedAt = bal.optString("at", ""),
                error = bal.optString("error", ""),
                sessions = o.optInt("sessions", 0),
                todayCost = today.optDouble("cost", 0.0),
                todayTurns = today.optInt("turns", 0),
                weekCost = week.optDouble("cost", 0.0),
                monthCost = month.optDouble("cost", 0.0),
                monthTurns = month.optInt("turns", 0),
                monthHit = month.optLong("hit", 0),
                monthMiss = month.optLong("miss", 0),
                monthOut = month.optLong("out", 0),
                totalCost = total.optDouble("cost", 0.0),
                totalTurns = total.optInt("turns", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 安装命令：额外确保 dsh-oneclick.sh 已就位 */
    fun installCmd(ctx: Context): String =
        bootstrap(ctx) + bootstrapInstallScript(ctx) + "bash ~/dsh/dsh-ctl.sh install"

    fun parseStatus(raw: String): Status? {
        val i = raw.indexOf('{')
        if (i < 0) return null
        // 必须真的是状态 JSON：没有 service 字段就不是（避免 getconf 等被误判）
        if (!raw.contains("\"service\"")) return null
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
                model = o.optString("model", "MISSING"),
                modelName = o.optString("modelName", ""),
                olService = o.optString("olState") == "UP",
                olPortCode = o.optString("olPortCode", "000"),
                olPort = o.optString("olPort", "5244"),
                olUrl = o.optString("olUrl", ""),
                olPid = o.optString("olPid", ""),
                olRuntime = o.optString("olRuntime", ""),
                olVersion = o.optString("olVersion", ""),
                olBoot = o.optString("olBoot") == "ON",
                olInstalled = o.optString("olInstalled") == "1"
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseUrl(raw: String): String? =
        raw.lineSequence().firstOrNull { it.startsWith("URL=") }?.removePrefix("URL=")?.trim()
            ?.takeIf { it.isNotEmpty() }
}
