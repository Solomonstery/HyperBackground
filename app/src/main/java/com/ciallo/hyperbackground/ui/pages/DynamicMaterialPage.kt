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
import top.yukonga.miuix.kmp.window.WindowDialog

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
    var showTopGradient by remember { mutableStateOf(false) }
    var showBottomGradient by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(config.getBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, false)) }
    var blur by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, true) && !clear)
    }
    var bottomGradient by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, false))
    }
    var bottomClear by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_BOTTOM_CLEAR_ENABLED, false))
    }
    UiCard(activity, Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.top_gradient),
            summary = stringResource(R.string.top_gradient_summary),
            endActions = {
                Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { showTopGradient = true },
        )
        BasicComponent(
            title = stringResource(R.string.bottom_gradient),
            summary = stringResource(R.string.bottom_gradient_summary),
            endActions = {
                Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
            },
            onClick = { showBottomGradient = true },
        )
    }
    TopGradientDialog(
        activity = activity,
        show = showTopGradient,
        blur = blur,
        clear = clear,
        bottomActive = bottomGradient && !bottomClear,
        onBlurChange = { value ->
            blur = value
            if (value) bottomGradient = false
            config.edit().putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, value)
                .also { if (value) it.putBoolean(BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, false) }
                .apply()
        },
        onClearChange = { value ->
            clear = value
            config.edit().putBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, value)
                .putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, blur)
                .apply()
        },
        onDismiss = { showTopGradient = false },
    )
    BottomGradientDialog(
        activity = activity,
        show = showBottomGradient,
        gradient = bottomGradient,
        clear = bottomClear,
        onGradientChange = { value ->
            bottomGradient = value
            if (value) blur = false
            config.edit().putBoolean(BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, value)
                .also { if (value) it.putBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, false) }
                .apply()
        },
        onClearChange = { value ->
            bottomClear = value
            config.edit().putBoolean(BackgroundContract.UI_BOTTOM_CLEAR_ENABLED, value)
                .putBoolean(BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, bottomGradient)
                .apply()
        },
        onDismiss = { showBottomGradient = false },
    )
}

@Composable
private fun TopGradientDialog(
    activity: MainActivity,
    show: Boolean,
    blur: Boolean,
    clear: Boolean,
    bottomActive: Boolean,
    onBlurChange: (Boolean) -> Unit,
    onClearChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val config = activity.config
    var strength by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, 10).coerceIn(0, 100).toFloat())
    }
    var opacity by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_TOP_BLUR_OPACITY, 100).coerceIn(0, 100).toFloat())
    }
    val motion = spring<IntSize>(dampingRatio = 0.82f, stiffness = 420f)
    val active = blur && !clear && !bottomActive
    WindowDialog(
        title = stringResource(R.string.top_gradient),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.top_blur),
                summary = stringResource(R.string.top_blur_summary) + " · " +
                    stringResource(R.string.gradient_blur_conflict),
                checked = active,
                enabled = !clear,
                onCheckedChange = { onBlurChange(it) },
            )
            AnimatedVisibility(
                visible = active,
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
                onCheckedChange = { onClearChange(it) },
            )
        }
    }
}

@Composable
private fun BottomGradientDialog(
    activity: MainActivity,
    show: Boolean,
    gradient: Boolean,
    clear: Boolean,
    onGradientChange: (Boolean) -> Unit,
    onClearChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val config = activity.config
    var strength by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH, 10).coerceIn(0, 100).toFloat())
    }
    var opacity by remember {
        mutableFloatStateOf(config.getInt(BackgroundContract.UI_BOTTOM_GRADIENT_OPACITY, 100).coerceIn(0, 100).toFloat())
    }
    WindowDialog(
        title = stringResource(R.string.bottom_gradient),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.bottom_gradient_enabled),
                summary = stringResource(R.string.bottom_gradient_enabled_summary) + " · " +
                    stringResource(R.string.gradient_blur_conflict),
                checked = gradient && !clear,
                enabled = !clear,
                onCheckedChange = { value ->
                    onGradientChange(value)
                },
            )
            AnimatedVisibility(visible = gradient && !clear) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SliderPreference(
                        label = stringResource(R.string.bottom_gradient_strength), value = strength,
                        range = 0f..100f, suffix = "%", defaultValue = 10f,
                        onValueChange = { strength = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH, it.toInt()).apply()
                        },
                    )
                    SliderPreference(
                        label = stringResource(R.string.bottom_gradient_opacity), value = opacity,
                        range = 0f..100f, suffix = "%", defaultValue = 100f,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = {
                            config.edit().putInt(BackgroundContract.UI_BOTTOM_GRADIENT_OPACITY, it.toInt()).apply()
                        },
                    )
                }
            }
            SwitchPreference(
                title = stringResource(R.string.bottom_clear),
                summary = stringResource(R.string.bottom_clear_summary),
                checked = clear,
                enabled = !gradient || clear,
                onCheckedChange = { value ->
                    onClearChange(value)
                },
            )
        }
    }
}
