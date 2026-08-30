package com.ciallo.hyperbackground.appearance

import android.content.Context
import android.net.Uri
import com.ciallo.hyperbackground.HyperBackgroundApp
import java.io.File
import java.io.FileOutputStream

/**
 * UI 侧的外观配置读写门面。持有 [SettingsAppearanceStore] / [DeviceProfileStore]，
 * 把选中的图片落盘到 filesDir/settings_appearance/<slot>.bin（供 [SettingsAppearanceProvider] 跨进程读取），
 * 并通过 [HyperBackgroundApp.xposedService] 把配置同步写入本地 + 远端 SharedPreferences。
 *
 * 落盘 / 探测 MIME / 导入 / 清除的逻辑严格照搬 HyperChanger 的 MainActivity 实现。
 */
class AppearanceUiController(context: Context) {
    private val appContext = context.applicationContext
    private val appearanceStore = SettingsAppearanceStore(appContext)
    private val deviceProfileStore = DeviceProfileStore(appContext)

    val appearance: SettingsAppearanceSettings get() = appearanceStore.settings
    val deviceProfile: DeviceProfileSettings get() = deviceProfileStore.settings

    private val service get() = HyperBackgroundApp.xposedService

    fun reload() {
        appearanceStore.reload()
        deviceProfileStore.reload()
    }

    fun updateAppearance(transform: (SettingsAppearanceSettings) -> SettingsAppearanceSettings) {
        appearanceStore.update(service, transform)
    }

    fun updateDeviceProfile(transform: (DeviceProfileSettings) -> DeviceProfileSettings) {
        deviceProfileStore.update(service, transform)
    }

    /** 导入图片：落盘 + 记录 MIME/版本号；logo 槽位额外保留高级材质模式。返回是否成功。 */
    fun importAppearance(slot: String, uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val mime = detectAppearanceMime(appContext, uri)
            val target = copyAppearanceFile(appContext, slot, uri)
            appearanceStore.update(service) {
                when (slot) {
                    APPEARANCE_SLOT_HOME -> it.copy(homeMime = mime, homeVersion = target.lastModified())
                    APPEARANCE_SLOT_DEVICE -> it.copy(deviceMime = mime, deviceVersion = target.lastModified())
                    APPEARANCE_SLOT_DEVICE_IMAGE -> it.copy(tutorialCardImageMime = mime, tutorialCardImageVersion = target.lastModified())
                    APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> it.copy(tutorialCardLogoMime = mime, tutorialCardLogoVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> it.copy(tutorialCardBackgroundMime = mime, tutorialCardBackgroundVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> it.copy(style2ImageMime = mime, style2ImageVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> it.copy(style2LogoMime = mime, style2LogoVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> it.copy(style2BackgroundMime = mime, style2BackgroundVersion = target.lastModified())
                    else -> it.copy(logoMime = mime, logoVersion = target.lastModified(), logoMode = LOGO_MODE_KEEP_ADVANCED_MATERIAL)
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 清除图片：删除落盘文件并复位对应 MIME/版本号；home/device/style1 机型图片会同时关闭对应开关。 */
    fun clearAppearance(slot: String) {
        runCatching {
            appearanceFile(appContext, slot).delete()
            appearanceStore.update(service) {
                when (slot) {
                    APPEARANCE_SLOT_HOME -> it.copy(homeEnabled = false, homeMime = "", homeVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_DEVICE -> it.copy(deviceEnabled = false, deviceMime = "", deviceVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_DEVICE_IMAGE -> it.copy(tutorialCardImageMime = "", tutorialCardImageVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> it.copy(tutorialCardLogoMime = "", tutorialCardLogoVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> it.copy(tutorialCardBackgroundMime = "", tutorialCardBackgroundVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> it.copy(style2ImageMime = "", style2ImageVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> it.copy(style2LogoMime = "", style2LogoVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> it.copy(style2BackgroundMime = "", style2BackgroundVersion = System.currentTimeMillis())
                    else -> it.copy(logoMime = "", logoVersion = System.currentTimeMillis(), logoMode = LOGO_MODE_SYSTEM)
                }
            }
        }
    }

    private fun appearanceFile(context: Context, slot: String): File =
        File(File(context.filesDir, "settings_appearance"), "$slot.bin")

    private fun copyAppearanceFile(context: Context, slot: String, uri: Uri): File {
        val directory = File(context.filesDir, "settings_appearance")
        check(directory.exists() || directory.mkdirs()) { "无法创建配置目录" }
        val target = appearanceFile(context, slot)
        val temporary = File(directory, "$slot.bin.tmp")
        if (temporary.exists()) temporary.delete()
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 200L * 1024L * 1024L) { "文件不能超过200MB" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        check(!target.exists() || target.delete()) { "无法替换旧文件" }
        check(temporary.renameTo(target)) { "无法保存文件" }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    private fun detectAppearanceMime(context: Context, uri: Uri): String {
        val reported = context.contentResolver.getType(uri).orEmpty()
        if (reported.isNotBlank() && reported != "application/octet-stream") return reported
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).orEmpty() else ""
            }
        }.getOrDefault("").orEmpty().lowercase()
        return when {
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".xml") -> "application/xml"
            reported.isNotBlank() -> reported
            else -> "application/octet-stream"
        }
    }
}
