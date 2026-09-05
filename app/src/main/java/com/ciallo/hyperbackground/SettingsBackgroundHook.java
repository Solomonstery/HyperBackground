package com.ciallo.hyperbackground;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class SettingsBackgroundHook {
    private static final Map<Activity, Runnable> PENDING_GLOBAL = Collections.synchronizedMap(new WeakHashMap<>());
    static void install(String packageName, ClassLoader classLoader) {
        boolean settings = BackgroundContract.PACKAGE_SETTINGS.equals(packageName);
        boolean contacts = BackgroundContract.PACKAGE_CONTACTS.equals(packageName);

        hookGlobalActivities();
        hookInstrumentationLifecycle();
        hookKnownPackageLifecycle(packageName, classLoader);

        // 主题（深浅色）与文字色强制对所有支持的作用域进程生效，不再局限于设置进程，
        // 这样应用详情页等由其它进程提供的页面也能被强制控制。
        SettingsThemeOverride.install(packageName);
        TextColorOverride.install();

        if (settings) {
            SettingsSearchMaskOverride.install(classLoader);
            SettingsTopBarBlurHook.install(classLoader);
            SettingsTopBarClearHook.install(classLoader);
            hookHomeActivity(classLoader);
            hookHomeFragment(classLoader);
            hookDeviceFragment(classLoader);
        }

        if (contacts) {
            hookContactsActivity(classLoader);
            hookDialpadLayout(classLoader);
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
                            if (param.thisObject instanceof Activity) applyGlobalNow((Activity) param.thisObject);
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

    private static void hookKnownPackageLifecycle(String packageName, ClassLoader classLoader) {
        String className = null;
        if (BackgroundContract.PACKAGE_PHONE.equals(packageName)) {
            className = "com.android.phone.settings.BaseActivity";
        } else if (BackgroundContract.PACKAGE_ACCOUNT.equals(packageName)) {
            className = "com.xiaomi.account.ui.BaseActivity";
        } else if (BackgroundContract.PACKAGE_THEME_MANAGER.equals(packageName)) {
            className = "com.android.thememanager.basemodule.base.AbstractBaseActivity";
        }
        if (className == null) return;
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
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

    // 内容层刚 inflate 完成（onContentChanged）时同步挂背景，赶在第一帧绘制之前，
    // 避免先绘制原生底色、再于下一帧 post 补背景造成的黑/白闪。挂载失败时退回异步兜底。
    private static void applyGlobalNow(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            BackgroundApplier.applyGlobal(activity);
        } catch (Throwable ignored) {
            scheduleGlobal(activity);
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

    private static void hookContactsActivity(ClassLoader classLoader) {
        final String className = "com.android.contacts.activities.PeopleActivity";
        try {
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyContacts((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyContacts((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onContentChanged",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyContacts((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onStop",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.stopContacts((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onDestroy",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) BackgroundApplier.destroyContacts((Activity) param.thisObject);
                        }
                    });
            XposedBridge.log("[HyperBackground] Installed contacts PeopleActivity background hooks");
        } catch (Throwable error) { logHookError("PeopleActivity", error); }
    }

    // 拨号盘键盘容器 DialpadLayout 在 onFinishInflate 时（其子 view 已 findViewById 完毕、绘制第一帧之前）
    // 同步处理拨号盘背景（默认设 alpha / 自定义叠加独立背景图），根除“先露原生底色再变透”的先灰后透闪烁。
    private static void hookDialpadLayout(ClassLoader classLoader) {
        final String className = "com.android.contacts.dialer.view.DialpadLayout";
        try {
            XposedHelpers.findAndHookMethod(
                    className, classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                BackgroundApplier.applyDialpadOnInflate((View) param.thisObject);
                            }
                        }
                    });
            XposedBridge.log("[HyperBackground] Installed DialpadLayout background hook");
        } catch (Throwable error) { logHookError("DialpadLayout", error); }
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
                                SettingsTopBarBlurHook.markHomeFragment(param.thisObject);
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
