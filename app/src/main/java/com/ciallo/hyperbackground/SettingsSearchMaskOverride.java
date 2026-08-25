package com.ciallo.hyperbackground;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsSearchMaskOverride {
    private static final String SETTINGS_FRAGMENT = "com.android.settings.SettingsFragment";
    private SettingsSearchMaskOverride() {}

    static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    SETTINGS_FRAGMENT,
                    classLoader,
                    "onInflateView",
                    android.view.LayoutInflater.class,
                    android.view.ViewGroup.class,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object view = param.getResult();
                            if (view instanceof View) {
                                clearLoadingMask((View) view);
                            }
                            clearWindowMask(param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    SETTINGS_FRAGMENT,
                    classLoader,
                    "setSearchMaskVisiable",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            clearWindowMask(param.thisObject);
                        }
                    });
            XposedBridge.log("[HyperBackground] Settings search masks made transparent");
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not hook Settings search masks: " + error);
            XposedBridge.log(error);
        }
    }

    private static void clearLoadingMask(View root) {
        int id = root.getResources().getIdentifier("search_loading", "id", root.getContext().getPackageName());
        View loading = id == 0 ? null : root.findViewById(id);
        if (loading != null) loading.setBackgroundColor(Color.TRANSPARENT);
    }

    private static void clearWindowMask(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (!(activity instanceof Activity)) return;
            Activity hostActivity = (Activity) activity;
            Window window = hostActivity.getWindow();
            if (window == null) return;
            int id = hostActivity.getResources().getIdentifier("search_mask", "id", hostActivity.getPackageName());
            View mask = id == 0 ? null : window.findViewById(id);
            if (mask != null) mask.setBackgroundColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
    }
}
