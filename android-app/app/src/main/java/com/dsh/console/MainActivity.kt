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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    private var lastStatus: Status? = null

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

        b.btnMenu.setOnClickListener { showMenu(it) }
        b.btnTopRight.setOnClickListener { showSettings() }
        b.ring.setOnClickListener { openConsole() }
        b.centerPanel.setOnClickListener { openConsole() }
        b.actStart.setOnClickListener { ctl(getString(R.string.act_start), "start") }
        b.actStop.setOnClickListener {
            confirm(getString(R.string.confirm_stop)) { ctl(getString(R.string.act_stop), "stop") }
        }
        b.actLog.setOnClickListener { toggleLog() }
        b.actSettings.setOnClickListener { showSettings() }
        b.btnLogClear.setOnClickListener { b.tvLog.text = "" }
        b.btnHome.setOnClickListener {
            b.svLog.scrollTo(0, 0)
            ctl(getString(R.string.act_refresh), "status")
        }
        b.tvHost.setOnClickListener { copyUrl() }

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

    // ---------------- Termux 调用 ----------------

    private fun ctl(label: String, vararg args: String, silent: Boolean = false, stdin: String? = null) {
        if (busy > 0 && !silent) { toast(getString(R.string.msg_busy, busy)); return }
        busy++
        if (!silent && label.isNotEmpty()) log("▶ " + label)

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
            log(getString(R.string.msg_call_failed, e.message ?: ""))
            log(getString(R.string.hint_allow_external))
        }
    }

    private fun onResult(intent: Intent?) {
        val bundle = intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY)
            ?: intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY_LEGACY)
        val stdout = bundle?.getString("stdout")?.trim().orEmpty()
        val stderr = bundle?.getString("stderr")?.trim().orEmpty()

        if (bundle == null) log(getString(R.string.msg_no_bundle))
        if (stdout.isNotEmpty()) onStdout(stdout)
        if (stderr.isNotEmpty()) log("[stderr] " + stderr)

        busy = (busy - 1).coerceAtLeast(0)
    }

    private fun onStdout(out: String) {
        val st = DshApi.parseStatus(out)
        if (st != null) { applyStatus(st); return }

        if (installPolling) {
            b.installCard.visibility = View.VISIBLE
            b.tvInstall.text = out
            return
        }

        val lines = out.lines()
        val urlLine = lines.lastOrNull { it.startsWith("URL=") }
        val rest = lines.filterNot { it.startsWith("URL=") }.joinToString("\n").trim()

        if (urlLine != null) {
            lastUrl = urlLine.removePrefix("URL=").trim()
            if (lastUrl.isNotEmpty()) hostLabel()
        }
        if (rest.isNotEmpty()) log(rest)
        if (urlLine != null && pendingOpen && lastUrl.isNotEmpty()) {
            pendingOpen = false
            launchConsole(lastUrl)
        }
    }

    private fun hostLabel() {
        val host = lastUrl.substringAfter("://").substringBefore("/")
        if (host.isNotEmpty()) b.tvHost.text = host
    }

    // ---------------- 状态渲染 ----------------

    private fun applyStatus(s: Status) {
        lastStatus = s

        b.ring.sweep = if (s.service) 300f else 110f
        b.tvState.text = getString(if (s.service) R.string.state_running else R.string.state_stopped)
        b.tvState.setTextColor(ContextCompat.getColor(this, if (s.service) R.color.fg else R.color.dim))

        b.tvPortValue.text = s.portCode
        b.tvPortValue.setTextColor(
            ContextCompat.getColor(this, if (s.port) R.color.fg else R.color.bad)
        )

        b.tvModelValue.text = getString(if (s.model == "OK") R.string.model_ok else R.string.model_missing)
        b.tvModelValue.setTextColor(
            ContextCompat.getColor(this, if (s.model == "OK") R.color.ok else R.color.dim)
        )

        b.tvProcValue.text = s.procs.ifEmpty { "0" }
        b.tvUptimeValue.text = if (s.runtime.isNotEmpty()) s.runtime else "--:--:--"

        if (s.url.isNotEmpty()) { lastUrl = s.url; hostLabel() }

        if (s.installing != installPolling) {
            installPolling = s.installing
            b.installCard.visibility = if (s.installing) View.VISIBLE else View.GONE
            if (!s.installing) b.tvInstall.text = ""
        }
    }

    // ---------------- 交互 ----------------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.menu_preflight)).setOnMenuItemClickListener {
                ctl(getString(R.string.act_preflight), "preflight"); true
            }
            menu.add(getString(R.string.menu_install)).setOnMenuItemClickListener {
                installDialog(); true
            }
            menu.add(getString(R.string.menu_console)).setOnMenuItemClickListener {
                openConsole(); true
            }
            menu.add(getString(R.string.menu_copy)).setOnMenuItemClickListener {
                copyUrl(); true
            }
            menu.add(getString(R.string.menu_about)).setOnMenuItemClickListener {
                about(); true
            }
            show()
        }
    }

    private fun showSettings() {
        val s = lastStatus
        val msg = buildString {
            append(getString(R.string.settings_app)).append(": ").append(BuildConfig.VERSION_NAME).append('\n')
            append(getString(R.string.settings_ctl)).append(": ").append(s?.ctlVersion ?: DshApi.CTL_VERSION).append('\n')
            append(getString(R.string.settings_dsh)).append(": ")
            append(s?.dshVersion?.ifEmpty { "-" } ?: "-").append('\n')
            append(getString(R.string.settings_url)).append(":\n")
            append(lastUrl.ifEmpty { "-" })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMessage(msg)
            .setPositiveButton(R.string.settings_copy) { _, _ -> copyUrl() }
            .setNeutralButton(R.string.menu_console) { _, _ -> openConsole() }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun installDialog() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val et = EditText(this).apply {
            hint = getString(R.string.install_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            gravity = Gravity.START
        }
        box.addView(et)

        AlertDialog.Builder(this)
            .setTitle(R.string.install_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val key = et.text.toString().trim()
                b.tvInstall.text = ""
                b.installCard.visibility = View.VISIBLE
                installPolling = true
                askNotificationPermission()
                InstallService.start(this, key)
                log(getString(R.string.act_install) + " → 后台执行中，通知栏可见进度")
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

    private fun toggleLog() {
        val show = b.logCard.visibility != View.VISIBLE
        b.logCard.visibility = if (show) View.VISIBLE else View.GONE
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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTI)
        }
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
