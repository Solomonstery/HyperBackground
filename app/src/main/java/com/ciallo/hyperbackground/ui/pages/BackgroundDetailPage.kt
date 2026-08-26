package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

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
        if (slot == BackgroundContract.GLOBAL) {
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
