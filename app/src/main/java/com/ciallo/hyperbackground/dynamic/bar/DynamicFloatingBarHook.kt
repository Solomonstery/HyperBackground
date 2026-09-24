package com.ciallo.hyperbackground.dynamic.bar

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_FLOATING_BAR
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

/** Only suspended MIUIX action menus; docked bottom bars keep their native material. */
internal object DynamicFloatingBarHook {
    private const val TAG = "HyperBackgroundBars"
    private const val VIEW_CLASS = "miuix.appcompat.internal.view.menu.action.ResponsiveActionMenuView"

    private val views = Collections.synchronizedMap(WeakHashMap<View, Unit>())
    private val materialSignatures = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val handler = Handler(Looper.getMainLooper())
    private var preferences: SharedPreferences? = null
    @Volatile private var palette = DynamicMaterialPalette()
    private var refresh: (() -> Unit)? = null
    private val keys = setOf(
        KEY_CUSTOM_CARD_ENABLED, KEY_COMPONENT_FLOATING_BAR, KEY_CARD_BACKGROUND_MODE,
        KEY_CARD_DARK_FOLLOWS_LIGHT, KEY_LIGHT_CARD_COLOR, KEY_DARK_CARD_COLOR,
        KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR, KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
        KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS,
        KEY_APP_SCOPE_DISABLED, KEY_APP_COMPONENT_DISABLED,
    )
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in keys) {
            palette = DynamicMaterialPalette.read(prefs)
            handler.post { refresh?.invoke() }
        }
    }

    fun install(module: XposedModule, loader: ClassLoader, prefs: SharedPreferences) {
        val type = Class.forName(VIEW_CLASS, false, loader)
        val update = type.getDeclaredMethod("updateBackground").apply { isAccessible = true }
        val suspend = type.getMethod("isSuspend")
        val blur = type.getMethod("isApplyBlur")
        val source = type.getDeclaredField("mSuspendMenuBackground").apply { isAccessible = true }

        module.hook(update).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bars:suspended-background").intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View ?: return@intercept result
                if (suspend.invoke(view) == true) {
                    views[view] = Unit
                    // Keep the native blur unless the user explicitly selected a solid color.
                    if (blur.invoke(view) != true || palette.mode == CARD_BACKGROUND_COLOR &&
                        palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.FLOATING_BAR)
                    ) {
                        val original = source.get(view) as? Drawable
                        FloatingBarMaterial.background(original, view.context, palette)
                            ?.let { view.background = it }
                    }
                }
                result
            }

        val layout = type.getDeclaredMethod(
            "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        module.hook(layout).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bars:material-layout").intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View ?: return@intercept result
                val signature = if (suspend.invoke(view) == true && blur.invoke(view) != true &&
                    view.isAttachedToWindow
                ) FloatingBarMaterial.signature(view, palette) else 0
                val previous = materialSignatures[view]
                if (previous != null && previous != signature) {
                    FloatingBarMaterial.clear(view)
                    materialSignatures.remove(view)
                }
                if (signature != 0 && materialSignatures[view] != signature &&
                    FloatingBarMaterial.apply(view, palette)
                ) materialSignatures[view] = signature
                result
            }

        val detach = type.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
        module.hook(detach).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bars:material-detach").intercept { chain ->
                val view = chain.thisObject as? View
                if (view != null && materialSignatures.remove(view) != null) {
                    FloatingBarMaterial.clear(view)
                }
                chain.proceed()
            }

        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        palette = DynamicMaterialPalette.read(prefs)
        refresh = {
            val active = synchronized(views) { views.keys.toList() }
            active.forEach { view ->
                if (view.isAttachedToWindow) runCatching {
                    update.invoke(view)
                    view.requestLayout()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        module.log(Log.INFO, TAG, "Suspended MIUIX action menu material hook installed")
    }
}
