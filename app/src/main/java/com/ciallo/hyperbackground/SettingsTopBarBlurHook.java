package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsTopBarBlurHook {
    private static final String HOME_ACTIVITY = "com.android.settings.MiuiSettings";
    private static final String HOME_LAYOUT = "hyperbackground_settings_home_layout";
    private static final String LOG_SCROLL = "hyperbackground_blur_logged_scroll";
    private static final String LOG_READY = "hyperbackground_blur_logged_ready";
    private static final String LOG_BAR_MASK = "hyperbackground_blur_logged_bar_mask";
    private static final String BAR_BLUR_SUPPRESSED = "hyperbackground_bar_blur_suppressed";
    private static Method setBlurTypeMethod;
    private static Method setBlurModeMethod;
    private static Method setViewBlurModeMethod;
    private static Method setGradientParamsMethod;
    private static Method clearBlendColorMethod;

    private SettingsTopBarBlurHook() {}

    static void install(ClassLoader classLoader) {
        try {
            setBlurTypeMethod = View.class.getMethod("setMiBackgroundBlurType", int.class);
            setBlurModeMethod = View.class.getMethod("setMiBackgroundBlurMode", int.class);
            setViewBlurModeMethod = View.class.getMethod("setMiViewBlurMode", int.class);
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
                            View layout = (View) param.thisObject;
                            logOnce(param.thisObject, LOG_SCROLL,
                                    "NestedHeaderLayout scrolling callback reached");

                            if (!isHomeLayout(param.thisObject, layout.getContext())
                                    || !isOverlayMode(param.thisObject)) {
                                return;
                            }

                            int progress = (Integer) param.args[0];
                            int headerHeight = getIntField(param.thisObject, "mHeaderTotalHeight");
                            Object overBg = getField(param.thisObject, "mOverBgView");
                            Object blurHelper = getField(param.thisObject, "mBlurUiHelper");
                            if (headerHeight <= 0 || !(overBg instanceof View)) {
                                return;
                            }

                            View overlay = (View) overBg;
                            if (!HookRuntime.preferences().getBoolean(
                                    BackgroundContract.UI_TOP_BLUR_ENABLED, true)) {
                                clearGradientBlur(overlay);
                                setBlurEnabled(param.thisObject, blurHelper, false);
                                overlay.setAlpha(0f);
                                overlay.setVisibility(View.INVISIBLE);
                                return;
                            }
                            if (progress >= 0 || layout.getTop() > 0) {
                                clearGradientBlur(overlay);
                                setBlurEnabled(param.thisObject, blurHelper, false);
                                overlay.setAlpha(0f);
                                overlay.setVisibility(View.INVISIBLE);
                                return;
                            }

                            float scrollFraction = Math.min(1f, -progress / (float) headerHeight);
                            setBlurEnabled(param.thisObject, blurHelper, true);
                            clearNativeStickyMask(overlay);
                            clearNativeBlurBackground(param.thisObject, overlay);
                            clearMaterialMask(overlay);
                            int height = overlay.getHeight();
                            if (height <= 0) {
                                logOnce(param.thisObject, LOG_READY,
                                        "System blur overlay height is unavailable");
                                return;
                            }

                            float density = overlay.getResources().getDisplayMetrics().density;
                            // Very large radii visually saturate near the transparent edge, even
                            // with a mathematically linear gradient. Limit the peak by the mask
                            // height so the visible transition is distributed across the bar.
                            int strength = HookRuntime.preferences().getInt(
                                    BackgroundContract.UI_TOP_BLUR_STRENGTH, 10);
                            int opacity = HookRuntime.preferences().getInt(
                                    BackgroundContract.UI_TOP_BLUR_OPACITY, 100);
                            float blurAlpha = scrollFraction
                                    * Math.max(0, Math.min(100, opacity)) / 100f;
                            float peakRadius = Math.min(
                                    Math.max(0, Math.min(100, strength)) * density,
                                    height * 0.5f);
                            float radius = peakRadius * scrollFraction;
                            // HyperOS setBgCommonLinearGradientBlur vertical protocol:
                            // startX, startY, startRadius, endX, endY, endRadius.
                            float[] gradient = new float[]{0f, 0f, radius, 0f, height, 0f};
                            try {
                                // OS4's gradient API only supplies the parameters. The
                                // background and view blur modes must be enabled separately.
                                setBlurModeMethod.invoke(overlay, 1);
                                setViewBlurModeMethod.invoke(overlay, 1);
                                setBlurTypeMethod.invoke(overlay, 2);
                                setGradientParamsMethod.invoke(overlay, gradient, 1);
                                overlay.setVisibility(View.VISIBLE);
                                overlay.setAlpha(blurAlpha);
                                logOnce(param.thisObject, LOG_READY,
                                        "System linear gradient blur active, radiusPx=" + radius
                                                + " height=" + height + " alpha=" + blurAlpha);
                            } catch (ReflectiveOperationException error) {
                                logOnce(param.thisObject, LOG_READY,
                                        "System linear gradient blur invocation failed: " + error);
                            }
                        }
                    });
            // HyperOS 4's native black gradient is applied from applyBlur().
            // Clear it after the vendor method completes; changing only the
            // mOverBgView background cannot replace that Canvas-drawn mask.
            XposedHelpers.findAndHookMethod(
                    "miuix.nestedheader.widget.NestedHeaderLayout",
                    classLoader,
                    "applyBlur",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || !isHomeLayout(param.thisObject,
                                    ((View) param.thisObject).getContext())
                                    || !isTopBlurEnabled()) {
                                return;
                            }
                            Object overBg = getField(param.thisObject, "mOverBgView");
                            if (overBg instanceof View) {
                                View overlay = (View) overBg;
                                clearNativeStickyMask(overlay);
                                clearNativeBlurBackground(param.thisObject, overlay);
                                clearMaterialMask(overlay);
                            }
                        }
                    });
            // HyperOS 4's visible black gradient on the settings home page is
            // painted by the floating ActionBar, not by NestedHeaderLayout.
            // SettingsFragment.setupActionBarOverlayMask() installs an
            // OverlayMaskConfig (floating mask color, alpha factors 0.98 -> 0)
            // and ActionBarContainer draws it on every scroll pass. Skip that
            // paint entirely so only the module's gradient blur stays visible.
            try {
                XposedHelpers.findAndHookMethod(
                        "miuix.appcompat.internal.app.widget.ActionBarContainer",
                        classLoader,
                        "drawOverlayMaskIfNeeded",
                        android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!(param.thisObject instanceof View)) return;
                                if (!isSettingsHome(((View) param.thisObject).getContext())) {
                                    return;
                                }
                                if (!isTopBlurEnabled()) {
                                    restoreNativeActionBarBlur(param.thisObject);
                                    return;
                                }
                                logOnce(param.thisObject, LOG_BAR_MASK,
                                        "Action bar overlay mask skipped on settings home");
                                param.setResult(null);
                            }
                        });
            } catch (Throwable error) {
                XposedBridge.log("[HyperBackground] Action bar mask hook unavailable: " + error);
            }
            // The ActionBar owns another fixed 40dp gradient blur. Suppress only
            // that render operation while retaining applyBlur's state callbacks,
            // which keep the stock opaque background hidden.
            try {
                XposedHelpers.findAndHookMethod(
                        "miuix.appcompat.internal.app.widget.ActionBarContainer",
                        classLoader,
                        "applyVerticalGradientBlurInternal",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!(param.thisObject instanceof View)) return;
                                View bar = (View) param.thisObject;
                                if (!isSettingsHome(bar.getContext()) || !isTopBlurEnabled()) return;
                                clearGradientBlur(bar);
                                clearMaterialMask(bar);
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, BAR_BLUR_SUPPRESSED, Boolean.TRUE);
                                param.setResult(null);
                            }
                        });
            } catch (Throwable error) {
                XposedBridge.log("[HyperBackground] Action bar blur hook unavailable: " + error);
            }
            // Both OS4 helpers derive their dark tint from the Pured_Regular
            // material. Prevent every refresh path from applying that tint to
            // views hosted by the Settings home activity.
            try {
                XposedHelpers.findAndHookMethod(
                        "miuix.view.MiuiBlurUiHelper",
                        classLoader,
                        "applyColorBlend",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object target = getField(param.thisObject, "mTargetView");
                                if (target instanceof View
                                        && isSettingsHome(((View) target).getContext())
                                        && isManagedTopBlurView((View) target)
                                        && isTopBlurEnabled()) {
                                    clearMaterialMask((View) target);
                                    param.setResult(null);
                                }
                            }
                        });
            } catch (Throwable error) {
                XposedBridge.log("[HyperBackground] Material blend hook unavailable: " + error);
            }
            // OS4 paints its built-in solid-color gradient directly in this
            // method. The mask color alone is not enough because the vendor
            // method is still invoked on every draw pass.
            try {
                XposedHelpers.findAndHookMethod(
                        "miuix.nestedheader.widget.NestedHeaderStickyMaskImpl",
                        classLoader,
                        "drawOverlayMask",
                        android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object stickyView = getField(param.thisObject, "mStickyView");
                                if (stickyView instanceof View
                                        && isSettingsHome(((View) stickyView).getContext())
                                        && isTopBlurEnabled()) {
                                    param.setResult(null);
                                }
                            }
                        });
            } catch (Throwable error) {
                XposedBridge.log("[HyperBackground] Native sticky mask hook unavailable: " + error);
            }
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
            if (setBlurModeMethod != null) setBlurModeMethod.invoke(overlay, 0);
            if (setViewBlurModeMethod != null) setViewBlurModeMethod.invoke(overlay, 0);
            if (setBlurTypeMethod != null) setBlurTypeMethod.invoke(overlay, 0);
        } catch (ReflectiveOperationException ignored) {
            // The stock helper below still clears the complete blur state.
        }
    }

    private static void clearMaterialMask(View overlay) {
        if (clearBlendColorMethod == null) return;
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

    private static boolean isTopBlurEnabled() {
        return HookRuntime.preferences().getBoolean(
                BackgroundContract.UI_TOP_BLUR_ENABLED, true);
    }

    private static boolean isManagedTopBlurView(View view) {
        String name = view.getClass().getName();
        return "miuix.appcompat.internal.app.widget.ActionBarContainer".equals(name)
                || "miuix.nestedheader.widget.NestedHeaderOverlayMaskView".equals(name);
    }

    private static void restoreNativeActionBarBlur(Object bar) {
        if (!Boolean.TRUE.equals(
                XposedHelpers.getAdditionalInstanceField(bar, BAR_BLUR_SUPPRESSED))) {
            return;
        }
        XposedHelpers.setAdditionalInstanceField(bar, BAR_BLUR_SUPPRESSED, Boolean.FALSE);
        Object helper = getField(bar, "mBlurHelper");
        if (helper == null) return;
        try {
            XposedHelpers.callMethod(helper, "resetBlurParams");
            XposedHelpers.callMethod(helper, "refreshBlur");
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Could not restore action bar blur: " + error);
        }
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

    private static void clearNativeStickyMask(View overlay) {
        try {
            // OS4 draws its black gradient in NestedHeaderStickyMaskImpl, not in
            // mOverBgView's background. Make that native paint transparent so the
            // system blur layer below remains visible.
            Object mask = getField(overlay, "mStickyMaskImpl");
            if (mask == null) {
                XposedHelpers.callMethod(overlay, "isStickyMaskEnabled");
                mask = getField(overlay, "mStickyMaskImpl");
            }
            if (mask != null) {
                setIntField(mask, "mMaskColor", 0);
                try {
                    XposedHelpers.callMethod(overlay, "setStickyMaskEnabled", false, false);
                } catch (Throwable ignored) {
                    // Older builds do not expose the two-argument overload.
                }
                overlay.invalidate();
            }
        } catch (Throwable ignored) {
            // OS3 does not expose the sticky mask implementation.
        }
    }

    private static void clearNativeBlurBackground(Object layout, View overlay) {
        try {
            // OS4 stores the original dark blur drawable separately and restores
            // it from mMaskBackgroundInBlur. Remove that drawable before applying
            // the module-owned transparent gradient blur.
            Object nativeMask = getField(layout, "mMaskBackgroundInBlur");
            if (nativeMask != null && overlay.getBackground() == nativeMask) {
                overlay.setBackground(null);
            }
        } catch (Throwable ignored) {
            // OS3 has no in-blur mask drawable.
        }
    }

    private static boolean isOverlayMode(Object layout) {
        try {
            Object value = XposedHelpers.callMethod(layout, "isOverlayMode");
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Throwable ignored) {
            // HyperOS 3 may only expose the backing field.
        }
        return getBooleanField(layout, "mIsOverlayMode");
    }

    private static void setIntField(Object instance, String name, int value)
            throws ReflectiveOperationException {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(instance, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // The field may be declared by a superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setBlurEnabled(Object layout, Object blurHelper, boolean enabled) {
        try {
            // OS4 Settings disables this helper after creating the layout. Re-enable it
            // explicitly before applying the custom gradient to mOverBgView.
            XposedHelpers.callMethod(layout, "setEnableBlur", enabled);
        } catch (Throwable ignored) {
            // Fall back to the helper on OS3 builds without the layout method.
        }
        if (blurHelper != null) {
            try {
                XposedHelpers.callMethod(blurHelper, "setEnableBlur", enabled);
                XposedHelpers.callMethod(blurHelper, "applyBlur", enabled);
            } catch (Throwable ignored) {
                // A missing vendor helper must not break the scrolling callback.
            }
        }
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
