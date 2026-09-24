package com.ciallo.hyperbackground.appearance

/**
 * 动态适配的「组件类型」键。
 *
 * 三个地方共用同一套键，保证「全局开关 / 按包开关 / hook 侧判定」三者的语义永远对得上：
 * - 「组件作用域」页的全局开关（`componentGroupCard` 等字段）；
 * - 「软件作用域详情」页的按包开关；
 * - hook 侧 `DynamicMaterialPalette.enabledFor(packageName, component)`。
 *
 * 配置里「某包的某组件被单独关闭」以 `"$component|$packageName"` 编码进
 * `SettingsAppearanceSettings.disabledAppComponents`：包名不含 `|`，所以拆分无歧义，
 * 且一个 StringSet 就能同时表达「组件 × 包」两个维度，不必为每个组件各开一个键。
 */
object ComponentKeys {
    const val GROUP_CARD = "group_card"
    const val STANDALONE_CARD = "standalone_card"
    const val POPUP = "popup"
    const val SEARCH = "search"
    const val FLOATING_BAR = "floating_bar"
    const val TOP_BAR_BUTTON = "top_bar_button"

    /**
     * 全局壁纸：把 GLOBAL 槽位那张背景图套用到作用域内各应用的大页面（GLOBAL 背景通道）。
     * 它不是「材质」，而是与材质并列的一类可独立开关的适配能力。
     */
    const val GLOBAL_WALLPAPER = "global_wallpaper"
    const val LAYOUT_CLEANUP = "layout_cleanup"

    /** 全部组件键，顺序即 UI 里的展示顺序。 */
    val ALL = listOf(
        GROUP_CARD,
        STANDALONE_CARD,
        POPUP,
        SEARCH,
        FLOATING_BAR,
        TOP_BAR_BUTTON,
        GLOBAL_WALLPAPER,
        LAYOUT_CLEANUP,
    )

    private const val SEPARATOR = '|'

    fun encode(component: String, packageName: String): String = "$component$SEPARATOR$packageName"

    fun componentOf(entry: String): String = entry.substringBefore(SEPARATOR)

    fun packageOf(entry: String): String = entry.substringAfter(SEPARATOR, "")
}
