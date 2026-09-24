package com.ciallo.hyperbackground

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.DEFAULT_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.DEFAULT_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_SEARCH
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.SETTINGS_APPEARANCE_PREFERENCES
import com.ciallo.hyperbackground.appearance.SoftGlassParams
import com.ciallo.hyperbackground.appearance.decodeSoftGlass
import com.ciallo.hyperbackground.dynamic.card.SettingsSoftGlassDrawable
import com.ciallo.hyperbackground.util.callMethod
import com.ciallo.hyperbackground.util.hookMethod
import com.ciallo.hyperbackground.util.log

internal object SettingsSearchMaskOverride {
    private const val SETTINGS_FRAGMENT = "com.android.settings.SettingsFragment"
    private const val SEARCH_ACTION_MODE_VIEW = "miuix.appcompat.internal.app.widget.SearchActionModeView"

    private data class SearchGlassConfig(
        val color: Int,
        val params: SoftGlassParams,
        val density: Float,
    )

    @JvmStatic
    fun install(classLoader: ClassLoader) {
        try {
            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "onInflateView",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Bundle::class.java,
            ) {
                val view = result as? View
                if (view != null) clearLoadingMask(view)
                clearWindowMask(thisObject)
            }

            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
            ) {
                val root = args[0] as? View ?: return@hookMethod
                applySearchSoftGlass(root)
            }

            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "setSearchMaskVisiable",
                Boolean::class.javaPrimitiveType!!,
            ) {
                clearWindowMask(thisObject)
            }

            hookMethod(
                SEARCH_ACTION_MODE_VIEW,
                classLoader,
                "onFinishInflate",
            ) {
                val searchView = thisObject as? View ?: return@hookMethod
                applyActionModeSoftGlass(searchView)
            }

            hookMethod(
                SEARCH_ACTION_MODE_VIEW,
                classLoader,
                "animateToVisibility",
                Boolean::class.javaPrimitiveType!!,
            ) {
                if (args[0] != true) return@hookMethod
                val searchView = thisObject as? View ?: return@hookMethod
                applyActionModeSoftGlass(searchView)
            }
            log("[HyperBackground] Settings search masks made transparent")
        } catch (error: Throwable) {
            log("[HyperBackground] Could not hook Settings search masks: $error")
            log(error)
        }
    }

    private fun applySearchSoftGlass(root: View) {
        try {
            val config = searchGlassConfig(root) ?: return
            val stubId = root.resources.getIdentifier("search_mode_stub", "id", root.context.packageName)
            val stub = if (stubId == 0) null else root.findViewById<View>(stubId)
            val inputArea = stub?.findViewById<View>(android.R.id.inputArea) ?: return
            applySoftGlass(inputArea, config)
        } catch (error: Throwable) {
            log("[HyperBackground] Could not apply soft glass to Settings search: $error")
        }
    }

    private fun applyActionModeSoftGlass(searchView: View) {
        try {
            val config = searchGlassConfig(searchView) ?: return
            val id = searchView.resources.getIdentifier("search_container", "id", searchView.context.packageName)
            val container = if (id == 0) null else searchView.findViewById<View>(id)
            if (container != null) applySoftGlass(container, config)
        } catch (error: Throwable) {
            log("[HyperBackground] Could not apply soft glass to active Settings search: $error")
        }
    }

    private fun searchGlassConfig(view: View): SearchGlassConfig? {
        val prefs = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES) ?: return null
        val values = prefs.all
        if (values[KEY_CUSTOM_CARD_ENABLED] as? Boolean != true) return null
        if (values[KEY_CARD_BACKGROUND_MODE] as? Int != CARD_BACKGROUND_SOFT_GLASS) return null
        // 组件作用域：搜索框软玻璃受「搜索框」开关控制，遮罩清除不受此开关影响。
        if (values[KEY_COMPONENT_SEARCH] as? Boolean == false) return null

        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val darkFollowsLight = values[KEY_CARD_DARK_FOLLOWS_LIGHT] as? Boolean ?: false
        val dark = night && !darkFollowsLight
        return SearchGlassConfig(
            color = if (dark) {
                values[KEY_DARK_FROST_COLOR] as? Int ?: DEFAULT_DARK_FROST_COLOR
            } else {
                values[KEY_LIGHT_FROST_COLOR] as? Int ?: DEFAULT_LIGHT_FROST_COLOR
            },
            params = decodeSoftGlass(
                values[if (dark) KEY_DARK_SOFT_GLASS else KEY_LIGHT_SOFT_GLASS] as? String,
            ),
            density = view.resources.displayMetrics.density,
        )
    }

    private fun applySoftGlass(view: View, config: SearchGlassConfig) {
        // The search bar's native background is not ours to repaint. It is a Miuix selector of
        // SmoothContainerDrawable2 (drawable/miuix_appcompat_search_mode_edit_text_bg_*), and the
        // system's own SearchViewMaterialImpl keys its BackgroundAlphaTarget on that very drawable
        // for `search_mode_stub`'s children: alpha is forced to 0 while its glass is on and animated
        // back to 1 when it is off. A palette color painted into it is therefore either invisible or
        // left as a flat film that replaces the native material - which is what made the initial
        // (collapsed) search box lose its soft glass while the opened input state stayed correct.
        // Keep the drawable as the shape/outline source and let the material own the fill: the tint
        // travels in the shader channels through the color-aware overload.
        SettingsSoftGlassDrawable.applyToView(view, config.color, config.params, config.density)
    }

    private fun clearLoadingMask(root: View) {
        val id = root.resources.getIdentifier("search_loading", "id", root.context.packageName)
        val loading = if (id == 0) null else root.findViewById<View>(id)
        loading?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun clearWindowMask(fragment: Any?) {
        try {
            val activity = fragment?.callMethod("getActivity") as? Activity ?: return
            val window: Window = activity.window ?: return
            val id = activity.resources.getIdentifier("search_mask", "id", activity.packageName)
            val mask = if (id == 0) null else window.findViewById<View>(id)
            mask?.setBackgroundColor(Color.TRANSPARENT)
        } catch (_: Throwable) {
        }
    }
}
