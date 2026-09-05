package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 清除设置主页顶栏遮罩（NestedHeaderLayout.mOverBgView）的黑/白底色框。
 *
 * <p>该选项只作用于 {@code com.android.settings.MiuiSettings} 首页。开启时首页清除优先，
 * 但不会关闭其它设置二级页的全局顶栏模糊。
 */
final class SettingsTopBarClearHook {
    private static final String HOME_ACTIVITY = "com.android.settings.MiuiSettings";
    private static final String HOME_LAYOUT = "hyperbackground_settings_home_layout";
    private static final String LOG_CLEARED = "hyperbackground_clear_logged";

    private SettingsTopBarClearHook() {}

    static boolean isClearEnabled() {
        return HookRuntime.preferences().getBoolean(
                BackgroundContract.UI_TOP_CLEAR_ENABLED, false);
    }

    static boolean shouldClear(Context context) {
        return isClearEnabled() && isSettingsHome(context);
    }

    /**
     * 清除首页 NestedHeaderLayout 的遮罩，并返回该布局是否由清除模式接管。
     */
    static boolean clearLayoutIfEnabled(Object layoutObject) {
        if (!(layoutObject instanceof View)) return false;
        View layout = (View) layoutObject;
        if (!isClearEnabled() || !isHomeLayout(layoutObject, layout.getContext())) return false;
        Object overBg = getField(layoutObject, "mOverBgView");
        if (overBg instanceof View) {
            clearOverlay((View) overBg, layoutObject);
        }
        disableLayoutBlur(layoutObject);
        return true;
    }

    static void install(ClassLoader classLoader) {
        hookLayoutMethod(classLoader, "onFinishInflate");
        hookLayoutMethod(classLoader, "onScrollingProgressUpdated", int.class);
        hookLayoutMethod(classLoader, "updateOverBgState", int.class, int.class);
        hookLayoutMethod(classLoader, "applyBlur", boolean.class);
        XposedBridge.log("[HyperBackground] Settings home top bar clear hook installed");
    }

    private static void hookLayoutMethod(
            ClassLoader classLoader, String methodName, Object... parameterTypes) {
        try {
            Object[] hookArgs = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, hookArgs, 0, parameterTypes.length);
            hookArgs[parameterTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    clearLayoutIfEnabled(param.thisObject);
                }
            };
            XposedHelpers.findAndHookMethod(
                    "miuix.nestedheader.widget.NestedHeaderLayout",
                    classLoader,
                    methodName,
                    hookArgs);
        } catch (Throwable error) {
            // HyperOS / MIUIX 各版本方法不完全一致；单个兜底点缺失不能影响其它 Hook。
            XposedBridge.log("[HyperBackground] Top bar clear method unavailable: "
                    + methodName + ": " + error);
        }
    }

    private static void clearOverlay(View overlay, Object layout) {
        try {
            clearVendorBlur(overlay);
            overlay.setBackground(null);
            overlay.setAlpha(0f);
            overlay.setVisibility(View.INVISIBLE);
            try {
                XposedHelpers.callMethod(overlay, "setStickyMaskEnabled", false, false);
            } catch (Throwable ignored) {
                // Older versions do not expose this API.
            }
            overlay.invalidate();
            logOnce(layout, "Settings home top bar mask cleared");
        } catch (Throwable ignored) {
            // 清除失败不影响其余背景功能。
        }
    }

    private static void clearVendorBlur(View view) {
        try {
            XposedHelpers.callMethod(view, "setMiBackgroundBlurMode", 0);
        } catch (Throwable ignored) {
            // Vendor method is optional.
        }
        try {
            XposedHelpers.callMethod(view, "setMiViewBlurMode", 0);
        } catch (Throwable ignored) {
            // Vendor method is optional.
        }
        try {
            XposedHelpers.callMethod(view, "setMiBackgroundBlurType", 0);
        } catch (Throwable ignored) {
            // Vendor method is optional.
        }
        try {
            XposedHelpers.callMethod(view, "clearMiBackgroundBlendColor");
        } catch (Throwable ignored) {
            // Vendor method is optional.
        }
    }

    private static void disableLayoutBlur(Object layout) {
        try {
            XposedHelpers.callMethod(layout, "setEnableBlur", false);
        } catch (Throwable ignored) {
            // HyperOS 3 may only expose the helper.
        }
        Object helper = getField(layout, "mBlurUiHelper");
        if (helper == null) return;
        try {
            XposedHelpers.callMethod(helper, "setEnableBlur", false);
            XposedHelpers.callMethod(helper, "applyBlur", false);
        } catch (Throwable ignored) {
            // Vendor helper methods vary by release.
        }
    }

    private static boolean isHomeLayout(Object layout, Context context) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(layout, HOME_LAYOUT))
                || isSettingsHome(context);
    }

    private static boolean isSettingsHome(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return HOME_ACTIVITY.equals(current.getClass().getName());
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return false;
    }

    private static Object getField(Object instance, String name) {
        try {
            return XposedHelpers.getObjectField(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logOnce(Object instance, String message) {
        if (Boolean.TRUE.equals(
                XposedHelpers.getAdditionalInstanceField(instance, LOG_CLEARED))) return;
        XposedHelpers.setAdditionalInstanceField(instance, LOG_CLEARED, Boolean.TRUE);
        XposedBridge.log("[HyperBackground] " + message);
    }
}
