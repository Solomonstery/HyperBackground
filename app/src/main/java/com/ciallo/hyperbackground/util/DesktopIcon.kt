package com.ciallo.hyperbackground.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ciallo.hyperbackground.BackgroundContract

/**
 * 桌面图标开关。
 *
 * 桌面图标不挂在 [com.ciallo.hyperbackground.ui.MainActivity] 上，而是由清单里的
 * `activity-alias`（[BackgroundContract.ACTIVITY_ALIAS_SUFFIX]）承载 LAUNCHER intent-filter：
 * 禁用该别名组件即从启动器消失，MainActivity 本身（持有 MODULE_SETTINGS 入口）完全不受影响，
 * 因此隐藏图标后仍可从 LSPosed 管理器打开模块界面。
 */
object DesktopIcon {
    private fun alias(context: Context): ComponentName =
        ComponentName(context.packageName, context.packageName + BackgroundContract.ACTIVITY_ALIAS_SUFFIX)

    /** 设置桌面图标可见性；返回是否写入成功（组件未声明或权限异常时为 false）。 */
    fun setVisible(context: Context, visible: Boolean): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }.isSuccess

    /** 当前桌面图标是否可见：只有显式禁用才会返回 false，其余状态（含默认）都视为可见。 */
    fun isVisible(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(alias(context)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(true)
}
