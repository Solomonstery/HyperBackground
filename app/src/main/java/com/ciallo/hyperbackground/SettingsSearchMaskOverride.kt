package com.ciallo.hyperbackground

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ciallo.hyperbackground.util.callMethod
import com.ciallo.hyperbackground.util.hookMethod

/**
 * Keeps Settings' search overlays transparent so the home wallpaper remains visible.
 *
 * Search-box material is handled by DynamicSearchMaterialHook. These two masks are page
 * overlays owned by Settings itself and must stay independent from the search component switch:
 * otherwise typing a query makes the opaque loading/mask layer cover the hooked wallpaper.
 */
internal object SettingsSearchMaskOverride {
    private const val SETTINGS_FRAGMENT = "com.android.settings.SettingsFragment"

    fun install(classLoader: ClassLoader) {
        var installed = false

        runCatching {
            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "onInflateView",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Bundle::class.java,
            ) {
                (result as? View)?.let(::clearLoadingMask)
                clearFragmentMasks(thisObject)
            }
            installed = true
        }.onFailure { error ->
            HookRuntime.log("[HyperBackground] Settings search loading-mask hook unavailable: $error")
        }

        // Settings calls this again as the query/loading state changes. Re-clear after the
        // framework has written its native background so every keystroke keeps the wallpaper.
        runCatching {
            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "setSearchMaskVisiable",
                Boolean::class.javaPrimitiveType!!,
            ) {
                clearFragmentMasks(thisObject)
            }
            installed = true
        }.onFailure { error ->
            HookRuntime.log("[HyperBackground] Settings search window-mask hook unavailable: $error")
        }

        if (installed) {
            HookRuntime.log("[HyperBackground] Settings search masks made transparent")
        }
    }

    private fun clearFragmentMasks(fragment: Any?) {
        if (fragment == null) return
        runCatching { fragment.callMethod("getView") as? View }
            .getOrNull()
            ?.let(::clearLoadingMask)

        val activity = runCatching { fragment.callMethod("getActivity") as? Activity }
            .getOrNull() ?: return
        val id = activity.resources.getIdentifier("search_mask", "id", activity.packageName)
        if (id != 0) activity.window?.findViewById<View>(id)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun clearLoadingMask(root: View) {
        val id = root.resources.getIdentifier("search_loading", "id", root.context.packageName)
        if (id != 0) root.findViewById<View>(id)?.setBackgroundColor(Color.TRANSPARENT)
    }
}
