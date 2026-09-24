package com.ciallo.hyperbackground.dynamic.card

import android.util.Log
import android.view.View
import android.content.SharedPreferences
import com.ciallo.hyperbackground.dynamic.popup.DynamicPopupMaterialHook
import com.ciallo.hyperbackground.dynamic.bar.DynamicFloatingBarHook
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.util.Collections

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

    /** 成功接管的分组装饰器数量（诊断用）。 */
    @Volatile var discoveredDecorations: Int = 0
        private set

    fun install(value: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences) {
        module = value
        CardSurfaceDetector.onTranslucentCard = { view, alpha ->
            DynamicCardBackgroundHook.logCandidate(view, "matched translucent-card alpha=$alpha")
        }
        runCatching { installLayoutCompleteHook() }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic layout-complete hook unavailable", it) }
        runCatching { installItemDecorationHook(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic ItemDecoration discovery unavailable", it) }
        // 弹窗（菜单 / 下拉选择框）材质：MIUI 用 PopupView / miuix AlertDialog，不是 PopupWindow，
        // 单独一条入口，需要目标进程的 classLoader 才能定位 miuix 类。
        runCatching { DynamicPopupMaterialHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic popup material hook unavailable", it) }
        runCatching { DynamicFloatingBarHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic floating bar hook unavailable", it) }
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

    /** `addItemDecoration` 有两个重载（带 / 不带 index），都要接。 */
    private fun installItemDecorationHook(classLoader: ClassLoader) {
        val type = classLoader.loadClass(RECYCLER_VIEW_CLASS)
        val methods = type.declaredMethods.filter {
            it.name == "addItemDecoration" && it.parameterCount >= 1
        }
        check(methods.isNotEmpty()) { "RecyclerView.addItemDecoration not found" }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:add-decoration-$index").intercept { chain ->
                    val result = chain.proceed()
                    chain.getArg(0)?.let(::onDecorationAdded)
                    result
                }
        }
        module.log(Log.INFO, TAG, "Dynamic ItemDecoration discovery installed: ${methods.size} overload(s)")
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
