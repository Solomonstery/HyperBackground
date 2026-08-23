package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class BackgroundApplier {
    private static final String HOME_SESSION = "hyperbackground.home.session";
    private static final String GLOBAL_SESSION = "hyperbackground.global.session";
    private static final String DEVICE_SESSION = "hyperbackground.device.session";
    private static final String DEVICE_ACTIVE = "hyperbackground.device.active";
    private static final String ORIGINAL_TEXT_COLOR = "hyperbackground.original.text.color";

    private BackgroundApplier() {}

    static void applyHome(Activity activity) {
        if (activity == null) return;
        applyLayer(activity, BackgroundContract.HOME, HOME_SESSION, true);
        applyFontMode(activity);
    }

    static void stopHome(Activity activity) { stopLayer(activity, HOME_SESSION); }

    static void applyGlobal(Activity activity) {
        if (activity == null) return;
        if (shouldSkipGlobal(activity)) {
            removeGlobal(activity);
            return;
        }
        applyLayer(activity, BackgroundContract.GLOBAL, GLOBAL_SESSION, false);
    }

    static void stopGlobal(Activity activity) { stopLayer(activity, GLOBAL_SESSION); }

    static void destroyGlobal(Activity activity) { removeGlobal(activity); }

    static void enterDevice(Activity activity) {
        if (activity == null) return;
        XposedHelpers.setAdditionalInstanceField(activity, DEVICE_ACTIVE, Boolean.TRUE);
        removeGlobal(activity);
    }

    static void leaveDevice(Activity activity) {
        if (activity == null) return;
        XposedHelpers.removeAdditionalInstanceField(activity, DEVICE_ACTIVE);
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null && !activity.isFinishing()) decor.post(() -> applyGlobal(activity));
        } catch (Throwable ignored) {}
    }

    private static boolean shouldSkipGlobal(Activity activity) {
        String packageName = activity.getPackageName();
        String className = activity.getClass().getName();

        // Keep permission / authorization / transient confirmation windows fully native.
        if (isSensitiveTransientActivity(className) || isSensitiveTransientWindow(activity)) return true;

        if ("com.android.settings".equals(packageName)) {
            if ("com.android.settings.MiuiSettings".equals(className)) return true;
            return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(activity, DEVICE_ACTIVE));
        }

        // Device interconnection is the only retained cross-package extension because it has
        // been verified to render the global background correctly. Pairing/permission popups stay native.
        if ("com.milink.service".equals(packageName)) {
            return !(className.contains(".ui.connectivitysettings.")
                    || className.equals("com.milink.ui.setting.SettingActivity")
                    || className.endsWith(".NetWorkingActivity"));
        }

        return true;
    }

    private static boolean isSensitiveTransientWindow(Activity activity) {
        if (activity == null) return false;
        TypedArray a = null;
        try {
            int[] attrs = new int[] { android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating };
            a = activity.obtainStyledAttributes(attrs);
            if (a.getBoolean(0, false) || a.getBoolean(1, false)) return true;
        } catch (Throwable ignored) {
        } finally {
            if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
        }
        try {
            Window w = activity.getWindow();
            if (w != null) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                if (lp != null && (lp.width != android.view.WindowManager.LayoutParams.MATCH_PARENT
                        || lp.height != android.view.WindowManager.LayoutParams.MATCH_PARENT)
                        && lp.width > 0 && lp.height > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isSensitiveTransientActivity(String className) {
        if (className == null) return false;
        String n = className.toLowerCase();
        return n.contains("permissionactivity")
                || n.contains("requirepermission")
                || n.contains("permissiondialog")
                || n.contains("authorization")
                || n.contains("authorize")
                || n.contains("accesscheckactivity")
                || n.contains("confirmcredential")
                || n.contains("confirmdialog")
                || n.contains("grant")
                || n.endsWith("ctaactivity")
                || n.contains("transparentactivity")
                || n.contains("dialogactivity");
    }

    private static void removeGlobal(Activity activity) {
        if (activity == null) return;
        try {
            LayerSession old = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, GLOBAL_SESSION);
            if (old != null) removeLayer(activity, GLOBAL_SESSION, old);
        } catch (Throwable error) { log("removeGlobal", error); }
    }

    private static void applyLayer(Activity activity, String slot, String fieldKey, boolean home) {
        try {
            BackgroundContract.Source source = BackgroundContract.query(activity, slot);
            LayerSession old = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, fieldKey);
            if (!source.exists) { if (old != null) removeLayer(activity, fieldKey, old); return; }

            View contentView = activity.findViewById(android.R.id.content);
            if (!(contentView instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) contentView;

            // Activity.onCreate is commonly hooked through the framework base method.
            // HyperOS Settings frequently calls setContentView() *after* super.onCreate(),
            // which can remove an already inserted media view while leaving our session alive.
            // Never trust a cached session unless its media view is still attached to the
            // current android.R.id.content hierarchy.
            if (old != null && old.media.sourceKey().equals(source.cacheKey())
                    && old.media.getParent() == content && old.observedRoot == content) {
                old.media.onHostResume();
                old.refresh(activity, home);
                return;
            }
            if (old != null) removeLayer(activity, fieldKey, old);
            View originalRoot = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            BackgroundMediaView media = new BackgroundMediaView(activity, source);
            ViewGroup.LayoutParams mediaParams = content instanceof FrameLayout
                    ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

            LayerSession session = new LayerSession(media);
            session.clear(content);
            if (originalRoot != null) session.clear(originalRoot);
            clearNamed(activity, session, "nestedheaderlayout");
            clearNamed(activity, session, "scroll_headers");
            clearNamed(activity, session, "main_content");
            if (!home) {
                clearNamed(activity, session, "prefs_container");
                clearNamed(activity, session, "preference_recyclerview");
                clearNamed(activity, session, "recycler_view");
                clearNamed(activity, session, "content");
                clearNamed(activity, session, "content_view");
                clearNamed(activity, session, "content_wrapper");
                clearNamed(activity, session, "action_bar_activity_content");
                clearNamed(activity, session, "area_content");
                clearNamed(activity, session, "auto_content");
            }
            try {
                if (!home && activity.getWindow() != null) {
                    activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                }
            } catch (Throwable ignored) {}
            content.addView(media, 0, mediaParams);
            session.attach(activity, content, home);
            XposedHelpers.setAdditionalInstanceField(activity, fieldKey, session);
        } catch (Throwable error) { log("applyLayer/" + slot, error); }
    }

    private static void stopLayer(Activity activity, String fieldKey) {
        if (activity == null) return;
        try {
            LayerSession s = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, fieldKey);
            if (s != null) s.media.onHostStop();
        } catch (Throwable error) { log("stopLayer", error); }
    }

    private static void removeLayer(Activity activity, String fieldKey, LayerSession session) {
        XposedHelpers.removeAdditionalInstanceField(activity, fieldKey);
        ViewGroup parent = session.media.getParent() instanceof ViewGroup ? (ViewGroup) session.media.getParent() : null;
        if (parent != null) parent.removeView(session.media);
        session.media.dispose();
        session.detach();
        session.restore();
    }

    static boolean shouldSuppressDeviceShader(Object fragment) {
        try {
            Context context = contextFromFragment(fragment);
            return context != null && BackgroundContract.query(context, BackgroundContract.DEVICE).exists;
        } catch (Throwable error) { log("shouldSuppressDeviceShader", error); return false; }
    }

    static void applyDevice(Object fragment) {
        if (fragment == null) return;
        try {
            Context context = contextFromFragment(fragment); if (context == null) return;
            Activity activity = activityFromFragment(fragment);
            BackgroundContract.Source source = BackgroundContract.query(context, BackgroundContract.DEVICE);
            DeviceSession old = (DeviceSession) XposedHelpers.getAdditionalInstanceField(fragment, DEVICE_SESSION);
            if (!source.exists) { if (old != null) removeDevice(fragment, old, true); if (activity != null) applyFontMode(activity); return; }
            if (old != null && old.media.sourceKey().equals(source.cacheKey())) { stopOriginalShader(fragment, old.backgroundView); old.media.onHostResume(); if (activity != null) applyFontMode(activity); return; }
            int rememberedVisibility = old != null ? old.originalVisibility : Integer.MIN_VALUE;
            Object field = XposedHelpers.getObjectField(fragment, "mBgEffectView"); if (!(field instanceof View)) return;
            View backgroundView = (View) field; if (!(backgroundView.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) backgroundView.getParent(); BackgroundMediaView media = new BackgroundMediaView(context, source);
            if (old != null) removeDevice(fragment, old, false); stopOriginalShader(fragment, backgroundView);
            int index = parent.indexOfChild(backgroundView); if (index < 0) index = 0;
            try { parent.addView(media, index + 1, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); }
            catch (Throwable e) { media.dispose(); backgroundView.setVisibility(rememberedVisibility != Integer.MIN_VALUE ? rememberedVisibility : View.VISIBLE); throw e; }
            DeviceSession session = new DeviceSession(backgroundView, rememberedVisibility != Integer.MIN_VALUE ? rememberedVisibility : backgroundView.getVisibility(), media);
            backgroundView.setVisibility(View.INVISIBLE); XposedHelpers.setAdditionalInstanceField(fragment, DEVICE_SESSION, session);
            if (activity != null) applyFontMode(activity);
        } catch (Throwable error) { log("applyDevice", error); }
    }

    static void stopDevice(Object fragment) {
        if (fragment == null) return;
        try { DeviceSession s = (DeviceSession) XposedHelpers.getAdditionalInstanceField(fragment, DEVICE_SESSION); if (s != null) s.media.onHostStop(); }
        catch (Throwable error) { log("stopDevice", error); }
    }

    static void destroyDevice(Object fragment) {
        if (fragment == null) return;
        try {
            DeviceSession session = (DeviceSession) XposedHelpers.removeAdditionalInstanceField(fragment, DEVICE_SESSION);
            if (session != null) removeDeviceView(session, false);
            Activity activity = activityFromFragment(fragment);
        } catch (Throwable error) { log("destroyDevice", error); }
    }

    static void applyFontMode(Activity activity) {
        if (activity == null) return;
        try {
            BackgroundContract.Source s = BackgroundContract.query(activity, BackgroundContract.HOME);
            View root = activity.findViewById(android.R.id.content); if (root == null) return;
            applyTextRecursive(root, s.fontMode);
        } catch (Throwable error) { log("applyFontMode", error); }
    }

    private static void applyTextRecursive(View view, int mode) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Object saved = XposedHelpers.getAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR);
            if (mode == BackgroundContract.FONT_FOLLOW) {
                if (saved instanceof Integer) { tv.setTextColor((Integer) saved); XposedHelpers.removeAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR); }
            } else {
                if (!(saved instanceof Integer)) XposedHelpers.setAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR, tv.getCurrentTextColor());
                TextColorOverride.apply(tv, mode);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view; for (int i = 0; i < g.getChildCount(); i++) applyTextRecursive(g.getChildAt(i), mode);
        }
    }

    private static void removeDevice(Object fragment, DeviceSession session, boolean restoreBackground) {
        XposedHelpers.removeAdditionalInstanceField(fragment, DEVICE_SESSION); removeDeviceView(session, restoreBackground);
    }
    private static void removeDeviceView(DeviceSession session, boolean restoreBackground) {
        ViewGroup parent = session.media.getParent() instanceof ViewGroup ? (ViewGroup) session.media.getParent() : null;
        if (parent != null) parent.removeView(session.media); session.media.dispose();
        if (restoreBackground) session.backgroundView.setVisibility(session.originalVisibility);
    }
    private static void stopOriginalShader(Object fragment, View backgroundView) {
        try { Object controller = XposedHelpers.getObjectField(fragment, "mBgEffectController"); if (controller != null) XposedHelpers.callMethod(controller, "stop"); } catch (Throwable ignored) {}
        try { backgroundView.setRenderEffect(null); } catch (Throwable ignored) {}
    }
    private static Context contextFromFragment(Object fragment) {
        Object context = XposedHelpers.callMethod(fragment, "getContext"); if (context instanceof Context) return (Context) context;
        Object activity = XposedHelpers.callMethod(fragment, "getActivity"); return activity instanceof Context ? (Context) activity : null;
    }
    private static Activity activityFromFragment(Object fragment) {
        try { Object a = XposedHelpers.callMethod(fragment, "getActivity"); return a instanceof Activity ? (Activity) a : null; } catch (Throwable ignored) { return null; }
    }
    private static void clearNamed(Activity activity, LayerSession session, String name) {
        String packageName = activity.getPackageName();
        int id = activity.getResources().getIdentifier(name, "id", packageName); if (id == 0) return;
        View view = activity.findViewById(id); if (view != null) session.clear(view);
    }
    private static void log(String stage, Throwable error) { XposedBridge.log("[HyperBackground] " + stage + " failed: " + error); XposedBridge.log(error); }

    private static final class LayerSession {
        final BackgroundMediaView media;
        final List<View> clearedViews = new ArrayList<>();
        final List<Drawable> originalBackgrounds = new ArrayList<>();
        ViewGroup observedRoot;
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean homeMode;

        LayerSession(BackgroundMediaView media) { this.media = media; }

        void clear(View view) {
            if (view == null || view == media || clearedViews.contains(view)) return;
            clearedViews.add(view);
            originalBackgrounds.add(view.getBackground());
            view.setBackground(null);
        }

        void attach(final Activity activity, ViewGroup root, boolean home) {
            observedRoot = root;
            homeMode = home;
            refresh(activity, home);
            layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try { refresh(activity, homeMode); } catch (Throwable ignored) {}
                }
            };
            try { root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener); } catch (Throwable ignored) {}
        }

        void refresh(Activity activity, boolean home) {
            if (home || activity == null || observedRoot == null) return;
            clearPageSurfaces(activity, observedRoot, observedRoot, 0);
        }

        void detach() {
            if (observedRoot != null && layoutListener != null) {
                try {
                    ViewTreeObserver observer = observedRoot.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
                } catch (Throwable ignored) {}
            }
            observedRoot = null;
            layoutListener = null;
        }

        void restore() {
            for (int i = 0; i < clearedViews.size(); i++) {
                try { clearedViews.get(i).setBackground(originalBackgrounds.get(i)); } catch (Throwable ignored) {}
            }
            clearedViews.clear();
            originalBackgrounds.clear();
        }

        private void clearPageSurfaces(Activity activity, View view, View root, int depth) {
            if (view == null || view == media) return;
            if (view.getVisibility() != View.VISIBLE) return;
            if (isPageSurface(activity, view, root, depth)) clear(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) clearPageSurfaces(activity, group.getChildAt(i), root, depth + 1);
            }
        }

        private boolean isPageSurface(Activity activity, View view, View root, int depth) {
            if (view == root) return true;
            Drawable bg = view.getBackground();
            if (bg == null) return false;

            int rootWidth = Math.max(root.getWidth(), activity.getResources().getDisplayMetrics().widthPixels);
            int rootHeight = Math.max(root.getHeight(), activity.getResources().getDisplayMetrics().heightPixels);
            int width = view.getWidth();
            int height = view.getHeight();
            boolean large = width >= (int) (rootWidth * 0.72f) && height >= (int) (rootHeight * 0.32f);

            String idName = resourceEntryName(activity, view.getId());
            String pkg = activity.getPackageName();

            // Device interconnection uses a full-width opaque host surface around the
            // actual cards. Clear only that page-level host; cards keep horizontal margins.
            if ("com.milink.service".equals(pkg)
                    && view instanceof ViewGroup
                    && width >= (int) (rootWidth * 0.965f)
                    && height >= (int) (rootHeight * 0.05f)
                    && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")) {
                return true;
            }

            if (!large) return false;

            String cls = view.getClass().getName().toLowerCase();

            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false;
            if (containsAny(cls, "cardview", "button", "switch", "checkbox", "imageview")) return false;

            if (containsAny(idName,
                    "content", "container", "recycler", "list", "prefs", "preference",
                    "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true;
            if (containsAny(cls,
                    "recyclerview", "nestedscrollview", "scrollview", "listview",
                    "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true;

            // HyperOS/MIUIX preference pages often use anonymous FrameLayout/LinearLayout
            // wrappers with a full-page theme surface. Restrict this fallback to very
            // large containers so normal preference cards keep their native backgrounds.
            return view instanceof ViewGroup
                    && width >= (int) (rootWidth * 0.90f)
                    && height >= (int) (rootHeight * 0.62f);
        }

        private String resourceEntryName(Activity activity, int id) {
            if (id == View.NO_ID || id == 0) return "";
            try { return activity.getResources().getResourceEntryName(id).toLowerCase(); }
            catch (Throwable ignored) { return ""; }
        }

        private boolean containsAny(String value, String... needles) {
            if (value == null || value.isEmpty()) return false;
            for (String needle : needles) if (value.contains(needle)) return true;
            return false;
        }
    }
    private static final class DeviceSession {
        final View backgroundView; final int originalVisibility; final BackgroundMediaView media;
        DeviceSession(View backgroundView, int originalVisibility, BackgroundMediaView media) { this.backgroundView = backgroundView; this.originalVisibility = originalVisibility; this.media = media; }
    }
}
