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

/** 官方接口的单个时间区间（金额 + 请求数 + tokens） */
data class CostPeriod(
    val cost: Double,
    val req: Int,
    val hit: Long,
    val miss: Long,
    val resp: Long
) {
    val tokens: Long get() = hit + miss + resp
    val cacheRate: Double get() = if (hit + miss == 0L) 0.0 else 100.0 * hit / (hit + miss)
}

/**
 * dsh-cost.sh json 的输出。
 *
 * 全部来自 platform.deepseek.com 官方接口（余额 / 消费 / 请求数 / tokens），
 * 与「DeepSeek 开放平台」网页一致，不再依赖本地会话扫描或余额差分估算。
 */
data class Cost(
    val source: String,
    val at: String,
    val error: String,
    val currency: String,
    val balance: Double,
    val granted: Double,
    val totalCost: Double,
    val today: CostPeriod,
    val yesterday: CostPeriod,
    val month: CostPeriod,
    val d30: CostPeriod,
    val d7: CostPeriod,
    val models: Map<String, Double>,
    val keys: Map<String, Double>
)

object DshApi {

    /** 与 assets/dsh-ctl.sh 里的 CTL_VER 保持一致 */
    const val CTL_VERSION = "7"

    /** 与 assets/dsh-cost.sh 的版本对应：改脚本就让旧标记失效，重新落盘一次 */
    private const val COST_VER = "5"

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
        // 官方费用 JSON 的特征键：source + periods（status JSON 没有）
        if (!raw.contains("\"periods\"") || !raw.contains("\"source\"")) return null
        return try {
            val o = org.json.JSONObject(raw.substring(i, raw.lastIndexOf('}') + 1))
            val ps = o.optJSONObject("periods") ?: org.json.JSONObject()
            fun per(k: String): CostPeriod {
                val p = ps.optJSONObject(k) ?: org.json.JSONObject()
                return CostPeriod(
                    cost = p.optDouble("cost", 0.0),
                    req = p.optInt("req", 0),
                    hit = p.optLong("hit", 0),
                    miss = p.optLong("miss", 0),
                    resp = p.optLong("resp", 0)
                )
            }
            fun flat(k: String): Map<String, Double> {
                val j = o.optJSONObject(k) ?: return emptyMap()
                val m = LinkedHashMap<String, Double>()
                val it = j.keys()
                while (it.hasNext()) { val key = it.next(); m[key] = j.optDouble(key, 0.0) }
                return m
            }
            Cost(
                source = o.optString("source", ""),
                at = o.optString("at", ""),
                error = if (o.isNull("err")) "" else o.optString("err", ""),
                currency = o.optString("currency", "CNY"),
                balance = o.optDouble("balance", 0.0),
                granted = o.optDouble("granted", 0.0),
                totalCost = o.optDouble("totalCost", 0.0),
                today = per("today"), yesterday = per("yesterday"),
                month = per("month"), d30 = per("d30"), d7 = per("d7"),
                models = flat("models"), keys = flat("keys")
            )
        } catch (e: Exception) {
            null
        }
    }

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
