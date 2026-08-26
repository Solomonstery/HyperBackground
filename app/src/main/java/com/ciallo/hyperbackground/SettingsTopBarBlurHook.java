package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsTopBarBlurHook {
    private static final String HOME_ACTIVITY = "com.android.settings.MiuiSettings";
    private static final String HOME_LAYOUT = "hyperbackground_settings_home_layout";
    private static final String LOG_SCROLL = "hyperbackground_blur_logged_scroll";
    private static final String LOG_READY = "hyperbackground_blur_logged_ready";
    private static final String GRADIENT_LAYERS = "hyperbackground_blur_gradient_layers";
    private static final int LAYER_COUNT = 12;
    private static Class<?> blurUtilsClass;
    private static Method setBackgroundBlurMethod;
    private static Method clearBackgroundBlurMethod;

    private SettingsTopBarBlurHook() {}

    static void install(ClassLoader classLoader) {
        try {
            blurUtilsClass = Class.forName("miuix.core.util.MiuiBlurUtils", false, classLoader);
            setBackgroundBlurMethod = blurUtilsClass.getDeclaredMethod(
                    "setBackgroundBlur", View.class, int.class, int.class);
            setBackgroundBlurMethod.setAccessible(true);
            clearBackgroundBlurMethod = blurUtilsClass.getDeclaredMethod(
                    "clearBackgroundBlur", View.class);
            clearBackgroundBlurMethod.setAccessible(true);

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
                            if (progress >= 0 || layout.getTop() > 0) {
                                XposedHelpers.callMethod(blurHelper, "applyBlur", false);
                                overlay.setAlpha(0f);
                                overlay.setVisibility(View.INVISIBLE);
                                hideGradientLayers(param.thisObject);
                                return;
                            }

                            float fraction = Math.min(1f, -progress / (float) headerHeight);
                            int blurDp = getIntField(blurHelper, "mBlurEffect");
                            if (blurDp <= 0) {
                                // Ask the stock helper to create the current HyperOS material
                                // parameters, then remove its uniform tinted blur surface.
                                XposedHelpers.callMethod(blurHelper, "applyBlur", true);
                                blurDp = getIntField(blurHelper, "mBlurEffect");
                                XposedHelpers.callMethod(blurHelper, "applyBlur", false);
                            }
                            if (blurDp <= 0) {
                                logOnce(param.thisObject, LOG_READY,
                                        "System blur helper active but material radius is unavailable");
                                return;
                            }

                            overlay.setAlpha(0f);
                            overlay.setVisibility(View.INVISIBLE);
                            applyGradientLayers(param.thisObject, layout, overlay, blurDp, fraction);
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

    private static boolean isHomeLayout(Object layout, Context context) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(layout, HOME_LAYOUT))
                || isSettingsHome(context);
    }

    private static void applyGradientLayers(
            Object owner, View layout, View overlay, int blurDp, float scrollFraction) {
        if (!(layout instanceof ViewGroup)) return;
        GradientLayers gradient = getOrCreateGradientLayers(owner, (ViewGroup) layout, overlay);
        if (gradient == null) return;

        int width = overlay.getWidth();
        int height = overlay.getHeight();
        if (width <= 0 || height <= 0) return;

        gradient.container.setVisibility(View.VISIBLE);
        gradient.container.layout(overlay.getLeft(), overlay.getTop(), overlay.getRight(), overlay.getBottom());
        float density = overlay.getResources().getDisplayMetrics().density;
        int layerHeight = (int) Math.ceil(height / (float) LAYER_COUNT);
        boolean apiResult = true;

        for (int index = 0; index < LAYER_COUNT; index++) {
            int top = index * layerHeight;
            int bottom = Math.min(height, (index + 1) * layerHeight + 2);
            View layer = gradient.layers[index];
            layer.layout(0, top, width, bottom);

            float position = (index + 0.5f) / LAYER_COUNT;
            float spatialStrength = 1f - (position * position * (3f - 2f * position));
            int radius = Math.max(1, Math.round(
                    blurDp * density * scrollFraction * spatialStrength));
            if (gradient.lastRadii[index] == radius) continue;
            try {
                Object result = setBackgroundBlurMethod.invoke(null, layer, radius, 1);
                apiResult &= !(result instanceof Boolean) || (Boolean) result;
                gradient.lastRadii[index] = radius;
            } catch (ReflectiveOperationException error) {
                logOnce(owner, LOG_READY, "System gradient blur invocation failed: " + error);
                return;
            }
        }

        logOnce(owner, LOG_READY,
                "System vertical gradient blur active, layers=" + LAYER_COUNT
                        + " materialDp=" + blurDp + " apiResult=" + apiResult);
    }

    private static GradientLayers getOrCreateGradientLayers(
            Object owner, ViewGroup parent, View overlay) {
        Object existing = XposedHelpers.getAdditionalInstanceField(owner, GRADIENT_LAYERS);
        if (existing instanceof GradientLayers) return (GradientLayers) existing;

        FrameLayout container = new FrameLayout(parent.getContext());
        container.setClickable(false);
        container.setFocusable(false);
        container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        View[] layers = new View[LAYER_COUNT];
        for (int index = 0; index < LAYER_COUNT; index++) {
            View layer = new View(parent.getContext());
            layer.setClickable(false);
            layers[index] = layer;
            container.addView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        int overlayIndex = parent.indexOfChild(overlay);
        parent.addView(container, Math.max(0, overlayIndex + 1),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        GradientLayers gradient = new GradientLayers(container, layers);
        XposedHelpers.setAdditionalInstanceField(owner, GRADIENT_LAYERS, gradient);
        return gradient;
    }

    private static void hideGradientLayers(Object owner) {
        Object value = XposedHelpers.getAdditionalInstanceField(owner, GRADIENT_LAYERS);
        if (!(value instanceof GradientLayers)) return;
        GradientLayers gradient = (GradientLayers) value;
        gradient.container.setVisibility(View.INVISIBLE);
        for (int index = 0; index < gradient.layers.length; index++) {
            try {
                clearBackgroundBlurMethod.invoke(null, gradient.layers[index]);
            } catch (ReflectiveOperationException ignored) {
                // The container is hidden even if a vendor blur cleanup call fails.
            }
            gradient.lastRadii[index] = -1;
        }
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

    private static final class GradientLayers {
        final FrameLayout container;
        final View[] layers;
        final int[] lastRadii = new int[LAYER_COUNT];

        GradientLayers(FrameLayout container, View[] layers) {
            this.container = container;
            this.layers = layers;
            java.util.Arrays.fill(lastRadii, -1);
        }
    }
}
