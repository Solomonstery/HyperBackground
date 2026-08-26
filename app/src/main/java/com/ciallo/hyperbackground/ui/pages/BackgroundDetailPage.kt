package com.ciallo.hyperbackground.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.BackgroundPickerPreference
import com.ciallo.hyperbackground.ui.components.SliderPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BackgroundDetailPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    slot: String,
    revision: Int,
) {
    val config = activity.config
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
        item { SectionTitle(stringResource(R.string.scope)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                BackgroundPickerPreference(activity = activity, slot = slot)
            }
        }
        if (slot == BackgroundContract.HOME) {
            item { SectionTitle(stringResource(R.string.blur)) }
            item { TopBlurCard(activity) }
        }
        if (slot == BackgroundContract.GLOBAL) {
            item { SectionTitle(stringResource(R.string.settings_appearance)) }
            item { SettingsAppearanceCard(activity) }
            item { SectionTitle(stringResource(R.string.uninstall_notice_title)) }
            item { UninstallNoticeCard(activity) }
        }
    }
}

/**
 * 卸载 / 取消挂载前的操作须知卡片。
 * 提醒用户先将颜色模式恢复默认、重启作用域并回访已挂载页面，避免深浅色状态残留。
 */
@Composable
private fun UninstallNoticeCard(activity: MainActivity) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.uninstall_notice_intro),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            listOf(
                stringResource(R.string.uninstall_notice_step_1),
                stringResource(R.string.uninstall_notice_step_2),
                stringResource(R.string.uninstall_notice_step_3),
                stringResource(R.string.uninstall_notice_step_4),
            ).forEachIndexed { index, step ->
                Text(
                    "${index + 1}. $step",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun TopBlurCard(activity: MainActivity) {
    val config = activity.config
    var enabled by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, true))
    }
    var strength by remember {
        mutableFloatStateOf(
            config.getInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, 10)
                .coerceIn(0, 100)
                .toFloat(),
        )
    }
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SwitchPreference(
                title = stringResource(R.string.top_blur),
                summary = stringResource(R.string.top_blur_summary),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    config.edit().putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, it).apply()
                },
            )
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SliderPreference(
                        label = stringResource(R.string.blur_strength),
                        value = strength,
                        range = 0f..100f,
                        suffix = "%",
                        onValueChange = { strength = it },
                        onValueChangeFinished = {
                            config.edit()
                                .putInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, it.toInt())
                                .apply()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAppearanceCard(activity: MainActivity) {
    val config = activity.config
    var settingsTheme by remember {
        mutableIntStateOf(config.getInt(BackgroundContract.SETTINGS_THEME_MODE, BackgroundContract.SETTINGS_THEME_FOLLOW))
    }
    var fontMode by remember {
        mutableIntStateOf(config.getInt(BackgroundContract.FONT_MODE, BackgroundContract.FONT_FOLLOW))
    }
    val themeOptions = listOf(
        stringResource(R.string.follow_system),
        stringResource(R.string.light),
        stringResource(R.string.dark),
    )
    val fontOptions = listOf(
        stringResource(R.string.follow_system),
        stringResource(R.string.light_text),
        stringResource(R.string.dark_text),
    )

    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_theme),
                items = themeOptions,
                selectedIndex = settingsTheme.coerceIn(themeOptions.indices),
                onSelectedIndexChange = {
                    settingsTheme = it
                    config.edit().putInt(BackgroundContract.SETTINGS_THEME_MODE, it).apply()
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.text_color),
                items = fontOptions,
                selectedIndex = fontMode.coerceIn(fontOptions.indices),
                onSelectedIndexChange = {
                    fontMode = it
                    config.edit().putInt(BackgroundContract.FONT_MODE, it).apply()
                },
            )
        }
    }
}
