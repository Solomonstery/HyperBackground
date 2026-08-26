package com.ciallo.hyperbackground;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

public final class BackgroundContract {
    public static final String PACKAGE_SETTINGS = "com.android.settings";
    public static final String PACKAGE_MILINK = "com.milink.service";
    public static final String PACKAGE_PHONE = "com.android.phone";
    public static final String PACKAGE_ACCOUNT = "com.xiaomi.account";
    public static final String PACKAGE_THEME_MANAGER = "com.android.thememanager";
    public static final String PACKAGE_HOME = "com.miui.home";
    public static final String PACKAGE_SECURITY_CENTER = "com.miui.securitycenter";
    public static final String PACKAGE_POWER_KEEPER = "com.miui.powerkeeper";
    public static final String PACKAGE_MI_SETTINGS = "com.xiaomi.misettings";

    private static final String[] SUPPORTED_PACKAGES = new String[] {
            PACKAGE_SETTINGS,
            PACKAGE_MILINK,
            PACKAGE_PHONE,
            PACKAGE_ACCOUNT,
            PACKAGE_THEME_MANAGER,
            PACKAGE_HOME,
            PACKAGE_SECURITY_CENTER,
            PACKAGE_POWER_KEEPER,
            PACKAGE_MI_SETTINGS
    };

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".provider";
    public static final String HOME = "home";
    public static final String DEVICE = "device";
    public static final String GLOBAL = "global";
    public static final String PREFS = "backgrounds";
    public static final String MIME_PREFIX = "mime_";
    public static final String OPACITY_PREFIX = "opacity_";
    public static final String BLUR_ENABLED_PREFIX = "blur_enabled_";
    public static final String BLUR_RADIUS_PREFIX = "blur_radius_";
    public static final String FONT_MODE = "font_mode";
    static final String DEVICE_LOGO_MODE = "device_logo_mode";
    static final String DEVICE_LOGO_TEXT = "device_logo_text";
    static final String DEVICE_LOGO_COLOR = "device_logo_color";
    public static final String SETTINGS_THEME_MODE = "settings_theme_mode";

    public static final String UI_MONET = "ui_monet";
    public static final String UI_THEME_COLOR_ENABLED = "ui_theme_color_enabled";
    public static final String UI_ACCENT = "ui_accent";
    public static final String UI_THEME_MODE = "ui_theme_mode";
    public static final String UI_BG_MIME = "ui_bg_mime";
    public static final String UI_BG_OPACITY = "ui_bg_opacity";
    public static final String UI_BG_BLUR_ENABLED = "ui_bg_blur_enabled";
    public static final String UI_BG_BLUR_RADIUS = "ui_bg_blur_radius";
    public static final String UI_CARD_OPACITY = "ui_card_opacity";
    public static final String UI_SAYING_API = "ui_saying_api";
    public static final String UI_SAYING_KEY = "ui_saying_key";
    static final String UI_SCROLL_Y = "ui_scroll_y";
    public static final String DIAGNOSTIC_QUERY_PREFIX = "diagnostic_query_";
    public static final String DIAGNOSTIC_SLOT_PREFIX = "diagnostic_slot_";
    public static final String DIAGNOSTIC_ACTIVITY_PREFIX = "diagnostic_activity_";
    public static final String DIAGNOSTIC_RENDER_PREFIX = "diagnostic_render_";
    static final String METHOD_REPORT_DIAGNOSTIC = "report_diagnostic";
    static final String EXTRA_DIAGNOSTIC_MESSAGE = "message";

    public static final int UI_THEME_FOLLOW = 0;
    public static final int UI_THEME_LIGHT = 1;
    public static final int UI_THEME_DARK = 2;

    public static final int FONT_FOLLOW = 0;
    public static final int FONT_LIGHT = 1;
    public static final int FONT_DARK = 2;

    static final int DEVICE_LOGO_SYSTEM = 0;
    static final int DEVICE_LOGO_CUSTOM_TEXT = 1;
    static final int DEVICE_LOGO_HIDDEN = 2;

    public static final int SETTINGS_THEME_FOLLOW = 0;
    public static final int SETTINGS_THEME_LIGHT = 1;
    public static final int SETTINGS_THEME_DARK = 2;

    static final String COLUMN_MIME = "mime_type";
    static final String COLUMN_SIZE = "_size";
    static final String COLUMN_MODIFIED = "last_modified";
    static final String COLUMN_OPACITY = "opacity";
    static final String COLUMN_BLUR_ENABLED = "blur_enabled";
    static final String COLUMN_BLUR_RADIUS = "blur_radius";
    static final String COLUMN_FONT_MODE = "font_mode";
    static final String COLUMN_DEVICE_LOGO_MODE = "device_logo_mode";
    static final String COLUMN_DEVICE_LOGO_TEXT = "device_logo_text";
    static final String COLUMN_DEVICE_LOGO_COLOR = "device_logo_color";
    static final String COLUMN_SETTINGS_THEME_MODE = "settings_theme_mode";

    private BackgroundContract() {}

    static boolean isSupportedPackage(String packageName) {
        if (packageName == null) return false;
        for (String supported : SUPPORTED_PACKAGES) {
            if (supported.equals(packageName)) return true;
        }
        return false;
    }

    static Uri uri(String slot) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY).appendPath(slot).build();
    }

    static Source query(Context context, String slot) {
        Uri uri = uri(slot);
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{
                    COLUMN_MIME, COLUMN_SIZE, COLUMN_MODIFIED, COLUMN_OPACITY,
                    COLUMN_BLUR_ENABLED, COLUMN_BLUR_RADIUS, COLUMN_FONT_MODE,
                    COLUMN_DEVICE_LOGO_MODE, COLUMN_DEVICE_LOGO_TEXT, COLUMN_DEVICE_LOGO_COLOR, COLUMN_SETTINGS_THEME_MODE
            }, context.getClass().getName(), null, null);
            if (cursor == null || !cursor.moveToFirst()) return Source.missing(slot, uri);
            String mime = string(cursor, COLUMN_MIME, "application/octet-stream");
            long size = longValue(cursor, COLUMN_SIZE, -1L);
            long modified = longValue(cursor, COLUMN_MODIFIED, -1L);
            int opacity = intValue(cursor, COLUMN_OPACITY, 100);
            boolean blur = intValue(cursor, COLUMN_BLUR_ENABLED, 0) != 0;
            int blurRadius = intValue(cursor, COLUMN_BLUR_RADIUS, 20);
            int fontMode = intValue(cursor, COLUMN_FONT_MODE, FONT_FOLLOW);
            int deviceLogoMode = intValue(cursor, COLUMN_DEVICE_LOGO_MODE, DEVICE_LOGO_SYSTEM);
            String deviceLogoText = string(cursor, COLUMN_DEVICE_LOGO_TEXT, "HyperOS");
            int deviceLogoColor = intValue(cursor, COLUMN_DEVICE_LOGO_COLOR, 0xFF111111);
            int settingsThemeMode = intValue(cursor, COLUMN_SETTINGS_THEME_MODE, SETTINGS_THEME_FOLLOW);
            boolean exists = size >= 0L;
            return new Source(slot, uri, mime, size, modified, exists,
                    opacity, blur, blurRadius, fontMode, deviceLogoMode, deviceLogoText, deviceLogoColor, settingsThemeMode);
        } catch (Throwable error) {
            Log.e("HyperBackground", "Cannot query " + slot + " background from "
                    + context.getPackageName() + " via " + AUTHORITY, error);
            return Source.missing(slot, uri);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    static void reportDiagnostic(Context context, String message) {
        if (context == null || message == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_DIAGNOSTIC_MESSAGE, message);
            context.getContentResolver().call(uri(GLOBAL), METHOD_REPORT_DIAGNOSTIC, null, extras);
        } catch (Throwable ignored) {}
    }

    private static String string(Cursor c, String name, String fallback) {
        int i = c.getColumnIndex(name); return i >= 0 ? c.getString(i) : fallback;
    }
    private static int intValue(Cursor c, String name, int fallback) {
        int i = c.getColumnIndex(name); return i >= 0 ? c.getInt(i) : fallback;
    }
    private static long longValue(Cursor c, String name, long fallback) {
        int i = c.getColumnIndex(name); return i >= 0 ? c.getLong(i) : fallback;
    }

    static final class Source {
        final String slot; final Uri uri; final String mime; final long size; final long modified;
        final boolean exists; final int opacity; final boolean blurEnabled; final int blurRadius;
        final int fontMode; final int deviceLogoMode; final String deviceLogoText; final int deviceLogoColor; final int settingsThemeMode;
        Source(String slot, Uri uri, String mime, long size, long modified, boolean exists,
               int opacity, boolean blurEnabled, int blurRadius, int fontMode,
               int deviceLogoMode, String deviceLogoText, int deviceLogoColor, int settingsThemeMode) {
            this.slot = slot; this.uri = uri; this.mime = mime == null ? "application/octet-stream" : mime;
            this.size = size; this.modified = modified; this.exists = exists;
            this.opacity = Math.max(0, Math.min(100, opacity));
            this.blurEnabled = blurEnabled; this.blurRadius = Math.max(0, Math.min(80, blurRadius));
            this.fontMode = fontMode; this.deviceLogoMode = deviceLogoMode;
            this.deviceLogoText = deviceLogoText == null ? "HyperOS" : deviceLogoText;
            this.deviceLogoColor = deviceLogoColor; this.settingsThemeMode = settingsThemeMode;
        }
        static Source missing(String slot, Uri uri) {
            return new Source(slot, uri, "", -1L, -1L, false, 100, false, 20, FONT_FOLLOW,
                    DEVICE_LOGO_SYSTEM, "HyperOS", 0xFF111111, SETTINGS_THEME_FOLLOW);
        }
        boolean isVideo() { return mime.startsWith("video/"); }
        String cacheKey() {
            return slot + ':' + mime + ':' + size + ':' + modified + ':' + opacity + ':'
                    + blurEnabled + ':' + blurRadius + ':' + fontMode + ':' + deviceLogoMode + ':'
                    + deviceLogoText + ':' + deviceLogoColor + ':' + settingsThemeMode;
        }
    }
}
