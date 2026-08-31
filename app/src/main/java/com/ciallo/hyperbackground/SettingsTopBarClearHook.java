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
 * <p>这是与 {@link SettingsTopBarBlurHook}（OS3 顶栏渐变模糊）完全独立的一套逻辑，
 * 二者互斥：当 {@link BackgroundContract#UI_TOP_CLEAR_ENABLED} 开启时清除优先，
 * 顶栏模糊 hook 会主动短路让位；关闭时本 hook 完全不介入，模糊逻辑照旧。
 *
 * <p>与模糊 hook 只在下拉滚动 + overlay 模式下生效不同，本 hook 不依赖滚动位置，
 * 也覆盖 overlay / non-overlay 两种模式，因此能清掉“静止状态下就存在”的那条顶栏框。
 */
final class SettingsTopBarClearHook {
    private static final String HOME_ACTIVITY = "com.android.settings.MiuiSettings";
    private static final String HOME_LAYOUT = "hyperbackground_settings_home_layout";
    private static final String LOG_CLEARED = "hyperbackground_clear_logged";

    private SettingsTopBarClearHook() {}

    /** 供模糊 hook 查询“清除”是否开启，实现清除优先的互斥短路。 */
    static boolean isClearEnabled() {
        return HookRuntime.preferences().getBoolean(
                BackgroundContract.UI_TOP_CLEAR_ENABLED, false);
    }

    static void install(ClassLoader classLoader) {
        try {
            XC_MethodHook clearHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    if (!isClearEnabled()) return;
                    View layout = (View) param.thisObject;
                    if (!isHomeLayout(param.thisObject, layout.getContext())) return;
                    Object overBg = getField(param.thisObject, "mOverBgView");
                    if (overBg instanceof View) {
                        clearOverlay((View) overBg, param.thisObject);
                    }
                }
            };

            // 滚动回调：任何滚动进度变化都重新压平遮罩，防止系统在滚动中把它设回可见。
            XposedHelpers.findAndHookMethod(
                    "miuix.nestedheader.widget.NestedHeaderLayout",
                    classLoader,
                    "onScrollingProgressUpdated",
                    int.class,
                    clearHook);
            // 状态兜底：系统在这里决定 mOverBgView 的显隐，afterHook 里再抹平一次。
            XposedHelpers.findAndHookMethod(
                    "miuix.nestedheader.widget.NestedHeaderLayout",
                    classLoader,
                    "updateOverBgState",
                    int.class,
                    int.class,
                    clearHook);
            XposedBridge.log("[HyperBackground] Settings top bar clear hook installed");
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not hook settings top bar clear: " + error);
            XposedBridge.log(error);
        }
    }

    private static void clearOverlay(View overlay, Object layout) {
        try {
            overlay.setBackground(null);
            overlay.setAlpha(0f);
            overlay.setVisibility(View.INVISIBLE);
            // 关闭 sticky mask 的额外绘制，否则 onDraw 仍会画出遮罩色块。
            try {
                XposedHelpers.callMethod(overlay, "setStickyMaskEnabled", false, false);
            } catch (Throwable ignored) {
                // 该 API 不存在时，背景已清空即可，忽略。
            }
            logOnce(layout, "Settings home top bar mask cleared");
        } catch (Throwable ignored) {
            // 清除失败不影响其余功能。
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
        if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(instance, LOG_CLEARED))) return;
        XposedHelpers.setAdditionalInstanceField(instance, LOG_CLEARED, Boolean.TRUE);
        XposedBridge.log("[HyperBackground] " + message);
    }
}
