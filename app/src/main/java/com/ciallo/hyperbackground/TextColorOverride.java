package com.ciallo.hyperbackground;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps the user-selected Settings text mode stable when MIUIX/Preference rebinds views. */
final class TextColorOverride {
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();
    private TextColorOverride() {}

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(INTERNAL.get()) || !(param.thisObject instanceof TextView)) return;
                    int mode = readMode((TextView) param.thisObject);
                    if (mode != BackgroundContract.FONT_FOLLOW) param.args[0] = forcedColor(mode);
                }
            });
            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", ColorStateList.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(INTERNAL.get()) || !(param.thisObject instanceof TextView)) return;
                    int mode = readMode((TextView) param.thisObject);
                    if (mode != BackgroundContract.FONT_FOLLOW) param.args[0] = ColorStateList.valueOf(forcedColor(mode));
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not install persistent text-color hook: " + error);
            XposedBridge.log(error);
        }
    }

    static void apply(TextView view, int mode) {
        if (mode == BackgroundContract.FONT_FOLLOW) return;
        try {
            INTERNAL.set(Boolean.TRUE);
            view.setTextColor(forcedColor(mode));
        } finally {
            INTERNAL.remove();
        }
    }

    private static int readMode(TextView view) {
        try {
            Context context = view.getContext();
            if (context == null || !"com.android.settings".equals(context.getPackageName())) return BackgroundContract.FONT_FOLLOW;
            return BackgroundContract.query(context, BackgroundContract.HOME).fontMode;
        } catch (Throwable ignored) {
            return BackgroundContract.FONT_FOLLOW;
        }
    }

    private static int forcedColor(int mode) {
        return mode == BackgroundContract.FONT_LIGHT ? Color.WHITE : Color.rgb(24, 24, 26);
    }
}
