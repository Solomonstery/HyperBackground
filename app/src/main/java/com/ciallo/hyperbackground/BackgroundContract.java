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
    // 自定义 LOGO（导入 SVG/XML/图片替换设置「我的设备」LOGO）。
    public static final String LOGO_ENABLED = "logo_enabled";
    public static final String LOGO_MODE = "logo_mode";
    public static final String LOGO_SCALE = "logo_scale";
    public static final String LOGO_MIME = "logo_mime";
    public static final String LOGO_VERSION = "logo_version";
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
    public static final String UI_BOTTOM_BAR_BLUR_ENABLED = "ui_bottom_bar_blur_enabled";
    public static final String UI_FLOATING_BOTTOM_BAR = "ui_floating_bottom_bar";
    public static final String UI_TOP_BLUR_ENABLED = "ui_top_blur_enabled";
    public static final String UI_TOP_BLUR_STRENGTH = "ui_top_blur_strength";
    public static final String UI_SAYING_ENABLED = "ui_saying_enabled";
    public static final String UI_SAYING_API = "ui_saying_api";
    public static final String UI_SAYING_KEY = "ui_saying_key";
    public static final String UI_IGNORED_UPDATE_VERSION = "ui_ignored_update_version";
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
    // 自定义 LOGO 模式：0=系统默认；1=替换并清除周围高级材质；2=保留高级材质（推荐导入 SVG/XML）。
    public static final int LOGO_MODE_SYSTEM = 0;
    public static final int LOGO_MODE_NO_ADVANCED_MATERIAL = 1;
    public static final int LOGO_MODE_KEEP_ADVANCED_MATERIAL = 2;
    public static final String LOGO_REMOTE_NAME = "logo.bin";
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

    /** 读取当前自定义 LOGO 配置（全局单例，与背景 slot 无关）。供 hook 侧 LogoOverride 使用。 */
    static LogoConfig queryLogo() {
        SharedPreferences prefs = HookRuntime.preferences();
        boolean enabled = prefs.getBoolean(LOGO_ENABLED, false);
        int mode = prefs.getInt(LOGO_MODE, LOGO_MODE_KEEP_ADVANCED_MATERIAL);
        int scale = prefs.getInt(LOGO_SCALE, 100);
        long version = prefs.getLong(LOGO_VERSION, 0L);
        String mime = prefs.getString(LOGO_MIME, "application/octet-stream");
        return new LogoConfig(enabled, mode, scale, version, mime);
    }

    static final class LogoConfig {
        final boolean enabled;
        final int mode;
        final int scale;
        final long version;
        final String mime;

        LogoConfig(boolean enabled, int mode, int scale, long version, String mime) {
            this.enabled = enabled;
            this.mode = mode;
            this.scale = Math.max(50, Math.min(200, scale));
            this.version = version;
            this.mime = mime == null ? "application/octet-stream" : mime;
        }

        boolean active() { return enabled && mode != LOGO_MODE_SYSTEM && version > 0L; }

        ParcelFileDescriptor openFile() throws FileNotFoundException {
            return HookRuntime.openRemoteFile(LOGO_REMOTE_NAME);
        }

        String cacheKey() { return enabled + ":" + mode + ":" + scale + ":" + version + ":" + mime; }
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
