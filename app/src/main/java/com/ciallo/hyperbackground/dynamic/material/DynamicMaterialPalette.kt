package com.ciallo.hyperbackground.dynamic.material

import android.content.SharedPreferences
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_FLOATING_BAR
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_GROUP_CARD
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_POPUP
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_SEARCH
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_STANDALONE_CARD
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
    val disabledPackages: Set<String> = emptySet(),
) {
    /**
     * 目标进程是否应套用材质：在全局开关之上，再排除「软件作用域」里被单独关闭的包。
     * [packageName] 为目标进程包名；为空（框架进程未上报）时不参与过滤。
     */
    fun enabledFor(packageName: String?): Boolean =
        enabled && (packageName == null || packageName !in disabledPackages)

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
                disabledPackages = (values[KEY_APP_SCOPE_DISABLED] as? Set<*>)
                    ?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            )
        }
    }
}
