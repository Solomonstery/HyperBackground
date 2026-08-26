package com.ciallo.hyperbackground;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 强制设置界面的深浅色。
 *
 * <p>普通设置页面走资源限定符（night 目录），只改 attachBaseContext 的 Context 就够；
 * 但应用详情页（InstalledAppDetailsTop）的 header 卡片由 miuix 组件绘制，miuix 通过
 * {@code ViewUtils.isNightMode(context) -> context.getResources().getConfiguration().isNightModeActive()}
 * 判定深浅色，读取的是 Activity 实际使用的那份 Resources 的 Configuration，
 * 而不是 attachBaseContext 传入的临时 Context。因此需要额外把 Activity 自身的
 * Resources / OverrideConfiguration 也改掉，并在进程级用 UiModeManager 兜底。
 */
final class SettingsThemeOverride {
    private SettingsThemeOverride() {}

    static void install() {
        // 方案 A-1：替换 attachBaseContext 的 Context（覆盖走资源限定符的普通页面）。
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "attachBaseContext",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context base = (Context) param.args[0];
                            if (base == null) return;
                            int mode = resolveMode(base);
                            if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return;
                            Configuration config = forcedConfiguration(base.getResources().getConfiguration(), mode);
                            param.args[0] = base.createConfigurationContext(config);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Settings theme attachBaseContext hook failed: " + error);
            XposedBridge.log(error);
        }

        // 方案 A-2：在 Activity.onCreate 之前，把 Activity 实际使用的那份 Resources 的
        // Configuration 一并改掉，让 miuix 的 ViewUtils.isNightMode 也跟随（覆盖应用详情页 header 卡片）。
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            int mode = resolveMode(activity);
                            if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return;
                            forceActivityConfiguration(activity, mode);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Settings theme onCreate hook failed: " + error);
            XposedBridge.log(error);
        }

        // 方案 B：进程级兜底。UiModeManager.setApplicationNightMode 会统一改写整个进程
        // Resources 的 isNightModeActive，覆盖那些绕过 Activity Context 的组件。
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            int mode = resolveMode(activity);
                            if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return;
                            applyApplicationNightMode(activity, mode);
                            forceActivityConfiguration(activity, mode);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Settings theme onResume hook failed: " + error);
            XposedBridge.log(error);
        }
    }

    private static int resolveMode(Context context) {
        try {
            return BackgroundContract.query(context, BackgroundContract.HOME).settingsThemeMode;
        } catch (Throwable ignored) {
            return BackgroundContract.SETTINGS_THEME_FOLLOW;
        }
    }

    private static Configuration forcedConfiguration(Configuration source, int mode) {
        Configuration config = new Configuration(source);
        int night = mode == BackgroundContract.SETTINGS_THEME_DARK
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return config;
    }

    /**
     * 直接改写 Activity 使用的 Resources 的 Configuration，使
     * {@code activity.getResources().getConfiguration().isNightModeActive()} 返回目标值。
     */
    private static void forceActivityConfiguration(Activity activity, int mode) {
        try {
            Resources resources = activity.getResources();
            if (resources == null) return;
            Configuration current = resources.getConfiguration();
            boolean wantNight = mode == BackgroundContract.SETTINGS_THEME_DARK;
            if (current.isNightModeActive() == wantNight) return;
            Configuration forced = forcedConfiguration(current, mode);
            resources.updateConfiguration(forced, resources.getDisplayMetrics());
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] forceActivityConfiguration failed: " + error);
        }
    }

    private static void applyApplicationNightMode(Context context, int mode) {
        try {
            UiModeManager manager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (manager == null) return;
            int target = mode == BackgroundContract.SETTINGS_THEME_DARK
                    ? UiModeManager.MODE_NIGHT_YES
                    : UiModeManager.MODE_NIGHT_NO;
            manager.setApplicationNightMode(target);
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] setApplicationNightMode failed: " + error);
        }
    }
}
