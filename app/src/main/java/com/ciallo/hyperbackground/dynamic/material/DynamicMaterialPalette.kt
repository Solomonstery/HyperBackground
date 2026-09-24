package com.ciallo.hyperbackground.dynamic.material

import android.content.SharedPreferences
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_FLOATING_BAR
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_GLOBAL_WALLPAPER
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_GROUP_CARD
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_POPUP
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_SEARCH
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_STANDALONE_CARD
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_TOP_BAR_BUTTON
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.SoftGlassParams
import com.ciallo.hyperbackground.appearance.decodeSoftGlass

internal data class DynamicMaterialPalette(
    val enabled: Boolean = false,
    val light: Int = DEFAULT_LIGHT_CARD_COLOR,
    val dark: Int = DEFAULT_DARK_CARD_COLOR,
    val mode: Int = CARD_BACKGROUND_COLOR,
    val lightFrost: Int = DEFAULT_LIGHT_FROST_COLOR,
    val darkFrost: Int = DEFAULT_DARK_FROST_COLOR,
    val lightBlur: Int = DEFAULT_CARD_BLUR,
    val darkBlur: Int = DEFAULT_CARD_BLUR,
    val lightGlass: SoftGlassParams = SoftGlassParams(),
    val darkGlass: SoftGlassParams = SoftGlassParams(),
    val darkFollowsLight: Boolean = false,
    val groupCard: Boolean = true,
    val standaloneCard: Boolean = true,
    val popup: Boolean = true,
    val search: Boolean = true,
    val floatingBar: Boolean = true,
    val topBarButton: Boolean = false,
    val globalWallpaper: Boolean = true,
    val disabledPackages: Set<String> = emptySet(),
    /** 被单独关闭的「组件|包名」集合，见 [ComponentKeys]。 */
    val disabledComponents: Set<String> = emptySet(),
) {
    /**
     * 目标进程是否应套用材质：在全局开关之上，再排除「软件作用域」里被单独关闭的包。
     * [packageName] 为目标进程包名；为空（框架进程未上报）时不参与过滤。
     */
    fun enabledFor(packageName: String?): Boolean =
        enabled && (packageName == null || packageName !in disabledPackages)

    /**
     * 目标进程是否套用某组件（[component] 取 [ComponentKeys] 里的键）。
     *
     * 三级判定依次取与，与「组件作用域 / 软件作用域详情」两页的开关语义严格对齐：
     * 全局组件开关 → 整包开关 → 该包该组件是否被单独关闭。
     *
     * 「全局壁纸」是例外：它把 GLOBAL 槽位的背景图套到页面上，与「自定义卡片」总开关
     * 无关（那个开关管的是卡片材质），所以不受 [enabled] 约束。
     */
    fun enabledFor(packageName: String?, component: String): Boolean {
        if (!componentSwitch(component)) return false
        val pkg = packageName ?: return true
        if (pkg in disabledPackages) return false
        if (ComponentKeys.encode(component, pkg) in disabledComponents) return false
        return component == ComponentKeys.GLOBAL_WALLPAPER || enabled
    }

    /** 「组件作用域」页里该组件的全局开关值；未知键按启用处理。 */
    private fun componentSwitch(component: String): Boolean = when (component) {
        ComponentKeys.GROUP_CARD -> groupCard
        ComponentKeys.STANDALONE_CARD -> standaloneCard
        ComponentKeys.POPUP -> popup
        ComponentKeys.SEARCH -> search
        ComponentKeys.FLOATING_BAR -> floatingBar
        ComponentKeys.TOP_BAR_BUTTON -> topBarButton
        ComponentKeys.GLOBAL_WALLPAPER -> globalWallpaper
        else -> true
    }

    companion object {
        fun read(prefs: SharedPreferences): DynamicMaterialPalette {
            val values = prefs.all
            return DynamicMaterialPalette(
                enabled = values[KEY_CUSTOM_CARD_ENABLED] as? Boolean ?: false,
                light = values[KEY_LIGHT_CARD_COLOR] as? Int ?: DEFAULT_LIGHT_CARD_COLOR,
                dark = values[KEY_DARK_CARD_COLOR] as? Int ?: DEFAULT_DARK_CARD_COLOR,
                mode = values[KEY_CARD_BACKGROUND_MODE] as? Int ?: CARD_BACKGROUND_COLOR,
                lightFrost = values[KEY_LIGHT_FROST_COLOR] as? Int ?: DEFAULT_LIGHT_FROST_COLOR,
                darkFrost = values[KEY_DARK_FROST_COLOR] as? Int ?: DEFAULT_DARK_FROST_COLOR,
                lightBlur = (values[KEY_LIGHT_CARD_BLUR] as? Int ?: DEFAULT_CARD_BLUR).coerceIn(0, 80),
                darkBlur = (values[KEY_DARK_CARD_BLUR] as? Int ?: DEFAULT_CARD_BLUR).coerceIn(0, 80),
                lightGlass = decodeSoftGlass(values[KEY_LIGHT_SOFT_GLASS] as? String),
                darkGlass = decodeSoftGlass(values[KEY_DARK_SOFT_GLASS] as? String),
                darkFollowsLight = values[KEY_CARD_DARK_FOLLOWS_LIGHT] as? Boolean ?: false,
                groupCard = values[KEY_COMPONENT_GROUP_CARD] as? Boolean ?: true,
                standaloneCard = values[KEY_COMPONENT_STANDALONE_CARD] as? Boolean ?: true,
                popup = values[KEY_COMPONENT_POPUP] as? Boolean ?: true,
                search = values[KEY_COMPONENT_SEARCH] as? Boolean ?: true,
                floatingBar = values[KEY_COMPONENT_FLOATING_BAR] as? Boolean ?: true,
                topBarButton = values[KEY_COMPONENT_TOP_BAR_BUTTON] as? Boolean ?: false,
                globalWallpaper = values[KEY_COMPONENT_GLOBAL_WALLPAPER] as? Boolean ?: true,
                disabledPackages = (values[KEY_APP_SCOPE_DISABLED] as? Set<*>)
                    ?.filterIsInstance<String>()?.toSet() ?: emptySet(),
                disabledComponents = (values[KEY_APP_COMPONENT_DISABLED] as? Set<*>)
                    ?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            )
        }
    }
}
