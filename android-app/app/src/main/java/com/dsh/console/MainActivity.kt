package com.dsh.console

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dsh.console.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var seq = 100

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 接收 Termux 回传的执行结果 */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val bundle = intent?.getBundleExtra(TermuxRunner.EXTRA_RESULT_BUNDLE)
            val stdout = bundle?.getString("stdout")?.trim().orEmpty()
            val stderr = bundle?.getString("stderr")?.trim().orEmpty()
            val exit = bundle?.getInt("exitCode", -1) ?: -1
            val errmsg = bundle?.getString("errmsg")?.trim().orEmpty()

            if (stdout.isNotEmpty()) log(stdout)
            if (stderr.isNotEmpty()) log("[stderr] $stderr")
            if (errmsg.isNotEmpty()) log("[termux] $errmsg")
            log("── 退出码 $exit ──")

            if (stdout.contains("SERVICE=UP")) {
                b.tvStatus.text = "服务：运行中 ●   看门狗：" +
                    if (stdout.contains("WATCHDOG=UP")) "运行中 ●" else "未运行 ○"
                b.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
            } else if (stdout.contains("SERVICE=DOWN")) {
                b.tvStatus.text = "服务：已停止 ○   （点「启动」拉起）"
                b.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bad))
            }
            val url = Regex("https?://\\S+").find(stdout)?.value
            if (url != null) {
                lastUrl = url
                if (pendingOpen) {
                    pendingOpen = false
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    log("↗ 打开 $url")
                }
            } else if (pendingOpen) {
                pendingOpen = false
                log("✗ 没拿到地址：服务可能还没启动")
            }
        }
    }

    private var lastUrl: String? = null
    private var pendingOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val filter = IntentFilter(TermuxRunner.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }

        b.btnPreflight.setOnClickListener { preflight() }
        b.btnRefresh.setOnClickListener { refreshStatus() }
        b.btnInstall.setOnClickListener { installDsh() }
        b.btnStart.setOnClickListener { run("setsid bash ~/dsh/dsh-watchdog.sh >/dev/null 2>&1 < /dev/null & echo started", "启动 DSH") }
        b.btnStop.setOnClickListener { run(stopCmd, "停止 DSH") }
        b.btnOpen.setOnClickListener { openWeb() }

        log("DSH 控制台就绪")
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
    }

    // ---------------- 命令定义 ----------------

    private val stopCmd = """
        if [ -f ~/dsh/dsh-watchdog.pid ]; then kill "$(cat ~/dsh/dsh-watchdog.pid)" 2>/dev/null && echo "看门狗已停"; fi
        pkill -f "expose-internal[s]" 2>/dev/null && echo "dsh web 已停" || echo "dsh web 未在运行"
    """.trimIndent()

    private val statusCmd = """
        if pgrep -f "expose-internal[s]" >/dev/null 2>&1; then echo SERVICE=UP; else echo SERVICE=DOWN; fi
        if [ -f ~/dsh/dsh-watchdog.pid ] && kill -0 "$(cat ~/dsh/dsh-watchdog.pid)" 2>/dev/null; then echo WATCHDOG=UP; else echo WATCHDOG=DOWN; fi
        if command -v dsh >/dev/null 2>&1; then echo "dsh: $(dsh --version 2>/dev/null | head -1)"; else echo "dsh: 未安装"; fi
        sed -n 's/^dsh web: //p' ~/dsh/dsh-web-url.txt 2>/dev/null | head -1
    """.trimIndent()

    private val preflightCmd = """
        echo "--- 环境体检 ---"
        echo "HOME=$HOME"
        command -v bash >/dev/null && echo "bash: OK" || echo "bash: 缺失"
        command -v node >/dev/null && echo "node: $(node -v)" || echo "node: 缺失"
        command -v dsh  >/dev/null && echo "dsh: $(dsh --version 2>/dev/null|head -1)" || echo "dsh: 未安装"
        [ -d ~/dsh ] && echo "~/dsh: 存在" || echo "~/dsh: 不存在"
        grep -q 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null && echo "allow-external-apps: OK" || echo "allow-external-apps: 未设置"
        command -v termux-battery-status >/dev/null && echo "termux-api 脚本: OK" || echo "termux-api 脚本: 缺失"
        echo "PREFLIGHT_DONE"
    """.trimIndent()

    // ---------------- 动作 ----------------

    private fun run(cmd: String, label: String, background: Boolean = true) {
        log("▶ $label")
        val pi = PendingIntent.getBroadcast(
            this, ++seq,
            Intent(TermuxRunner.ACTION_RESULT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            startService(TermuxRunner.buildIntent(cmd, label, background, pi))
        } catch (e: Exception) {
            log("✗ 调用 Termux 失败：${e.message}")
            log("  请确认：① Termux 已装 ② allow-external-apps=true")
        }
    }

    private fun preflight() = run(preflightCmd, "环境体检")

    private fun refreshStatus() = run(statusCmd, "读取状态")

    private fun installDsh() {
        val key = b.etApiKey.text.toString().trim()
        val script = assets.open("dsh-oneclick.sh").readBytes()
        val b64 = Base64.encodeToString(script, Base64.NO_WRAP)
        val args = if (key.isNotEmpty()) " --api-key '$key'" else ""
        log("▶ 安装 dsh（脚本 ${script.size} 字节已注入，可能耗时 10~20 分钟）")
        val cmd = """
            set -e
            mkdir -p ~/dsh && echo '$b64' | base64 -d > ~/dsh/dsh-oneclick.sh
            chmod +x ~/dsh/dsh-oneclick.sh
            bash ~/dsh/dsh-oneclick.sh$args
        """.trimIndent()
        run(cmd, "安装 dsh")
    }

    private fun openWeb() {
        pendingOpen = true
        run(statusCmd, "取地址并打开")
    }

    // ---------------- 日志 ----------------

    private fun log(msg: String) {
        runOnUiThread {
            b.tvLog.append("[${fmt.format(Date())}] $msg\n")
            b.svLog.post { b.svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}
