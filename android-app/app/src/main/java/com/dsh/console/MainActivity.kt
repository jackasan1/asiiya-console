package com.dsh.console

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.hypot
import kotlin.math.max
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
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
    /** 用户主动点过「停止」后，不再自动拉起 */
    private var userStopped = false
    /** 连续几次探测到服务未运行 */
    private var downTicks = 0
    private var lastAutoStart = 0L

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

        b.btnMenu.setOnClickListener { b.drawer.openDrawer(GravityCompat.START) }
        listOf(b.circleStart, b.circleStop, b.circleLog, b.circleSettings,
               b.btnMenu, b.btnTopRight, b.btnConsole).forEach { pressable(it) }
        b.btnTopRight.setOnClickListener { toggleTheme(it) }
        b.ring.setOnClickListener { openConsole() }
        b.centerPanel.setOnClickListener { openConsole() }
        b.actStart.setOnClickListener {
            userStopped = false; downTicks = 0
            ringBusy(); action(getString(R.string.act_start), "start")
        }
        b.actStop.setOnClickListener {
            confirm(getString(R.string.confirm_stop)) {
                userStopped = true; downTicks = 0
                ringBusy(); action(getString(R.string.act_stop), "stop")
            }
        }
        b.actLog.setOnClickListener { toggleLog() }
        b.actSettings.setOnClickListener { showSheet() }

        // 设置弹出卡片
        b.sheetBg.setOnClickListener { hideSheet() }
        b.btnSetClose.setOnClickListener { hideSheet() }
        b.btnSetCopy.setOnClickListener { copyUrl() }
        b.btnSetConsole.setOnClickListener { hideSheet(); openConsole() }
        b.tvSetKey.setOnClickListener { hideSheet(); keyDialog() }
        b.btnLogClear.setOnClickListener { b.tvLog.text = "" }
        b.btnConsole.setOnClickListener { openConsole() }

        // 左侧抽屉
        b.tvDrawerVer.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, DshApi.CTL_VERSION)
        b.drawerPreflight.setOnClickListener { closeDrawer(); ctl(getString(R.string.act_preflight), "preflight") }
        b.drawerInstall.setOnClickListener { showInstallPage(true) }
        b.drawerBack.setOnClickListener { showInstallPage(false) }
        b.navInstall.setOnClickListener { showInstallPage(false); closeDrawer(); installDialog() }
        b.navRepair.setOnClickListener {
            showInstallPage(false); closeDrawer()
            ringBusy(); action(getString(R.string.act_repair), "repair")
        }
        b.navUninstall.setOnClickListener { showInstallPage(false); closeDrawer(); uninstallDialog() }
        b.drawerConsole.setOnClickListener { closeDrawer(); openConsole() }
        b.drawerCopy.setOnClickListener { closeDrawer(); copyUrl() }
        b.drawerAbout.setOnClickListener { closeDrawer(); about() }
        b.drawerLogs.setOnClickListener { closeDrawer(); logFileDialog() }
        b.tvHost.setOnClickListener { copyUrl() }

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, DshApi.CTL_VERSION)
        log(getString(R.string.msg_ready))
        ctl(getString(R.string.act_refresh), "status")
        maybeRevealTheme()
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
        if (!silent && label.isNotEmpty()) log("▶ " + label, K.CMD)

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
            out.lines().filter { it.isNotBlank() }.forEach { log(it, kindOf(it)) }
            return
        }

        val lines = out.lines()
        val urlLine = lines.lastOrNull { it.startsWith("URL=") }
        val rest = lines.filterNot { it.startsWith("URL=") }.joinToString("\n").trim()

        if (urlLine != null) {
            lastUrl = urlLine.removePrefix("URL=").trim()
            if (lastUrl.isNotEmpty()) hostLabel()
        }
        rest.lines().filter { it.isNotBlank() }.forEach { log(it, kindOf(it)) }
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
        val prev = lastStatus
        lastStatus = s

        if (prev == null || prev.service != s.service) pulse()

        // ---- 保活：前台检测到服务挂了就自动拉起（用户主动停止的除外）----
        if (s.service) {
            downTicks = 0
        } else if (!userStopped && auto && busy == 0) {
            downTicks++
            if (downTicks == 2) log(getString(R.string.msg_service_down))
            val now = System.currentTimeMillis()
            if (downTicks >= 3 && now - lastAutoStart > 120_000L) {
                lastAutoStart = now
                downTicks = 0
                log(getString(R.string.msg_auto_start))
                action(getString(R.string.act_start), "start")
            }
        }
        b.ring.spin(false)
        b.ring.animateTo(if (s.service) 300f else 110f, 850)
        b.tvState.text = getString(if (s.service) R.string.state_running else R.string.state_stopped)
        b.tvState.setTextColor(ContextCompat.getColor(this, if (s.service) R.color.fg else R.color.dim))

        rollText(b.tvPortValue, s.portCode)
        b.tvPortValue.setTextColor(
            ContextCompat.getColor(this, if (s.port) R.color.fg else R.color.bad)
        )

        b.tvModelValue.text = getString(if (s.model == "OK") R.string.model_ok else R.string.model_missing)
        b.tvModelValue.setTextColor(
            ContextCompat.getColor(this, if (s.model == "OK") R.color.ok else R.color.dim)
        )

        rollText(b.tvProcValue, s.procs.ifEmpty { "0" })
        b.tvUptimeValue.text = if (s.runtime.isNotEmpty()) s.runtime else "--:--:--"

        if (s.url.isNotEmpty()) { lastUrl = s.url; hostLabel() }

        if (s.installing != installPolling) {
            installPolling = s.installing
            b.installCard.visibility = if (s.installing) View.VISIBLE else View.GONE
            if (!s.installing) b.tvInstall.text = ""
        }
    }

    // ---------------- 交互 ----------------

    // ---------------- 主题 ----------------

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private fun isNightNow(): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateThemeIcon() {
        b.ivTheme.setImageResource(if (isNightNow()) R.drawable.ic_sun else R.drawable.ic_moon)
    }

    /** 点太阳/月亮：记录点击位置 → 图标旋转 → 切主题 → 新界面圆形揭示 */
    private fun toggleTheme(v: View) {
        val next = if (isNightNow()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        prefs.edit()
            .putBoolean("reveal", true)
            .putFloat("cx", loc[0] + v.width / 2f)
            .putFloat("cy", loc[1] + v.height / 2f)
            .putInt("night", next)
            .apply()
        v.animate().rotationBy(180f).scaleX(0.75f).scaleY(0.75f).setDuration(180)
            .withEndAction { AppCompatDelegate.setDefaultNightMode(next) }
            .start()
        toast(getString(if (next == AppCompatDelegate.MODE_NIGHT_YES) R.string.theme_dark else R.string.theme_light))
    }

    private fun maybeRevealTheme() {
        if (!prefs.getBoolean("reveal", false)) { updateThemeIcon(); return }
        prefs.edit().putBoolean("reveal", false).apply()
        val cx = prefs.getFloat("cx", 0f).toInt()
        val cy = prefs.getFloat("cy", 0f).toInt()
        val root = b.root
        root.post {
            updateThemeIcon()
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return@post
            val r = hypot(max(cx, w - cx).toDouble(), max(cy, h - cy).toDouble()).toFloat()
            try {
                val anim = ViewAnimationUtils.createCircularReveal(root, cx, cy, 0f, r)
                anim.duration = 420L
                anim.interpolator = AccelerateDecelerateInterpolator()
                anim.start()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeDrawer() { b.drawer.closeDrawers() }

    // ---------------- 设置弹出卡片 ----------------

    private fun showSheet() {
        val st = lastStatus
        b.tvSetApp.text = BuildConfig.VERSION_NAME
        b.tvSetCtl.text = st?.ctlVersion ?: DshApi.CTL_VERSION
        b.tvSetDsh.text = st?.dshVersion?.ifEmpty { "-" } ?: "-"
        b.tvSetUrl.text = lastUrl.ifEmpty { "-" }
        b.tvSetKey.text = getString(if (st?.model == "OK") R.string.model_ok else R.string.model_missing)
        b.sheetScrim.visibility = View.VISIBLE
        b.sheetCard.post {
            val h = b.sheetCard.height.toFloat().let { if (it > 0) it else 420f }
            b.sheetCard.translationY = h
            b.sheetCard.animate().translationY(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
            b.sheetBg.animate().alpha(0.45f).setDuration(220).start()
        }
    }

    private fun hideSheet() {
        val h = b.sheetCard.height.toFloat().let { if (it > 0) it else 420f }
        b.sheetCard.animate().translationY(h).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
        b.sheetBg.animate().alpha(0f).setDuration(180).withEndAction {
            b.sheetScrim.visibility = View.GONE
            b.sheetCard.translationY = 0f
        }.start()
    }

    // ---------------- 抽屉二级页 ----------------

    private fun showInstallPage(show: Boolean) {
        val from = if (show) b.drawerRoot else b.drawerInstallPage
        val to = if (show) b.drawerInstallPage else b.drawerRoot
        var w = b.drawerRoot.width.toFloat()
        if (w <= 0) w = 720f
        from.animate().translationX(-w).alpha(0f).setDuration(190).withEndAction {
            from.visibility = View.GONE
            from.translationX = 0f
            from.alpha = 1f
        }.start()
        to.visibility = View.VISIBLE
        to.translationX = if (show) w else -w
        to.alpha = 0f
        to.animate().translationX(0f).alpha(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // ---------------- 仪表盘动画 ----------------

    /** 启动/停止中：弧变短并绕圈转 + 中心圆盘脉冲 */
    private fun ringBusy() {
        b.ring.spin(true)
        b.ring.animateTo(90f, 260)
        pulse()
    }

    private fun pulse() {
        b.centerPanel.animate().scaleX(1.07f).scaleY(1.07f).setDuration(140)
            .withEndAction {
                b.centerPanel.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
    }

    /** 统一的动作入口：自动展开日志卡，让输出可见 */
    private fun action(label: String, vararg args: String, stdin: String? = null) {
        if (b.logCard.visibility != View.VISIBLE) b.logCard.visibility = View.VISIBLE
        ctl(label, *args, stdin = stdin)
    }

    /** 数字平滑滚动；非数字则直接替换 */
    private fun rollText(tv: TextView, target: String) {
        val a = (tv.text?.toString() ?: "").trim().toIntOrNull()
        val b = target.trim().toIntOrNull()
        if (a != null && b != null && a != b) {
            ValueAnimator.ofInt(a, b).apply {
                duration = 420
                interpolator = DecelerateInterpolator()
                addUpdateListener { tv.text = (it.animatedValue as Int).toString() }
                start()
            }
        } else {
            tv.text = target
        }
    }

    // ---------------- API Key 管理 ----------------

    private fun keyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.key_title)
            .setPositiveButton(R.string.key_change) { _, _ -> keyInputDialog() }
            .setNeutralButton(R.string.key_clear) { _, _ ->
                confirm(getString(R.string.key_clear) + "？") {
                    action(getString(R.string.key_clear), "clearkey")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun keyInputDialog() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val et = EditText(this).apply {
            hint = getString(R.string.key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.key_set)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val k = et.text.toString().trim()
                if (k.isEmpty()) { toast(getString(R.string.key_hint)); return@setPositiveButton }
                action(getString(R.string.key_set), "setkey", stdin = "$k\n")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_title)
            .setMessage(R.string.uninstall_msg)
            .setPositiveButton(R.string.uninstall_all) { _, _ ->
                action(getString(R.string.act_uninstall), "uninstall-npm")
            }
            .setNeutralButton(R.string.uninstall_scripts) { _, _ ->
                action(getString(R.string.act_uninstall), "uninstall")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 选择要查看的 Termux 侧日志文件 */
    private fun logFileDialog() {
        val files = arrayOf("dsh-web.log", "dsh-watchdog.log", "install.log", "dsh-boot.log")
        AlertDialog.Builder(this)
            .setTitle(R.string.logfiles_title)
            .setItems(files) { _, which ->
                action(files[which], "tail", files[which], "4000")
            }
            .setNegativeButton(R.string.cancel, null)
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
                b.logCard.visibility = View.VISIBLE
                b.installCard.visibility = View.VISIBLE
                installPolling = true
                ringBusy()
                askNotificationPermission()
                // 用 MainActivity 这条已验证可用的路径发起安装
                ctl(getString(R.string.act_install), "install",
                    stdin = if (key.isEmpty()) "" else "$key\n")
                InstallService.watch(this)
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

    // ---------------- 彩色 + 打字机日志 ----------------

    enum class K { CMD, OK, WARN, ERR, DATA, DIM }

    private val logBuf = SpannableStringBuilder()
    private val q = ArrayDeque<CharSequence>()
    private var typing = false

    private fun colorOf(k: K): Int = when (k) {
        K.CMD -> R.color.accent
        K.OK -> R.color.ok
        K.WARN -> R.color.warn
        K.ERR -> R.color.bad
        K.DATA -> R.color.accent2
        K.DIM -> R.color.dim
    }

    private fun makeLine(msg: String, k: K): CharSequence {
        val sb = SpannableStringBuilder()
        val ts = SpannableString("[" + fmt.format(Date()) + "] ")
        ts.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.log_ts)),
            0, ts.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append(ts)
        val body = SpannableString(msg + "\n")
        body.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, colorOf(k))),
            0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (k == K.CMD || k == K.ERR) {
            body.setSpan(StyleSpan(Typeface.BOLD), 0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(body)
        return sb
    }

    /** 入队后由 pump 逐行吐出，形成打字机效果 */
    private fun log(msg: String, k: K = K.DIM) {
        val line = makeLine(msg, k)
        runOnUiThread {
            q.addLast(line)
            if (!typing) { typing = true; ui.post(pump) }
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            val next = q.removeFirstOrNull()
            if (next == null) { typing = false; return }
            logBuf.append(next)
            if (logBuf.length > 80000) {
                val cut = logBuf.indexOf("\n", 40000)
                if (cut > 0) logBuf.delete(0, cut + 1)
            }
            b.tvLog.text = logBuf
            b.svLog.post { b.svLog.fullScroll(View.FOCUS_DOWN) }
            ui.postDelayed(this, if (q.size > 20) 6L else 40L)
        }
    }

    /** 按内容猜颜色 */
    private fun kindOf(l: String): K = when {
        l.contains("✓") -> K.OK
        l.contains("✗") -> K.ERR
        l.trim().startsWith("!") -> K.WARN
        l.startsWith("SERVICE=UP") || l.startsWith("WATCHDOG=UP") || l.startsWith("PORT=UP") -> K.OK
        l.contains("=DOWN") -> K.ERR
        l.startsWith("URL=") -> K.DATA
        l.startsWith("{") || l.startsWith("  \"") -> K.DATA
        l.contains("✗") || l.contains("失败") || l.contains("超时") || l.contains("未取到") || l.contains("[stderr]") -> K.ERR
        l.contains("已就绪") || l.contains("就绪") || l.contains("完成") || l.contains("已拉起") -> K.OK
        l.contains("⚠") || l.contains("未设置") || l.contains("需要修复") -> K.WARN
        l.contains(": OK") -> K.OK
        l.contains(": 缺失") || l.contains("未安装") -> K.WARN
        else -> K.DIM
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
