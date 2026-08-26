package com.ciallo.hyperbackground;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SettingsBackgroundHook implements IXposedHookLoadPackage {
    private static final Map<Activity, Runnable> PENDING_GLOBAL = Collections.synchronizedMap(new WeakHashMap<>());
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!BackgroundContract.isSupportedPackage(lpparam.packageName)) return;
        boolean settings = BackgroundContract.PACKAGE_SETTINGS.equals(lpparam.packageName);

        XposedBridge.log("[HyperBackground] injected package=" + lpparam.packageName
                + " process=" + lpparam.processName + " version=" + BuildConfig.VERSION_NAME);
        hookGlobalActivities();
        hookInstrumentationLifecycle();
        hookKnownPackageLifecycle(lpparam);

        // 主题（深浅色）与文字色强制对所有支持的作用域进程生效，不再局限于设置进程，
        // 这样应用详情页等由其它进程提供的页面也能被强制控制。
        SettingsThemeOverride.install();
        TextColorOverride.install();

        if (settings) {
            SettingsSearchMaskOverride.install(lpparam.classLoader);
            hookHomeActivity(lpparam.classLoader);
            hookHomeFragment(lpparam.classLoader);
            hookDeviceFragment(lpparam.classLoader);
        }
    }

    private static void hookInstrumentationLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callActivityOnCreate",
                    Activity.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Activity) scheduleGlobal((Activity) param.args[0]);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callActivityOnResume",
                    Activity.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Activity) scheduleGlobal((Activity) param.args[0]);
                        }
                    });
        } catch (Throwable error) {
            logHookError("Instrumentation lifecycle", error);
        }
    }

    private static void hookGlobalActivities() {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) scheduleGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onPostCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) scheduleGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) scheduleGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onContentChanged",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) scheduleGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onStop",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.stopGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.destroyGlobal((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable error) {
            logHookError("Global Activities", error);
        }
    }

    private static void hookKnownPackageLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        String className = null;
        if (BackgroundContract.PACKAGE_PHONE.equals(lpparam.packageName)) {
            className = "com.android.phone.settings.BaseActivity";
        } else if (BackgroundContract.PACKAGE_ACCOUNT.equals(lpparam.packageName)) {
            className = "com.xiaomi.account.ui.BaseActivity";
        } else if (BackgroundContract.PACKAGE_THEME_MANAGER.equals(lpparam.packageName)) {
            className = "com.android.thememanager.basemodule.base.AbstractBaseActivity";
        }
        if (className == null) return;
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    lpparam.classLoader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) scheduleGlobal((Activity) param.thisObject);
                        }
                    });
            XposedBridge.log("[HyperBackground] precise lifecycle hook=" + className);
        } catch (Throwable error) {
            // The launcher settings class can be supplied by a shared native runtime and
            // may not declare onCreate itself. Framework lifecycle hooks remain active.
            logHookError("precise lifecycle " + className, error);
        }
    }

    private static void scheduleGlobal(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final View decor;
        try {
            decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            synchronized (PENDING_GLOBAL) {
                if (PENDING_GLOBAL.containsKey(activity)) return;
                Runnable task = () -> {
                    synchronized (PENDING_GLOBAL) { PENDING_GLOBAL.remove(activity); }
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        BackgroundApplier.applyGlobal(activity);
                    }
                };
                PENDING_GLOBAL.put(activity, task);
                decor.post(task);
            }
        } catch (Throwable ignored) {
            BackgroundApplier.applyGlobal(activity);
        }
    }

    private static void hookHomeActivity(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.MiuiSettings",
                    classLoader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyHome((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.MiuiSettings",
                    classLoader,
                    "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyHome((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.MiuiSettings",
                    classLoader,
                    "onStop",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.stopHome((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable error) { logHookError("MiuiSettings", error); }
    }

    private static void hookHomeFragment(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.SettingsFragment",
                    classLoader,
                    "onViewCreated",
                    android.view.View.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object activity = XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (activity instanceof Activity
                                    && "com.android.settings.MiuiSettings".equals(activity.getClass().getName())) {
                                BackgroundApplier.applyHome((Activity) activity);
                            }
                        }
                    });
        } catch (Throwable error) { logHookError("SettingsFragment", error); }
    }

    private static void hookDeviceFragment(ClassLoader classLoader) {
        final String className = "com.android.settings.device.MiuiMyDeviceSettings";
        try {
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "startRuntimeShader", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (BackgroundApplier.shouldSuppressDeviceShader(param.thisObject)) param.setResult(null);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onViewCreated", android.view.View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object a = XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (a instanceof Activity) BackgroundApplier.enterDevice((Activity) a);
                            BackgroundApplier.applyDevice(param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    className, classLoader, "setDeviceShaderBackground",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            BackgroundApplier.applyDevice(param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object a = XposedHelpers.callMethod(param.thisObject, "getActivity");
                            if (a instanceof Activity) BackgroundApplier.enterDevice((Activity) a);
                            BackgroundApplier.applyDevice(param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onStop",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) { BackgroundApplier.stopDevice(param.thisObject); }
                    });

            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onDestroy",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object a = XposedHelpers.callMethod(param.thisObject, "getActivity");
                            BackgroundApplier.destroyDevice(param.thisObject);
                            if (a instanceof Activity) BackgroundApplier.leaveDevice((Activity) a);
                        }
                    });
        } catch (Throwable error) { logHookError("MiuiMyDeviceSettings", error); }
    }

    private static void logHookError(String target, Throwable error) {
        XposedBridge.log("[HyperBackground] Could not hook " + target + ": " + error);
        XposedBridge.log(error);
    }
}
