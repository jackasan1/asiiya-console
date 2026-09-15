package com.dsh.console

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 通过 Termux 的 RUN_COMMAND Intent 在后台执行 shell。
 * 常量取自 TermuxConstants (termux-app v0.118.x)。
 */
object TermuxRunner {

    const val PKG = "com.termux"
    const val ACTION_RUN = "$PKG.RUN_COMMAND"
    const val ACTION_RESULT = "com.dsh.console.RUN_RESULT"

    // RUN_COMMAND_SERVICE.Extra 常量
    private const val EXTRA_PATH = "$PKG.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "$PKG.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "$PKG.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BG = "$PKG.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_STDIN = "$PKG.RUN_COMMAND_STDIN"
    private const val EXTRA_LABEL = "$PKG.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_PENDING = "$PKG.RUN_COMMAND_PENDING_INTENT"

    /** TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE == "result" */
    const val BUNDLE_KEY = "result"
    /** 兼容旧键名 */
    const val BUNDLE_KEY_LEGACY = "$PKG.RUN_COMMAND_RESULT_BUNDLE"

    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /**
     * @param stdin 通过 RUN_COMMAND_STDIN 传输（不进命令行参数，避免密钥出现在 intent 里）
     */
    fun intent(
        cmd: String,
        label: String,
        stdin: String? = null,
        result: PendingIntent? = null
    ): Intent = Intent().apply {
        setClassName(PKG, "$PKG.app.RunCommandService")
        action = ACTION_RUN
        putExtra(EXTRA_PATH, BASH)
        putExtra(EXTRA_ARGS, arrayOf("-lc", cmd))
        putExtra(EXTRA_WORKDIR, HOME)
        putExtra(EXTRA_BG, true)
        putExtra(EXTRA_LABEL, label)
        if (stdin != null) putExtra(EXTRA_STDIN, stdin)
        if (result != null) putExtra(EXTRA_PENDING, result)
    }

    fun resultPendingIntent(ctx: Context, reqCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, reqCode,
            Intent(ACTION_RESULT).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
}
