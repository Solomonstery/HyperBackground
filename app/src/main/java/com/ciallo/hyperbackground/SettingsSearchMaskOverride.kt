package com.ciallo.hyperbackground

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.ciallo.hyperbackground.util.callMethod
import com.ciallo.hyperbackground.util.hookMethod
import com.ciallo.hyperbackground.util.log

internal object SettingsSearchMaskOverride {
    private const val SETTINGS_FRAGMENT = "com.android.settings.SettingsFragment"

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
