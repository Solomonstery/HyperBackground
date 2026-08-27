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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自定义 LOGO 二级页：导入 SVG/XML/图片替换设置「我的设备」页 LOGO。
 * 提供启用开关、模式选择（系统默认 / 不保留高级材质 / 保留高级材质）、缩放（50%-200%）与清除。
 */
@Composable
fun CustomLogoPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    revision: Int,
) {
    val config = activity.config
    var enabled by remember(revision) {
        mutableStateOf(config.getBoolean(BackgroundContract.LOGO_ENABLED, false))
    }
    var mode by remember(revision) {
        mutableIntStateOf(
            config.getInt(BackgroundContract.LOGO_MODE, BackgroundContract.LOGO_MODE_KEEP_ADVANCED_MATERIAL),
        )
    }
    var scale by remember(revision) {
        mutableFloatStateOf(config.getInt(BackgroundContract.LOGO_SCALE, 100).coerceIn(50, 200).toFloat())
    }
    val hasLogo = remember(revision) { config.logoFile.isFile }
    val modeOptions = listOf(
        stringResource(R.string.logo_mode_system),
        stringResource(R.string.logo_mode_no_advanced_material),
        stringResource(R.string.logo_mode_keep_advanced_material),
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
        item { SectionTitle(stringResource(R.string.custom_logo)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.logo_enable),
                        summary = stringResource(R.string.logo_enable_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            config.edit().putBoolean(BackgroundContract.LOGO_ENABLED, it).apply()
                            activity.refreshUi()
                        },
                    )
                    AnimatedVisibility(
                        visible = enabled,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
                    ) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.logo_mode),
                                items = modeOptions,
                                selectedIndex = mode.coerceIn(modeOptions.indices),
                                onSelectedIndexChange = {
                                    mode = it
                                    config.edit().putInt(BackgroundContract.LOGO_MODE, it).apply()
                                    activity.refreshUi()
                                },
                            )
                            LogoImportEntry(activity = activity, hasLogo = hasLogo)
                            Column(Modifier.padding(bottom = 8.dp)) {
                                SliderPreference(
                                    label = stringResource(R.string.logo_scale),
                                    value = scale,
                                    range = 50f..200f,
                                    suffix = "%",
                                    onValueChange = { scale = it },
                                    onValueChangeFinished = {
                                        config.edit()
                                            .putInt(BackgroundContract.LOGO_SCALE, it.toInt())
                                            .apply()
                                        activity.refreshUi()
                                    },
                                )
                            }
                            AnimatedVisibility(visible = hasLogo) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.restore_default),
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            activity.clearLogo()
                                            activity.refreshUi()
                                        },
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.logo_hint),
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun LogoImportEntry(activity: MainActivity, hasLogo: Boolean) {
    val summary = if (hasLogo) {
        stringResource(R.string.enabled_size, humanLogoSize(activity.config.logoFile))
    } else {
        stringResource(R.string.system_default)
    }
    BasicComponent(
        title = stringResource(R.string.logo_import),
        summary = summary,
        endActions = {
            Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
        },
        onClick = {
            activity.chooseLogo { uri, mime -> activity.saveLogo(uri, mime) }
        },
    )
}

private fun humanLogoSize(file: File): String {
    val bytes = file.length()
    return when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576f)
        bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024f)
        else -> "$bytes B"
    }
}
