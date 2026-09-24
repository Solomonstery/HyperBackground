package com.ciallo.hyperbackground.mydevice

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「我的设备」页卡片替换链共用的小工具。
 *
 * 之前这些 helper 在 4 个卡片类里各写了一份（dp / isNight / sourceText / storageFraction），
 * 语义相同但实现有细微差别，改一处容易漏。统一收在这里后，卡片类只保留各自的布局与绘制逻辑。
 */

internal fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun View.dp(value: Float): Float = value * resources.displayMetrics.density

internal fun Context.deviceDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.deviceDp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun View.isNight(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun View.themedColor(attribute: Int, fallback: Int): Int = TypedValue().let { value ->
    if (context.theme.resolveAttribute(attribute, value, true)) {
        if (value.resourceId != 0) runCatching { context.getColor(value.resourceId) }.getOrDefault(value.data) else value.data
    } else fallback
}

/** 读取 stock 卡片内部的 title / summary 文案；找不到对应 id 时返回空串。 */
internal fun View.childText(source: View, idName: String): String {
    val id = resources.getIdentifier(idName, "id", context.packageName)
    return (source.findViewById<View>(id) as? TextView)?.text?.toString()?.trim().orEmpty()
}

/** 同上，但找不到 id 时退化为 source 自身（版本号等直接挂在 source 上的场景）。 */
internal fun View.textOrSelf(source: View?, idName: String): String {
    val id = resources.getIdentifier(idName, "id", context.packageName)
    val target = source?.findViewById<View>(id) ?: source
    return (target as? TextView)?.text?.toString().orEmpty()
}

/** 从「已用 64.2GB/256GB」这类文案里解出 0..1000 的进度值。 */
internal fun storageFraction(summary: String): Int {
    val values = STORAGE_VALUE.findAll(summary).take(2).mapNotNull { match ->
        val raw = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
        val multiplier = when (match.groupValues[2].uppercase(Locale.ROOT).firstOrNull()) {
            'T' -> 1024f * 1024f
            'G' -> 1024f
            'M' -> 1f
            'K' -> 1f / 1024f
            else -> 1f
        }
        raw * multiplier
    }.toList()
    if (values.size < 2 || values[1] <= 0f) return 0
    return (values[0] / values[1] * 1000f).roundToInt().coerceIn(0, 1000)
}

internal val STORAGE_VALUE = Regex("(\\d+(?:[.,]\\d+)?)\\s*([KMGTkmgt])?[Bb]?")

internal fun View.clipRounded(radius: Float) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}

internal fun monetAccent(context: Context): Int = runCatching {
    context.getColor(android.R.color.system_accent1_500)
}.getOrDefault(0xff7183aa.toInt())

/** 顶部大卡的通用挂载参数：左右 12dp、顶部 18dp、高度 [height] dp。 */
internal fun overlayCardLayoutParams(context: Context, height: Int) = FrameLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    context.deviceDp(height),
    android.view.Gravity.TOP,
).apply {
    leftMargin = context.deviceDp(12)
    rightMargin = context.deviceDp(12)
    topMargin = context.deviceDp(18)
}

/** 复制一份布局参数并把高度压到 [height]，用于收紧版本卡下方的占位间隔。 */
internal fun compactVersionCardSpacer(params: ViewGroup.LayoutParams, height: Int): ViewGroup.LayoutParams = when (params) {
    is android.widget.LinearLayout.LayoutParams -> android.widget.LinearLayout.LayoutParams(params).apply { this.height = height }
    is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params).apply { this.height = height }
    is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params).apply { this.height = height }
    else -> ViewGroup.LayoutParams(params).apply { this.height = height }
}
