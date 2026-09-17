package com.dsh.console

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Rect
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
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    /** 首次状态到达前显示骨架态 */
    private var skeleton = true
    private var skeletonAnim: ObjectAnimator? = null
    private var logEmpty = true
    /** 网盘未运行时点了「打开」→ 启动就绪后自动打开 */
    private var pendingOpenOl = false
    /** 费用卡：最近一次数据 */
    private var lastCost: Cost? = null
    private var costLoaded = false
    /** 点费用卡后请求的明细报告：拿到输出就弹窗 */
    private var pendingCostReport = false
    private var pendingCostReportAt = 0L
    /** 每 6 次 5s 轮询（=30s）刷一次费用 */
    private var costTick = 0

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
        b.actRestart.setOnClickListener {
            confirm(getString(R.string.act_restart) + "？") { ringBusy(); action(getString(R.string.act_restart), "restart") }
        }
        b.drawerPatch.setOnClickListener { closeDrawer(); action(getString(R.string.menu_checkpatch), "checkpatch") }

        // 手风琴分组：按项目分类（默认展开 dsh，最常用）
        setupGroup(b.grpApp, b.grpAppItems, b.grpAppArrow, false)
        setupGroup(b.grpDsh, b.grpDshItems, b.grpDshArrow, true)
        setupGroup(b.grpOl, b.grpOlItems, b.grpOlArrow, false)
        setupGroup(b.grpCost, b.grpCostItems, b.grpCostArrow, false)

        // 分组里的快捷项
        b.drawerStart.setOnClickListener { closeDrawer(); userStopped = false; ringBusy(); action(getString(R.string.act_start), "start") }
        b.drawerStop.setOnClickListener { closeDrawer(); userStopped = true; ringBusy(); action(getString(R.string.act_stop), "stop") }
        b.drawerRestart.setOnClickListener {
            closeDrawer()
            confirm(getString(R.string.act_restart) + "？") { ringBusy(); action(getString(R.string.act_restart), "restart") }
        }

        // 全面屏手势：把左侧边缘排除出系统返回手势，让抽屉侧滑可用
        if (Build.VERSION.SDK_INT >= 29) {
            b.drawer.post {
                val w = (30 * resources.displayMetrics.density).toInt()
                b.drawer.systemGestureExclusionRects =
                    listOf(Rect(0, 0, w, b.drawer.height))
            }
        }
        b.drawerUpdate.setOnClickListener { closeDrawer(); checkUpdate(false) }
        b.drawerBuild.setOnClickListener { closeDrawer(); action(getString(R.string.menu_buildtime), "buildtime", "6") }
        listOf(b.actStart, b.actStop, b.actRestart, b.actLog,
               b.btnMenu, b.btnTopRight, b.btnConsole).forEach { pressable(it) }
        b.btnTopRight.setOnClickListener { toggleTheme(it) }
        // 方案 A：点标题行或「打开」按钮进控制台；更多收进 ⋮
        b.dshHead.setOnClickListener { openConsole() }
        b.btnDshMore.setOnClickListener { dshMoreDialog() }
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

        // 设置弹出卡片
        b.sheetBg.setOnClickListener { hideSheet() }
        b.btnSetClose.setOnClickListener { hideSheet() }
        b.btnSetCopy.setOnClickListener { copyUrl() }
        b.btnSetConsole.setOnClickListener { hideSheet(); openConsole() }
        b.tvSetKey.setOnClickListener { hideSheet(); keyDialog() }
        b.tvSetModel.setOnClickListener { hideSheet(); modelDialog() }
        b.tvCfgPort.setOnClickListener { numConfDialog("port", getString(R.string.cfg_port), 1024, 65535) }
        b.tvCfgWd.setOnClickListener { numConfDialog("wdInterval", getString(R.string.cfg_wd), 15, 3600) }
        b.tvCfgBoot.setOnClickListener { bootConfDialog() }
        // 日志区嵌在主界面的 ScrollView 里，必须禁止父级拦截触摸，否则内层永远滚不动
        b.svLog.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        b.btnLogClear.setOnClickListener { b.tvLog.text = "" }
        b.btnLogCopy.setOnClickListener {
            val t = b.tvLog.text?.toString().orEmpty()
            if (t.isNotEmpty()) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("dsh-log", t))
                toast(getString(R.string.log_copied))
            }
        }
        b.btnConsole.setOnClickListener { openConsole() }

        // ---- OpenList 网盘卡 ----
        b.btnOlStart.setOnClickListener { olPrimary() }
        b.btnOlStop.setOnClickListener { action(getString(R.string.act_stop), "openlist-stop") }
        b.btnOlOpen.setOnClickListener { openOpenList() }
        b.btnOlMore.setOnClickListener { olMoreDialog() }
        b.swipeMain.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.accent2)
        )
        b.swipeMain.setOnRefreshListener {
            b.swipeMain.isRefreshing = false
            ctl(getString(R.string.act_refresh), "status")
        }

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

        // ---- OpenList 网盘组 ----
        b.drawerOlStart.setOnClickListener { closeDrawer(); action(getString(R.string.act_start), "openlist-start") }
        b.drawerOlStop.setOnClickListener { closeDrawer(); action(getString(R.string.act_stop), "openlist-stop") }
        b.drawerOlRestart.setOnClickListener { closeDrawer(); action(getString(R.string.act_restart), "openlist-restart") }
        b.drawerOlOpen.setOnClickListener { closeDrawer(); openOpenList() }
        b.drawerOlCopy.setOnClickListener { closeDrawer(); copyOlUrl() }
        b.drawerOlPasswd.setOnClickListener { closeDrawer(); olPasswdDialog(false) }
        b.drawerOlLog.setOnClickListener {
            closeDrawer(); action(getString(R.string.ol_view_log), "openlist-log", "4000")
        }
        b.drawerOlInstall.setOnClickListener { closeDrawer(); olPrimary() }
        b.drawerOlBoot.setOnClickListener {
            closeDrawer()
            val on = lastStatus?.olBoot == true
            action(getString(R.string.ol_boot_setting), "openlist-boot", if (on) "off" else "on")
        }

        // ---- DeepSeek 费用组 ----
        b.drawerCostReport.setOnClickListener { closeDrawer(); showCostDetail() }
        b.drawerCost30.setOnClickListener { closeDrawer(); costCommandDialog("daily", "30") }
        b.drawerCostHourly.setOnClickListener { closeDrawer(); costCommandDialog("hourly") }
        b.drawerCostKeys.setOnClickListener { closeDrawer(); costCommandDialog("keys") }
        b.drawerCostRefresh.setOnClickListener { closeDrawer(); costCommandDialog("refresh") }
        b.drawerLogs.setOnClickListener { closeDrawer(); logFileDialog() }
        b.costCard.setOnClickListener { showCostDetail() }
        pressable(b.costCard)

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, DshApi.CTL_VERSION)
        log(getString(R.string.msg_ready))
        ctl(getString(R.string.act_refresh), "status")
        maybeRevealTheme()
        checkUpdate(true)
        startSkeleton()
        maybeFirstRun()

        // 桌面小组件触发的动作
        when (intent?.getStringExtra("widget_action")) {
            "start" -> { userStopped = false; ringBusy(); action(getString(R.string.act_start), "start") }
            "stop" -> { userStopped = true; ringBusy(); action(getString(R.string.act_stop), "stop") }
            "open" -> openConsole()
        }
    }

    override fun onResume() { super.onResume(); auto = true; ui.post(tick) }
    override fun onPause() { super.onPause(); auto = false; ui.removeCallbacks(tick) }

    /** 每 5 秒自动轮询；安装中则轮询安装日志 */
    private val tick = object : Runnable {
        override fun run() {
            if (!auto) return
            // 明细请求超时兜底：Termux 不回来时别把后续输出吞进弹窗
            if (pendingCostReport && System.currentTimeMillis() - pendingCostReportAt > 20_000L) {
                pendingCostReport = false
            }
            if (busy == 0) {
                if (installPolling) {
                    ctl("", "log", "4000", silent = true)
                } else {
                    ctl("", "status", silent = true)
                    // 费用卡：每 30 秒刷一次（余额走官方接口，用量的本地扫描不到 1 秒）
                    if (costTick++ % 6 == 0) ctlCost(true, "json")
                }
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

    /** 费用查询调用：命令与状态轮询不同，走 DshApi.costCmd */
    private fun ctlCost(silent: Boolean, vararg args: String) {
        if (busy > 0 && !silent) { toast(getString(R.string.msg_busy, busy)); return }
        busy++
        try {
            startService(
                TermuxRunner.intent(
                    DshApi.costCmd(this, *args),
                    "cost",
                    null,
                    TermuxRunner.resultPendingIntent(this, ++seq)
                )
            )
        } catch (e: Exception) {
            busy = (busy - 1).coerceAtLeast(0)
            pendingCostReport = false
            log(getString(R.string.msg_call_failed, e.message ?: ""))
        }
    }

    // ---------------- 费用卡 ----------------

    private fun money(v: Double): String =
        if (v < 1.0) String.format(Locale.US, "¥%.4f", v)
        else String.format(Locale.US, "¥%.2f", v)

    private fun fmtLeft(min: Int): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) String.format(Locale.US, "%dh%02dm", h, m)
        else String.format(Locale.US, "%dm", m)
    }

    private fun tokens(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.2fM", n / 1e6)
        n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }

    private fun applyCost(c: Cost) {
        lastCost = c
        costLoaded = true

        if (c.error.isNotEmpty()) {
            b.tvCostBal.text = getString(R.string.cost_dash)
            b.tvCostState.text = getString(R.string.cost_state_err)
            b.tvCostState.setTextColor(ContextCompat.getColor(this, R.color.bad))
            b.tvCostSub.text =
                if (c.error == "NO_TOKEN" || c.error.contains("invalid token", true))
                    getString(R.string.cost_need_login)
                else c.error
            b.tvCostPeak.text = getString(R.string.cost_peak_init)
            b.tvCostBudget.text = ""
            b.tvCostBudgetPct.text = ""
            b.pbCostBudget.progress = 0
            b.sparkCost.setData(FloatArray(0))
        } else {
            // 官方数据（与「DeepSeek 开放平台」网页同源）
            b.tvCostBal.text = money(c.balance)
            b.tvCostState.text = getString(R.string.cost_state_official)
            b.tvCostState.setTextColor(ContextCompat.getColor(this, R.color.ok))
            b.tvCostSub.text = getString(R.string.cost_sub_official, c.at, money(c.totalCost))
        }

        b.tvCostToday.text = money(c.today.cost)
        b.tvCostYesterday.text = money(c.yesterday.cost)
        b.tvCostMonth.text = money(c.month.cost)
        b.tvCostD30.text = money(c.d30.cost)
        b.tvCostHint.text = getString(R.string.cost_hint_usage, c.today.req, tokens(c.today.tokens))

        // 峰谷档位（峰=琥珀，谷=绿）
        val left = fmtLeft(c.peakMinutesLeft)
        b.tvCostPeak.text = (if (c.peakIsPeak)
            getString(R.string.cost_peak_line, left, money(c.peakCost), money(c.offCost))
        else
            getString(R.string.cost_off_line, left, money(c.peakCost), money(c.offCost))) +
            if (c.peakWeekend) getString(R.string.cost_weekend) else ""
        b.tvCostPeak.setTextColor(
            ContextCompat.getColor(this, if (c.peakIsPeak) R.color.warn else R.color.ok))

        // 预算进度（≥80% 接近、≥100% 超支）
        if (c.budgetDaily > 0) {
            b.tvCostBudget.text =
                getString(R.string.cost_budget_fmt, money(c.today.cost), money(c.budgetDaily))
            b.tvCostBudgetPct.text = String.format(Locale.US, "%.0f%%", c.dailyPct)
            val col = when {
                c.dailyPct >= 100 -> R.color.bad
                c.dailyPct >= 80 -> R.color.warn
                else -> R.color.ok
            }
            val c2 = ContextCompat.getColor(this, col)
            b.tvCostBudgetPct.setTextColor(c2)
            b.pbCostBudget.progressTintList = ColorStateList.valueOf(c2)
            b.pbCostBudget.progress = c.dailyPct.coerceIn(0.0, 100.0).toInt()
        } else {
            b.tvCostBudget.text = getString(R.string.cost_budget_none)
            b.tvCostBudgetPct.text = ""
            b.pbCostBudget.progress = 0
        }

        // 近 14 天柱状
        b.sparkCost.setData(FloatArray(c.daily.size) { c.daily[it].toFloat() })
        b.tvCostSpark.text = if (c.daily.isNotEmpty())
            getString(R.string.cost_spark_fmt, c.daily.size, money(c.dailyTotal), money(c.dailyAvg))
        else getString(R.string.cost_spark_init)
    }

    /** 点卡片：拉一份明细（余额 + 今日/昨日/近7天/本月/累计 + 分模型 + 对账）弹窗显示 */
    private fun showCostDetail() = costCommandDialog("report")

    /** 抽屉里点费用项：跑对应子命令，输出照样走明细弹窗 */
    private fun costCommandDialog(vararg args: String) {
        if (busy > 0) { toast(getString(R.string.msg_busy, busy)); return }
        pendingCostReport = true
        pendingCostReportAt = System.currentTimeMillis()
        toast(getString(R.string.cost_loading))
        ctlCost(true, *args)
    }

    private fun costReportDialog(text: String) {
        val tv = TextView(this)
        tv.text = text.trim()
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(ContextCompat.getColor(this, R.color.fg))
        tv.textSize = 11.5f
        val pad = (14 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cost_report_title)
            .setView(sv)
            .setPositiveButton(R.string.cost_report_ok) { _, _ ->
                toast(getString(R.string.cost_report_copied))
                copyText(text.trim())
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh-cost", s))
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
        // 明细报告优先（点卡片触发的）
        if (pendingCostReport) {
            pendingCostReport = false
            costReportDialog(out)
            return
        }
        val st = DshApi.parseStatus(out)
        if (st != null) { applyStatus(st); return }

        // 费用 JSON（含 balance/today 键，和 status 不冲突）
        DshApi.parseCost(out)?.let { applyCost(it); return }

        if (parseConf(out)) return

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

    /** 刷新服务卡头部：副标题（版本 · 地址）+ 状态徽标 */
    private fun hostLabel() {
        val host = lastUrl.substringAfter("://").substringBefore("/")
        val st = lastStatus
        b.tvDshSub.text = getString(
            R.string.dsh_card_sub_fmt, (st?.dshVersion ?: "").ifEmpty { "?" }, host.ifEmpty { "--" })
        val up = st?.service == true
        b.tvDshState.text = getString(if (up) R.string.state_running else R.string.state_stopped)
        b.tvDshState.setTextColor(ContextCompat.getColor(this, if (up) R.color.ok else R.color.dim))
    }

    // ---------------- 状态渲染 ----------------

    private fun applyStatus(s: Status) {
        val prev = lastStatus
        lastStatus = s

        stopSkeleton()
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
        b.ivDshIcon.alpha = if (s.service) 1f else 0.5f

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

        applyOpenList(s)
    }

    // ---------------- 交互 ----------------

    /** 首次启动引导 */
    private fun maybeFirstRun() {
        if (prefs.getBoolean("seen", false)) return
        prefs.edit().putBoolean("seen", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.firstrun_title)
            .setMessage(R.string.firstrun_body)
            .setPositiveButton(R.string.firstrun_go) { _, _ ->
                action(getString(R.string.act_preflight), "preflight")
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    // ---------------- 运行配置 ----------------

    private var cfgLoaded = false

    /** 解析 getconf 的 JSON；命中返回 true */
    private fun parseConf(raw: String): Boolean {
        val i = raw.indexOf('{')
        if (i < 0 || raw.indexOf("\"port\"") < 0) return false
        return try {
            val o = org.json.JSONObject(raw.substring(i, raw.lastIndexOf('}') + 1))
            b.tvCfgPort.text = o.optString("port", "-")
            b.tvCfgWd.text = o.optString("wdInterval", "-") + getString(R.string.cfg_hint)
            b.tvCfgBoot.text = if (o.optString("boot") == "on") getString(R.string.model_ok) else getString(R.string.model_missing)
            cfgLoaded = true
            true
        } catch (e: Exception) { false }
    }

    private fun numConfDialog(key: String, title: String, min: Int, max: Int) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "$min ~ $max"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dim))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                action(title, "setconf", key, v)
                ui.postDelayed({ ctl("", "getconf", silent = true) }, 800)
                if (key == "port") toast(getString(R.string.cfg_title) + " 需重启服务生效")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bootConfDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cfg_boot)
            .setItems(arrayOf(getString(R.string.model_ok), getString(R.string.model_missing))) { _, which ->
                action(getString(R.string.cfg_boot), "setconf", "boot", if (which == 0) "on" else "off")
                ui.postDelayed({ ctl("", "getconf", silent = true) }, 800)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 检查更新 ----------------

    /** silent=true 时不打日志（启动时静默检查） */
    private fun checkUpdate(silent: Boolean) {
        val mine = BuildConfig.GIT_SHA.take(7)
        if (mine == "unknown" || mine.isEmpty()) {
            if (!silent) log(getString(R.string.update_failed) + "（构建未注入版本）")
            return
        }
        if (!silent) log(getString(R.string.update_checking))
        Thread {
            val (sha, err) = fetchLatestSha()
            runOnUiThread {
                when {
                    sha == null ->
                        if (!silent) log(getString(R.string.update_failed) + (err?.let { "（$it）" } ?: ""))
                    sha == mine ->
                        if (!silent) log(getString(R.string.update_uptodate) + "  ($mine)")
                    else -> {
                        log(getString(R.string.update_available) + "  （本机 $mine → 最新 $sha）")
                        // 自动检查只在日志提示；手动点「检查更新」才弹窗
                        if (!silent) askUpdate(sha)
                    }
                }
            }
        }.start()
    }

    /**
     * 取远端最新 commit 短 sha。
     * 优先用 commits/main.atom —— 它是普通页面，**不受匿名 API 60次/小时限流**；
     * 失败再退回 API（会带上具体 HTTP 码便于排查）。
     */
    private fun fetchLatestSha(): Pair<String?, String?> {
        try {
            val c = java.net.URL("https://github.com/jackasan1/deepseek-harness-android/commits/main.atom")
                .openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "DSHConsole")
            val code = c.responseCode
            if (code == 200) {
                val body = c.inputStream.bufferedReader().readText()
                c.disconnect()
                Regex("Grit::Commit/([0-9a-f]{40})")
                    .find(body)?.groupValues?.get(1)?.take(7)?.let { return it to null }
                return null to "feed 解析失败"
            }
            c.disconnect()
        } catch (e: Exception) { /* 落到 API 重试 */ }

        try {
            val c = java.net.URL("https://api.github.com/repos/jackasan1/deepseek-harness-android/commits/main")
                .openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "DSHConsole")
            val code = c.responseCode
            if (code != 200) {
                c.disconnect()
                return null to if (code == 403) "HTTP 403 匿名限流" else "HTTP $code"
            }
            val body = c.inputStream.bufferedReader().readText()
            c.disconnect()
            Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"")
                .find(body)?.groupValues?.get(1)?.take(7)?.let { return it to null }
            return null to "解析失败"
        } catch (e: Exception) {
            return null to (e.message?.take(48) ?: "网络异常")
        }
    }

    /** 服务卡右上角 ⋮：与网盘卡一致的「更多」入口 */
    private fun dshMoreDialog() {
        // 只放本卡片（dsh 服务）相关的事；「关于 App」在抽屉的「应用」组里
        val items = arrayOf(
            getString(R.string.act_settings),
            getString(R.string.menu_copy),
            getString(R.string.logfiles_title)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dsh_card_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSheet()
                    1 -> copyUrl()
                    2 -> logFileDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 应用内更新 ----------------

    /** 固定下载地址：GitHub 会把 /releases/latest 解析到最新 release（CI 每次推送重建） */
    private val APK_URL =
        "https://github.com/jackasan1/deepseek-harness-android/releases/latest/download/app-release.apk"

    private var updating = false

    private fun askUpdate(sha: String) {
        if (updating) { toast(getString(R.string.update_downloading)); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(getString(R.string.update_dialog_msg, BuildConfig.VERSION_NAME, sha))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(sha) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startUpdate(sha: String) {
        // Android 8+ 要先允许「安装未知应用」
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_perm_title)
                .setMessage(R.string.update_perm_msg)
                .setPositiveButton(R.string.update_perm_go) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        toast(getString(R.string.update_perm_failed))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        updating = true
        log(getString(R.string.update_downloading))
        Thread {
            try {
                val dir = File(getExternalFilesDir(null), "update")
                dir.mkdirs()
                val f = File(dir, "dsh-console-$sha.apk")
                val c = java.net.URL(APK_URL).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 90000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "DSHConsole")
                val code = c.responseCode
                if (code != 200) throw Exception("HTTP $code")
                c.inputStream.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) } }
                c.disconnect()
                val mb = f.length() / 1024 / 1024
                runOnUiThread {
                    updating = false
                    log(getString(R.string.update_downloaded, mb))
                    installApk(f)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updating = false
                    log(getString(R.string.update_failed) + "（下载：${e.message?.take(60)}）")
                }
            }
        }.start()
    }

    private fun installApk(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            log(getString(R.string.update_installing))
        } catch (e: Exception) {
            log(getString(R.string.update_failed) + "（安装：${e.message?.take(60)}）")
        }
    }

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
        v.animate().rotationBy(180f).scaleX(0.70f).scaleY(0.70f).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
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
                val anim = ViewAnimationUtils.createCircularReveal(root, cx, cy, 12f, r)
                anim.duration = 980L
                anim.interpolator = android.view.animation.PathInterpolator(0.04f, 0.72f, 0.10f, 1f)
                anim.start()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeDrawer() { b.drawer.closeDrawers() }

    // ---------------- 设置弹出卡片 ----------------

    private fun showSheet() {
        ctl("", "getconf", silent = true)
        val st = lastStatus
        b.tvSetApp.text = BuildConfig.VERSION_NAME
        b.tvSetCtl.text = st?.ctlVersion ?: DshApi.CTL_VERSION
        b.tvSetDsh.text = st?.dshVersion?.ifEmpty { "-" } ?: "-"
        b.tvSetUrl.text = lastUrl.ifEmpty { "-" }
        b.tvSetKey.text = getString(if (st?.model == "OK") R.string.model_ok else R.string.model_missing)
        b.tvSetModel.text = st?.modelName?.ifEmpty { "-" } ?: "-"
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
        b.ivDshIcon.animate().rotationBy(360f).setDuration(650).start()
        pulse()
    }

    private fun pulse() {
        b.ivDshIcon.animate().scaleX(1.25f).scaleY(1.25f).setDuration(140)
            .withEndAction {
                b.ivDshIcon.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
    }

    /** 统一的动作入口：自动展开日志卡，让输出可见 */
    private fun action(label: String, vararg args: String, stdin: String? = null) {
        if (b.logCard.visibility != View.VISIBLE) b.logCard.visibility = View.VISIBLE
        ctl(label, *args, stdin = stdin)
    }

    /** 手风琴分组：点击标题展开/收起，箭头旋转 */
    private fun setupGroup(header: View, items: View, arrow: TextView, expanded: Boolean) {
        items.visibility = if (expanded) View.VISIBLE else View.GONE
        arrow.rotation = if (expanded) 0f else -90f
        header.setOnClickListener {
            val show = items.visibility != View.VISIBLE
            items.visibility = if (show) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (show) 0f else -90f).setDuration(180).start()
        }
    }

    /** 只有当前已在底部时才自动跟随；用户上滑查看历史时不打断 */
    private fun autoScrollIfAtBottom() {
        val sv = b.svLog
        val child = sv.getChildAt(0) ?: return
        val threshold = (resources.displayMetrics.density * 32).toInt()
        val atBottom = sv.scrollY + sv.height >= child.height - threshold
        if (atBottom) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    /** 首次状态到达前的骨架态：占位 + 呼吸闪烁 */
    private fun startSkeleton() {
        b.tvPortValue.text = "—"
        b.tvModelValue.text = "—"
        b.tvProcValue.text = "—"
        b.tvUptimeValue.text = "--:--:--"
        b.tvDshState.text = getString(R.string.state_loading)
        b.tvLog.text = getString(R.string.log_empty)
        ui.postDelayed({
            if (skeleton) {
                skeletonAnim = ObjectAnimator.ofFloat(b.ivDshIcon, "alpha", 1f, 0.35f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }, 1200)
    }

    private fun stopSkeleton() {
        if (!skeleton) return
        skeleton = false
        skeletonAnim?.cancel()
        skeletonAnim = null
        b.ivDshIcon.alpha = 1f
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

    /** 选择默认模型 */
    private fun modelDialog() {
        val models = arrayOf("deepseek-flash", "deepseek-v4-pro")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_pick)
            .setItems(models) { _, which ->
                action(getString(R.string.settings_model), "setmodel", models[which])
                toast(getString(R.string.model_switched))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- API Key 管理 ----------------

    private fun keyDialog() {
        MaterialAlertDialogBuilder(this)
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dim))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.key_set)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val k = et.text.toString().trim()
                if (k.isEmpty()) { toast(getString(R.string.key_hint)); return@setPositiveButton }
                action(getString(R.string.key_set), "setkey", stdin = "$k\n")
                ui.postDelayed({ ctl(getString(R.string.key_title), "checkey") }, 1500)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uninstallDialog() {
        MaterialAlertDialogBuilder(this)
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

    // ---------------- OpenList 网盘 ----------------

    private fun olUrl(s: Status): String =
        s.olUrl.ifEmpty { "http://127.0.0.1:" + s.olPort.ifEmpty { "5244" } }

    /** 主按钮：未安装则引导安装，已安装则启动 */
    private fun olPrimary() {
        val s = lastStatus
        if (s != null && !s.olInstalled) {
            confirm(getString(R.string.ol_install_confirm)) {
                action(getString(R.string.ol_install), "openlist-install")
            }
        } else {
            action(getString(R.string.act_start), "openlist-start")
        }
    }

    /** 打开网盘：未运行先启动，就绪后自动拉起内嵌界面 */
    private fun openOpenList() {
        val s = lastStatus ?: run { toast(getString(R.string.ol_wait_status)); return }
        if (!s.olInstalled) { olPrimary(); return }
        if (!s.olService) {
            pendingOpenOl = true
            toast(getString(R.string.ol_starting))
            action(getString(R.string.act_start), "openlist-start")
            return
        }
        launchConsole(olUrl(s))
    }

    private fun openOpenListExternal() {
        val s = lastStatus ?: return
        if (!s.olService) { openOpenList(); return }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(olUrl(s))))
    }

    private fun copyOlUrl() {
        val s = lastStatus ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("openlist", olUrl(s)))
        toast(getString(R.string.copied))
    }

    private fun olMoreDialog() {
        val s = lastStatus
        val installed = s?.olInstalled == true
        val running = s?.olService == true
        val boot = s?.olBoot == true
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()

        if (!installed) {
            labels.add(getString(R.string.ol_install))
            acts.add { olPrimary() }
        } else {
            labels.add(getString(R.string.ol_restart))
            acts.add { action(getString(R.string.ol_restart), "openlist-restart") }

            labels.add(getString(if (running) R.string.act_stop else R.string.act_start))
            acts.add {
                val lbl = getString(if (running) R.string.act_stop else R.string.act_start)
                action(lbl, if (running) "openlist-stop" else "openlist-start")
            }

            labels.add(getString(R.string.ol_open_browser))
            acts.add { openOpenListExternal() }

            labels.add(getString(R.string.ol_set_passwd))
            acts.add { olPasswdDialog(false) }

            labels.add(getString(R.string.ol_reset_passwd))
            acts.add { olPasswdDialog(true) }

            labels.add(getString(if (boot) R.string.ol_boot_off else R.string.ol_boot_on))
            acts.add {
                action(
                    getString(R.string.ol_boot_setting),
                    "openlist-boot",
                    if (boot) "off" else "on"
                )
            }

            labels.add(getString(R.string.ol_copy_url))
            acts.add { copyOlUrl() }
        }
        labels.add(getString(R.string.ol_view_log))
        acts.add { action(getString(R.string.ol_view_log), "openlist-log") }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ol_title)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** random=true 走随机重置，否则弹出输入框改密码 */
    private fun olPasswdDialog(random: Boolean) {
        if (random) {
            confirm(getString(R.string.ol_reset_confirm)) {
                action(getString(R.string.ol_reset_passwd), "openlist-passwd-random")
            }
            return
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val et = EditText(this).apply {
            hint = getString(R.string.ol_passwd_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dim))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ol_set_passwd)
            .setMessage(R.string.ol_set_passwd_msg)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val p = et.text.toString().trim()
                if (p.length < 6) {
                    toast(getString(R.string.ol_passwd_short))
                    return@setPositiveButton
                }
                action(getString(R.string.ol_set_passwd), "openlist-passwd", stdin = p + "\n")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyOpenList(s: Status) {
        if (!s.olInstalled) {
            b.btnOlStart.text = getString(R.string.ol_install)
            b.tvOlState.text = getString(R.string.ol_not_installed)
            b.tvOlState.setTextColor(ContextCompat.getColor(this, R.color.dim))
            b.tvOlSub.text = getString(R.string.ol_sub_not_installed)
            b.tvOlPortValue.text = getString(R.string.ol_dash)
            b.tvOlUptimeValue.text = getString(R.string.ol_dash)
            b.tvOlBootValue.text =
                getString(if (s.olBoot) R.string.ol_flag_on else R.string.ol_flag_off)
            return
        }
        b.btnOlStart.text = getString(R.string.act_start)

        val up = s.olService
        b.tvOlState.text = getString(if (up) R.string.state_running else R.string.state_stopped)
        b.tvOlState.setTextColor(ContextCompat.getColor(this, if (up) R.color.ok else R.color.dim))
        b.tvOlSub.text = getString(R.string.ol_sub_fmt, s.olVersion.ifEmpty { "?" })

        b.tvOlPortValue.text = s.olPort.ifEmpty { "5244" }
        val httpOk = s.olPortCode == "200" || s.olPortCode == "401"
        b.tvOlPortValue.setTextColor(
            ContextCompat.getColor(this, if (httpOk) R.color.fg else R.color.bad)
        )

        b.tvOlUptimeValue.text = s.olRuntime.ifEmpty { getString(R.string.ol_dash) }
        b.tvOlBootValue.text = getString(if (s.olBoot) R.string.ol_flag_on else R.string.ol_flag_off)
        b.tvOlBootValue.setTextColor(
            ContextCompat.getColor(this, if (s.olBoot) R.color.fg else R.color.dim)
        )

        if (pendingOpenOl && up) {
            pendingOpenOl = false
            launchConsole(olUrl(s))
        }
    }

    /** 选择要查看的 Termux 侧日志文件 */
    private fun logFileDialog() {
        val files = arrayOf("dsh-web.log", "dsh-watchdog.log", "install.log", "dsh-boot.log")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logfiles_title)
            .setItems(files) { _, which ->
                action(files[which], "tail", files[which], "4000")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun about() {
        MaterialAlertDialogBuilder(this)
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
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dim))
        }
        box.addView(et)

        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this).setMessage(msg)
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
            if (logEmpty) { logBuf.clear(); logEmpty = false }
            logBuf.append(next)
            if (logBuf.length > 80000) {
                val cut = logBuf.indexOf("\n", 40000)
                if (cut > 0) logBuf.delete(0, cut + 1)
            }
            b.tvLog.text = logBuf
            autoScrollIfAtBottom()
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
