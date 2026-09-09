package com.ciallo.hyperbackground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机完成后自动换一张随机背景。
 *
 * 仅在总开关开启、且触发模式为「仅开机」或「手动+开机」时工作。模块进程被系统拉起后
 * 在后台线程逐槽位下载；下载完 [ConfigManager.importRandomBackground] 会同步到 libxposed
 * remote（若 service 已绑定），否则由 [HyperBackgroundApp.onServiceBind] 的全量同步兜底。
 * 手动点「换一张」按钮不经过本接收器。
 */
class BootRandomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = ConfigManager.get(context)
        if (!config.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false)) return
        val mode = config.getInt(BackgroundContract.UI_RANDOM_BG_MODE, BackgroundContract.RANDOM_BG_MODE_MANUAL)
        if (mode != BackgroundContract.RANDOM_BG_MODE_BOOT && mode != BackgroundContract.RANDOM_BG_MODE_BOTH) return
        val slots = config.getStringSet(BackgroundContract.UI_RANDOM_BG_SLOTS, mutableSetOf()) ?: return
        if (slots.isEmpty()) return

        val pending = goAsync()
        Thread({
            try {
                slots.forEach { slot ->
                    runCatching { RandomBackgroundFetcher.fetchForSlotBlocking(context, slot) }
                }
            } finally {
                pending.finish()
            }
        }, "RandomBg-Boot").start()
    }
}
