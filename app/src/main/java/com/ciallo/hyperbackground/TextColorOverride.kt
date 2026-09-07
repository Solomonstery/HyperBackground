package com.ciallo.hyperbackground

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView

/** Keeps the user-selected Settings text mode stable when MIUIX/Preference rebinds views. */
internal object TextColorOverride {
    private val INTERNAL = ThreadLocal<Boolean>()

    @Volatile
    private var modeLoaded = false

    @Volatile
    private var cachedMode: Int = BackgroundContract.FONT_FOLLOW

    @JvmStatic
    fun invalidateConfig() {
        modeLoaded = false
    }

    @JvmStatic
    fun install() {
        try {
            hookMethod(TextView::class.java, "setTextColor", Int::class.javaPrimitiveType!!) {
                val textView = thisObject as? TextView ?: return@hookMethod
                if (INTERNAL.get() == true) return@hookMethod
                val mode = readMode(textView)
                if (mode != BackgroundContract.FONT_FOLLOW) args[0] = forcedColor(mode)
            }
            hookMethod(TextView::class.java, "setTextColor", ColorStateList::class.java) {
                val textView = thisObject as? TextView ?: return@hookMethod
                if (INTERNAL.get() == true) return@hookMethod
                val mode = readMode(textView)
                if (mode != BackgroundContract.FONT_FOLLOW) {
                    args[0] = ColorStateList.valueOf(forcedColor(mode))
                }
            }
        } catch (error: Throwable) {
            log("[HyperBackground] Could not install persistent text-color hook: $error")
            log(error)
        }
    }

    @JvmStatic
    fun apply(view: TextView, mode: Int) {
        cachedMode = mode
        modeLoaded = true
        if (mode == BackgroundContract.FONT_FOLLOW) return
        try {
            INTERNAL.set(true)
            view.setTextColor(forcedColor(mode))
        } finally {
            INTERNAL.remove()
        }
    }

    private fun readMode(view: TextView): Int {
        if (modeLoaded) return cachedMode
        return try {
            val context: Context = view.getContext() ?: return BackgroundContract.FONT_FOLLOW
            if (!BackgroundContract.isSupportedPackage(context.packageName)) {
                return BackgroundContract.FONT_FOLLOW
            }
            cachedMode = BackgroundContract.query(context, BackgroundContract.HOME).fontMode
            modeLoaded = true
            cachedMode
        } catch (_: Throwable) {
            BackgroundContract.FONT_FOLLOW
        }
    }

    private fun forcedColor(mode: Int): Int =
        if (mode == BackgroundContract.FONT_LIGHT) Color.WHITE else Color.rgb(24, 24, 26)
}
