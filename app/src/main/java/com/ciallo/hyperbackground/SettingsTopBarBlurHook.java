package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.view.View;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsTopBarBlurHook {
    private static final String HOME_ACTIVITY = "com.android.settings.MiuiSettings";
    private static final String HOME_LAYOUT = "hyperbackground_settings_home_layout";
    private static final String LOG_SCROLL = "hyperbackground_blur_logged_scroll";
    private static final String LOG_READY = "hyperbackground_blur_logged_ready";
    private static Method setBlurTypeMethod;
    private static Method setGradientParamsMethod;
    private static Method clearBlendColorMethod;
    // #region debug-point light-lag-blur —— 逐帧回调计数，定位后移除
    private static int dbgBlurCalls;
    // #endregion

    private SettingsTopBarBlurHook() {}

    static void install(ClassLoader classLoader) {
        try {
            setBlurTypeMethod = View.class.getMethod("setMiBackgroundBlurType", int.class);
            setGradientParamsMethod = View.class.getMethod(
                    "setBackgroundGradientBlurParams", float[].class, int.class);
            clearBlendColorMethod = View.class.getMethod("clearMiBackgroundBlendColor");

            XposedHelpers.findAndHookMethod(
                    "miuix.nestedheader.widget.NestedHeaderLayout",
                    classLoader,
                    "onScrollingProgressUpdated",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;
                            // #region debug-point light-lag-blur —— 顶栏滚动逐帧回调频率，定位后移除
                            dbgBlurCalls++;
                            if (dbgBlurCalls % 60 == 0) {
                                XposedBridge.log("[HyperBackground][HBDBG] blurHook.scroll calls=" + dbgBlurCalls
                                        + " clearEnabled=" + SettingsTopBarClearHook.isClearEnabled());
                            }
                            // #endregion
                            // 清除顶栏与顶栏模糊互斥：清除开启时让位，交由 SettingsTopBarClearHook 处理，
                            // 本模糊逻辑（OS3 专用）整体短路，不做任何遮罩操作。
                            if (SettingsTopBarClearHook.isClearEnabled()) return;
                            View layout = (View) param.thisObject;
                            logOnce(param.thisObject, LOG_SCROLL,
                                    "NestedHeaderLayout scrolling callback reached");

                            if (!isHomeLayout(param.thisObject, layout.getContext())
                                    || getBooleanField(param.thisObject, "mInSearchMode")
                                    || !getBooleanField(param.thisObject, "mIsOverlayMode")) {
                                return;
                            }

                            int progress = (Integer) param.args[0];
                            int headerHeight = getIntField(param.thisObject, "mHeaderTotalHeight");
                            Object overBg = getField(param.thisObject, "mOverBgView");
                            Object blurHelper = getField(param.thisObject, "mBlurUiHelper");
                            if (headerHeight <= 0 || !(overBg instanceof View) || blurHelper == null) {
                                return;
                            }

                            View overlay = (View) overBg;
                            if (!HookRuntime.preferences().getBoolean(
                                    BackgroundContract.UI_TOP_BLUR_ENABLED, true)) {
                                clearGradientBlur(overlay);
                                XposedHelpers.callMethod(blurHelper, "applyBlur", false);
                                overlay.setAlpha(0f);
                                overlay.setVisibility(View.INVISIBLE);
                                return;
                            }
                            if (progress >= 0 || layout.getTop() > 0) {
                                clearGradientBlur(overlay);
                                XposedHelpers.callMethod(blurHelper, "applyBlur", false);
                                overlay.setAlpha(0f);
                                overlay.setVisibility(View.INVISIBLE);
                                return;
                            }

                            float scrollFraction = Math.min(1f, -progress / (float) headerHeight);
                            XposedHelpers.callMethod(blurHelper, "applyBlur", true);
                            clearMaterialMask(overlay);
                            int blurDp = getIntField(blurHelper, "mBlurEffect");
                            int height = overlay.getHeight();
                            if (blurDp <= 0 || height <= 0) {
                                logOnce(param.thisObject, LOG_READY,
                                        "System blur material or overlay height is unavailable");
                                return;
                            }

                            float density = overlay.getResources().getDisplayMetrics().density;
                            // Very large radii visually saturate near the transparent edge, even
                            // with a mathematically linear gradient. Limit the peak by the mask
                            // height so the visible transition is distributed across the bar.
                            int strength = HookRuntime.preferences().getInt(
                                    BackgroundContract.UI_TOP_BLUR_STRENGTH, 10);
                            float peakRadius = Math.min(
                                    Math.max(0, Math.min(100, strength)) * density,
                                    height * 0.5f);
                            float radius = peakRadius * scrollFraction;
                            // HyperOS setBgCommonLinearGradientBlur vertical protocol:
                            // startX, startY, startRadius, endX, endY, endRadius.
                            float[] gradient = new float[]{0f, 0f, radius, 0f, height, 0f};
                            try {
                                setBlurTypeMethod.invoke(overlay, 2);
                                setGradientParamsMethod.invoke(overlay, gradient, 1);
                                overlay.setVisibility(View.VISIBLE);
                                overlay.setAlpha(scrollFraction);
                                logOnce(param.thisObject, LOG_READY,
                                        "System linear gradient blur active, materialDp=" + blurDp
                                                + " radiusPx=" + radius + " height=" + height);
                            } catch (ReflectiveOperationException error) {
                                logOnce(param.thisObject, LOG_READY,
                                        "System linear gradient blur invocation failed: " + error);
                            }
                        }
                    });
            XposedBridge.log("[HyperBackground] Settings top bar progressive blur hook installed");
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not hook settings top bar blur: " + error);
            XposedBridge.log(error);
        }
    }

    static void markHomeFragment(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (!(activity instanceof Activity)
                    || !HOME_ACTIVITY.equals(activity.getClass().getName())) {
                return;
            }
            Object layout = XposedHelpers.getObjectField(fragment, "mNestedHeaderLayout");
            if (layout != null) {
                XposedHelpers.setAdditionalInstanceField(layout, HOME_LAYOUT, Boolean.TRUE);
                XposedBridge.log("[HyperBackground] Settings home NestedHeaderLayout marked");
            }
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not mark settings home layout: " + error);
        }
    }

    private static void clearGradientBlur(View overlay) {
        try {
            setBlurTypeMethod.invoke(overlay, 0);
        } catch (ReflectiveOperationException ignored) {
            // The stock helper below still clears the complete blur state.
        }
    }

    private static void clearMaterialMask(View overlay) {
        overlay.setBackgroundColor(Color.TRANSPARENT);
        try {
            clearBlendColorMethod.invoke(overlay);
        } catch (ReflectiveOperationException ignored) {
            // Gradient blur remains usable even when this vendor cleanup API is absent.
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

    private static boolean getBooleanField(Object instance, String name) {
        Object value = getField(instance, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static int getIntField(Object instance, String name) {
        Object value = getField(instance, name);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static void logOnce(Object instance, String key, String message) {
        if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(instance, key))) return;
        XposedHelpers.setAdditionalInstanceField(instance, key, Boolean.TRUE);
        XposedBridge.log("[HyperBackground] " + message);
    }
}
