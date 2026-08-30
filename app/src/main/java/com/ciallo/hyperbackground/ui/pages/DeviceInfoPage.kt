package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_LOGO
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_SYSTEM
import com.ciallo.hyperbackground.appearance.DeviceProfileSettings
import com.ciallo.hyperbackground.appearance.LOGO_MODE_SYSTEM
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.SliderWithInputPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「设备信息覆盖」二级页（仅 C-1 显示覆盖）。照搬 HyperChanger 的 SystemSettings + DeviceProfileEditor：
 * - 设置应用：启用开关、骁龙图标开关；样式为系统默认时可选自定义 LOGO 并导入/缩放。
 * - 设备信息各字段的文本覆盖（基础参数 / 全部参数与信息 / 我的设备影像参数）。
 *
 * 覆盖值仅改变设置页面的显示，不修改任何系统属性。
 */
@Composable
fun DeviceInfoPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val profile = activity.deviceProfile
    val appearance = activity.appearance
    val updateProfile: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit = { transform ->
        activity.updateDeviceProfile(transform)
    }

    // 字段映射严格对应 HyperChanger DeviceProfileEditor 的顺序与 setter。
    val fields = listOf(
        stringResource(R.string.device_field_model) to profile.model,
        stringResource(R.string.device_field_processor) to profile.processor,
        stringResource(R.string.device_field_ram) to profile.ram,
        stringResource(R.string.device_field_battery) to profile.battery,
        stringResource(R.string.device_field_screen_size) to profile.screenSize,
        stringResource(R.string.device_field_resolution) to profile.resolution,
        stringResource(R.string.device_field_camera_rear) to profile.cameraRear,
        stringResource(R.string.device_field_camera_front) to profile.cameraFront,
        stringResource(R.string.device_field_camera) to profile.camera,
        stringResource(R.string.device_field_os_version) to profile.osVersion,
        stringResource(R.string.device_field_android_version) to profile.androidVersion,
        stringResource(R.string.device_field_storage) to profile.storage,
        stringResource(R.string.device_field_kernel) to profile.kernel,
        stringResource(R.string.device_field_baseband) to profile.baseband,
        stringResource(R.string.device_field_hardware) to profile.hardware,
    )
    val setters: List<(DeviceProfileSettings, String) -> DeviceProfileSettings> = listOf(
        { p, v -> p.copy(model = v) }, { p, v -> p.copy(processor = v) },
        { p, v -> p.copy(ram = v) }, { p, v -> p.copy(battery = v) },
        { p, v -> p.copy(screenSize = v) }, { p, v -> p.copy(resolution = v) },
        { p, v -> p.copy(cameraRear = v) }, { p, v -> p.copy(cameraFront = v) },
        { p, v -> p.copy(camera = v) }, { p, v -> p.copy(osVersion = v) },
        { p, v -> p.copy(androidVersion = v) }, { p, v -> p.copy(storage = v) },
        { p, v -> p.copy(kernel = v) }, { p, v -> p.copy(baseband = v) },
        { p, v -> p.copy(hardware = v) },
    )

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle(stringResource(R.string.device_info_group_settings)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.device_info_enable),
                    checked = profile.enabled,
                    onCheckedChange = { enabled -> updateProfile { it.copy(enabled = enabled) } },
                )
                SwitchPreference(
                    title = stringResource(R.string.device_info_snapdragon),
                    checked = profile.snapdragonIcon,
                    onCheckedChange = { enabled -> updateProfile { it.copy(snapdragonIcon = enabled) } },
                )
                // 仅系统默认设备界面样式下暴露自定义 LOGO（与 HyperChanger 一致）。
                if (appearance.deviceInterfaceStyle == DEVICE_INTERFACE_STYLE_SYSTEM) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.logo_mode_title),
                        items = listOf(
                            stringResource(R.string.logo_mode_system),
                            stringResource(R.string.logo_mode_no_material),
                            stringResource(R.string.logo_mode_keep_material),
                        ),
                        selectedIndex = appearance.logoMode.coerceIn(0, 2),
                        onSelectedIndexChange = { index -> activity.updateAppearance { it.copy(logoMode = index) } },
                    )
                    if (appearance.logoMode != LOGO_MODE_SYSTEM) {
                        ArrowRow(
                            title = stringResource(R.string.logo_import),
                            summary = appearance.logoMime.ifBlank { stringResource(R.string.logo_not_imported) },
                            onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_LOGO, logo = true) },
                        )
                        ArrowRow(
                            title = stringResource(R.string.logo_clear),
                            summary = if (appearance.logoMime.isBlank()) stringResource(R.string.logo_none) else stringResource(R.string.logo_imported),
                            onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_LOGO) },
                        )
                        AppearanceSlider(
                            label = stringResource(R.string.logo_scale),
                            value = appearance.logoScale.toFloat(),
                            range = 50f..200f,
                            suffix = "%",
                            onValueChangeFinished = { value -> activity.updateAppearance { it.copy(logoScale = value.toInt()) } },
                        )
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.device_info_group_basic)) }
        // 基础参数：前 6 项。
        for (index in 0 until 6) {
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    DeviceProfileField(fields[index].first, fields[index].second) { next ->
                        updateProfile { setters[index](it, next) }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.device_info_group_all)) }
        // 全部参数与信息：索引 8 起（摄像头/OS/Android/存储/内核/基带/硬件）。
        for (index in 8 until fields.size) {
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    DeviceProfileField(fields[index].first, fields[index].second) { next ->
                        updateProfile { setters[index](it, next) }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.device_info_group_camera)) }
        // 我的设备影像参数：索引 6..7（后摄/前摄）。
        for (index in 6 until 8) {
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    DeviceProfileField(fields[index].first, fields[index].second) { next ->
                        updateProfile { setters[index](it, next) }
                    }
                }
            }
        }
    }
}

/** 带本地状态的滑块：拖动时更新本地状态使滑块实时跟手，松手时才落库。 */
@Composable
private fun AppearanceSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "%",
    onValueChangeFinished: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    SliderWithInputPreference(
        label = label,
        value = local,
        range = range,
        suffix = suffix,
        onValueChange = { local = it },
        onValueChangeFinished = onValueChangeFinished,
    )
}

/** 导入/清除行：与主页 ScopeEntry 一致的 BasicComponent + 右箭头。 */
@Composable
private fun ArrowRow(title: String, summary: String, onClick: () -> Unit) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
        },
        onClick = onClick,
    )
}

/** 单个设备参数覆盖输入框：照搬源码 DeviceProfileField。 */
@Composable
private fun DeviceProfileField(label: String, value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    TextField(
        value = text,
        onValueChange = { next -> text = next; onValueChange(next) },
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    )
}
