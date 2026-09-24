package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.componentEnabled
import com.ciallo.hyperbackground.appearance.isComponentEnabledFor
import com.ciallo.hyperbackground.appearance.withAppComponentEnabled
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 组件键 → (标题, 副标题) 文案；顺序与 [ComponentKeys.ALL] 一致。 */
private val COMPONENT_TEXT = mapOf(
    ComponentKeys.GROUP_CARD to
        (R.string.component_group_card to R.string.component_group_card_summary),
    ComponentKeys.STANDALONE_CARD to
        (R.string.component_standalone_card to R.string.component_standalone_card_summary),
    ComponentKeys.POPUP to
        (R.string.component_popup to R.string.component_popup_summary),
    ComponentKeys.SEARCH to
        (R.string.component_search to R.string.component_search_summary),
    ComponentKeys.FLOATING_BAR to
        (R.string.component_floating_bar to R.string.component_floating_bar_summary),
    ComponentKeys.TOP_BAR_BUTTON to
        (R.string.component_top_bar_button to R.string.component_top_bar_button_summary),
    ComponentKeys.GLOBAL_WALLPAPER to
        (R.string.component_global_wallpaper to R.string.component_global_wallpaper_summary),
)

/**
 * 「软件作用域」详情页：针对单个作用域应用，逐组件控制它启用哪些动态适配。
 *
 * 只列出「组件作用域」里全局已开启的组件 —— 全局关掉的组件在这个包里一定不生效，
 * 展示出来只会让人误以为能单独打开。全局再打开后，这里会自动出现对应的行。
 *
 * 每行的开关写进 `disabledAppComponents`（`"组件|包名"` 编码，见 [ComponentKeys]）；
 * 「全局壁纸」与材质组件同构，但同名开关在「组件作用域」页也存在，两层是父子关系。
 */
@Composable
fun AppScopeDetailPage(
    activity: MainActivity,
    packageName: String,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val appearance = activity.appearance
    val globalWallpaperReady = activity.config.currentBackgroundFile(BackgroundContract.GLOBAL).isFile
    val components = ComponentKeys.ALL.filter { appearance.componentEnabled(it) }

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
        item { SectionTitle(stringResource(R.string.app_scope_detail_components_title)) }
        if (components.isEmpty()) {
            item { AppScopeDetailHint(activity, stringResource(R.string.app_scope_detail_hint)) }
        } else {
            item {
                UiCard(activity, Modifier.fillMaxWidth()) {
                    Column {
                        components.forEach { component ->
                            val (titleRes, summaryRes) = COMPONENT_TEXT.getValue(component)
                            val unset = component == ComponentKeys.GLOBAL_WALLPAPER && !globalWallpaperReady
                            SwitchPreference(
                                title = stringResource(titleRes),
                                summary = stringResource(
                                    if (unset) R.string.component_global_wallpaper_unset else summaryRes,
                                ),
                                checked = appearance.isComponentEnabledFor(packageName, component),
                                onCheckedChange = { value ->
                                    activity.updateAppearance {
                                        it.withAppComponentEnabled(packageName, component, value)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppScopeDetailHint(activity: MainActivity, text: String) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
        )
    }
}
