package com.ciallo.hyperbackground;

import android.app.Activity;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SettingsBackgroundHook implements IXposedHookLoadPackage {
    private static final String SETTINGS = "com.android.settings";
    private static final String MILINK = "com.milink.service";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean settings = SETTINGS.equals(lpparam.packageName);
        boolean milink = MILINK.equals(lpparam.packageName);
        if (!settings && !milink) return;

        hookGlobalActivities();

        if (settings) {
            SettingsThemeOverride.install();
            ThemeChannelOverride.install();
            TextColorOverride.install();
            hookHomeActivity(lpparam.classLoader);
            hookHomeFragment(lpparam.classLoader);
            hookDeviceFragment(lpparam.classLoader);
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
                            if (param.thisObject instanceof Activity) BackgroundApplier.applyGlobal((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                final Activity activity = (Activity) param.thisObject;
                                try {
                                    android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                                    if (decor != null) decor.post(() -> BackgroundApplier.applyGlobal(activity));
                                    else BackgroundApplier.applyGlobal(activity);
                                } catch (Throwable ignored) {
                                    BackgroundApplier.applyGlobal(activity);
                                }
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onContentChanged",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                final Activity activity = (Activity) param.thisObject;
                                try {
                                    android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                                    if (decor != null) decor.post(() -> BackgroundApplier.applyGlobal(activity));
                                    else BackgroundApplier.applyGlobal(activity);
                                } catch (Throwable ignored) {
                                    BackgroundApplier.applyGlobal(activity);
                                }
                            }
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
