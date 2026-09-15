package com.dsh.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 安装 dsh 期间的前台服务：常驻通知显示进度，切到后台也能看到。
 * 每 10 秒交替轮询 `dsh-ctl.sh status`（判断是否装完）和 `log`（取最新进度）。
 */
class InstallService : Service() {

    companion object {
        private const val CH_ID = "dsh_install"
        private const val NOTI_ONGOING = 1001
        private const val NOTI_DONE = 1002
        private const val ACTION_TICK = "com.dsh.console.INSTALL_TICK"
        private const val ACTION_START = "com.dsh.console.INSTALL_WATCH"
        private const val EXTRA_KEY = "api_key"
        private const val POLL_MS = 10_000L
        private const val MAX_TICKS = 300          // 约 50 分钟上限

        fun start(ctx: Context, apiKey: String) {
            val i = Intent(ctx, InstallService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KEY, apiKey)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var seq = 900
    private var ticks = 0
    private var busy = false
    private var wasRunning = false
    private var lastLine = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onResult(i)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val f = IntentFilter(ACTION_TICK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(noti("正在准备安装…", true))

        if (intent?.action == ACTION_START) {
            val key = intent.getStringExtra(EXTRA_KEY) ?: ""
            send(DshApi.installCmd(this), "install", if (key.isEmpty()) "" else "$key\n")
        }

        ui.removeCallbacks(tick)
        ui.postDelayed(tick, POLL_MS)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks > MAX_TICKS) { finish("安装超时，请查看日志"); return }
            if (!busy) {
                if (ticks % 3 == 0) send(DshApi.cmd(this@InstallService, "status"), "status")
                else send(DshApi.cmd(this@InstallService, "log", "1200"), "log")
            }
            ui.postDelayed(this, POLL_MS)
        }
    }

    private fun send(cmd: String, label: String, stdin: String? = null) {
        busy = true
        try {
            startService(
                TermuxRunner.intent(
                    cmd, label, stdin,
                    TermuxRunner.resultPendingIntent(this, ++seq, ACTION_TICK)
                )
            )
        } catch (e: Exception) {
            busy = false
        }
    }

    private fun onResult(intent: Intent?) {
        busy = false
        val b = intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY)
            ?: intent?.getBundleExtra(TermuxRunner.BUNDLE_KEY_LEGACY)
        val out = b?.getString("stdout")?.trim().orEmpty()

        val st = DshApi.parseStatus(out)
        if (st != null) {
            if (st.installing) {
                wasRunning = true
                note("安装中…（HTTP ${st.portCode}）")
            } else if (wasRunning) {
                finish("安装已完成")
            } else {
                note("正在启动安装进程…")
            }
            return
        }

        val line = out.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isNotEmpty()) {
            lastLine = line
            note(line.take(140))
        }
    }

    // ---------------- 通知 ----------------

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ONGOING, n)
        }
    }

    private fun note(text: String) {
        goForeground(noti(text, true))
    }

    private fun noti(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (lastLine.isEmpty()) text else text + "\n" + lastLine
            ))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun finish(text: String) {
        ui.removeCallbacks(tick)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTI_DONE,
            NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    if (lastLine.isEmpty()) text else text + "\n" + lastLine
                ))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        )
        stopForeground(true)
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "DSH 安装进度", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
