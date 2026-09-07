package com.ciallo.hyperbackground

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle

/**
 * 强制作用域进程界面的深浅色。
 *
 * 对所有被注入的作用域进程生效（不仅是设置进程），这样应用详情页等由其它进程
 * 提供的页面也能被强制控制。
 *
 * 普通设置页面走资源限定符（night 目录），只改 attachBaseContext 的 Context 就够；
 * 但应用详情页（InstalledAppDetailsTop）的 header 卡片由 miuix 组件绘制，miuix 通过
 * `ViewUtils.isNightMode(context) -> context.getResources().getConfiguration().isNightModeActive()`
 * 判定深浅色，读取的是 Activity 实际使用的那份 Resources 的 Configuration，
 * 而不是 attachBaseContext 传入的临时 Context。因此需要额外把 Activity 自身的
 * Resources / OverrideConfiguration 也改掉，并在进程级用 UiModeManager 兜底。
 */
internal object SettingsThemeOverride {
    // 当前被注入进程的包名。通讯录与拨号（com.android.contacts）读联系人专属深浅色键，
    // 与全局强制深浅色独立并存；其余进程读全局键。static 字段在每个进程各持一份。
    @Volatile
    private var currentPackage: String? = null

    @JvmStatic
    fun install(packageName: String?) {
        currentPackage = packageName
        // 方案 A-1：替换 attachBaseContext 的 Context（覆盖走资源限定符的普通页面）。
        try {
            hookMethod(Activity::class.java, "attachBaseContext", Context::class.java) {
                val base = args[0] as? Context ?: return@hookMethod
                val mode = resolveMode(base)
                if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return@hookMethod
                val config = forcedConfiguration(base.resources.configuration, mode)
                args[0] = base.createConfigurationContext(config)
            }
        } catch (error: Throwable) {
            log("[HyperBackground] Settings theme attachBaseContext hook failed: $error")
            log(error)
        }

        // 方案 A-2：在 Activity.onCreate 之前，把 Activity 实际使用的那份 Resources 的
        // Configuration 一并改掉，让 miuix 的 ViewUtils.isNightMode 也跟随（覆盖应用详情页 header 卡片）。
        try {
            hookMethod(Activity::class.java, "onCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                val mode = resolveMode(activity)
                if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return@hookMethod
                forceActivityConfiguration(activity, mode)
            }
        } catch (error: Throwable) {
            log("[HyperBackground] Settings theme onCreate hook failed: $error")
            log(error)
        }

        // 方案 B：进程级兜底。UiModeManager.setApplicationNightMode 会统一改写整个进程
        // Resources 的 isNightModeActive，覆盖那些绕过 Activity Context 的组件。
        try {
            hookMethod(Activity::class.java, "onResume") {
                val activity = thisObject as? Activity ?: return@hookMethod
                val mode = resolveMode(activity)
                // 注意：FOLLOW 时不能直接 return。必须调用 applyApplicationNightMode
                // 让它把 per-app 夜间模式还原为 AUTO，主动撤销此前写入的强制覆盖，
                // 否则设置等应用的系统深浅色开关会被残留覆盖锁死。
                applyApplicationNightMode(activity, mode)
                if (mode != BackgroundContract.SETTINGS_THEME_FOLLOW) {
                    forceActivityConfiguration(activity, mode)
                }
            }
        } catch (error: Throwable) {
            log("[HyperBackground] Settings theme onResume hook failed: $error")
            log(error)
        }
    }

    private fun resolveMode(context: Context): Int = try {
        // 联系人进程：读联系人专属深浅色键（与全局独立并存）。setApplicationNightMode 是进程级单值，
        // 联系人进程只受这一个键控制，不与全局键互相覆盖。其余进程沿用全局强制深浅色。
        if (BackgroundContract.PACKAGE_CONTACTS == currentPackage) {
            HookRuntime.preferences().getInt(
                BackgroundContract.CONTACTS_THEME_MODE,
                BackgroundContract.SETTINGS_THEME_FOLLOW,
            )
        } else {
            BackgroundContract.query(context, BackgroundContract.HOME).settingsThemeMode
        }
    } catch (_: Throwable) {
        BackgroundContract.SETTINGS_THEME_FOLLOW
    }

    private fun forcedConfiguration(source: Configuration, mode: Int): Configuration {
        val config = Configuration(source)
        val night =
            if (mode == BackgroundContract.SETTINGS_THEME_DARK) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return config
    }

    /**
     * 直接改写 Activity 使用的 Resources 的 Configuration，使
     * `activity.getResources().getConfiguration().isNightModeActive()` 返回目标值。
     */
    private fun forceActivityConfiguration(activity: Activity, mode: Int) {
        try {
            val resources: Resources = activity.resources
            val current = resources.configuration
            val wantNight = mode == BackgroundContract.SETTINGS_THEME_DARK
            if (current.isNightModeActive == wantNight) return
            val forced = forcedConfiguration(current, mode)
            resources.updateConfiguration(forced, resources.displayMetrics)
        } catch (error: Throwable) {
            log("[HyperBackground] forceActivityConfiguration failed: $error")
        }
    }

    /**
     * 用 [UiModeManager.setApplicationNightMode] 设置本进程（作用域应用自身）的
     * per-app 夜间模式。
     *
     * 关键：FOLLOW 时必须显式设回 [UiModeManager.MODE_NIGHT_AUTO]，
     * 主动撤销此前写入的强制覆盖——否则之前钉入的 per-app 覆盖会一直残留，
     * 导致该应用（尤其是设置）的系统深浅色开关被锁死、无法切换。
     */
    private fun applyApplicationNightMode(context: Context, mode: Int) {
        try {
            val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return
            val target = when (mode) {
                BackgroundContract.SETTINGS_THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                BackgroundContract.SETTINGS_THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_AUTO // FOLLOW：还原为跟随系统，撤销 per-app 覆盖
            }
            manager.setApplicationNightMode(target)
        } catch (error: Throwable) {
            log("[HyperBackground] setApplicationNightMode failed: $error")
        }
    }
}
