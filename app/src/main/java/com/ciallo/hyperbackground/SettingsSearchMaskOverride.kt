package com.ciallo.hyperbackground

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.Window

internal object SettingsSearchMaskOverride {
    private const val SETTINGS_FRAGMENT = "com.android.settings.SettingsFragment"

    @JvmStatic
    fun install(classLoader: ClassLoader) {
        try {
            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "onInflateView",
                android.view.LayoutInflater::class.java,
                android.view.ViewGroup::class.java,
                android.os.Bundle::class.java,
            ) {
                val view = result as? View
                if (view != null) clearLoadingMask(view)
                clearWindowMask(thisObject)
            }

            hookMethod(
                SETTINGS_FRAGMENT,
                classLoader,
                "setSearchMaskVisiable",
                Boolean::class.javaPrimitiveType!!,
            ) {
                clearWindowMask(thisObject)
            }
            log("[HyperBackground] Settings search masks made transparent")
        } catch (error: Throwable) {
            log("[HyperBackground] Could not hook Settings search masks: $error")
            log(error)
        }
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
