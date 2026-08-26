package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.ciallo.hyperbackground.ui.components.SliderPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    var opacity by remember(slot, revision) { mutableFloatStateOf(config.backgroundOpacity(slot).toFloat()) }
    var blur by remember(slot, revision) { mutableStateOf(config.backgroundBlurEnabled(slot)) }
    var radius by remember(slot, revision) { mutableFloatStateOf(config.backgroundBlurRadius(slot).toFloat()) }
    val file = remember(slot, revision) { config.backgroundFile(slot) }
    val allowVideo = slot == BackgroundContract.DEVICE
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
                Column(Modifier.padding(vertical = 12.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (file.isFile) stringResource(R.string.enabled_size, humanSize(file.length()))
                            else stringResource(R.string.system_default),
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(if (allowVideo) R.string.choose_media else R.string.choose_image),
                            onClick = { activity.chooseBackground(slot) },
                        )
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.restore_default),
                            onClick = { activity.clearBackground(slot) },
                        )
                    }
                    SliderPreference(
                        label = stringResource(R.string.opacity),
                        value = opacity,
                        range = 0f..100f,
                        suffix = "%",
                        onValueChange = { opacity = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.OPACITY_PREFIX + slot, it.toInt()).apply()
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.background_blur),
                        summary = stringResource(R.string.background_blur_summary),
                        checked = blur,
                        onCheckedChange = {
                            blur = it
                            config.edit().putBoolean(BackgroundContract.BLUR_ENABLED_PREFIX + slot, it).apply()
                        },
                    )
                    SliderPreference(
                        label = stringResource(R.string.blur_strength),
                        value = radius,
                        range = 0f..80f,
                        onValueChange = { radius = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.BLUR_RADIUS_PREFIX + slot, it.toInt()).apply()
                        },
                    )
                }
            }
        }
        if (slot == BackgroundContract.HOME) {
            item { SectionTitle(stringResource(R.string.settings_appearance)) }
            item { SettingsAppearanceCard(activity) }
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

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024f)
    else -> "$bytes B"
}
