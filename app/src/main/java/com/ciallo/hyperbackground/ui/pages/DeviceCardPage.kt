﻿package com.ciallo.hyperbackground.ui.pages

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
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_DEVICE_IMAGE
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_LOGO
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_ONE
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_SYSTEM
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_TWO
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSettings
import com.ciallo.hyperbackground.appearance.style2LogoHorizontalOffsetForAlignment
import com.ciallo.hyperbackground.appearance.style2LogoVerticalOffsetForAlignment
import com.ciallo.hyperbackground.appearance.style2TextHorizontalOffsetForAlignment
import com.ciallo.hyperbackground.appearance.style2TextVerticalOffsetForAlignment
import com.ciallo.hyperbackground.appearance.withStyle2LogoHorizontalOffset
import com.ciallo.hyperbackground.appearance.withStyle2LogoVerticalOffset
import com.ciallo.hyperbackground.appearance.withStyle2TextHorizontalOffset
import com.ciallo.hyperbackground.appearance.withStyle2TextVerticalOffset
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
 * 「自定义我的设备界面」二级页。表单结构严格照搬 HyperChanger 的 TutorialDeviceCardSettings：
 * 顶部选择「系统默认 / 样式1 / 样式2」，其余分组按所选样式动态展开。
 *
 * 适配点（仅换壳，不改逻辑）：卡片用 [UiCard]，滑块用带输入框的 [SliderWithInputPreference]，
 * 导入/清除行用与主页一致的 [ArrowRow]（BasicComponent + 右箭头）。
 */
@Composable
fun DeviceCardPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val appearance = activity.appearance
    val update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit = { transform ->
        activity.updateAppearance(transform)
    }
    val style = appearance.deviceInterfaceStyle.coerceIn(DEVICE_INTERFACE_STYLE_SYSTEM, DEVICE_INTERFACE_STYLE_TWO)
    val selectStyle: (Int) -> Unit = { selected ->
        update {
            it.copy(
                deviceInterfaceStyle = selected,
                tutorialCardEnabled = selected == DEVICE_INTERFACE_STYLE_ONE,
                tutorialCardInfoCardsEnabled = selected == DEVICE_INTERFACE_STYLE_ONE,
            )
        }
    }
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
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.device_card_style),
                    items = listOf(
                        stringResource(R.string.device_card_style_system),
                        stringResource(R.string.device_card_style_one),
                        stringResource(R.string.device_card_style_two),
                    ),
                    selectedIndex = style,
                    onSelectedIndexChange = selectStyle,
                )
            }
        }

        if (style == DEVICE_INTERFACE_STYLE_SYSTEM) {
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
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
                }
            }
        }

        if (style == DEVICE_INTERFACE_STYLE_ONE) {
            item { SectionTitle(stringResource(R.string.group_device_image)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.device_image_import),
                        summary = appearance.tutorialCardImageMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_DEVICE_IMAGE, logo = false) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.device_image_clear),
                        summary = if (appearance.tutorialCardImageMime.isBlank()) stringResource(R.string.image_not_imported) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_DEVICE_IMAGE) },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.device_image_scale),
                        value = appearance.tutorialCardImageScale.toFloat(),
                        range = 40f..200f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardImageScale = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.device_image_logo_spacing),
                        value = appearance.tutorialCardImageLogoSpacing.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardImageLogoSpacing = value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_background_image)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.background_image_import),
                        summary = appearance.tutorialCardBackgroundMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND, logo = false) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.background_image_clear),
                        summary = if (appearance.tutorialCardBackgroundMime.isBlank()) stringResource(R.string.image_not_imported) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND) },
                    )
                    BlurSlider(
                        label = stringResource(R.string.background_image_blur),
                        value = appearance.tutorialCardBackgroundBlur,
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardBackgroundBlur = value) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_h_offset),
                        value = appearance.tutorialCardBackgroundHorizontalOffset.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardBackgroundHorizontalOffset = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_v_offset),
                        value = appearance.tutorialCardBackgroundVerticalOffset.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardBackgroundVerticalOffset = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_scale),
                        value = appearance.tutorialCardBackgroundScale.toFloat(),
                        range = 40f..200f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardBackgroundScale = value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_logo)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.logo_import),
                        summary = appearance.tutorialCardLogoMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, logo = true) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.logo_clear),
                        summary = if (appearance.tutorialCardLogoMime.isBlank()) stringResource(R.string.logo_none) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO) },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.logo_scale),
                        value = appearance.tutorialCardLogoScale.toFloat(),
                        range = 40f..200f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardLogoScale = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.logo_v_offset),
                        value = appearance.tutorialCardLogoVerticalOffset.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardLogoVerticalOffset = value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_bottom_mark)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    AppearanceSlider(
                        label = stringResource(R.string.bottom_logo_text_spacing),
                        value = appearance.tutorialCardTextSpacing.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(tutorialCardTextSpacing = value.toInt()) } },
                    )
                    TextFieldRow(
                        label = stringResource(R.string.signature),
                        value = appearance.tutorialCardAuthor,
                        onValueChange = { value -> update { it.copy(tutorialCardAuthor = value) } },
                    )
                }
            }
        }

        if (style == DEVICE_INTERFACE_STYLE_TWO) {
            item { SectionTitle(stringResource(R.string.group_device_image)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.device_image_import),
                        summary = appearance.style2ImageMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, logo = false) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.device_image_clear),
                        summary = if (appearance.style2ImageMime.isBlank()) stringResource(R.string.image_not_imported) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE) },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.device_image_scale),
                        value = appearance.style2ImageScale.toFloat(),
                        range = 40f..200f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(style2ImageScale = value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_background_image)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.background_image_import),
                        summary = appearance.style2BackgroundMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND, logo = false) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.background_image_clear),
                        summary = if (appearance.style2BackgroundMime.isBlank()) stringResource(R.string.image_not_imported) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND) },
                    )
                    BlurSlider(
                        label = stringResource(R.string.background_image_blur),
                        value = appearance.style2BackgroundBlur,
                        onValueChangeFinished = { value -> update { it.copy(style2BackgroundBlur = value) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_h_offset),
                        value = appearance.style2BackgroundHorizontalOffset.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(style2BackgroundHorizontalOffset = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_v_offset),
                        value = appearance.style2BackgroundVerticalOffset.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(style2BackgroundVerticalOffset = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.background_image_scale),
                        value = appearance.style2BackgroundScale.toFloat(),
                        range = 40f..200f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(style2BackgroundScale = value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_logo_version)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    ArrowRow(
                        title = stringResource(R.string.logo_import),
                        summary = appearance.style2LogoMime.ifBlank { stringResource(R.string.logo_not_imported) },
                        onClick = { activity.chooseAppearanceImage(APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, logo = true) },
                    )
                    ArrowRow(
                        title = stringResource(R.string.logo_clear),
                        summary = if (appearance.style2LogoMime.isBlank()) stringResource(R.string.logo_none) else stringResource(R.string.logo_imported),
                        onClick = { activity.clearAppearanceImage(APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO) },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.logo_version_alignment),
                        items = listOf(
                            stringResource(R.string.align_left),
                            stringResource(R.string.align_center),
                            stringResource(R.string.align_right),
                        ),
                        selectedIndex = appearance.style2LogoAlignment.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoAlignment = value) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.logo_version_line_spacing),
                        value = appearance.style2LogoVersionSpacing.toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { it.copy(style2LogoVersionSpacing = value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.logo_version_h_offset),
                        value = appearance.style2LogoHorizontalOffsetForAlignment().toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { current -> current.withStyle2LogoHorizontalOffset(current.style2LogoAlignment, value.toInt()) } },
                    )
                    AppearanceSlider(
                        label = stringResource(R.string.logo_version_v_offset),
                        value = appearance.style2LogoVerticalOffsetForAlignment().toFloat(),
                        range = -120f..120f,
                        suffix = "%",
                        onValueChangeFinished = { value -> update { current -> current.withStyle2LogoVerticalOffset(current.style2LogoAlignment, value.toInt()) } },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.group_text)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.text_show_custom),
                        checked = appearance.style2TextEnabled,
                        onCheckedChange = { value -> update { it.copy(style2TextEnabled = value) } },
                    )
                    if (appearance.style2TextEnabled) {
                        TextFieldRow(
                            label = stringResource(R.string.text_content),
                            value = appearance.style2Text,
                            onValueChange = { value -> update { it.copy(style2Text = value) } },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.text_independent),
                            checked = appearance.style2TextIndependent,
                            onCheckedChange = { value -> update { it.copy(style2TextIndependent = value) } },
                        )
                        AppearanceSlider(
                            label = stringResource(R.string.text_scale),
                            value = appearance.style2TextScale.toFloat(),
                            range = 40f..200f,
                            suffix = "%",
                            onValueChangeFinished = { value -> update { it.copy(style2TextScale = value.toInt()) } },
                        )
                        if (!appearance.style2TextIndependent) {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.text_position),
                                items = listOf(
                                    stringResource(R.string.text_position_above),
                                    stringResource(R.string.text_position_below),
                                ),
                                selectedIndex = appearance.style2TextPosition.coerceIn(0, 1),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextPosition = value) } },
                            )
                            if (appearance.style2TextPosition == 0) {
                                AppearanceSlider(
                                    label = stringResource(R.string.text_logo_line_spacing),
                                    value = appearance.style2TextSpacingAbove.toFloat(),
                                    range = -120f..120f,
                                    suffix = "%",
                                    onValueChangeFinished = { value -> update { it.copy(style2TextSpacingAbove = value.toInt()) } },
                                )
                            } else {
                                AppearanceSlider(
                                    label = stringResource(R.string.text_version_line_spacing),
                                    value = appearance.style2TextSpacingBelow.toFloat(),
                                    range = -120f..120f,
                                    suffix = "%",
                                    onValueChangeFinished = { value -> update { it.copy(style2TextSpacingBelow = value.toInt()) } },
                                )
                            }
                        } else {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.text_position),
                                items = listOf(
                                    stringResource(R.string.text_align_center),
                                    stringResource(R.string.align_left),
                                    stringResource(R.string.align_right),
                                ),
                                selectedIndex = appearance.style2TextAlignment.coerceIn(0, 2),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextAlignment = value) } },
                            )
                            AppearanceSlider(
                                label = stringResource(R.string.text_v_offset),
                                value = appearance.style2TextVerticalOffsetForAlignment().toFloat(),
                                range = -120f..120f,
                                suffix = "%",
                                onValueChangeFinished = { value -> update { current -> current.withStyle2TextVerticalOffset(current.style2TextAlignment, value.toInt()) } },
                            )
                            AppearanceSlider(
                                label = stringResource(R.string.text_h_offset),
                                value = appearance.style2TextHorizontalOffsetForAlignment().toFloat(),
                                range = -120f..120f,
                                suffix = "%",
                                onValueChangeFinished = { value -> update { current -> current.withStyle2TextHorizontalOffset(current.style2TextAlignment, value.toInt()) } },
                            )
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.group_text_color_mode)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    val colorModes = listOf(
                        stringResource(R.string.color_mode_follow),
                        stringResource(R.string.color_mode_dark),
                        stringResource(R.string.color_mode_light),
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.color_mode_logo),
                        items = colorModes,
                        selectedIndex = appearance.style2LogoColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoColorMode = value) } },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.color_mode_version),
                        items = colorModes,
                        selectedIndex = appearance.style2VersionColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2VersionColorMode = value) } },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.color_mode_text),
                        items = colorModes,
                        selectedIndex = appearance.style2TextColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2TextColorMode = value) } },
                    )
                }
            }
        }
    }
}

/**
 * 带本地状态的滑块：拖动时更新本地状态使滑块实时跟手，松手时才落库。
 * 与 [BackgroundDetailPage] 中 `remember` 驱动滑块的写法一致，避免只在松手时才刷新导致拖动无动画。
 */
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

/** 背景模糊滑块：0..25dp，与源码取值范围一致（本项目滑块按整数 dp 展示）。 */
@Composable
private fun BlurSlider(
    label: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
) {
    AppearanceSlider(
        label = label,
        value = value,
        range = 0f..25f,
        suffix = "dp",
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

/** 单行文本输入：照搬源码 TutorialCardTextField，用 Miuix TextField。 */
@Composable
private fun TextFieldRow(label: String, value: String, onValueChange: (String) -> Unit) {
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
