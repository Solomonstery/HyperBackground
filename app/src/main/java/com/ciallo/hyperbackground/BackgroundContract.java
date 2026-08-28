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
    public static final String PACKAGE_CONTACTS = "com.android.contacts";

    private static final String[] SUPPORTED_PACKAGES = new String[] {
            PACKAGE_SETTINGS, PACKAGE_MILINK, PACKAGE_PHONE, PACKAGE_ACCOUNT,
            PACKAGE_THEME_MANAGER, PACKAGE_HOME, PACKAGE_SECURITY_CENTER,
            PACKAGE_POWER_KEEPER, PACKAGE_MI_SETTINGS, PACKAGE_CONTACTS
    };

    public static final String HOME = "home";
    public static final String DEVICE = "device";
    public static final String GLOBAL = "global";
    // 通讯录与拨号（com.android.contacts）主界面背景通道，与 home/device/global 同构。
    public static final String CONTACTS = "contacts";
    // 拨号盘独立背景通道：与 contacts 同构的一条媒体通道，但只注入到拨号盘键盘容器（DialpadLayout）内，
    // 与 contacts 整页背景叠加共存——整页背景照旧，拨号盘弹出时在键盘区额外叠这张图。
    public static final String CONTACTS_DIALPAD = "contacts_dialpad";
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
    // 通讯录与拨号「拨号盘 / 列表适配」：开关开启后清除列表纯黑底、并把拨号盘键盘面板设为半透明。
    public static final String CONTACTS_SURFACE_ADAPT = "contacts_surface_adapt";
    // 拨号盘键盘面板不透明度（0-100，默认 60），仅在适配开关开启时生效。
    public static final String CONTACTS_DIALPAD_OPACITY = "contacts_dialpad_opacity";
    // 拨号盘背景模式：默认（用系统原生拨号盘底、仅按上面的不透明度设 alpha）/ 自定义（叠加用户选的图）。
    public static final String CONTACTS_DIALPAD_BG_MODE = "contacts_dialpad_bg_mode";
    public static final int CONTACTS_DIALPAD_BG_DEFAULT = 0;
    public static final int CONTACTS_DIALPAD_BG_CUSTOM = 1;
    // 拨号盘自定义背景在拨号盘区域内的定位焦点（0=左/上，50=居中，100=右/下），默认居中。
    // 放大时决定取景、缩小时决定摆放位置，让图可在区域内横纵向自由定位。
    public static final String CONTACTS_DIALPAD_FOCUS_X = "contacts_dialpad_focus_x";
    public static final String CONTACTS_DIALPAD_FOCUS_Y = "contacts_dialpad_focus_y";
    // 拨号盘自定义背景缩放大小（1-200，100=等比贴满基准，>100 放大溢出裁切、<100 缩小四周留边）。
    public static final String CONTACTS_DIALPAD_ZOOM = "contacts_dialpad_zoom";
    public static final int CONTACTS_DIALPAD_ZOOM_MIN = 1;
    public static final int CONTACTS_DIALPAD_ZOOM_MAX = 200;
    public static final int CONTACTS_DIALPAD_ZOOM_DEFAULT = 100;
    // 通讯录与拨号进程专属深浅色（与全局强制深浅色独立并存，仅作用于 com.android.contacts 进程）。
    // 三态取值复用 SETTINGS_THEME_FOLLOW/LIGHT/DARK。
    public static final String CONTACTS_THEME_MODE = "contacts_theme_mode";

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
        if (!HOME.equals(slot) && !DEVICE.equals(slot) && !GLOBAL.equals(slot)
                && !CONTACTS.equals(slot) && !CONTACTS_DIALPAD.equals(slot)) {
            throw new IllegalArgumentException("Unknown background slot: " + slot);
        }
        return "background_" + slot + ".bin";
    }

    static Source query(android.content.Context ignored, String slot) {
        SharedPreferences prefs = HookRuntime.preferences();
        long size = prefs.getLong(SIZE_PREFIX + slot, -1L);
        long modified = prefs.getLong(MODIFIED_PREFIX + slot, -1L);
        // 横纵向定位焦点、缩放大小仅对「拨号盘自定义背景」通道生效；其它通道（home/device/global/contacts
        // 整页背景等）必须用中性默认值（焦点居中 + zoom=100=等比贴满不额外缩放），否则调拨号盘的
        // 「缩放/位置」会把这些全局键读进整页背景的 Source，导致整页背景也被一起缩放位移。
        boolean isDialpad = CONTACTS_DIALPAD.equals(slot);
        // 屏幕坐标系定位：横向恒居中铺满，focusX 不再由 UI 控制、恒为 50；纵向偏移由 focusY 决定。
        int focusX = 50;
        int focusY = isDialpad ? prefs.getInt(CONTACTS_DIALPAD_FOCUS_Y, 50) : 50;
        int zoom = isDialpad ? prefs.getInt(CONTACTS_DIALPAD_ZOOM, CONTACTS_DIALPAD_ZOOM_DEFAULT)
                : CONTACTS_DIALPAD_ZOOM_DEFAULT;
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
                prefs.getInt(SETTINGS_THEME_MODE, SETTINGS_THEME_FOLLOW),
                focusX,
                focusY,
                zoom
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
        // 拨号盘自定义背景专用：横纵向定位焦点、缩放大小（其它通道用默认值 50/50/100，行为与旧版一致）。
        final int focusX;
        final int focusY;
        final int zoom;

        Source(String slot, String mime, long size, long modified, boolean exists,
               int opacity, boolean blurEnabled, int blurRadius, int fontMode,
               int deviceLogoMode, String deviceLogoText, int deviceLogoColor, int settingsThemeMode,
               int focusX, int focusY, int zoom) {
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
            this.focusX = Math.max(0, Math.min(100, focusX));
            this.focusY = Math.max(0, Math.min(100, focusY));
            this.zoom = Math.max(CONTACTS_DIALPAD_ZOOM_MIN, Math.min(CONTACTS_DIALPAD_ZOOM_MAX, zoom));
        }

        boolean isVideo() { return mime.startsWith("video/"); }

        ParcelFileDescriptor openFile() throws FileNotFoundException {
            return HookRuntime.openRemoteFile(remoteMediaName(slot));
        }

        String cacheKey() {
            return slot + ':' + mime + ':' + size + ':' + modified + ':' + opacity + ':'
                    + blurEnabled + ':' + blurRadius + ':' + fontMode + ':' + deviceLogoMode + ':'
                    + deviceLogoText + ':' + deviceLogoColor + ':' + settingsThemeMode + ':'
                    + focusX + ':' + focusY + ':' + zoom;
        }
    }
}
