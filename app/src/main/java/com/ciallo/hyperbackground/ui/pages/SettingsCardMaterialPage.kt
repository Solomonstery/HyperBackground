package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.DEFAULT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.SoftGlassParams
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.CardBlurPreference
import com.ciallo.hyperbackground.ui.components.CardColorPreference
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.SliderPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「卡片材质」二级页，结构对齐 HyperIsland 的 IslandMaterialPage：
 * 顶部浅色/深色标签切换（TabRow 由 MainActivity 的 Screen 持有），深色页放跟随浅色开关，
 * 开启后隐藏具体参数。磨砂（底色+模糊）与柔光玻璃全部参数集中在本页，浅色/深色各一套。
 */
@Composable
fun SettingsCardMaterialPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    pagerState: PagerState,
) {
    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        MaterialThemeTab(activity, dark = page == 1, padding = padding)
    }
}

@Composable
private fun MaterialThemeTab(activity: MainActivity, dark: Boolean, padding: PaddingValues) {
    val appearance = activity.appearance
    val follows = dark && appearance.cardDarkFollowsLight
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dark) {
            item { SectionTitle(stringResource(R.string.settings_card_follow_section)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_card_dark_follows_light),
                        summary = stringResource(R.string.settings_card_dark_follows_light_summary),
                        checked = appearance.cardDarkFollowsLight,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(cardDarkFollowsLight = value) }
                        },
                    )
                }
            }
        }
        if (!follows) {
            item { SectionTitle(stringResource(R.string.settings_card_frost_section)) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    CardColorPreference(
                        title = stringResource(
                            if (dark) R.string.settings_card_dark_tint else R.string.settings_card_light_tint,
                        ),
                        color = if (dark) appearance.darkFrostColor else appearance.lightFrostColor,
                        onSave = { color ->
                            activity.updateAppearance {
                                if (dark) it.copy(darkFrostColor = color) else it.copy(lightFrostColor = color)
                            }
                        },
                    )
                    CardBlurPreference(
                        label = stringResource(
                            if (dark) R.string.settings_card_dark_blur else R.string.settings_card_light_blur,
                        ),
                        radius = if (dark) appearance.darkCardBlur else appearance.lightCardBlur,
                        onSave = { value ->
                            activity.updateAppearance {
                                if (dark) it.copy(darkCardBlur = value) else it.copy(lightCardBlur = value)
                            }
                        },
                    )
                }
            }
            item { SoftGlassSection(activity, dark) }
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.settings_card_material_restore),
                        onClick = {
                            activity.updateAppearance {
                                if (dark) {
                                    it.copy(
                                        darkFrostColor = DEFAULT_DARK_FROST_COLOR,
                                        darkCardBlur = DEFAULT_CARD_BLUR,
                                        darkSoftGlass = SoftGlassParams(),
                                    )
                                } else {
                                    it.copy(
                                        lightFrostColor = DEFAULT_LIGHT_FROST_COLOR,
                                        lightCardBlur = DEFAULT_CARD_BLUR,
                                        lightSoftGlass = SoftGlassParams(),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 柔光玻璃参数：基础（模糊半径+高光）/ 光影 / 折射 / 背景四组卡片。 */
@Composable
private fun SoftGlassSection(activity: MainActivity, dark: Boolean) {
    val params = if (dark) activity.appearance.darkSoftGlass else activity.appearance.lightSoftGlass
    val save: ((SoftGlassParams) -> SoftGlassParams) -> Unit = { transform ->
        activity.updateAppearance {
            if (dark) it.copy(darkSoftGlass = transform(it.darkSoftGlass))
            else it.copy(lightSoftGlass = transform(it.lightSoftGlass))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.settings_card_soft_glass_section))
        UiCard(activity, Modifier.fillMaxWidth()) {
            var blur by remember(params.blurRadiusDp) { mutableFloatStateOf(params.blurRadiusDp.toFloat()) }
            SliderPreference(
                label = stringResource(R.string.settings_card_glass_blur_radius),
                value = blur,
                range = 0f..80f,
                onValueChange = { blur = it },
                onValueChangeFinished = { value -> save { it.copy(blurRadiusDp = value.toInt()) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_card_glass_highlight),
                checked = params.highlight,
                onCheckedChange = { value -> save { it.copy(highlight = value) } },
            )
        }
        SectionTitle(stringResource(R.string.settings_card_glass_lighting_section))
        UiCard(activity, Modifier.fillMaxWidth()) {
            DecimalSlider(stringResource(R.string.settings_card_glass_soft_light), params.softLight) { value ->
                save { it.copy(softLight = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_saturation), params.saturation) { value ->
                save { it.copy(saturation = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_brightness), params.brightness) { value ->
                save { it.copy(brightness = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_darker), params.darker) { value ->
                save { it.copy(darker = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_transparency), params.transparency) { value ->
                save { it.copy(transparency = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_burn), params.burn) { value ->
                save { it.copy(burn = value) }
            }
        }
        SectionTitle(stringResource(R.string.settings_card_glass_refraction_section))
        UiCard(activity, Modifier.fillMaxWidth()) {
            DecimalSlider(stringResource(R.string.settings_card_glass_refraction), params.refraction) { value ->
                save { it.copy(refraction = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_edge_thickness), params.edgeThickness) { value ->
                save { it.copy(edgeThickness = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_reflection), params.reflection) { value ->
                save { it.copy(reflection = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_directional_light), params.directionalLightIntensity) { value ->
                save { it.copy(directionalLightIntensity = value) }
            }
        }
        SectionTitle(stringResource(R.string.settings_card_glass_background_section))
        UiCard(activity, Modifier.fillMaxWidth()) {
            DecimalSlider(stringResource(R.string.settings_card_glass_background_saturation), params.backgroundSaturation) { value ->
                save { it.copy(backgroundSaturation = value) }
            }
            DecimalSlider(stringResource(R.string.settings_card_glass_background_brightness), params.backgroundBrightness) { value ->
                save { it.copy(backgroundBrightness = value) }
            }
        }
    }
}

/** Bionics 柔光参数均为 -50..50 的百分比刻度，0.1 步进，保留一位小数显示。 */
@Composable
private fun DecimalSlider(label: String, value: Double, onSave: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = decimalDisplay(draft),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = draft,
            onValueChange = { draft = (it * 10).toInt() / 10f },
            onValueChangeFinished = { onSave((draft * 10).roundToInt() / 10.0) },
            valueRange = -50f..50f,
            steps = 999,
        )
    }
}

private fun decimalDisplay(value: Float): String {
    val hundredths = String.format(Locale.ROOT, "%.2f", value)
    return if (hundredths.endsWith("0")) String.format(Locale.ROOT, "%.1f", value) else hundredths
}
