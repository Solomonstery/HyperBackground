package com.ciallo.hyperbackground.appearance

import org.json.JSONObject
import java.util.Locale

/**
 * 「卡片」配置的剪贴板编解码器，结构对齐 HyperIsland 的 IslandMaterialService：
 * 导出为带标识的 JSON，导入时先校验标识再整体覆盖卡片字段。
 *
 * 顶层字段：
 * - `version`：格式版本，当前为 1；
 * - `type`：固定 [TYPE]，用于拒绝其他模块 / 其他页面的配置；
 * - `card`：卡片子参数（卡片样式、浅色/深色配色、模糊半径、柔光玻璃参数、深色跟随浅色）。
 *
 * 颜色统一按 `#AARRGGBB` 字符串落盘，与配色组件展示的十六进制一致。
 */
object CardConfigCodec {
    const val TYPE = "hyperbackground_card_config"
    const val VERSION = 1

    fun encode(settings: SettingsAppearanceSettings): String = JSONObject().apply {
        put("version", VERSION)
        put("type", TYPE)
        put(
            "card",
            JSONObject().apply {
                put("backgroundMode", settings.cardBackgroundMode)
                put("customEnabled", settings.customCardEnabled)
                put("lightColor", encodeColor(settings.lightCardColor))
                put("darkColor", encodeColor(settings.darkCardColor))
                put("lightFrostColor", encodeColor(settings.lightFrostColor))
                put("darkFrostColor", encodeColor(settings.darkFrostColor))
                put("lightBlur", settings.lightCardBlur)
                put("darkBlur", settings.darkCardBlur)
                put("darkFollowsLight", settings.cardDarkFollowsLight)
                put("lightSoftGlass", encodeSoftGlass(settings.lightSoftGlass))
                put("darkSoftGlass", encodeSoftGlass(settings.darkSoftGlass))
            },
        )
    }.toString(2)

    /**
     * 解析剪贴板 JSON，返回仅带卡片字段的配置对象；
     * 标识 / 版本不匹配或缺少 card 时抛 [IllegalArgumentException]，由调用方提示导入失败。
     */
    fun decode(raw: String): SettingsAppearanceSettings {
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == VERSION && root.optString("type") == TYPE) {
            "unsupported card config"
        }
        val card = root.optJSONObject("card") ?: throw IllegalArgumentException("missing card")
        val defaults = SettingsAppearanceSettings()
        return SettingsAppearanceSettings(
            cardBackgroundMode = card.optInt("backgroundMode", defaults.cardBackgroundMode),
            customCardEnabled = card.optBoolean("customEnabled", defaults.customCardEnabled),
            lightCardColor = decodeColor(card.optString("lightColor"), defaults.lightCardColor),
            darkCardColor = decodeColor(card.optString("darkColor"), defaults.darkCardColor),
            lightFrostColor = decodeColor(card.optString("lightFrostColor"), defaults.lightFrostColor),
            darkFrostColor = decodeColor(card.optString("darkFrostColor"), defaults.darkFrostColor),
            lightCardBlur = card.optInt("lightBlur", defaults.lightCardBlur).coerceIn(0, 80),
            darkCardBlur = card.optInt("darkBlur", defaults.darkCardBlur).coerceIn(0, 80),
            cardDarkFollowsLight = card.optBoolean("darkFollowsLight", defaults.cardDarkFollowsLight),
            lightSoftGlass = decodeSoftGlass(card.optJSONObject("lightSoftGlass")),
            darkSoftGlass = decodeSoftGlass(card.optJSONObject("darkSoftGlass")),
        )
    }

    /** 只把 [source] 的卡片字段合并进 [target]，其余（背景图、机型卡片、文字样式等）保持不动。 */
    fun mergeCard(
        target: SettingsAppearanceSettings,
        source: SettingsAppearanceSettings,
    ): SettingsAppearanceSettings = target.copy(
        cardBackgroundMode = source.cardBackgroundMode.coerceIn(CARD_BACKGROUND_COLOR, CARD_BACKGROUND_SOFT_GLASS),
        customCardEnabled = source.customCardEnabled,
        lightCardColor = source.lightCardColor,
        darkCardColor = source.darkCardColor,
        lightFrostColor = source.lightFrostColor,
        darkFrostColor = source.darkFrostColor,
        lightCardBlur = source.lightCardBlur,
        darkCardBlur = source.darkCardBlur,
        cardDarkFollowsLight = source.cardDarkFollowsLight,
        lightSoftGlass = source.lightSoftGlass,
        darkSoftGlass = source.darkSoftGlass,
    )

    /** 卡片配置恢复出厂：复用数据类默认值，只覆盖卡片相关字段。 */
    fun reset(target: SettingsAppearanceSettings): SettingsAppearanceSettings =
        mergeCard(target, SettingsAppearanceSettings())

    private fun encodeColor(value: Int): String = String.format(Locale.ROOT, "#%08X", value)

    /** 支持 `#AARRGGBB` 与 `#RRGGBB`（缺省不透明），无法解析时回退 [fallback]。 */
    private fun decodeColor(raw: String, fallback: Int): Int {
        val hex = raw.trim().removePrefix("#")
        val normalized = when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return fallback
        }
        return normalized.toLongOrNull(16)?.toInt() ?: fallback
    }

    private fun encodeSoftGlass(params: SoftGlassParams): JSONObject = JSONObject().apply {
        put("blurRadiusDp", params.blurRadiusDp)
        put("softLight", params.softLight)
        put("saturation", params.saturation)
        put("brightness", params.brightness)
        put("darker", params.darker)
        put("transparency", params.transparency)
        put("burn", params.burn)
        put("refraction", params.refraction)
        put("edgeThickness", params.edgeThickness)
        put("reflection", params.reflection)
        put("directionalLightIntensity", params.directionalLightIntensity)
        put("backgroundSaturation", params.backgroundSaturation)
        put("backgroundBrightness", params.backgroundBrightness)
        put("highlight", params.highlight)
    }

    private fun decodeSoftGlass(raw: JSONObject?): SoftGlassParams {
        val defaults = SoftGlassParams()
        raw ?: return defaults
        return SoftGlassParams(
            blurRadiusDp = raw.optInt("blurRadiusDp", defaults.blurRadiusDp).coerceIn(0, 80),
            softLight = raw.optDouble("softLight", defaults.softLight),
            saturation = raw.optDouble("saturation", defaults.saturation),
            brightness = raw.optDouble("brightness", defaults.brightness),
            darker = raw.optDouble("darker", defaults.darker),
            transparency = raw.optDouble("transparency", defaults.transparency),
            burn = raw.optDouble("burn", defaults.burn),
            refraction = raw.optDouble("refraction", defaults.refraction),
            edgeThickness = raw.optDouble("edgeThickness", defaults.edgeThickness),
            reflection = raw.optDouble("reflection", defaults.reflection),
            directionalLightIntensity = raw.optDouble("directionalLightIntensity", defaults.directionalLightIntensity),
            backgroundSaturation = raw.optDouble("backgroundSaturation", defaults.backgroundSaturation),
            backgroundBrightness = raw.optDouble("backgroundBrightness", defaults.backgroundBrightness),
            highlight = raw.optBoolean("highlight", defaults.highlight),
        )
    }
}
