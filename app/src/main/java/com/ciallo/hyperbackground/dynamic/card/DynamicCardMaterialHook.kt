package com.ciallo.hyperbackground.dynamic.card

import android.util.Log
import android.view.View
import android.content.SharedPreferences
import android.graphics.Canvas
import android.view.ViewGroup
import com.ciallo.hyperbackground.dynamic.popup.DynamicPopupMaterialHook
import com.ciallo.hyperbackground.dynamic.search.DynamicSearchMaterialHook
import com.ciallo.hyperbackground.dynamic.bar.DynamicFloatingBarHook
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 纯动态路由的 hook 层：不认任何第三方类名 / 资源名，只认**结构**与**行为**。
 *
 * 两个 hook 各自解决一个「纯动态无法覆盖」的缺口：
 *
 * 1. `View.onSizeChanged` —— 布局完成时机。`dispatchAttachedToWindow` / inflate 阶段
 *    `width == 0`，[CardSurfaceDetector] 必然早退成 `too-small`，通用路由形同虚设；
 *    在真实尺寸写回的那一刻补判一次，独立卡片才谈得上被接管。
 *
 * 2. `RecyclerView.addItemDecoration` —— 分组卡的发现入口。MIUIX 的分组卡**不是**某个 View 的
 *    背景，而是由 `ItemDecoration` 在 `onDraw` 里自己画的，所以视图树层面的判定永远碰不到它。
 *    改为 hook 这个**AndroidX 公开 API**，直接拿到运行时真实注册的装饰器实例，
 *    再按结构（字段类型 / 方法签名）反推它要怎么接管——不再需要知道它叫什么。
 */
internal object DynamicCardMaterialHook {

    private const val TAG = "HyperBackgroundCards"

    /** AndroidX 公开名，R8 不会改（模块与目标 apk 共用同一个 loader 里的这一份类）。 */
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

    private lateinit var module: XposedModule

    /** 已经处理过的装饰器类，避免同一页多次注册时重复挂 hook。 */
    private val processed = Collections.synchronizedSet(HashSet<Class<*>>())
    private val scanned = Collections.synchronizedMap(WeakHashMap<ViewGroup, Unit>())

    /** 成功接管的分组装饰器数量（诊断用）。 */
    @Volatile var discoveredDecorations: Int = 0
        private set

    /** Read the actual decorations, including builds where AndroidX method names are obfuscated. */
    internal fun hasGroupDecoration(view: View): Boolean {
        val type = runCatching { view.javaClass.classLoader?.loadClass(RECYCLER_VIEW_CLASS) }.getOrNull()
            ?: return false
        if (!type.isInstance(view)) return false
        return runCatching {
            decorations(view, type).any { DynamicCardBackgroundHook.isGroupDecoration(it.javaClass) }
        }.getOrDefault(false)
    }

    private fun decorationType(type: Class<*>): Class<*>? = type.declaredClasses.firstOrNull { candidate ->
        candidate.declaredMethods.any { method ->
            method.name == "getItemOffsets" && method.parameterTypes.firstOrNull() == android.graphics.Rect::class.java &&
                method.parameterTypes.any { it == type } &&
                candidate.declaredMethods.any { draw -> draw.name == "onDraw" &&
                    draw.parameterTypes.firstOrNull() == Canvas::class.java }
        }
    } ?: runCatching { type.classLoader?.loadClass("androidx.recyclerview.widget.RecyclerView\$ItemDecoration") }.getOrNull()

    private fun decorations(view: View, type: Class<*>): List<Any> {
        val decoration = decorationType(type) ?: return emptyList()
        val count = (type.getMethod("getItemDecorationCount").invoke(view) as? Int) ?: return emptyList()
        val getter = type.declaredMethods.firstOrNull {
            Modifier.isPublic(it.modifiers) && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                decoration.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true } ?: return emptyList()
        return (0 until count).mapNotNull { getter.invoke(view, it) }
    }

    fun install(value: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences) {
        module = value
        CardSurfaceDetector.onTranslucentCard = { view, alpha ->
            DynamicCardBackgroundHook.logCandidate(view, "matched translucent-card alpha=$alpha")
        }
        runCatching { installLayoutCompleteHook() }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic layout-complete hook unavailable", it) }
        runCatching { installItemDecorationHook(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic ItemDecoration discovery unavailable", it) }
        // MIUIX menus and list popups use their own rounded containers, not PopupWindow backgrounds.
        runCatching { DynamicPopupMaterialHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic popup material hook unavailable", it) }
        runCatching { DynamicFloatingBarHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic floating bar hook unavailable", it) }
        runCatching { DynamicSearchMaterialHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic search material hook unavailable", it) }
        module.log(
            Log.INFO, TAG,
            "Dynamic card routing installed: decorations=$discoveredDecorations",
        )
    }

    /**
     * 布局完成时机。`onSizeChanged` 只在尺寸**真的变化**时触发，所以这不是每帧路径：
     * 首次布局每个视图一次，之后只有 resize 才会再来。
     */
    private fun installLayoutCompleteHook() {
        val method = View::class.java.declaredMethods.firstOrNull { candidate ->
            candidate.name == "onSizeChanged" && candidate.parameterCount == 4 &&
                candidate.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: error("View.onSizeChanged(int,int,int,int) not found")
        method.isAccessible = true
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:layout-complete").intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View
                val width = chain.getArg(0) as? Int ?: 0
                val height = chain.getArg(1) as? Int ?: 0
                if (view != null && width > 0 && height > 0) {
                    DynamicCardBackgroundHook.onViewLaidOut(view)
                }
                result
            }
        module.log(Log.INFO, TAG, "Dynamic layout-complete hook installed")
    }

    /** Hook the two-argument registration implementation, including R8-renamed builds. */
    private fun installItemDecorationHook(classLoader: ClassLoader) {
        val type = classLoader.loadClass(RECYCLER_VIEW_CLASS)
        val decoration = decorationType(type) ?: error("RecyclerView.ItemDecoration not found")
        // PreferenceFragment's FrameDecoration can be registered before the runtime
        // addItemDecoration hook observes it. Discover its inner ItemDecoration by
        // hierarchy rather than the R8-renamed inner class name (Settings and Security
        // Center ship different MIUIX builds).
        runCatching {
            val preference = classLoader.loadClass("miuix.preference.PreferenceFragment")
            preference.declaredClasses.filter { decoration.isAssignableFrom(it) }
                .forEach(::onDecorationAdded)
        }.onFailure { module.log(Log.DEBUG, TAG, "Preference decoration discovery unavailable", it) }
        val methods = type.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterCount == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[0] == decoration
        }
        check(methods.isNotEmpty()) { "RecyclerView ItemDecoration registration not found" }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:add-decoration-$index").intercept { chain ->
                    val result = chain.proceed()
                    chain.getArg(0)?.let(::onDecorationAdded)
                    result
                }
        }
        // Discover decorations that were registered before the add hook was installed.
        // RecyclerView draws ItemDecorations in onDraw, so inspect before proceeding.
        val countMethod = type.getMethod("getItemDecorationCount")
        val onDraw = type.getDeclaredMethod("onDraw", Canvas::class.java)
            .apply { isAccessible = true }
        module.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:existing-decorations").intercept { chain ->
                val recycler = chain.thisObject as? ViewGroup
                if (recycler != null && scanned[recycler] == null) {
                    runCatching {
                        val count = countMethod.invoke(recycler) as Int
                        val existing = decorations(recycler, type)
                        val types = existing.map { it.javaClass.name }
                        if (recycler.context.packageName == "com.xiaomi.account") {
                            module.log(Log.INFO, TAG, "RecyclerView decorations: ${recycler.javaClass.name} " +
                                "count=$count classes=${types.joinToString()} " +
                                "group=${existing.any { DynamicCardBackgroundHook.isGroupDecoration(it.javaClass) }}")
                        }
                        existing.forEach(::onDecorationAdded)
                    }.onSuccess { scanned[recycler] = Unit }
                        .onFailure { error -> module.log(Log.DEBUG, TAG, "Existing decorations unavailable", error) }
                }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "Dynamic ItemDecoration discovery installed: ${methods.size} registration method(s)")
    }

    /**
     * 装饰器在 `RecyclerView` 初始化时注册，早于它的第一次绘制，所以这里挂它的绘制 / 裁剪方法
     * 是来得及的。能不能接管由 [DynamicCardBackgroundHook.installDynamicDecoration] 决定——
     * 那一关要求类里存在 `(Canvas, RectF, Path, Drawable)` 的裁剪方法，普通分隔线会在那里被排除。
     */
    private fun onDecorationAdded(decoration: Any) {
        val type = decoration.javaClass
        synchronized(processed) { if (!processed.add(type)) return }
        if (DynamicCardBackgroundHook.installDynamicDecoration(type)) {
            discoveredDecorations++
        } else {
            module.log(Log.DEBUG, TAG, "Dynamic decoration skipped: ${type.name}")
        }
    }
}
