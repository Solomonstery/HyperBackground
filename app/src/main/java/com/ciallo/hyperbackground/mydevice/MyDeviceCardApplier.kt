package com.ciallo.hyperbackground.mydevice

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_DEVICE_IMAGE
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_ONE
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_THREE
import com.ciallo.hyperbackground.appearance.DEVICE_INTERFACE_STYLE_TWO
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSource
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSources
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「我的设备」页卡片替换的调度入口。
 *
 * 生命周期由 [com.ciallo.hyperbackground.appearance.SettingsAppearanceApplier.applyDevice] 转发：
 * 每次页面可见时调 [apply]（内部按 deviceInterfaceStyle 三选一，并清理其余样式的残留），
 * 页面销毁时调 [destroy] 逐个还原宿主状态。
 */
object MyDeviceCardApplier {
    private val tutorialCards = Collections.synchronizedMap(WeakHashMap<Any, TutorialCardSession>())
    private val deviceInfoCards = Collections.synchronizedMap(WeakHashMap<Any, DeviceInfoCardsSession>())
    private val harmonyCards = Collections.synchronizedMap(WeakHashMap<Any, HarmonyCardSession>())
    private val harmonyInfoCards = Collections.synchronizedMap(WeakHashMap<Any, HarmonyInfoCardsSession>())
    private val cosTopCards = Collections.synchronizedMap(WeakHashMap<Any, CosTopCardSession>())
    private val cosQuickCards = Collections.synchronizedMap(WeakHashMap<Any, CosQuickCardsSession>())

    fun apply(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return
            val targetId = context.resources.getIdentifier("miui_version_card_view", "id", context.packageName)
            // MiuiVersionCard is the scroll-aware host. The tutorial replaces its
            // layout content, rather than creating a root-level sibling, so its
            // translation remains coupled to the My Device scroll position.
            val target = root.findViewById<View>(targetId) as? FrameLayout ?: return
            val spacerId = context.resources.getIdentifier("version_card_click_view", "id", context.packageName)
            val spacer = root.findViewById<View>(spacerId)
            val animationLayoutId = context.resources.getIdentifier("version_layout", "id", context.packageName)
            // MiuiVersionCard animates this view on every scroll. Capture it
            // before adding our independent replacement card.
            val animationSource = target.findViewById<View>(animationLayoutId)
            val style = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE).deviceInterfaceStyle
            when (style) {
                DEVICE_INTERFACE_STYLE_ONE -> {
                    harmonyCards.remove(fragment)?.remove()
                    harmonyInfoCards.remove(fragment)?.remove()
                    cosTopCards.remove(fragment)?.remove()
                    cosQuickCards.remove(fragment)?.remove()
                    val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE)
                    val logo = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO)
                    applyDeviceInfoCards(fragment, context, root, source.copy(tutorialCardInfoCardsEnabled = true))
                    val old = tutorialCards[fragment]
                    val background = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND)
                    val key = source.cacheKey() + logo.cacheKey() + background.cacheKey()
                    if (old != null && old.matches(target, spacer) && old.key == key) {
                        old.enforceTutorialLayout(context)
                        old.view.refresh(context, source.tutorialCardImageScale, source.tutorialCardAuthor, source.tutorialCardLogoScale, source.tutorialCardLogoVerticalOffset, source.tutorialCardImageLogoSpacing, source.tutorialCardTextSpacing, source.tutorialCardBackgroundBlur, source.tutorialCardBackgroundHorizontalOffset, source.tutorialCardBackgroundVerticalOffset, source.tutorialCardBackgroundScale)
                    } else {
                        old?.remove()
                        val card = TutorialDeviceCardView(context, source, logo, background, root.findViewById<View>(context.resources.getIdentifier("miui_version_text", "id", context.packageName)))
                        target.addView(card, overlayCardLayoutParams(context, 180))
                        val session = TutorialCardSession(target, spacer, spacer?.layoutParams, animationSource, card, key)
                        session.enforceTutorialLayout(context)
                        tutorialCards[fragment] = session
                        card.refresh(context, source.tutorialCardImageScale, source.tutorialCardAuthor, source.tutorialCardLogoScale, source.tutorialCardLogoVerticalOffset, source.tutorialCardImageLogoSpacing, source.tutorialCardTextSpacing, source.tutorialCardBackgroundBlur, source.tutorialCardBackgroundHorizontalOffset, source.tutorialCardBackgroundVerticalOffset, source.tutorialCardBackgroundScale)
                    }
                }
                DEVICE_INTERFACE_STYLE_TWO -> {
                    tutorialCards.remove(fragment)?.remove()
                    deviceInfoCards.remove(fragment)?.remove()
                    harmonyCards.remove(fragment)?.remove()
                    harmonyInfoCards.remove(fragment)?.remove()
                    val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE)
                    val old = cosTopCards[fragment]
                    val key = source.cacheKey()
                    if (old != null && old.matches(target, spacer) && old.key == key) {
                        old.enforce(context)
                        old.view.refresh(source)
                    } else {
                        old?.remove()
                        val card = CosTopCardView(context, target, source)
                        target.addView(card, overlayCardLayoutParams(context, 180))
                        val session = CosTopCardSession(target, spacer, spacer?.layoutParams, animationSource, card, key)
                        session.enforce(context)
                        cosTopCards[fragment] = session
                    }
                    applyCosQuickCards(fragment, context, root)
                }
                DEVICE_INTERFACE_STYLE_THREE -> {
                    tutorialCards.remove(fragment)?.remove()
                    deviceInfoCards.remove(fragment)?.remove()
                    cosTopCards.remove(fragment)?.remove()
                    cosQuickCards.remove(fragment)?.remove()
                    val image = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE)
                    val logo = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO)
                    val background = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND)
                    val old = harmonyCards[fragment]
                    val key = image.cacheKey() + logo.cacheKey() + background.cacheKey()
                    val updateSource = root.findViewById<View>(context.resources.getIdentifier("miui_version_text", "id", context.packageName))
                    if (old != null && old.matches(target, spacer) && old.key == key) {
                        old.enforce(context)
                        old.view.refresh(image)
                    } else {
                        old?.remove()
                        val card = HarmonyUpdateCardView(context, logo, updateSource, background)
                        target.addView(card, overlayCardLayoutParams(context, 243))
                        val session = HarmonyCardSession(target, spacer, spacer?.layoutParams, animationSource, card, key)
                        session.enforce(context)
                        harmonyCards[fragment] = session
                        card.refresh(image)
                    }
                    applyHarmonyInfoCards(fragment, context, root, image)
                }
                else -> {
                    clear(fragment)
                }
            }
        }.onFailure { error -> Log.e(TAG, "device card apply failed", error) }
    }

    fun clear(fragment: Any?) {
        if (fragment == null) return
        tutorialCards.remove(fragment)?.remove()
        deviceInfoCards.remove(fragment)?.remove()
        harmonyCards.remove(fragment)?.remove()
        harmonyInfoCards.remove(fragment)?.remove()
        cosTopCards.remove(fragment)?.remove()
        cosQuickCards.remove(fragment)?.remove()
    }

    private fun applyHarmonyInfoCards(fragment: Any, context: android.content.Context, root: View, image: SettingsAppearanceSource) {
        val old = harmonyInfoCards[fragment]
        val nameId = context.resources.getIdentifier("device_name_card_view", "id", context.packageName)
        val storageId = context.resources.getIdentifier("device_memory_card_view", "id", context.packageName)
        val name = root.findViewById<View>(nameId) ?: return
        val storage = root.findViewById<View>(storageId) ?: return
        val parent = name.parent as? LinearLayout ?: return
        if (parent !== storage.parent) return
        if (old != null && old.matches(parent, name, storage, image.cacheKey())) {
            old.enforce()
            old.view.refresh(image.style2ImageScale)
            return
        }
        old?.remove()
        val row = HarmonyInfoCardsView(context, name, storage, image)
        val index = parent.indexOfChild(name).coerceAtLeast(0)
        // The stock parameter card adds a 12dp top margin. Reduce this
        // replacement row's contribution from 10dp to 5dp so the combined
        // gap is approximately 75% of the previous spacing.
        parent.addView(row, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.deviceDp(182)).apply { bottomMargin = context.deviceDp(4.88f) })
        val session = HarmonyInfoCardsSession(parent, name, storage, row, image.cacheKey())
        session.enforce()
        harmonyInfoCards[fragment] = session
    }

    private fun applyDeviceInfoCards(
        fragment: Any,
        context: android.content.Context,
        root: View,
        source: SettingsAppearanceSource,
    ) {
        val old = deviceInfoCards[fragment]
        if (!source.tutorialCardInfoCardsEnabled) {
            old?.remove()
            deviceInfoCards.remove(fragment)
            return
        }
        val nameId = context.resources.getIdentifier("device_name_card_view", "id", context.packageName)
        val storageId = context.resources.getIdentifier("device_memory_card_view", "id", context.packageName)
        val name = root.findViewById<View>(nameId) ?: return
        val storage = root.findViewById<View>(storageId) ?: return
        val parent = name.parent as? LinearLayout ?: return
        if (parent !== storage.parent) return
        if (old != null && old.matches(parent, name, storage)) {
            old.enforce()
            return
        }
        old?.remove()
        val row = DeviceInfoCardsView(context, name, storage)
        val index = parent.indexOfChild(name).coerceAtLeast(0)
        parent.addView(row, index, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.deviceDp(148),
        ).apply {
            bottomMargin = context.deviceDp(6)
        })
        val session = DeviceInfoCardsSession(parent, name, storage, row)
        session.enforce()
        deviceInfoCards[fragment] = session
    }

    private fun applyCosQuickCards(fragment: Any, context: android.content.Context, root: View) {
        val old = cosQuickCards[fragment]
        val nameId = context.resources.getIdentifier("device_name_card_view", "id", context.packageName)
        val storageId = context.resources.getIdentifier("device_memory_card_view", "id", context.packageName)
        val name = root.findViewById<View>(nameId) ?: return
        val storage = root.findViewById<View>(storageId) ?: return
        val parent = name.parent as? LinearLayout ?: return
        if (parent !== storage.parent) return
        if (old != null && old.matches(parent, name, storage)) {
            old.enforce()
            return
        }
        old?.remove()
        val row = CosQuickCardsView(context, name, storage)
        val index = parent.indexOfChild(name).coerceAtLeast(0)
        parent.addView(row, index, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.deviceDp(130),
        ))
        val session = CosQuickCardsSession(parent, name, storage, row)
        session.enforce()
        cosQuickCards[fragment] = session
    }

    private const val TAG = "HyperChangerMyDevice"
}
