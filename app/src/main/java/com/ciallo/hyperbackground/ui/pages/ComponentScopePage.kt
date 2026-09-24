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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「组件作用域」二级页：列出动态适配支持的组件类型，各自独立控制是否套用材质。
 *
 * 组件类型与动态路由三入口一一对应：
 * - 分组卡片 → RecyclerView 分组装饰器（DynamicCardMaterialHook + installDynamicDecoration）
 * - 独立卡片 → 自带卡面的视图（CardSurfaceDetector + standaloneTarget）
 * - 弹窗     → PopupView / miuix AlertDialog（DynamicPopupMaterialHook + popupBackgroundFor）
 */
@Composable
fun ComponentScopePage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val appearance = activity.appearance
    var persistentButtons by remember {
        mutableStateOf(activity.config.getBoolean(BackgroundContract.UI_TOP_BUTTON_BACKGROUND_ENABLED, false))
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
        item { SectionTitle(stringResource(R.string.component_scope_types_title)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.component_group_card),
                        summary = stringResource(R.string.component_group_card_summary),
                        checked = appearance.componentGroupCard,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentGroupCard = value) }
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.component_standalone_card),
                        summary = stringResource(R.string.component_standalone_card_summary),
                        checked = appearance.componentStandaloneCard,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentStandaloneCard = value) }
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.component_popup),
                        summary = stringResource(R.string.component_popup_summary),
                        checked = appearance.componentPopup,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentPopup = value) }
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.component_floating_bar),
                        summary = stringResource(R.string.component_floating_bar_summary),
                        checked = appearance.componentFloatingBar,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentFloatingBar = value) }
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.component_search),
                        summary = stringResource(R.string.component_search_summary),
                        checked = appearance.componentSearch,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentSearch = value) }
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.component_top_bar_button),
                        summary = stringResource(R.string.component_top_bar_button_summary),
                        checked = appearance.componentTopBarButton,
                        onCheckedChange = { value ->
                            activity.updateAppearance { it.copy(componentTopBarButton = value) }
                            if (!value) {
                                persistentButtons = false
                                activity.config.edit()
                                    .putBoolean(BackgroundContract.UI_TOP_BUTTON_BACKGROUND_ENABLED, false).apply()
                            }
                        },
                    )
                    AnimatedVisibility(
                        visible = appearance.componentTopBarButton,
                        enter = expandVertically(animationSpec = spring<IntSize>(dampingRatio = 0.82f, stiffness = 420f)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = spring<IntSize>(dampingRatio = 0.82f, stiffness = 420f)) + fadeOut(),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.top_button_background),
                            summary = stringResource(R.string.top_button_background_summary),
                            checked = persistentButtons,
                            onCheckedChange = { value ->
                                persistentButtons = value
                                activity.config.edit()
                                    .putBoolean(BackgroundContract.UI_TOP_BUTTON_BACKGROUND_ENABLED, value).apply()
                            },
                        )
                    }
                }
            }
        }
    }
}
