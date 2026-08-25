package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class BackgroundApplier {
    // Namespace Xposed additional fields with the stable application id so sessions cannot
    // collide with another module using similar field names inside the hooked process.
    private static final String FIELD_PREFIX = BuildConfig.APPLICATION_ID + ".hook.";
    private static final String HOME_SESSION = FIELD_PREFIX + "home.session";
    private static final String GLOBAL_SESSION = FIELD_PREFIX + "global.session";
    private static final String DEVICE_SESSION = FIELD_PREFIX + "device.session";
    private static final String DEVICE_ACTIVE = FIELD_PREFIX + "device.active";
    private static final String ORIGINAL_TEXT_COLOR = FIELD_PREFIX + "original.text.color";
    private static final String GLOBAL_DIAGNOSTIC = FIELD_PREFIX + "global.diagnostic";

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
            diagnostic(activity, "skip " + activity.getClass().getName());
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

        if (BackgroundContract.PACKAGE_SETTINGS.equals(packageName)) {
            if ("com.android.settings.MiuiSettings".equals(className)) return true;
            return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(activity, DEVICE_ACTIVE));
        }

        // Only known full-screen settings surfaces are accepted in cross-package processes.
        // Pairing, login, permission, payment and other transient/sensitive windows stay native.
        if (BackgroundContract.PACKAGE_MILINK.equals(packageName)) {
            return !(className.contains(".ui.connectivitysettings.")
                    || className.equals("com.milink.ui.setting.SettingActivity")
                    || className.endsWith(".NetWorkingActivity"));
        }

        if (BackgroundContract.PACKAGE_PHONE.equals(packageName)) {
            return !matchesPhoneSettings(className);
        }

        if (BackgroundContract.PACKAGE_ACCOUNT.equals(packageName)) {
            return !matchesAccountSettings(className);
        }

        if (BackgroundContract.PACKAGE_THEME_MANAGER.equals(packageName)) {
            return !matchesThemeSettings(className);
        }

        if (BackgroundContract.PACKAGE_HOME.equals(packageName)) {
            return !matchesHomeSettings(className);
        }

        if (BackgroundContract.PACKAGE_SECURITY_CENTER.equals(packageName)) {
            return !matchesSecurityCenterSettings(className);
        }

        if (BackgroundContract.PACKAGE_POWER_KEEPER.equals(packageName)) {
            // PowerKeeper is scoped only after the full-screen/transient-window checks above.
            return false;
        }

        if (BackgroundContract.PACKAGE_MI_SETTINGS.equals(packageName)) {
            return !matchesMiSettings(className);
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
                || n.contains("credential")
                || n.contains("password")
                || n.contains("pinactivity")
                || n.contains("payment")
                || n.contains("wallet")
                || n.contains("login")
                || n.contains("signin")
                || n.contains("oauth")
                || n.contains("passport")
                || n.contains("emergency")
                || n.contains("dialer")
                || n.contains("incall")
                || n.contains("confirmdialog")
                || n.contains("grant")
                || n.endsWith("ctaactivity")
                || n.contains("transparentactivity")
                || n.contains("dialogactivity");
    }

    private static boolean matchesPhoneSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.startsWith("com.android.phone.settings.")
                || n.contains("setting")
                || n.contains("calloptions")
                || n.contains("callbarringoptions")
                || n.contains("callfeaturessetting")
                || n.contains("callforwardtype")
                || n.contains("callforwardoptions")
                || n.contains("additionalcalloptions")
                || n.endsWith("fivegnrcasettingactivity")
                || n.endsWith("nrdisplayactivity");
    }

    private static boolean matchesAccountSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("accountsettings")
                || n.contains("accountsecurity")
                || n.contains("agreementandprivacy")
                || n.contains("systemadactivity")
                || n.contains("userdetailinfo")
                || n.contains("userphoneinfo")
                || n.contains("devicesettinglist")
                || n.contains("devicedetailinfo")
                || n.contains("snslistactivity")
                || n.contains("snsaccountactivity");
    }

    private static boolean matchesThemeSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("themesettings")
                || n.contains("themepreference")
                || n.contains("themeabout")
                || n.endsWith(".activity.themetabactivity")
                || n.contains("themeandwallpaper")
                || n.contains("wallpapersettings")
                || n.contains("wallpapersubsetting")
                || n.contains("wallpapertabactivity")
                || n.contains("wallpapermiuitab")
                || n.contains("privacysettings")
                || n.contains("authoritymanagement")
                || n.contains("supportthemeactivity")
                || n.contains("personalize")
                || n.contains("aifromsettings");
    }

    private static boolean matchesHomeSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("settingsactivity")
                || n.contains("homesettings")
                || n.contains("launchersettings");
    }

    private static boolean matchesSecurityCenterSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("setting")
                || n.contains("power")
                || n.contains("battery")
                || n.contains("autostart")
                || n.contains("appmanager")
                || n.contains("privacy")
                || n.contains("permission")
                || n.contains("networkassistant")
                || n.contains("garbage")
                || n.contains("optimiz");
    }

    private static boolean matchesMiSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains("healthy")
                || n.contains("usagestat")
                || n.contains("focusmode")
                || n.contains("devicelimit")
                || n.contains("appusage")
                || n.contains("screen")
                || n.contains("settings");
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
            if (!source.exists) {
                if (BackgroundContract.GLOBAL.equals(slot)) {
                    diagnostic(activity, "source-missing authority=" + BackgroundContract.AUTHORITY);
                }
                if (old != null) removeLayer(activity, fieldKey, old);
                return;
            }

            View contentView = activity.findViewById(android.R.id.content);
            if (!(contentView instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) contentView;
            ViewGroup host = selectLayerHost(activity, content, home);
            boolean transparentTopBar = host != content;

            // Keep the media in android.R.id.content. This is the path already verified on
            // device interconnection. Miuix secondary pages are the one deliberate exception:
            // their expanded action bar is a sibling of android.R.id.content, so the media must
            // sit one level higher in the known ActionBarOverlayLayout to continue behind the
            // status bar, back button and large title. We never promote to DecorView.
            if (old != null && old.media.sourceKey().equals(source.cacheKey())
                    && old.media.getParent() == host && old.observedRoot == host) {
                old.media.onHostResume();
                old.refresh(activity, home);
                return;
            }
            if (old != null) removeLayer(activity, fieldKey, old);
            View originalRoot = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            BackgroundMediaView media = new BackgroundMediaView(activity, source);
            ViewGroup.LayoutParams mediaParams = host instanceof FrameLayout
                    ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

            LayerSession session = new LayerSession(media);
            if (host != content) session.clear(host);
            session.clear(content);
            if (originalRoot != null) session.clear(originalRoot);
            clearNamed(activity, session, "nestedheaderlayout");
            clearNamed(activity, session, "nested_header_layout");
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
            host.addView(media, 0, mediaParams);
            session.attach(activity, host, home, transparentTopBar);
            XposedHelpers.setAdditionalInstanceField(activity, fieldKey, session);
            if (BackgroundContract.GLOBAL.equals(slot)) {
                diagnostic(activity, "applied host=" + host.getClass().getName()
                        + " root=" + (originalRoot == null ? "none" : originalRoot.getClass().getName())
                        + " topBar=" + (transparentTopBar ? "transparent" : "content-only")
                        + " activity=" + activity.getClass().getName());
            }
        } catch (Throwable error) { log("applyLayer/" + slot, error); }
    }

    private static ViewGroup selectLayerHost(Activity activity, ViewGroup content, boolean home) {
        // MobileNetworkSettings uses MIUIX ActionBarOverlayLayout for page animation.
        // Keep the custom background outside that animated container so it does not
        // slide together with the page and create a ghost/double-background frame.
        if (activity != null
                && "com.android.phone".equals(activity.getPackageName())
                && "com.android.phone.settings.MobileNetworkSettings".equals(
                        activity.getClass().getName())) {
            try {
                View windowContent = activity.findViewById(android.R.id.content);
                if (windowContent instanceof ViewGroup) {
                    ViewGroup windowHost = (ViewGroup) windowContent;
                    ViewParent parent = windowHost.getParent();

                    if (parent instanceof ViewGroup
                            && parent.getClass().getName().contains("ActionBarOverlayLayout")) {
                        return (ViewGroup) parent;
                    }
                    return windowHost;
                }
            } catch (Throwable error) {
                log("selectLayerHost/MobileNetworkSettings", error);
            }
        }

        if (activity == null || content == null) return content;
        try {
            Object parent = content.getParent();
            if (parent instanceof ViewGroup && isMiuixActionBarHost(activity, (ViewGroup) parent)) {
                return (ViewGroup) parent;
            }

            int id = activity.getResources().getIdentifier(
                    "action_bar_overlay_layout", "id", activity.getPackageName());
            View candidate = id == 0 ? null : activity.findViewById(id);
            if (candidate instanceof ViewGroup
                    && candidate != activity.getWindow().getDecorView()
                    && isAncestor((ViewGroup) candidate, content)
                    && isMiuixActionBarHost(activity, (ViewGroup) candidate)) {
                return (ViewGroup) candidate;
            }
        } catch (Throwable ignored) {}
        return content;
    }

    private static boolean isMiuixActionBarHost(Activity activity, ViewGroup view) {
        if (view == null) return false;
        String cls = view.getClass().getName().toLowerCase();
        String idName = resourceEntryName(activity, view);
        return cls.contains("miuix.appcompat.internal.app.widget.actionbaroverlaylayout")
                || cls.contains("miuix.appcompat.internal.app.widget.actionbarmovablelayout")
                || "action_bar_overlay_layout".equals(idName);
    }

    private static boolean isAncestor(ViewGroup ancestor, View child) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static String resourceEntryName(Activity activity, View view) {
        if (activity == null || view == null || view.getId() == View.NO_ID || view.getId() == 0) return "";
        try { return activity.getResources().getResourceEntryName(view.getId()).toLowerCase(); }
        catch (Throwable ignored) { return ""; }
    }

    private static void diagnostic(Activity activity, String message) {
        try {
            Object previous = XposedHelpers.getAdditionalInstanceField(activity, GLOBAL_DIAGNOSTIC);
            if (message.equals(previous)) return;
            XposedHelpers.setAdditionalInstanceField(activity, GLOBAL_DIAGNOSTIC, message);
            XposedBridge.log("[HyperBackground] " + activity.getPackageName() + " " + message);
            BackgroundContract.reportDiagnostic(activity, message);
        } catch (Throwable ignored) {}
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
        final List<ActionBarSurface> actionBarSurfaces = new ArrayList<>();
        ViewGroup observedRoot;
        boolean homeMode;
        boolean transparentTopBar;
        Window statusBarWindow;
        int originalStatusBarColor;
        boolean statusBarColorSaved;

        LayerSession(BackgroundMediaView media) { this.media = media; }

        void clear(View view) {
            if (view == null || view == media) return;
            if (!clearedViews.contains(view)) {
                clearedViews.add(view);
                originalBackgrounds.add(view.getBackground());
            }
            if (view.getBackground() != null) view.setBackground(null);
        }

        void attach(final Activity activity, ViewGroup root, boolean home, boolean transparentTopBar) {
            observedRoot = root;
            homeMode = home;
            this.transparentTopBar = transparentTopBar;
            if (transparentTopBar) prepareTransparentStatusBar(activity);
            refresh(activity, home);
        }

        void refresh(Activity activity, boolean home) {
            if (activity == null || observedRoot == null) return;
            clearPageSurfaces(activity, observedRoot, observedRoot, 0);
            if (transparentTopBar) clearActionBarSurfaces(activity, observedRoot, 0);
        }

        void detach() {
            observedRoot = null;
        }

        void restore() {
            for (int i = 0; i < clearedViews.size(); i++) {
                try { clearedViews.get(i).setBackground(originalBackgrounds.get(i)); } catch (Throwable ignored) {}
            }
            clearedViews.clear();
            originalBackgrounds.clear();
            for (ActionBarSurface state : actionBarSurfaces) {
                try {
                    XposedHelpers.callMethod(state.view, "setPrimaryBackground", state.primaryBackground);
                } catch (Throwable ignored) {}
            }
            actionBarSurfaces.clear();
            if (statusBarColorSaved && statusBarWindow != null) {
                try { statusBarWindow.setStatusBarColor(originalStatusBarColor); } catch (Throwable ignored) {}
            }
            statusBarWindow = null;
            statusBarColorSaved = false;
        }

        private void prepareTransparentStatusBar(Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window == null) return;
                if (!statusBarColorSaved) {
                    statusBarWindow = window;
                    originalStatusBarColor = window.getStatusBarColor();
                    statusBarColorSaved = true;
                }
                window.setStatusBarColor(Color.TRANSPARENT);
            } catch (Throwable ignored) {}
        }

        private void clearActionBarSurfaces(Activity activity, View view, int depth) {
            if (view == null || view == media || depth > 8) return;
            String idName = BackgroundApplier.resourceEntryName(activity, view);
            String cls = view.getClass().getName().toLowerCase();

            boolean actionBarView = containsAny(idName,
                    "action_bar_overlay_layout", "action_bar_container", "action_bar", "app_bar",
                    "collapsing_toolbar", "support_action_bar")
                    || cls.contains("actionbaroverlaylayout")
                    || cls.contains("actionbarmovablelayout")
                    || cls.contains("actionbarcontainer")
                    || cls.contains("appbarlayout")
                    || cls.contains("collapsingtoolbarlayout");
            if (actionBarView) {
                clear(view);
                clearMiuixPrimaryBackground(view, cls);
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    clearActionBarSurfaces(activity, group.getChildAt(i), depth + 1);
                }
            }
        }

        private void clearMiuixPrimaryBackground(View view, String className) {
            if (!className.contains("actionbarcontainer")) return;
            ActionBarSurface saved = null;
            for (ActionBarSurface state : actionBarSurfaces) {
                if (state.view == view) { saved = state; break; }
            }
            try {
                Object current = XposedHelpers.callMethod(view, "getPrimaryBackground");
                if (saved == null) {
                    saved = new ActionBarSurface(view, current instanceof Drawable ? (Drawable) current : null);
                    actionBarSurfaces.add(saved);
                }
                if (current != null) XposedHelpers.callMethod(view, "setPrimaryBackground", (Object) null);
            } catch (Throwable ignored) {}
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

            // The supplied Phone/Account/Theme builds split a Miuix page into several
            // full-width host panels instead of one full-height root. Clear those host
            // panels while retaining inset cards and controls.
            boolean externalSettingsPage = BackgroundContract.PACKAGE_PHONE.equals(pkg)
                    || BackgroundContract.PACKAGE_ACCOUNT.equals(pkg)
                    || BackgroundContract.PACKAGE_THEME_MANAGER.equals(pkg)
                    || BackgroundContract.PACKAGE_SECURITY_CENTER.equals(pkg)
                    || BackgroundContract.PACKAGE_POWER_KEEPER.equals(pkg)
                    || BackgroundContract.PACKAGE_MI_SETTINGS.equals(pkg);
            if (externalSettingsPage
                    && view instanceof ViewGroup
                    && depth <= 8
                    && width >= (int) (rootWidth * 0.94f)
                    && height >= (int) (rootHeight * 0.15f)
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
    private static final class ActionBarSurface {
        final View view;
        final Drawable primaryBackground;
        ActionBarSurface(View view, Drawable primaryBackground) {
            this.view = view;
            this.primaryBackground = primaryBackground;
        }
    }
    private static final class DeviceSession {
        final View backgroundView; final int originalVisibility; final BackgroundMediaView media;
        DeviceSession(View backgroundView, int originalVisibility, BackgroundMediaView media) { this.backgroundView = backgroundView; this.originalVisibility = originalVisibility; this.media = media; }
    }
}
