package com.ciallo.hyperbackground.ui.pages

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.BuildConfig
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.RootShell
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.SliderPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    themeMode: Int,
    themeColorEnabled: Boolean,
    monet: Boolean,
    accent: Int,
    onThemeMode: (Int) -> Unit,
    onThemeColorEnabled: (Boolean) -> Unit,
    onMonet: (Boolean) -> Unit,
    onAccent: (Int) -> Unit,
) {
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
        item { SectionTitle(stringResource(R.string.module_appearance)) }
        item {
            ModuleAppearanceCard(
                activity,
                themeMode,
                themeColorEnabled,
                monet,
                accent,
                onThemeMode,
                onThemeColorEnabled,
                onMonet,
                onAccent,
            )
        }
        item { SectionTitle(stringResource(R.string.language)) }
        item { LanguageCard(activity) }
        item { SectionTitle(stringResource(R.string.saying_settings)) }
        item { SayingSettingsCard(activity) }
        item { SectionTitle(stringResource(R.string.tools)) }
        item { ToolsCard(activity) }
        item { SectionTitle(stringResource(R.string.about)) }
        item { AboutCard(activity) }
    }
}

@Composable
private fun ModuleAppearanceCard(
    activity: MainActivity,
    themeMode: Int,
    themeColorEnabled: Boolean,
    monet: Boolean,
    accent: Int,
    onThemeMode: (Int) -> Unit,
    onThemeColorEnabled: (Boolean) -> Unit,
    onMonet: (Boolean) -> Unit,
    onAccent: (Int) -> Unit,
) {
    val config = activity.config
    var bgOpacity by remember(activity.revision) { mutableFloatStateOf(config.getInt(BackgroundContract.UI_BG_OPACITY, 100).toFloat()) }
    var blur by remember(activity.revision) { mutableStateOf(config.getBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, false)) }
    var radius by remember(activity.revision) { mutableFloatStateOf(config.getInt(BackgroundContract.UI_BG_BLUR_RADIUS, 20).toFloat()) }
    val themes = listOf(stringResource(R.string.follow_system), stringResource(R.string.light), stringResource(R.string.dark))
    val colors = listOf(
        0xFF6980FF, 0xFF45B6FE, 0xFF22B8A7, 0xFF45C46B, 0xFFF0C24D, 0xFFF79A47,
        0xFFF46F56, 0xFFEA5A89, 0xFFCA67E8, 0xFF8D6BE8,
    ).map(Long::toInt)
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.module_theme),
                items = themes,
                selectedIndex = themeMode.coerceIn(themes.indices),
                onSelectedIndexChange = onThemeMode,
            )
            SwitchPreference(
                title = stringResource(R.string.theme_color_enabled),
                summary = stringResource(R.string.theme_color_enabled_summary),
                checked = themeColorEnabled,
                onCheckedChange = onThemeColorEnabled,
            )
            SwitchPreference(
                title = stringResource(R.string.monet),
                summary = stringResource(R.string.monet_summary),
                checked = monet,
                enabled = themeColorEnabled,
                onCheckedChange = onMonet,
            )
            Column(Modifier.alpha(if (themeColorEnabled && !monet) 1f else 0.38f)) {
                Text(stringResource(R.string.accent_color), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                colors.chunked(5).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { value ->
                            Box(
                                Modifier.size(if ((accent and 0xFFFFFF) == (value and 0xFFFFFF)) 38.dp else 32.dp)
                                    .clip(CircleShape)
                                    .background(Color(value))
                                    .clickable(enabled = themeColorEnabled && !monet) { onAccent(value) },
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.module_background),
                    onClick = activity::chooseUiBackground,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.clear_background),
                    onClick = activity::clearUiBackground,
                )
            }
            SliderPreference(
                label = stringResource(R.string.background_opacity),
                value = bgOpacity,
                range = 0f..100f,
                suffix = "%",
                onValueChange = { bgOpacity = it },
                onValueChangeFinished = {
                    config.edit().putInt(BackgroundContract.UI_BG_OPACITY, it.toInt()).apply()
                    activity.refreshUi()
                },
            )
            var cardOpacity by remember(activity.cardOpacity) { mutableFloatStateOf(activity.cardOpacity * 100f) }
            SliderPreference(
                label = stringResource(R.string.card_opacity),
                value = cardOpacity,
                range = 0f..100f,
                suffix = "%",
                onValueChange = { cardOpacity = it },
                onValueChangeFinished = { activity.updateCardOpacity(it / 100f) },
            )
            SwitchPreference(
                title = stringResource(R.string.module_blur),
                checked = blur,
                onCheckedChange = {
                    blur = it
                    config.edit().putBoolean(BackgroundContract.UI_BG_BLUR_ENABLED, it).apply()
                    activity.refreshUi()
                },
            )
            SliderPreference(
                label = stringResource(R.string.blur_strength),
                value = radius,
                range = 0f..80f,
                onValueChange = { radius = it },
                onValueChangeFinished = {
                    config.edit().putInt(BackgroundContract.UI_BG_BLUR_RADIUS, it.toInt()).apply()
                    activity.refreshUi()
                },
            )
        }
    }
}

@Composable
private fun LanguageCard(activity: MainActivity) {
    val manager = activity.getSystemService(LocaleManager::class.java)
    val current = manager.applicationLocales.toLanguageTags()
    var selected by remember(current) {
        mutableIntStateOf(when {
            current.startsWith("zh") -> 1
            current.startsWith("en") -> 2
            else -> 0
        })
    }
    val options = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_chinese),
        stringResource(R.string.language_english),
    )
    UiCard(activity, Modifier.fillMaxWidth()) {
        OverlayDropdownPreference(
            title = stringResource(R.string.language),
            items = options,
            selectedIndex = selected,
            onSelectedIndexChange = {
                selected = it
                manager.applicationLocales = LocaleList.forLanguageTags(when (it) { 1 -> "zh-CN"; 2 -> "en"; else -> "" })
            },
        )
    }
}

@Composable
private fun SayingSettingsCard(activity: MainActivity) {
    val config = activity.config
    var api by remember { mutableStateOf(config.getString(BackgroundContract.UI_SAYING_API, DEFAULT_API) ?: DEFAULT_API) }
    var key by remember { mutableStateOf(config.getString(BackgroundContract.UI_SAYING_KEY, DEFAULT_KEY) ?: DEFAULT_KEY) }
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InputField(stringResource(R.string.api_address), api) { api = it }
            InputField(stringResource(R.string.response_field), key) { key = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(modifier = Modifier.weight(1f), text = stringResource(R.string.restore_default), onClick = {
                    api = DEFAULT_API
                    key = DEFAULT_KEY
                })
                TextButton(modifier = Modifier.weight(1f), text = stringResource(R.string.save_refresh), onClick = {
                    config.edit().putString(BackgroundContract.UI_SAYING_API, api.trim().ifBlank { DEFAULT_API })
                        .putString(BackgroundContract.UI_SAYING_KEY, key.trim()).apply()
                    activity.recreate()
                })
            }
        }
    }
}

@Composable
private fun InputField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            )
        }
    }
}

@Composable
private fun ToolsCard(activity: MainActivity) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.restart_scope_summary), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.restart_scope),
                onClick = { restartScopes(activity) },
            )
        }
    }
}

@Composable
private fun AboutCard(activity: MainActivity) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.author), style = MiuixTheme.textStyles.headline1)
            Text(stringResource(R.string.current_version, BuildConfig.VERSION_NAME), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.coolapk),
                    onClick = { activity.openUrl("https://www.coolapk.com/u/18795532") },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.github),
                    onClick = { activity.openUrl("https://github.com/Solomonstery/HyperBackground") },
                )
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.github_releases),
                onClick = { activity.openUrl("https://github.com/Solomonstery/HyperBackground/releases") },
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.open_app_info),
                onClick = {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                },
            )
        }
    }
}

private fun restartScopes(activity: MainActivity) {
    android.widget.Toast.makeText(activity, R.string.root_requested, android.widget.Toast.LENGTH_SHORT).show()
    thread {
        val success = SCOPE_PACKAGES.map { runCatching { RootShell.run("am force-stop $it").success }.getOrDefault(false) }.all { it }
        activity.runOnUiThread {
            android.widget.Toast.makeText(
                activity,
                if (success) R.string.scope_restarted else R.string.root_unavailable,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private val SCOPE_PACKAGES = listOf(
    BackgroundContract.PACKAGE_SETTINGS,
    BackgroundContract.PACKAGE_MILINK,
    BackgroundContract.PACKAGE_PHONE,
    BackgroundContract.PACKAGE_ACCOUNT,
    BackgroundContract.PACKAGE_THEME_MANAGER,
    BackgroundContract.PACKAGE_HOME,
    BackgroundContract.PACKAGE_SECURITY_CENTER,
    BackgroundContract.PACKAGE_POWER_KEEPER,
    BackgroundContract.PACKAGE_MI_SETTINGS,
)

private const val DEFAULT_API = "https://uapis.cn/api/v1/saying"
private const val DEFAULT_KEY = "text"
