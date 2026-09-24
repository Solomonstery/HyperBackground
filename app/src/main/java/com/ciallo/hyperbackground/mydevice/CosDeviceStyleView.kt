package com.ciallo.hyperbackground.mydevice

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.ciallo.hyperbackground.appearance.COS_CARD_DEFAULT_SIGNATURE
import com.ciallo.hyperbackground.appearance.COS_CARD_DEFAULT_SUBTITLE
import com.ciallo.hyperbackground.appearance.COS_CARD_DEFAULT_TITLE
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSource
import com.ciallo.hyperbackground.dynamic.card.DynamicCardBackgroundHook
import java.util.Locale
import kotlin.math.roundToInt

/**
 * COS OS4「我的设备」样式（移植自设置美化源码 CustomAppearanceRenderer）。
 *
 * 顶部大卡：半透明玻璃底 + 左侧标题/副标题/签名 + 右侧壁纸手机缩略图与实时时钟。
 * 与本模块样式1/样式3一致，挂进系统全屏 overlay 宿主 miui_version_card_view，
 * 由宿主 session 逐帧同步 version_layout 的位移/缩放/透明度，保留吸顶滚走动画。
 */
class CosTopCardView(
    context: Context,
    private val stock: View,
    source: SettingsAppearanceSource,
) : FrameLayout(context) {
    private val wash = View(context)
    private val titleView = buildText(34, true)
    private val subtitleView = buildText(16, false)
    private val signatureView = buildText(11, false)

    init {
        clipToPadding = false
        // overlay 挂载时 layoutParams 已提供左右 12dp 边距与顶部状态栏间距，
        // 根布局只保留卡片上下留白（玻璃卡 150dp，含上下边共 180dp 占位）。
        setPadding(0, dp(16), 0, dp(14))

        val card = FrameLayout(context).apply { clipRounded(dp(20).toFloat()) }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, dp(150)))
        card.addView(wash, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
        }
        card.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(labels, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        subtitleView.isSingleLine = true
        signatureView.isSingleLine = true
        labels.addView(titleView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        labels.addView(subtitleView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        labels.addView(signatureView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val phoneHost = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }
        content.addView(phoneHost, LinearLayout.LayoutParams(dp(100), dp(150)).apply {
            marginStart = dp(10)
            marginEnd = dp(3)
        })

        val phone = FrameLayout(context).apply { clipRounded(dp(14).toFloat()) }
        phoneHost.addView(phone, LayoutParams(LayoutParams.MATCH_PARENT, dp(150)).apply {
            topMargin = dp(18)
        })

        val wallpaper = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(loadWallpaper(context))
            if (drawable == null) setBackgroundColor(0xff162660.toInt())
        }
        phone.addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        phone.addView(View(context).apply { setBackgroundColor(0x44000000) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        phone.addView(buildClock(18f, "hh:mm", "HH:mm"), frameWrap(Gravity.TOP or Gravity.START, 7, 10))
        phone.addView(buildClock(12.6f, "dd/MM", "dd/MM"), frameWrap(Gravity.TOP or Gravity.START, 7, 30))
        phone.addView(buildClock(12.6f, "EEE", "EEE"), frameWrap(Gravity.TOP or Gravity.START, 7, 44))

        val border = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(3), 0xccffffff.toInt())
        }
        phone.foreground = border

        isClickable = true
        isFocusable = true
        setOnClickListener { stock.performClick() }
        isLongClickable = false

        refresh(source)
    }

    fun refresh(source: SettingsAppearanceSource) {
        val night = isNight()
        val accent = monetAccent(context)
        val rawTitle = source.cosCardTitle.ifBlank { COS_CARD_DEFAULT_TITLE }
        val spanned = SpannableString(rawTitle)
        val accentStart = rawTitle.lastIndexOf("OS4")
        if (accentStart >= 0) {
            spanned.setSpan(
                ForegroundColorSpan(accent),
                accentStart,
                accentStart + 3,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        titleView.text = spanned
        titleView.setTextColor(if (night) 0xfff5f5f7.toInt() else 0xff17171a.toInt())
        subtitleView.text = source.cosCardSubtitle.ifBlank { COS_CARD_DEFAULT_SUBTITLE }
        subtitleView.setTextColor(if (night) 0xd9f5f5f7.toInt() else 0xcc17171a.toInt())
        signatureView.text = source.cosCardSignature.ifBlank { COS_CARD_DEFAULT_SIGNATURE }
        signatureView.setTextColor(if (night) 0x99f5f5f7.toInt() else 0x9917171a.toInt())
        // 卡面材质：柔光玻璃 → 磨砂 → 纯色 → 透明（不支持时直接透明）。
        DynamicCardBackgroundHook.applyCustomCardMaterial(wash, dp(20).toFloat())
    }

    private fun buildText(sp: Int, bold: Boolean) = TextView(context).apply {
        textSize = sp.toFloat()
        gravity = Gravity.START
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(0f, 1.05f)
        includeFontPadding = false
    }

    private fun buildClock(sp: Float, format12: String, format24: String) = TextClock(context).apply {
        textSize = sp
        setTextColor(0xe6ffffff.toInt())
        format12Hour = format12
        format24Hour = format24
        includeFontPadding = false
    }

    private fun frameWrap(gravity: Int, startDp: Int, topDp: Int) =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity).apply {
            marginStart = dp(startDp)
            topMargin = dp(topDp)
        }

    private fun loadWallpaper(context: Context): Drawable? = runCatching {
        WallpaperManager.getInstance(context).drawable?.let { drawable ->
            drawable.constantState?.newDrawable()?.mutate() ?: drawable
        }
    }.getOrNull()
}

/**
 * 设备名称 / 存储空间两张玻璃快卡。挂载方式与 [DeviceInfoCardsView] 一致：
 * 替换滚动流中原双卡容器，stock 卡片仅作为数据与点击来源。
 *
 * 性能约定（对应用户对逐帧卡顿的零容忍）：背景、字号、颜色等静态样式只在构造时
 * 设置一次；[attach] 后的每帧回调只更新文案与存储进度条，不分配 Drawable/Paint。
 */
class CosQuickCardsView(
    context: Context,
    private val nameSource: View,
    private val storageSource: View,
) : LinearLayout(context) {
    private val nameTitle = TextView(context)
    private val nameValue = TextView(context)
    private val storageTitle = TextView(context)
    private val storageValue = TextView(context)
    private val glyph = PhoneGlyphView(context)
    private val storageBar = StorageBarView(context)
    private val deviceCard: FrameLayout
    private val storageCard: FrameLayout
    private var attached = false

    private val updateListener = android.view.ViewTreeObserver.OnPreDrawListener {
        updateData()
        true
    }

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false

        deviceCard = buildDeviceCard()
        addView(deviceCard, LayoutParams(0, dp(130), 1f).apply {
            leftMargin = dp(12)
            rightMargin = dp(8)
            bottomMargin = dp(16)
        })
        storageCard = buildStorageCard()
        addView(storageCard, LayoutParams(0, dp(130), 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(12)
            bottomMargin = dp(16)
        })

        applyStaticStyle()
        updateData()
    }

    fun attach() {
        if (attached) return
        viewTreeObserver.addOnPreDrawListener(updateListener)
        attached = true
    }

    fun dispose() {
        if (!attached) return
        runCatching { viewTreeObserver.removeOnPreDrawListener(updateListener) }
        attached = false
    }

    private fun buildDeviceCard(): FrameLayout = FrameLayout(context).apply {
        clipRounded(dp(19).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { nameSource.performClick() }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val iconSlot = FrameLayout(context)
        content.addView(iconSlot, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            topMargin = dp(16)
        })
        iconSlot.addView(glyph, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.START or Gravity.CENTER_VERTICAL))
        content.addView(nameTitle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        content.addView(nameValue, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    private fun buildStorageCard(): FrameLayout = FrameLayout(context).apply {
        clipRounded(dp(19).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { storageSource.performClick() }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(storageBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(16)
            leftMargin = dp(5)
            rightMargin = dp(5)
        })
        content.addView(storageTitle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        content.addView(storageValue, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    /** 背景、颜色、字号：构造时一次性设置。 */
    private fun applyStaticStyle() {
        val night = isNight()
        // 两张快卡的卡面同样交给卡片样式材质（不支持时透明）。
        DynamicCardBackgroundHook.applyCustomCardMaterial(deviceCard, dp(19).toFloat())
        DynamicCardBackgroundHook.applyCustomCardMaterial(storageCard, dp(19).toFloat())
        val primary = if (night) 0xfff5f5f7.toInt() else 0xff111114.toInt()
        val secondary = if (night) 0xffaaaab2.toInt() else 0xff777780.toInt()
        listOf(nameTitle, storageTitle).forEach {
            it.setTextColor(primary)
            it.textSize = 16f
            it.includeFontPadding = false
        }
        listOf(nameValue, storageValue).forEach {
            it.setTextColor(secondary)
            it.textSize = 14f
            it.includeFontPadding = false
            it.maxLines = 2
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val accent = monetAccent(context)
        glyph.setAccent(accent)
        storageBar.setAccent(accent)
    }

    /** 每帧只更新文本与进度，零对象分配。 */
    private fun updateData() {
        nameTitle.text = childText(nameSource, "title").ifBlank { "设备名称" }
        nameValue.text = resolveDeviceName()
        storageTitle.text = childText(storageSource, "title").ifBlank { "存储空间" }
        val stockStorage = childText(storageSource, "summary")
        storageValue.text = if (stockStorage.contains("GB")) stockStorage else computeStorageText()
        storageBar.ratio = computeStorageRatio()
    }

    private fun resolveDeviceName(): String {
        childText(nameSource, "summary").takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return Build.MODEL ?: "Android"
    }

    private fun computeStorageText(): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val used = total - stat.availableBytes
        val divisor = 1024.0 * 1024.0 * 1024.0
        val totalGb = total / divisor
        val usedGb = used / divisor
        val totalText = if (totalGb >= 100) totalGb.roundToInt().toString()
        else String.format(Locale.US, "%.1f", totalGb)
        String.format(Locale.US, "%.1fGB/%sGB", usedGb, totalText)
    }.getOrDefault("读取中")

    private fun computeStorageRatio(): Float = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        if (total <= 0) 0f else (total - stat.availableBytes).toFloat() / total.toFloat()
    }.getOrDefault(0f).coerceIn(0f, 1f)
}

/** 强调色圆底 + 白色手机 glyph，对应源码 PhoneGlyph。 */
private class PhoneGlyphView(context: Context) : View(context) {
    private val circle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val phone = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val cut = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    init {
        circle.color = monetAccent(context)
        phone.color = android.graphics.Color.WHITE
        cut.color = circle.color
    }

    fun setAccent(color: Int) {
        circle.color = color
        cut.color = color
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, minOf(cx, cy), circle)
        val scale = minOf(width, height) / 32f
        canvas.drawRoundRect(9.2f * scale, 7.2f * scale, 22.6064f * scale, 24.8f * scale,
            1.4f * scale, 1.4f * scale, phone)
        canvas.drawRoundRect(13.5f * scale, 20.2f * scale, 18.5f * scale, 21.8f * scale,
            0.3f * scale, 0.3f * scale, cut)
    }
}

/** 存储横条，track 为强调色压暗，fill 为强调色，对应源码 StorageBar。 */
private class StorageBarView(context: Context) : View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var ratio: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        setAccent(monetAccent(context))
    }

    fun setAccent(accent: Int) {
        fill.color = accent
        track.color = darken(accent, 0.78f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val barHeight = resources.displayMetrics.density * 5f
        val top = (height - barHeight) / 2f
        val radius = barHeight / 2f
        canvas.drawRoundRect(0f, top, width.toFloat(), top + barHeight, radius, radius, track)
        val right = maxOf(barHeight, width * ratio)
        canvas.drawRoundRect(0f, top, right, top + barHeight, radius, radius, fill)
    }

    private fun darken(color: Int, factor: Float): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(color) * factor).roundToInt(),
        (android.graphics.Color.green(color) * factor).roundToInt(),
        (android.graphics.Color.blue(color) * factor).roundToInt(),
    )
}
