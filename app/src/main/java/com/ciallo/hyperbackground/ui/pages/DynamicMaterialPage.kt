package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.SettingsCardColors
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.All

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
        item { SectionTitle(stringResource(R.string.dynamic_scope_title)) }
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.component_scope_title),
                    summary = stringResource(R.string.component_scope_summary),
                    startAction = {
                        Icon(
                            modifier = Modifier.padding(end = 16.dp).size(26.dp),
                            imageVector = MiuixIcons.All,
                            contentDescription = null,
                        )
                    },
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
