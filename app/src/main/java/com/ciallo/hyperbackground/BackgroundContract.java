package com.ciallo.hyperbackground;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

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
            PACKAGE_SETTINGS, PACKAGE_MILINK, PACKAGE_PHONE, PACKAGE_ACCOUNT,
            PACKAGE_THEME_MANAGER, PACKAGE_HOME, PACKAGE_SECURITY_CENTER,
            PACKAGE_POWER_KEEPER, PACKAGE_MI_SETTINGS
    };

    public static final String HOME = "home";
    public static final String DEVICE = "device";
    public static final String GLOBAL = "global";
    public static final String PREFS = "backgrounds";
    public static final String MIME_PREFIX = "mime_";
    public static final String SIZE_PREFIX = "size_";
    public static final String MODIFIED_PREFIX = "modified_";
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
    public static final String UI_SAYING_ENABLED = "ui_saying_enabled";
    public static final String UI_SAYING_API = "ui_saying_api";
    public static final String UI_SAYING_KEY = "ui_saying_key";
    static final String UI_SCROLL_Y = "ui_scroll_y";
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

    private BackgroundContract() {}

    static boolean isSupportedPackage(String packageName) {
        if (packageName == null) return false;
        for (String supported : SUPPORTED_PACKAGES) {
            if (supported.equals(packageName)) return true;
        }
        return false;
    }

    public static String remoteMediaName(String slot) {
        if (!HOME.equals(slot) && !DEVICE.equals(slot) && !GLOBAL.equals(slot)) {
            throw new IllegalArgumentException("Unknown background slot: " + slot);
        }
        return "background_" + slot + ".bin";
    }

    static Source query(android.content.Context ignored, String slot) {
        SharedPreferences prefs = HookRuntime.preferences();
        long size = prefs.getLong(SIZE_PREFIX + slot, -1L);
        long modified = prefs.getLong(MODIFIED_PREFIX + slot, -1L);
        return new Source(
                slot,
                prefs.getString(MIME_PREFIX + slot, "application/octet-stream"),
                size,
                modified,
                size >= 0L,
                prefs.getInt(OPACITY_PREFIX + slot, 100),
                prefs.getBoolean(BLUR_ENABLED_PREFIX + slot, false),
                prefs.getInt(BLUR_RADIUS_PREFIX + slot, 20),
                prefs.getInt(FONT_MODE, FONT_FOLLOW),
                prefs.getInt(DEVICE_LOGO_MODE, DEVICE_LOGO_SYSTEM),
                prefs.getString(DEVICE_LOGO_TEXT, "HyperOS"),
                prefs.getInt(DEVICE_LOGO_COLOR, 0xFF111111),
                prefs.getInt(SETTINGS_THEME_MODE, SETTINGS_THEME_FOLLOW)
        );
    }

    static void reportDiagnostic(android.content.Context ignored, String message) {
        if (message != null) HookRuntime.log(message);
    }

    static final class Source {
        final String slot;
        final String mime;
        final long size;
        final long modified;
        final boolean exists;
        final int opacity;
        final boolean blurEnabled;
        final int blurRadius;
        final int fontMode;
        final int deviceLogoMode;
        final String deviceLogoText;
        final int deviceLogoColor;
        final int settingsThemeMode;

        Source(String slot, String mime, long size, long modified, boolean exists,
               int opacity, boolean blurEnabled, int blurRadius, int fontMode,
               int deviceLogoMode, String deviceLogoText, int deviceLogoColor, int settingsThemeMode) {
            this.slot = slot;
            this.mime = mime == null ? "application/octet-stream" : mime;
            this.size = size;
            this.modified = modified;
            this.exists = exists;
            this.opacity = Math.max(0, Math.min(100, opacity));
            this.blurEnabled = blurEnabled;
            this.blurRadius = Math.max(0, Math.min(80, blurRadius));
            this.fontMode = fontMode;
            this.deviceLogoMode = deviceLogoMode;
            this.deviceLogoText = deviceLogoText == null ? "HyperOS" : deviceLogoText;
            this.deviceLogoColor = deviceLogoColor;
            this.settingsThemeMode = settingsThemeMode;
        }

        boolean isVideo() { return mime.startsWith("video/"); }

        ParcelFileDescriptor openFile() throws FileNotFoundException {
            return HookRuntime.openRemoteFile(remoteMediaName(slot));
        }

        String cacheKey() {
            return slot + ':' + mime + ':' + size + ':' + modified + ':' + opacity + ':'
                    + blurEnabled + ':' + blurRadius + ':' + fontMode + ':' + deviceLogoMode + ':'
                    + deviceLogoText + ':' + deviceLogoColor + ':' + settingsThemeMode;
        }
    }
}
