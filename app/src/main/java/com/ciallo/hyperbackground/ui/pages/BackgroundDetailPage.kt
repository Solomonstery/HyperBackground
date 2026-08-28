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
import androidx.compose.runtime.key
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
                // 通讯录：把「颜色模式」并入「背景」卡，作为「设置背景」下方的同卡条目（无独立分组标题）。
                if (slot == BackgroundContract.CONTACTS) {
                    ContactsThemePreference(activity)
                }
            }
        }
        if (slot == BackgroundContract.HOME) {
            item { SectionTitle(stringResource(R.string.blur)) }
            item { TopBlurCard(activity) }
        }
        if (slot == BackgroundContract.CONTACTS) {
            item { SectionTitle(stringResource(R.string.contacts_surface_title)) }
            // 「拨号盘与列表」卡内含：适配开关、键盘不透明度、以及并入的「拨号盘背景」下拉。
            item { ContactsSurfaceCard(activity, revision) }
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
 * 卸载 / 取消挂载前的操作须知卡片（警告样式）。
 * 提醒用户先将颜色模式恢复默认、重启作用域并回访已挂载页面，避免深浅色状态残留。
 */
@Composable
private fun UninstallNoticeCard(activity: MainActivity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = activity.cardOpacity),
            contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.uninstall_notice_intro),
                color = MiuixTheme.colorScheme.onPrimaryContainer,
            )
            listOf(
                stringResource(R.string.uninstall_notice_step_1),
                stringResource(R.string.uninstall_notice_step_2),
                stringResource(R.string.uninstall_notice_step_3),
                stringResource(R.string.uninstall_notice_step_4),
            ).forEachIndexed { index, step ->
                Text(
                    "${index + 1}. $step",
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
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
private fun ContactsSurfaceCard(activity: MainActivity, revision: Int) {
    val config = activity.config
    var enabled by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true))
    }
    var opacity by remember {
        mutableFloatStateOf(
            config.getInt(BackgroundContract.CONTACTS_DIALPAD_OPACITY, 60)
                .coerceIn(0, 100)
                .toFloat(),
        )
    }
    var dialpadMode by remember {
        mutableIntStateOf(
            config.getInt(
                BackgroundContract.CONTACTS_DIALPAD_BG_MODE,
                BackgroundContract.CONTACTS_DIALPAD_BG_DEFAULT,
            ),
        )
    }
    var scaleMode by remember {
        mutableIntStateOf(
            config.getInt(
                BackgroundContract.CONTACTS_DIALPAD_SCALE_MODE,
                BackgroundContract.CONTACTS_DIALPAD_SCALE_CROP,
            ),
        )
    }
    var focusY by remember {
        mutableFloatStateOf(
            config.getInt(BackgroundContract.CONTACTS_DIALPAD_FOCUS_Y, 50)
                .coerceIn(0, 100)
                .toFloat(),
        )
    }
    var zoom by remember {
        mutableFloatStateOf(
            config.getInt(
                BackgroundContract.CONTACTS_DIALPAD_ZOOM,
                BackgroundContract.CONTACTS_DIALPAD_ZOOM_MAX,
            ).coerceIn(
                BackgroundContract.CONTACTS_DIALPAD_ZOOM_MIN,
                BackgroundContract.CONTACTS_DIALPAD_ZOOM_MAX,
            ).toFloat(),
        )
    }
    val dialpadModeOptions = listOf(
        stringResource(R.string.contacts_dialpad_bg_default),
        stringResource(R.string.contacts_dialpad_bg_custom),
    )
    val scaleModeOptions = listOf(
        stringResource(R.string.contacts_dialpad_scale_crop),
        stringResource(R.string.contacts_dialpad_scale_fit),
        stringResource(R.string.contacts_dialpad_scale_stretch),
    )
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SwitchPreference(
                title = stringResource(R.string.contacts_surface_adapt),
                summary = stringResource(R.string.contacts_surface_adapt_summary),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    config.edit().putBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, it).apply()
                },
            )
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SliderPreference(
                        label = stringResource(R.string.contacts_dialpad_opacity),
                        value = opacity,
                        range = 0f..100f,
                        suffix = "%",
                        onValueChange = { opacity = it },
                        onValueChangeFinished = {
                            config.edit()
                                .putInt(BackgroundContract.CONTACTS_DIALPAD_OPACITY, it.toInt())
                                .apply()
                        },
                    )
                }
            }
            // 「拨号盘背景 默认/自定义」并入本卡：选「自定义」展开与其它通道一致的选图 + 透明度 + 清除。
            OverlayDropdownPreference(
                title = stringResource(R.string.contacts_dialpad_bg_mode),
                items = dialpadModeOptions,
                selectedIndex = dialpadMode.coerceIn(dialpadModeOptions.indices),
                onSelectedIndexChange = {
                    dialpadMode = it
                    config.edit().putInt(BackgroundContract.CONTACTS_DIALPAD_BG_MODE, it).apply()
                },
            )
            AnimatedVisibility(
                visible = dialpadMode == BackgroundContract.CONTACTS_DIALPAD_BG_CUSTOM,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    key(revision) {
                        BackgroundPickerPreference(activity = activity, slot = BackgroundContract.CONTACTS_DIALPAD)
                    }
                    // 缩放方式：贴满裁切 / 完整显示 / 拉伸填充，解决拨号盘区域比例与图片不一致导致的裁切 / 变形。
                    OverlayDropdownPreference(
                        title = stringResource(R.string.contacts_dialpad_scale),
                        items = scaleModeOptions,
                        selectedIndex = scaleMode.coerceIn(scaleModeOptions.indices),
                        onSelectedIndexChange = {
                            scaleMode = it
                            config.edit().putInt(BackgroundContract.CONTACTS_DIALPAD_SCALE_MODE, it).apply()
                        },
                    )
                    // 缩放大小：在所选缩放方式基础上再放大（100% 为原始基准），三种模式均生效。
                    SliderPreference(
                        label = stringResource(R.string.contacts_dialpad_zoom),
                        value = zoom,
                        range = BackgroundContract.CONTACTS_DIALPAD_ZOOM_MIN.toFloat()..
                            BackgroundContract.CONTACTS_DIALPAD_ZOOM_MAX.toFloat(),
                        suffix = "%",
                        onValueChange = { zoom = it },
                        onValueChangeFinished = {
                            config.edit()
                                .putInt(BackgroundContract.CONTACTS_DIALPAD_ZOOM, zoom.toInt())
                                .apply()
                        },
                    )
                    // 纵向位置：仅「贴满裁切」时有意义，决定裁掉上/下哪部分（0 顶、50 中、100 底）。
                    AnimatedVisibility(
                        visible = scaleMode == BackgroundContract.CONTACTS_DIALPAD_SCALE_CROP,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
                    ) {
                        SliderPreference(
                            label = stringResource(R.string.contacts_dialpad_focus_y),
                            value = focusY,
                            range = 0f..100f,
                            suffix = "%",
                            onValueChange = { focusY = it },
                            onValueChangeFinished = {
                                config.edit()
                                    .putInt(BackgroundContract.CONTACTS_DIALPAD_FOCUS_Y, focusY.toInt())
                                    .apply()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 通讯录与拨号进程专属深浅色下拉（并入「背景」卡，无独立卡壳）。三态：跟随系统 / 浅色 / 深色。
 * 写 CONTACTS_THEME_MODE；FOLLOW 时 hook 侧会主动撤销 per-app 覆盖。
 */
@Composable
private fun ContactsThemePreference(activity: MainActivity) {
    val config = activity.config
    var contactsTheme by remember {
        mutableIntStateOf(
            config.getInt(BackgroundContract.CONTACTS_THEME_MODE, BackgroundContract.SETTINGS_THEME_FOLLOW),
        )
    }
    val themeOptions = listOf(
        stringResource(R.string.follow_system),
        stringResource(R.string.light),
        stringResource(R.string.dark),
    )
    OverlayDropdownPreference(
        title = stringResource(R.string.contacts_theme),
        items = themeOptions,
        selectedIndex = contactsTheme.coerceIn(themeOptions.indices),
        onSelectedIndexChange = {
            contactsTheme = it
            config.edit().putInt(BackgroundContract.CONTACTS_THEME_MODE, it).apply()
        },
    )
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
