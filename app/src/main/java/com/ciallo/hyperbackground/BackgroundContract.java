package com.ciallo.hyperbackground;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

final class BackgroundContract {
    static final String PACKAGE_SETTINGS = "com.android.settings";
    static final String PACKAGE_MILINK = "com.milink.service";
    static final String PACKAGE_PHONE = "com.android.phone";
    static final String PACKAGE_ACCOUNT = "com.xiaomi.account";
    static final String PACKAGE_THEME_MANAGER = "com.android.thememanager";
    static final String PACKAGE_HOME = "com.miui.home";
    static final String PACKAGE_SECURITY_CENTER = "com.miui.securitycenter";
    static final String PACKAGE_POWER_KEEPER = "com.miui.powerkeeper";

    private static final String[] SUPPORTED_PACKAGES = new String[] {
            PACKAGE_SETTINGS,
            PACKAGE_MILINK,
            PACKAGE_PHONE,
            PACKAGE_ACCOUNT,
            PACKAGE_THEME_MANAGER,
            PACKAGE_HOME,
            PACKAGE_SECURITY_CENTER,
            PACKAGE_POWER_KEEPER
    };

    static final String AUTHORITY = "com.ciallo.hyperbackground.provider";
    static final String HOME = "home";
    static final String DEVICE = "device";
    static final String GLOBAL = "global";
    static final String PREFS = "backgrounds";
    static final String MIME_PREFIX = "mime_";
    static final String OPACITY_PREFIX = "opacity_";
    static final String BLUR_ENABLED_PREFIX = "blur_enabled_";
    static final String BLUR_RADIUS_PREFIX = "blur_radius_";
    static final String FONT_MODE = "font_mode";
    static final String DEVICE_LOGO_MODE = "device_logo_mode";
    static final String DEVICE_LOGO_TEXT = "device_logo_text";
    static final String DEVICE_LOGO_COLOR = "device_logo_color";
    static final String SETTINGS_THEME_MODE = "settings_theme_mode";

    static final String UI_MONET = "ui_monet";
    static final String UI_ACCENT = "ui_accent";
    static final String UI_THEME_MODE = "ui_theme_mode";
    static final String UI_BG_MIME = "ui_bg_mime";
    static final String UI_BG_OPACITY = "ui_bg_opacity";
    static final String UI_BG_BLUR_ENABLED = "ui_bg_blur_enabled";
    static final String UI_BG_BLUR_RADIUS = "ui_bg_blur_radius";
    static final String UI_CARD_OPACITY = "ui_card_opacity";
    static final String UI_SAYING_API = "ui_saying_api";
    static final String UI_SAYING_KEY = "ui_saying_key";
    static final String UI_SCROLL_Y = "ui_scroll_y";

    static final int UI_THEME_FOLLOW = 0;
    static final int UI_THEME_LIGHT = 1;
    static final int UI_THEME_DARK = 2;

    static final int FONT_FOLLOW = 0;
    static final int FONT_LIGHT = 1;
    static final int FONT_DARK = 2;

    static final int DEVICE_LOGO_SYSTEM = 0;
    static final int DEVICE_LOGO_CUSTOM_TEXT = 1;
    static final int DEVICE_LOGO_HIDDEN = 2;

    static final int SETTINGS_THEME_FOLLOW = 0;
    static final int SETTINGS_THEME_LIGHT = 1;
    static final int SETTINGS_THEME_DARK = 2;

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
            }, null, null, null);
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
        } catch (Throwable ignored) {
            return Source.missing(slot, uri);
        } finally {
            if (cursor != null) cursor.close();
        }
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
