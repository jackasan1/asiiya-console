package com.dsh.console

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 通过 Termux 的 RUN_COMMAND Intent 在后台执行 shell 命令。
 * 需要：Termux 已安装，且 ~/.termux/termux.properties 里 allow-external-apps=true
 * 与应用声明 com.termux.permission.RUN_COMMAND
 */
object TermuxRunner {

    const val PKG = "com.termux"
    const val ACTION_RUN = "$PKG.RUN_COMMAND"
    const val ACTION_RESULT = "com.dsh.console.RUN_RESULT"
    // TermuxConstants.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE == "result"
    const val EXTRA_RESULT_BUNDLE = "result"

    // Termux 的固定路径
    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /**
     * 构造一条 RUN_COMMAND Intent。
     * @param background true=后台无终端窗口执行（推荐）
     */
    fun buildIntent(
        cmd: String,
        label: String,
        background: Boolean,
        result: PendingIntent?
    ): Intent = Intent().apply {
        setClassName(PKG, "$PKG.app.RunCommandService")
        action = ACTION_RUN
        putExtra("$PKG.RUN_COMMAND_PATH", BASH)
        putExtra("$PKG.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
        putExtra("$PKG.RUN_COMMAND_WORKDIR", HOME)
        putExtra("$PKG.RUN_COMMAND_BACKGROUND", background)
        putExtra("$PKG.RUN_COMMAND_SESSION_ACTION", "0")
        putExtra("$PKG.RUN_COMMAND_COMMAND_LABEL", label)
        if (result != null) putExtra("$PKG.RUN_COMMAND_PENDING_INTENT", result)
    }
}
