package com.ciallo.hyperbackground.mydevice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ciallo.hyperbackground.dynamic.card.DynamicCardBackgroundHook

/** Runtime recreation of the tutorial's device_info_item_kashi and storage_info_item_kashi. */
class DeviceInfoCardsView(
    context: Context,
    private val nameSource: View,
    private val storageSource: View,
) : LinearLayout(context) {
    private val nameTitle = TextView(context)
    private val nameSummary = TextView(context)
    private val storageTitle = TextView(context)
    private val storageSummary = TextView(context)
    private val storageProgress = StorageProgressView(context)
    private lateinit var nameCard: View
    private lateinit var storageCard: View
    private val updateListener = ViewTreeObserver.OnPreDrawListener {
        refresh()
        true
    }
    private var listenerAttached = false

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
        gravity = Gravity.TOP

        addView(buildDeviceCard(context).also { nameCard = it }, LayoutParams(0, dp(148), 1f).apply { rightMargin = dp(4) })
        addView(buildStorageCard(context).also { storageCard = it }, LayoutParams(0, dp(148), 1f).apply { leftMargin = dp(4) })
        isClickable = false
        refresh()
    }

    fun attach() {
        if (listenerAttached) return
        viewTreeObserver.addOnPreDrawListener(updateListener)
        listenerAttached = true
    }

    fun dispose() {
        if (listenerAttached) {
            runCatching { viewTreeObserver.removeOnPreDrawListener(updateListener) }
            listenerAttached = false
        }
    }

    private fun buildDeviceCard(context: Context): View {
        val card = column(context).apply {
            setOnClickListener { nameSource.performClick() }
            isEnabled = nameSource.isEnabled
            alpha = nameSource.alpha
        }
        card.addView(DeviceSymbolView(context), LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            topMargin = dp(16)
        })
        card.addView(nameTitle, textParams(top = 12))
        card.addView(nameSummary, textParams(top = 4))
        return card
    }

    private fun buildStorageCard(context: Context): View {
        val card = column(context).apply {
            setOnClickListener { storageSource.performClick() }
            isEnabled = storageSource.isEnabled
            alpha = storageSource.alpha
        }
        val progressHost = FrameLayout(context)
        progressHost.addView(storageProgress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), Gravity.CENTER_VERTICAL))
        card.addView(progressHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(16)
        })
        card.addView(storageTitle, textParams(top = 12))
        card.addView(storageSummary, textParams(top = 4))
        return card
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(16), 0, dp(16), dp(16))
        isClickable = true
        isFocusable = true
    }

    private fun textParams(top: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun refresh() {
        nameTitle.text = childText(nameSource, "title").ifBlank { "设备名称" }
        nameSummary.text = childText(nameSource, "summary")
        storageTitle.text = childText(storageSource, "title").ifBlank { "存储空间" }
        storageSummary.text = childText(storageSource, "summary")
        storageProgress.progress = storageFraction(storageSummary.text?.toString().orEmpty())

        val primary = themedColor(android.R.attr.textColorPrimary, if (isNight()) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt())
        val secondary = themedColor(android.R.attr.textColorSecondary, if (isNight()) 0xB3FFFFFF.toInt() else 0x991B1B1B.toInt())
        listOf(nameTitle, storageTitle).forEach { configureText(it, primary, 16f) }
        listOf(nameSummary, storageSummary).forEach { configureText(it, secondary, 14f) }
        // 两张小卡卡面走卡片样式材质（柔光玻璃 → 磨砂 → 纯色 → 透明），ripple 色保留点击反馈。
        val ripple = themedColor(android.R.attr.colorControlHighlight, 0x22000000)
        DynamicCardBackgroundHook.applyCustomCardMaterial(nameCard, dp(19).toFloat(), ripple)
        DynamicCardBackgroundHook.applyCustomCardMaterial(storageCard, dp(19).toFloat(), ripple)
    }

    private fun configureText(view: TextView, color: Int, size: Float) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = false
        view.maxLines = if (size >= 16f) 1 else 2
        view.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private class DeviceSymbolView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8CB8FF.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 1.7f
        }
        private val speaker = Paint(stroke).apply { strokeWidth = context.resources.displayMetrics.density }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val circle = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(circle, width / 2f, height / 2f, fill)
            val phone = RectF(width * .31f, height * .20f, width * .69f, height * .80f)
            canvas.drawRoundRect(phone, width * .07f, width * .07f, stroke)
            canvas.drawLine(width * .42f, height * .69f, width * .58f, height * .69f, speaker)
        }
    }

    private class StorageProgressView(context: Context) : View(context) {
        var progress: Int = 0
            set(value) {
                field = value.coerceIn(0, 1000)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8CB8FF.toInt() }
        private val radius = context.resources.displayMetrics.density * 555f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, track)
            if (progress > 0) {
                val fillBounds = RectF(0f, 0f, width * progress / 1000f, height.toFloat())
                canvas.drawRoundRect(fillBounds, radius, radius, fill)
            }
        }
    }
}
