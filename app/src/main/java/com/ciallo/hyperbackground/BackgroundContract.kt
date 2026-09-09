package com.ciallo.hyperbackground

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

object BackgroundContract {
    const val PACKAGE_SETTINGS = "com.android.settings"
    const val PACKAGE_MILINK = "com.milink.service"
    const val PACKAGE_PHONE = "com.android.phone"
    const val PACKAGE_ACCOUNT = "com.xiaomi.account"
    const val PACKAGE_THEME_MANAGER = "com.android.thememanager"
    const val PACKAGE_HOME = "com.miui.home"
    const val PACKAGE_SECURITY_CENTER = "com.miui.securitycenter"
    const val PACKAGE_POWER_KEEPER = "com.miui.powerkeeper"
    const val PACKAGE_MI_SETTINGS = "com.xiaomi.misettings"
    const val PACKAGE_CONTACTS = "com.android.contacts"

    private val SUPPORTED_PACKAGES = arrayOf(
        PACKAGE_SETTINGS, PACKAGE_MILINK, PACKAGE_PHONE, PACKAGE_ACCOUNT,
        PACKAGE_THEME_MANAGER, PACKAGE_HOME, PACKAGE_SECURITY_CENTER,
        PACKAGE_POWER_KEEPER, PACKAGE_MI_SETTINGS, PACKAGE_CONTACTS,
    )

    const val HOME = "home"
    const val DEVICE = "device"
    const val GLOBAL = "global"
    // 通讯录与拨号（com.android.contacts）主界面背景通道，与 home/device/global 同构。
    const val CONTACTS = "contacts"
    // 拨号盘独立背景通道：与 contacts 同构的一条媒体通道，但只注入到拨号盘键盘容器（DialpadLayout）内，
    // 与 contacts 整页背景叠加共存——整页背景照旧，拨号盘弹出时在键盘区额外叠这张图。
    const val CONTACTS_DIALPAD = "contacts_dialpad"
    const val PREFS = "backgrounds"
    const val MIME_PREFIX = "mime_"
    const val SIZE_PREFIX = "size_"
    const val MODIFIED_PREFIX = "modified_"
    const val OPACITY_PREFIX = "opacity_"
    const val BLUR_ENABLED_PREFIX = "blur_enabled_"
    const val BLUR_RADIUS_PREFIX = "blur_radius_"
    // 背景亮度（每通道独立，键为 brightness_<slot>）：100=原图，<100 变暗，>100 提亮，范围 0-200。
    const val BRIGHTNESS_PREFIX = "brightness_"
    const val BRIGHTNESS_MIN = 0
    const val BRIGHTNESS_MAX = 200
    const val BRIGHTNESS_DEFAULT = 100
    const val FONT_MODE = "font_mode"
    internal const val DEVICE_LOGO_MODE = "device_logo_mode"
    internal const val DEVICE_LOGO_TEXT = "device_logo_text"
    internal const val DEVICE_LOGO_COLOR = "device_logo_color"
    const val SETTINGS_THEME_MODE = "settings_theme_mode"
    // 通讯录与拨号「拨号盘 / 列表适配」：开关开启后清除列表纯黑底、并把拨号盘键盘面板设为半透明。
    const val CONTACTS_SURFACE_ADAPT = "contacts_surface_adapt"
    // 拨号盘键盘面板不透明度（0-100，默认 60），仅在适配开关开启时生效。
    const val CONTACTS_DIALPAD_OPACITY = "contacts_dialpad_opacity"
    // 拨号盘背景模式：默认（用系统原生拨号盘底、仅按上面的不透明度设 alpha）/ 自定义（叠加用户选的图）。
    const val CONTACTS_DIALPAD_BG_MODE = "contacts_dialpad_bg_mode"
    const val CONTACTS_DIALPAD_BG_DEFAULT = 0
    const val CONTACTS_DIALPAD_BG_CUSTOM = 1
    // 拨号盘自定义背景在拨号盘区域内的定位焦点（0=左/上，50=居中，100=右/下），默认居中。
    // 放大时决定取景、缩小时决定摆放位置，让图可在区域内横纵向自由定位。
    const val CONTACTS_DIALPAD_FOCUS_X = "contacts_dialpad_focus_x"
    const val CONTACTS_DIALPAD_FOCUS_Y = "contacts_dialpad_focus_y"
    // 拨号盘自定义背景缩放大小（1-200，100=等比贴满基准，>100 放大溢出裁切、<100 缩小四周留边）。
    const val CONTACTS_DIALPAD_ZOOM = "contacts_dialpad_zoom"
    const val CONTACTS_DIALPAD_ZOOM_MIN = 1
    const val CONTACTS_DIALPAD_ZOOM_MAX = 200
    const val CONTACTS_DIALPAD_ZOOM_DEFAULT = 100
    // 设置主页背景缩放/定位：以整页 CENTER_CROP 为基准，且与拨号盘参数完全隔离。
    const val HOME_ZOOM = "home_zoom"
    const val HOME_FOCUS_X = "home_focus_x"
    const val HOME_FOCUS_Y = "home_focus_y"
    // 通讯录与拨号进程专属深浅色（与全局强制深浅色独立并存，仅作用于 com.android.contacts 进程）。
    // 三态取值复用 SETTINGS_THEME_FOLLOW/LIGHT/DARK。
    const val CONTACTS_THEME_MODE = "contacts_theme_mode"

    const val UI_MONET = "ui_monet"
    const val UI_THEME_COLOR_ENABLED = "ui_theme_color_enabled"
    const val UI_ACCENT = "ui_accent"
    const val UI_THEME_MODE = "ui_theme_mode"
    const val UI_BG_MIME = "ui_bg_mime"
    const val UI_BG_OPACITY = "ui_bg_opacity"
    const val UI_BG_BLUR_ENABLED = "ui_bg_blur_enabled"
    const val UI_BG_BLUR_RADIUS = "ui_bg_blur_radius"
    const val UI_CARD_OPACITY = "ui_card_opacity"
    const val UI_BOTTOM_BAR_BLUR_ENABLED = "ui_bottom_bar_blur_enabled"
    const val UI_FLOATING_BOTTOM_BAR = "ui_floating_bottom_bar"
    const val UI_TOP_BLUR_ENABLED = "ui_top_blur_enabled"
    const val UI_TOP_BLUR_STRENGTH = "ui_top_blur_strength"
    const val UI_TOP_BLUR_OPACITY = "ui_top_blur_opacity"
    // 清除设置主页顶栏遮罩。只作用于 MiuiSettings 首页；开启时首页清除优先，
    // 其它设置二级页仍可继续使用全局顶栏模糊。
    const val UI_TOP_CLEAR_ENABLED = "ui_top_clear_enabled"
    const val UI_SAYING_ENABLED = "ui_saying_enabled"
    const val UI_SAYING_API = "ui_saying_api"
    const val UI_SAYING_KEY = "ui_saying_key"
    // 随机背景（API 拉取）：与用户手动设置的背景独立存储（<slot>.random.bin），开关切换互不覆盖。
    // 总开关关闭或某槽位未勾选时，hook 侧仍读手动背景文件，手动图永远保留。
    const val UI_RANDOM_BG_ENABLED = "ui_random_bg_enabled"
    const val UI_RANDOM_BG_API = "ui_random_bg_api"
    // 主类别（acg/landscape/anime/pc_wallpaper/mobile_wallpaper/general_anime/ai_drawing/bq/furry），空=全随机。
    const val UI_RANDOM_BG_CATEGORY = "ui_random_bg_category"
    // 勾选生效的槽位集合（home/device/global/contacts/contacts_dialpad/ui），默认空。
    const val UI_RANDOM_BG_SLOTS = "ui_random_bg_slots"
    // 触发模式：0=仅手动按钮，1=仅开机自动，2=手动+开机。
    const val UI_RANDOM_BG_MODE = "ui_random_bg_mode"
    const val RANDOM_BG_MODE_MANUAL = 0
    const val RANDOM_BG_MODE_BOOT = 1
    const val RANDOM_BG_MODE_BOTH = 2
    // 槽位集合中表示「模块自身 UI 背景」的特殊值（系统槽位用 home/device/... 原名）。
    const val RANDOM_SLOT_UI = "ui"
    // random 文件元数据前缀，与手动 MIME_PREFIX/SIZE_PREFIX/MODIFIED_PREFIX 完全隔离。
    const val RANDOM_MIME_PREFIX = "random_mime_"
    const val RANDOM_SIZE_PREFIX = "random_size_"
    const val RANDOM_MODIFIED_PREFIX = "random_modified_"
    // 模块自身 UI 背景的 random 文件 mime（UI 背景不经过 libxposed remote，仅模块进程内读取）。
    const val UI_RANDOM_BG_UI_MIME = "ui_random_bg_ui_mime"
    const val UI_IGNORED_UPDATE_VERSION = "ui_ignored_update_version"
    internal const val UI_SCROLL_Y = "ui_scroll_y"
    const val UI_THEME_FOLLOW = 0
    const val UI_THEME_LIGHT = 1
    const val UI_THEME_DARK = 2
    const val FONT_FOLLOW = 0
    const val FONT_LIGHT = 1
    const val FONT_DARK = 2
    internal const val DEVICE_LOGO_SYSTEM = 0
    internal const val DEVICE_LOGO_CUSTOM_TEXT = 1
    internal const val DEVICE_LOGO_HIDDEN = 2
    const val SETTINGS_THEME_FOLLOW = 0
    const val SETTINGS_THEME_LIGHT = 1
    const val SETTINGS_THEME_DARK = 2

    internal fun isSupportedPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        for (supported in SUPPORTED_PACKAGES) {
            if (supported == packageName) return true
        }
        return false
    }

    fun remoteMediaName(slot: String, random: Boolean = false): String {
        if (slot != HOME && slot != DEVICE && slot != GLOBAL &&
            slot != CONTACTS && slot != CONTACTS_DIALPAD
        ) {
            throw IllegalArgumentException("Unknown background slot: $slot")
        }
        // random 背景用独立 remote 文件名，与用户手动背景 background_<slot>.bin 互不覆盖。
        return if (random) "background_$slot.random.bin" else "background_$slot.bin"
    }

    internal fun query(ignored: Context?, slot: String): Source {
        val prefs = HookRuntime.preferences()
        // 随机背景开关：开启且该槽位被勾选、且 random 文件已下载时读 random 文件，
        // 否则回退用户手动设置的文件（避免开了开关但还没换图时背景空白）。
        // 显示参数（透明度/模糊/亮度/缩放/焦点）两套图共用，仍按 slot 读取。
        val randomOn = prefs.getBoolean(UI_RANDOM_BG_ENABLED, false)
        val randomSlots = prefs.getStringSet(UI_RANDOM_BG_SLOTS, emptySet()) ?: emptySet()
        val randomSize = prefs.getLong(RANDOM_SIZE_PREFIX + slot, -1L)
        val useRandom = randomOn && randomSlots.contains(slot) && randomSize >= 0L
        val mimePrefix = if (useRandom) RANDOM_MIME_PREFIX else MIME_PREFIX
        val sizePrefix = if (useRandom) RANDOM_SIZE_PREFIX else SIZE_PREFIX
        val modifiedPrefix = if (useRandom) RANDOM_MODIFIED_PREFIX else MODIFIED_PREFIX
        val size = prefs.getLong(sizePrefix + slot, -1L)
        val modified = prefs.getLong(modifiedPrefix + slot, -1L)
        // 横纵向定位焦点、缩放大小按通道分别读取，避免拨号盘与设置主页互相污染。
        // 其它通道保持当前使用的中性 50/50/100 参数。
        val isDialpad = CONTACTS_DIALPAD == slot
        val isHome = HOME == slot
        val focusX = if (isHome) prefs.getInt(HOME_FOCUS_X, 50) else 50
        val focusY = when {
            isDialpad -> prefs.getInt(CONTACTS_DIALPAD_FOCUS_Y, 50)
            isHome -> prefs.getInt(HOME_FOCUS_Y, 50)
            else -> 50
        }
        val zoom = when {
            isDialpad -> prefs.getInt(CONTACTS_DIALPAD_ZOOM, CONTACTS_DIALPAD_ZOOM_DEFAULT)
            isHome -> prefs.getInt(HOME_ZOOM, CONTACTS_DIALPAD_ZOOM_DEFAULT)
            else -> CONTACTS_DIALPAD_ZOOM_DEFAULT
        }
        val brightness = prefs.getInt(BRIGHTNESS_PREFIX + slot, BRIGHTNESS_DEFAULT)
        return Source(
            slot,
            prefs.getString(mimePrefix + slot, "application/octet-stream"),
            size,
            modified,
            size >= 0L,
            prefs.getInt(OPACITY_PREFIX + slot, 100),
            prefs.getBoolean(BLUR_ENABLED_PREFIX + slot, false),
            prefs.getInt(BLUR_RADIUS_PREFIX + slot, 20),
            prefs.getInt(FONT_MODE, FONT_FOLLOW),
            prefs.getInt(DEVICE_LOGO_MODE, DEVICE_LOGO_SYSTEM),
            prefs.getString(DEVICE_LOGO_TEXT, "HyperOS"),
            prefs.getInt(DEVICE_LOGO_COLOR, 0xFF111111.toInt()),
            prefs.getInt(SETTINGS_THEME_MODE, SETTINGS_THEME_FOLLOW),
            focusX,
            focusY,
            zoom,
            brightness,
            useRandom,
        )
    }

    internal fun reportDiagnostic(ignored: Context?, message: String?) {
        if (message != null) HookRuntime.log(message)
    }

    internal class Source(
        val slot: String,
        mime: String?,
        val size: Long,
        val modified: Long,
        val exists: Boolean,
        opacity: Int,
        val blurEnabled: Boolean,
        blurRadius: Int,
        val fontMode: Int,
        val deviceLogoMode: Int,
        deviceLogoText: String?,
        val deviceLogoColor: Int,
        val settingsThemeMode: Int,
        focusX: Int,
        focusY: Int,
        zoom: Int,
        brightness: Int,
        // true=当前应渲染 random 背景（读 background_<slot>.random.bin）；false=用户手动背景。
        val random: Boolean = false,
    ) {
        val mime: String = mime ?: "application/octet-stream"
        val opacity: Int = opacity.coerceIn(0, 100)
        val blurRadius: Int = blurRadius.coerceIn(0, 80)
        val deviceLogoText: String = deviceLogoText ?: "HyperOS"
        // 拨号盘自定义背景专用：横纵向定位焦点、缩放大小（其它通道用默认值 50/50/100，行为与旧版一致）。
        val focusX: Int = focusX.coerceIn(0, 100)
        val focusY: Int = focusY.coerceIn(0, 100)
        val zoom: Int = zoom.coerceIn(CONTACTS_DIALPAD_ZOOM_MIN, CONTACTS_DIALPAD_ZOOM_MAX)
        // 背景亮度（每通道独立）：100=原图，<100 变暗，>100 提亮。
        val brightness: Int = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)

        fun isVideo(): Boolean = mime.startsWith("video/")

        @Throws(FileNotFoundException::class)
        fun openFile(): ParcelFileDescriptor = HookRuntime.openRemoteFile(remoteMediaName(slot, random))

        fun cacheKey(): String {
            return "$slot:$random:$mime:$size:$modified:$opacity:" +
                "$blurEnabled:$blurRadius:$fontMode:$deviceLogoMode:" +
                "$deviceLogoText:$deviceLogoColor:$settingsThemeMode:" +
                "$focusX:$focusY:$zoom:$brightness"
        }
    }
}
