package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.TypedValue;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Experimental "theme channel".
 *
 * HyperOS secondary Settings pages often paint their page surface from theme attributes
 * after the normal Activity/Fragment background hook has already run.  Instead of chasing
 * every private MIUIX container class, this test intercepts the two framework background
 * theme attributes while a secondary Settings Activity is in the foreground.
 *
 * This intentionally does NOT rewrite MIUIX card/surface colors, so preference cards,
 * switches and dialogs should keep their native appearance.  The feature is opt-in.
 */
final class ThemeChannelOverride {
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile boolean enabled;
    private static volatile boolean globalExists;

    private ThemeChannelOverride() {}

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!"com.android.settings".equals(activity.getPackageName())) return;
                    foreground = new WeakReference<>(activity);
                    refresh(activity);
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity current = foreground.get();
                    if (current == param.thisObject) foreground = new WeakReference<>(null);
                }
            });

            XposedHelpers.findAndHookMethod(
                    Resources.Theme.class,
                    "resolveAttribute",
                    int.class,
                    TypedValue.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.getResult()) || !enabled || !globalExists) return;
                            Activity activity = foreground.get();
                            if (!isSecondarySettings(activity)) return;

                            int attr = (Integer) param.args[0];
                            if (attr != android.R.attr.windowBackground && attr != android.R.attr.colorBackground) return;

                            TypedValue out = (TypedValue) param.args[1];
                            if (out == null) return;
                            out.type = TypedValue.TYPE_INT_COLOR_ARGB8;
                            out.data = Color.TRANSPARENT;
                            out.resourceId = 0;
                            out.assetCookie = 0;
                            out.string = null;
                            param.setResult(true);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Theme channel hook failed: " + error);
        }
    }

    private static void refresh(Activity activity) {
        try {
            BackgroundContract.Source source = BackgroundContract.query(activity, BackgroundContract.GLOBAL);
            enabled = source.themeChannelEnabled;
            globalExists = source.exists;
        } catch (Throwable ignored) {
            enabled = false;
            globalExists = false;
        }
    }

    private static boolean isSecondarySettings(Activity activity) {
        if (activity == null || !"com.android.settings".equals(activity.getPackageName())) return false;
        String name = activity.getClass().getName();
        if ("com.android.settings.MiuiSettings".equals(name)) return false;
        String lower = name.toLowerCase();
        return !lower.contains("permission")
                && !lower.contains("authorization")
                && !lower.contains("confirmcredential")
                && !lower.contains("dialogactivity")
                && !lower.contains("transparentactivity");
    }
}
