package com.ciallo.hyperbackground.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.SettingsCardColors
import com.ciallo.hyperbackground.ui.components.SliderPreference
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「动态适配」主页面：卡片背景样式 + 柔光参数入口 + 组件/软件作用域入口。
 *
 * 动态适配以「组件类型」为粒度接管材质：分组卡片（RecyclerView 分组装饰器）、
 * 独立卡片（自带卡面的视图）、弹窗（PopupView / miuix AlertDialog），
 * 全部走 [com.ciallo.hyperbackground.dynamic] 包内的纯动态路由。
 */
@Composable
fun DynamicMaterialPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    onOpenMaterial: () -> Unit,
    onOpenComponentScope: () -> Unit,
    onOpenAppScope: () -> Unit,
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
        item { SectionTitle(stringResource(R.string.settings_card_background)) }
        item { SettingsCardColors(activity, onOpenMaterial) }
        item { SectionTitle(stringResource(R.string.blur)) }
        item { TopBarEffects(activity) }
        item { SectionTitle(stringResource(R.string.dynamic_scope_title)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.component_scope_title),
                    summary = stringResource(R.string.component_scope_summary),
                    endActions = {
                        Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
                    },
                    onClick = onOpenComponentScope,
                )
                BasicComponent(
                    title = stringResource(R.string.app_scope_title),
                    summary = stringResource(R.string.app_scope_summary),
                    endActions = {
                        Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
                    },
                    onClick = onOpenAppScope,
                )
            }
        }
    }
}

@Composable
private fun TopBarEffects(activity: MainActivity) {
    val config = activity.config
    var clear by remember { mutableStateOf(config.getBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, false)) }
    var blur by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, true) && !clear)
    }
    var strength by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, 10).coerceIn(0, 100).toFloat())
    }
    var opacity by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_TOP_BLUR_OPACITY, 100).coerceIn(0, 100).toFloat())
    }
    val motion = spring<IntSize>(dampingRatio = 0.82f, stiffness = 420f)
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.top_blur),
                summary = stringResource(R.string.top_blur_summary),
                checked = blur && !clear,
                enabled = !clear,
                onCheckedChange = { value ->
                    blur = value
                    config.edit().putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, value)
                        .apply()
                },
            )
            AnimatedVisibility(
                visible = blur && !clear,
                enter = expandVertically(animationSpec = motion) + fadeIn(),
                exit = shrinkVertically(animationSpec = motion) + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SliderPreference(
                        label = stringResource(R.string.blur_strength), value = strength,
                        range = 0f..100f, suffix = "%", defaultValue = 10f,
                        onValueChange = { strength = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, it.toInt()).apply()
                        },
                    )
                    SliderPreference(
                        label = stringResource(R.string.top_blur_opacity), value = opacity,
                        range = 0f..100f, suffix = "%", defaultValue = 100f,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.UI_TOP_BLUR_OPACITY, it.toInt()).apply()
                        },
                    )
                }
            }
            SwitchPreference(
                title = stringResource(R.string.top_clear),
                summary = stringResource(R.string.top_clear_summary),
                checked = clear,
                enabled = !blur || clear,
                onCheckedChange = { value ->
                    clear = value
                    config.edit().putBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, value)
                        .putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, blur)
                        .apply()
                },
            )
        }
    }
}
