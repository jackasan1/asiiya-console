package com.dsh.console

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dsh.console.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val ui = Handler(Looper.getMainLooper())
    private var seq = 100
    private var busy = 0
    private var auto = true
    private var pendingOpen = false
    private var lastUrl = ""
    private var installPolling = false

    private val RUN_PERM = "com.termux.permission.RUN_COMMAND"
    private val REQ_RUN = 1
    private val REQ_NOTI = 2

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = onResult(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (checkSelfPermission(RUN_PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(RUN_PERM), REQ_RUN)
        }

        val f = IntentFilter(TermuxRunner.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }

        b.btnPreflight.setOnClickListener { ctl(getString(R.string.act_preflight), "preflight") }
        b.btnRefresh.setOnClickListener { ctl(getString(R.string.act_refresh), "status") }
        b.btnStart.setOnClickListener { ctl(getString(R.string.act_start), "start") }
        b.btnStop.setOnClickListener { confirm(getString(R.string.confirm_stop)) { ctl(getString(R.string.act_stop), "stop") } }
        b.btnInstall.setOnClickListener { confirmInstall() }
        b.btnOpen.setOnClickListener { openConsole() }
        b.tvUrl.setOnClickListener { copyUrl() }

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, DshApi.CTL_VERSION)
        log(getString(R.string.msg_ready))
        ctl(getString(R.string.act_refresh), "status")
    }

    override fun onResume() { super.onResume(); auto = true; ui.post(tick) }
    override fun onPause() { super.onPause(); auto = false; ui.removeCallbacks(tick) }

    /** 每 5 秒自动轮询；安装中则轮询安装日志 */
    private val tick = object : Runnable {
        override fun run() {
            if (!auto) return
            if (busy == 0) {
                if (installPolling) ctl("", "log", "4000", silent = true)
                else ctl("", "status", silent = true)
            }
            ui.postDelayed(this, 5000)
        }
    }

    // ---------------- 调用 Termux ----------------

    private fun ctl(label: String, vararg args: String, silent: Boolean = false, stdin: String? = null) {
        if (busy > 0 && !silent) { toast(getString(R.string.msg_busy, busy)); return }
        busy++
        if (!silent && label.isNotEmpty()) log("▶ " + label)
        if (!silent) setButtons(false)

        try {
            startService(
                TermuxRunner.intent(
                    DshApi.cmd(this, *args),
                    label.ifEmpty { "status" },
                    stdin,
                    TermuxRunner.resultPendingIntent(this, ++seq)
                )
            )
        } catch (e: Exception) {
            busy = (busy - 1).coerceAtLeast(0)
            setButtons(true)
            log(getString(R.string.msg_call_failed, e.message ?: ""))
            log(getString(R.string.hint_allow_external))
        }
    }

    private fun onResult(intent: Intent?) {
        val bundle = intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY)
            ?: intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY_LEGACY)

        val stdout = bundle?.getString("stdout")?.trim().orEmpty()
        val stderr = bundle?.getString("stderr")?.trim().orEmpty()
        val errmsg = bundle?.getString("errmsg")?.trim().orEmpty()

        if (bundle == null) {
            log(getString(R.string.msg_no_bundle))
        }
        if (stdout.isNotEmpty()) onStdout(stdout)
        if (stderr.isNotEmpty()) log("[stderr] " + stderr)
        if (errmsg.isNotEmpty()) log("[termux] " + errmsg)

        busy = (busy - 1).coerceAtLeast(0)
        if (busy == 0) setButtons(true)
    }

    private fun onStdout(out: String) {
        val st = DshApi.parseStatus(out)
        if (st != null) { applyStatus(st); return }

        if (installPolling) {
            b.svInstall.visibility = View.VISIBLE
            b.tvInstall.text = out
            return
        }

        val lines = out.lines()
        val urlLine = lines.lastOrNull { it.startsWith("URL=") }
        val rest = lines.filterNot { it.startsWith("URL=") }.joinToString("\n").trim()

        if (urlLine != null) {
            lastUrl = urlLine.removePrefix("URL=").trim()
            if (lastUrl.isNotEmpty()) b.tvUrl.text = lastUrl
        }
        if (rest.isNotEmpty()) log(rest)
        if (urlLine != null && pendingOpen && lastUrl.isNotEmpty()) {
            pendingOpen = false
            launchConsole(lastUrl)
        }
    }

    private fun applyStatus(s: Status) {
        paint(b.tvSvc, s.service, getString(R.string.st_service))
        paint(b.tvWd, s.watchdog, getString(R.string.st_watchdog))
        paint(b.tvPort, s.port, getString(R.string.st_port_fmt, s.portCode))

        b.tvVersion.text = getString(
            R.string.version_fmt, BuildConfig.VERSION_NAME, DshApi.CTL_VERSION
        ) + if (s.dshVersion.isNotEmpty()) "  ·  dsh ${s.dshVersion}" else ""

        if (s.url.isNotEmpty()) { lastUrl = s.url; b.tvUrl.text = s.url }
        else b.tvUrl.text = getString(R.string.st_no_url)

        if (s.installing != installPolling) {
            installPolling = s.installing
            b.svInstall.visibility = if (s.installing) View.VISIBLE else View.GONE
            if (!s.installing) b.tvInstall.text = ""
        }
    }

    private fun paint(tv: TextView, up: Boolean, label: String) {
        tv.text = getString(if (up) R.string.st_dot_up else R.string.st_dot_down, label)
        tv.setTextColor(ContextCompat.getColor(this, if (up) R.color.ok else R.color.dim))
    }

    // ---------------- 动作 ----------------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTI)
        }
    }

    private fun confirmInstall() {
        val key = b.etApiKey.text.toString().trim()
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_install_title)
            .setMessage(if (key.isEmpty()) getString(R.string.confirm_install_no_key)
                        else getString(R.string.confirm_install_with_key))
            .setPositiveButton(R.string.ok) { _, _ ->
                b.tvInstall.text = ""
                b.svInstall.visibility = View.VISIBLE
                installPolling = true
                askNotificationPermission()
                InstallService.start(this, key)
                log(getString(R.string.act_install) + " → 已交给前台服务，通知栏可见进度")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(R.string.ok) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openConsole() {
        pendingOpen = true
        ctl(getString(R.string.act_open), "open")
    }

    private fun launchConsole(url: String) {
        startActivity(Intent(this, ConsoleActivity::class.java).putExtra(ConsoleActivity.EXTRA_URL, url))
        log(getString(R.string.msg_opened))
    }

    private fun copyUrl() {
        if (lastUrl.isEmpty()) { toast(getString(R.string.no_url)); return }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("dsh", lastUrl))
        toast(getString(R.string.copied))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RUN) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            log(getString(if (ok) R.string.perm_granted else R.string.perm_denied))
        }
    }

    // ---------------- 视图 ----------------

    private fun setButtons(on: Boolean) {
        listOf(b.btnPreflight, b.btnRefresh, b.btnStart, b.btnStop, b.btnInstall, b.btnOpen)
            .forEach { it.isEnabled = on }
    }

    private fun log(msg: String) {
        runOnUiThread {
            b.tvLog.append("[${fmt.format(Date())}] $msg\n")
            b.svLog.post { b.svLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
