package com.ciallo.hyperbackground.dynamic.popup

import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_POPUP
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

/**
 * 弹窗整体背景的材质接管——**从源头**，不遍历内容树、不 hook `View.setBackground`。
 *
 * MIUI 的「右上角三点菜单」和「下拉选择框」都**不是** `android.widget.PopupWindow`，而是 MIUI
 * 自绘组件，所以 `PopupWindow.setBackgroundDrawable` 那条路对它们完全无效（这也是之前测试
 * 「hook installed 但弹窗没变色」的真正原因）：
 *
 * 1. **右上角三点菜单** → `miuix.popupwidget.widget.PopupView`（`FrameLayout` 子类）。
 *    背景在其 `applyContentView()` 里对 `content_view` 子 view 设置
 *    `R.attr.immersionWindowBackground` 解析出的 drawable。`PopupView` 提供公开方法
 *    `getContentView()` 直接拿到这个承载背景的 view。
 *
 * 2. **下拉选择框（ListPreference / 单选多选）** → `miuix.appcompat.app.AlertDialog`，其圆角
 *    面板是 `miuix.appcompat.internal.widget.DialogParentPanel2`（`LinearLayout` 子类）。
 *    背景来自它被 inflate 的 XML（`miuix_appcompat_alert_dialog_content`）里的 `android:background`
 *    属性，因此构造结束即已就绪，直接改 `view.background` 即可。
 *
 * 从源头解决：hook 这两个类的**构造方法**，构造完成后替换背景。每类每实例只触发一次，
 * 零遍历（PopupView 用其公开 `getContentView()` 直取，不 find、不递归）、零 post、零定时器。
 *
 * 类名说明：二者都是 `miuix` 库（以 jar 依赖打包进 apk）里的 `public class`，类名经 jadx 反编译
 * 验证为完整未混淆（R8 只压主 apk 自身的类，不改已编译的库类名），因此此处引用是安全的。
 */
internal object DynamicPopupMaterialHook {

    private const val TAG = "HyperBackgroundCards"

    /** miuix 菜单弹窗（右上角三点菜单）—— `FrameLayout` 自绘。 */
    private const val POPUP_VIEW_CLASS = "miuix.popupwidget.widget.PopupView"
    /** miuix 对话框圆角面板（下拉选择框的容器）—— `LinearLayout`。 */
    private const val DIALOG_PANEL_CLASS = "miuix.appcompat.internal.widget.DialogParentPanel2"

    private lateinit var module: XposedModule

    /** 目标 app 的 classLoader（miuix 类在目标进程里，必须用它加载）。 */
    private lateinit var targetLoader: ClassLoader
    @Volatile private var palette = DynamicMaterialPalette()
    private var preferences: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in setOf(
                KEY_CUSTOM_CARD_ENABLED, KEY_COMPONENT_POPUP, KEY_LIGHT_CARD_COLOR,
                KEY_DARK_CARD_COLOR, KEY_CARD_DARK_FOLLOWS_LIGHT,
            )
        ) palette = DynamicMaterialPalette.read(prefs)
    }

    /** 我们的替换是否正在写入，避免 hook 自己触发自己（递归）。 */
    private val writing = ThreadLocal<Boolean>()

    /** 已替换过的 view（身份去重），避免多构造链对同一实例重复替换。 */
    private val handled = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>()),
    )

    fun install(value: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences) {
        module = value
        targetLoader = classLoader
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        palette = DynamicMaterialPalette.read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        runCatching { installPopupViewHook() }
            .onFailure { module.log(Log.WARN, TAG, "PopupView constructor hook unavailable", it) }
        runCatching { installDialogPanelHook() }
            .onFailure { module.log(Log.WARN, TAG, "DialogParentPanel2 constructor hook unavailable", it) }
        module.log(Log.INFO, TAG, "Dynamic popup material hook installed (PopupView + DialogParentPanel2 sources)")
    }

    /**
     * 菜单弹窗：`PopupView` 构造完成后，用其公开方法 `getContentView()` 直取承载背景的 view 并替换。
     * 背景在 `init()` → `applyContentView()` 里已同步设好，构造返回即就绪，无需 post。
     */
    private fun installPopupViewHook() {
        val type = Class.forName(POPUP_VIEW_CLASS, false, targetLoader)
        type.declaredConstructors.forEachIndexed { index, ctor ->
            ctor.isAccessible = true
            module.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:popupview-ctor-$index").intercept { chain ->
                    val result = chain.proceed()
                    val popup = chain.thisObject as? View
                    if (popup != null && writing.get() != true) {
                        val contentView = runCatching {
                            type.getMethod("getContentView").invoke(popup) as? View
                        }.getOrNull()
                        if (contentView != null) {
                            replaceBackground(contentView, "menu PopupView.content")
                        } else {
                            // getContentView 拿不到时退化为「自身一层子 view 里有背景者」的浅查找，
                            // 仍然不做整树遍历。
                            findAndReplaceChildBackground(popup, "menu PopupView.child")
                        }
                    }
                    result
                }
        }
        module.log(Log.INFO, TAG, "PopupView constructor hook installed (${type.declaredConstructors.size} ctor)")
    }

    /**
     * 下拉框面板：`DialogParentPanel2` 自身即圆角面板，构造结束其 XML 背景已就绪，直接替换。
     */
    private fun installDialogPanelHook() {
        val type = Class.forName(DIALOG_PANEL_CLASS, false, targetLoader)
        type.declaredConstructors.forEachIndexed { index, ctor ->
            ctor.isAccessible = true
            module.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:dialogpanel-ctor-$index").intercept { chain ->
                    val result = chain.proceed()
                    val panel = chain.thisObject as? View
                    if (panel != null && writing.get() != true) {
                        replaceBackground(panel, "dialog DialogParentPanel2")
                    }
                    result
                }
        }
        module.log(Log.INFO, TAG, "DialogParentPanel2 constructor hook installed (${type.declaredConstructors.size} ctor)")
    }

    /** 替换 view 自身背景为色板填充克隆；色板关/无背景时不动。 */
    private fun replaceBackground(view: View, label: String) {
        if (!handled.add(view)) return
        val original = view.background ?: return
        val replacement = DynamicPopupBackground.create(original, view.context, palette)
        if (replacement == null) {
            module.log(Log.WARN, TAG, "$label bg: no replacement (palette off)")
            return
        }
        module.log(
            Log.INFO, TAG,
            "$label bg: original=${original.javaClass.name} -> tinted",
        )
        writing.set(true)
        try {
            view.background = replacement
        } finally {
            writing.remove()
        }
    }

    /** 浅查找（仅直接子 view）有背景者并替换，作为 getContentView 不可用时的兜底。 */
    private fun findAndReplaceChildBackground(parent: View, label: String) {
        if (parent !is ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child != null && child.background != null) {
                replaceBackground(child, label)
                return
            }
        }
    }
}
