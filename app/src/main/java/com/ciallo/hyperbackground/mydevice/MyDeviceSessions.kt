package com.ciallo.hyperbackground.mydevice

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 「我的设备」页卡片替换的宿主会话。
 *
 * 每个 session 负责三件事：记住宿主容器原有状态（背景 / 子 View 可见性 / 滚动条）、
 * 每次挂载时重新强制生效（enforce）、卸载时逐项还原（remove）。
 * 与卡片 View 一一对应，由 [MyDeviceCardApplier] 统一持有在 WeakHashMap 里。
 */

internal class TutorialCardSession(
    private val host: FrameLayout,
    private val spacer: View?,
    private val spacerLayoutParams: ViewGroup.LayoutParams?,
    private val animationSource: View?,
    val view: TutorialDeviceCardView,
    val key: String,
) {
    private val originalHostBackground = host.background
    private val originalChildVisibility = List(host.childCount) { index ->
        host.getChildAt(index)
    }.filter { it !== view }.map { it to it.visibility }
    private val animationSync = ViewTreeObserver.OnPreDrawListener {
        syncCardAnimation()
        true
    }
    private var animationListenerAttached = false

    fun matches(host: FrameLayout, spacer: View?): Boolean =
        this.host === host && this.spacer === spacer && view.parent === host

    fun enforceTutorialLayout(context: android.content.Context) {
        // Keep MiuiVersionCard itself alive: Settings moves it while scrolling.
        // Only its stock children are hidden, so the imported logo and card are
        // the sole visible content while all presenter references stay valid.
        host.background = null
        view.visibility = View.VISIBLE
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.INVISIBLE }
        attachAnimationSync()
        syncCardAnimation()
        val original = spacerLayoutParams ?: return
        spacer?.layoutParams = compactVersionCardSpacer(original, context.deviceDp(211))
    }

    fun remove() {
        if (animationListenerAttached) {
            runCatching { host.viewTreeObserver.removeOnPreDrawListener(animationSync) }
            animationListenerAttached = false
        }
        host.removeView(view)
        host.background = originalHostBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        spacer?.let { view -> spacerLayoutParams?.let { view.layoutParams = it } }
    }

    private fun attachAnimationSync() {
        if (animationListenerAttached) return
        host.viewTreeObserver.addOnPreDrawListener(animationSync)
        animationListenerAttached = true
    }

    private fun syncCardAnimation() {
        val source = animationSource ?: return
        view.translationX = source.translationX
        view.translationY = source.translationY
        view.scaleX = source.scaleX
        view.scaleY = source.scaleY
        view.alpha = source.alpha
    }
}

internal class HarmonyCardSession(
    private val host: FrameLayout,
    private val spacer: View?,
    private val spacerLayoutParams: ViewGroup.LayoutParams?,
    private val animationSource: View?,
    val view: HarmonyUpdateCardView,
    val key: String,
) {
    private val originalHostBackground = host.background
    private val originalChildVisibility = List(host.childCount) { index -> host.getChildAt(index) }
        .filter { it !== view }
        .map { it to it.visibility }
    private val animationSync = ViewTreeObserver.OnPreDrawListener { sync(); true }
    private var attached = false

    fun matches(host: FrameLayout, spacer: View?): Boolean = this.host === host && this.spacer === spacer && view.parent === host

    fun enforce(context: android.content.Context) {
        host.background = null
        view.visibility = View.VISIBLE
        view.attach()
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.INVISIBLE }
        if (!attached) {
            host.viewTreeObserver.addOnPreDrawListener(animationSync)
            attached = true
        }
        sync()
        val original = spacerLayoutParams ?: return
        spacer?.layoutParams = compactVersionCardSpacer(original, context.deviceDp(274))
    }

    fun remove() {
        view.dispose()
        if (attached) runCatching { host.viewTreeObserver.removeOnPreDrawListener(animationSync) }
        attached = false
        host.removeView(view)
        host.background = originalHostBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        spacer?.let { spacerLayoutParams?.let { params -> it.layoutParams = params } }
    }

    private fun sync() {
        val source = animationSource ?: return
        view.translationX = source.translationX
        view.translationY = source.translationY
        view.scaleX = source.scaleX
        view.scaleY = source.scaleY
        view.alpha = source.alpha
    }
}

internal class CosTopCardSession(
    private val host: FrameLayout,
    private val spacer: View?,
    private val spacerLayoutParams: ViewGroup.LayoutParams?,
    private val animationSource: View?,
    val view: CosTopCardView,
    val key: String,
) {
    private val originalHostBackground = host.background
    private val originalChildVisibility = List(host.childCount) { index -> host.getChildAt(index) }
        .filter { it !== view }
        .map { it to it.visibility }
    private val animationSync = ViewTreeObserver.OnPreDrawListener {
        syncCardAnimation()
        true
    }
    private var animationListenerAttached = false

    fun matches(host: FrameLayout, spacer: View?): Boolean =
        this.host === host && this.spacer === spacer && view.parent === host

    fun enforce(context: android.content.Context) {
        host.background = null
        view.visibility = View.VISIBLE
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.INVISIBLE }
        if (!animationListenerAttached) {
            host.viewTreeObserver.addOnPreDrawListener(animationSync)
            animationListenerAttached = true
        }
        syncCardAnimation()
        val original = spacerLayoutParams ?: return
        spacer?.layoutParams = compactVersionCardSpacer(original, context.deviceDp(211))
    }

    fun remove() {
        if (animationListenerAttached) {
            runCatching { host.viewTreeObserver.removeOnPreDrawListener(animationSync) }
            animationListenerAttached = false
        }
        host.removeView(view)
        host.background = originalHostBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        spacer?.let { spacerView -> spacerLayoutParams?.let { spacerView.layoutParams = it } }
    }

    private fun syncCardAnimation() {
        val source = animationSource ?: return
        view.translationX = source.translationX
        view.translationY = source.translationY
        view.scaleX = source.scaleX
        view.scaleY = source.scaleY
        view.alpha = source.alpha
    }
}

internal class CosQuickCardsSession(
    private val parent: LinearLayout,
    private val name: View,
    private val storage: View,
    private val view: CosQuickCardsView,
) {
    private val originalBackground = parent.background
    private val originalChildVisibility = List(parent.childCount) { index ->
        parent.getChildAt(index)
    }.filter { child -> child !== view }.map { child -> child to child.visibility }
    // 参数大卡（device_params）由动态卡面路由（CardSurfaceDetector）接管，
    // 与页面内其它原生卡片一致走「卡片样式」三种材质，这里不再写死玻璃色。

    fun matches(parent: LinearLayout, name: View, storage: View): Boolean =
        this.parent === parent && this.name === name && this.storage === storage && view.parent === parent

    fun enforce() {
        parent.background = null
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        view.attach()
    }

    fun remove() {
        view.dispose()
        (view.parent as? ViewGroup)?.removeView(view)
        parent.background = originalBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
    }
}

internal class DeviceInfoCardsSession(
    private val parent: LinearLayout,
    private val name: View,
    private val storage: View,
    private val view: DeviceInfoCardsView,
) {
    private val originalBackground = parent.background
    private val originalChildVisibility = List(parent.childCount) { index ->
        parent.getChildAt(index)
    }.filter { child -> child !== view }.map { child -> child to child.visibility }

    fun matches(parent: LinearLayout, name: View, storage: View): Boolean =
        this.parent === parent && this.name === name && this.storage === storage && view.parent === parent

    fun enforce() {
        // The stock container also owns the OS/guarantee rows and a shared
        // background. Hide all of it while retaining the name/storage views
        // as live data sources for the replacement cards.
        parent.background = null
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        view.attach()
    }

    fun remove() {
        view.dispose()
        (view.parent as? ViewGroup)?.removeView(view)
        parent.background = originalBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
    }
}

internal class HarmonyInfoCardsSession(
    private val parent: LinearLayout,
    private val name: View,
    private val storage: View,
    val view: HarmonyInfoCardsView,
    private val key: String,
) {
    private val scrollbarStates = HashMap<View, Pair<Boolean, Boolean>>()
    private val originalBackground = parent.background
    private val originalChildVisibility = List(parent.childCount) { index -> parent.getChildAt(index) }
        .filter { it !== view }
        .map { it to it.visibility }

    init {
        // The replacement row lives inside the page's NestedScrollView.
        // Hide scrollbars on that ancestor as well as on the original
        // card subtree; otherwise the first layout can flash the stock
        // scrollbar when the row is inserted.
        captureAndHideScrollbars(parent)
        var ancestor = parent.parent
        while (ancestor is View) {
            captureAndHideScrollbars(ancestor)
            ancestor = ancestor.parent
        }
    }

    fun matches(parent: LinearLayout, name: View, storage: View, key: String): Boolean =
        this.parent === parent && this.name === name && this.storage === storage && this.key == key && view.parent === parent

    fun enforce() {
        parent.background = null
        originalChildVisibility.forEach { (child, _) -> child.visibility = View.GONE }
        hideScrollbars(parent)
        view.attach()
    }

    fun remove() {
        view.dispose()
        (view.parent as? ViewGroup)?.removeView(view)
        parent.background = originalBackground
        originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        scrollbarStates.forEach { (child, state) ->
            child.isVerticalScrollBarEnabled = state.first
            child.isHorizontalScrollBarEnabled = state.second
        }
    }

    private fun captureAndHideScrollbars(target: View) {
        scrollbarStates.putIfAbsent(target, target.isVerticalScrollBarEnabled to target.isHorizontalScrollBarEnabled)
        hideScrollbars(target)
        if (target is ViewGroup) {
            for (index in 0 until target.childCount) captureAndHideScrollbars(target.getChildAt(index))
        }
    }

    private fun hideScrollbars(target: View) {
        target.isVerticalScrollBarEnabled = false
        target.isHorizontalScrollBarEnabled = false
        if (target is ViewGroup) {
            for (index in 0 until target.childCount) hideScrollbars(target.getChildAt(index))
        }
    }
}
