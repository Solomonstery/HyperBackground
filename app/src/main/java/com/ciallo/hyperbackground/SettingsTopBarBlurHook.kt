package com.ciallo.hyperbackground

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

import java.lang.Boolean.TYPE as BOOL
import java.lang.Integer.TYPE as INT
import java.lang.reflect.Method

import kotlin.math.min

object SettingsTopBarBlurHook {
    private const val HOME_ACTIVITY = "com.android.settings.MiuiSettings"
    private const val HOME_LAYOUT = "hyperbackground_settings_home_layout"
    private const val MANAGED_ACTIVITY = "hyperbackground_settings_blur_activity"
    private const val NESTED_ACTIVITY = "hyperbackground_settings_nested_activity"
    private const val ACTION_BAR_BLUR_VIEW = "hyperbackground_action_bar_blur_view"
    private const val LOG_SCROLL = "hyperbackground_blur_logged_scroll"
    private const val LOG_READY = "hyperbackground_blur_logged_ready"
    private const val LOG_BAR_MASK = "hyperbackground_blur_logged_bar_mask"
    private const val LOG_BAR_BLUR = "hyperbackground_blur_logged_bar_view"
    private const val BAR_BLUR_SUPPRESSED = "hyperbackground_bar_blur_suppressed"
    private lateinit var setBlurTypeMethod: Method
    private var setBlurModeMethod: Method? = null
    private var setViewBlurModeMethod: Method? = null
    private var setGradientParamsMethod: Method? = null
    private var clearBlendColorMethod: Method? = null

    fun install(classLoader: ClassLoader) {
        try {
            setBlurTypeMethod = View::class.java.getMethod("setMiBackgroundBlurType", INT)
            setBlurModeMethod = View::class.java.getMethod("setMiBackgroundBlurMode", INT)
            setViewBlurModeMethod = View::class.java.getMethod("setMiViewBlurMode", INT)
            setGradientParamsMethod = View::class.java.getMethod(
                "setBackgroundGradientBlurParams", FloatArray::class.java, INT)
            clearBlendColorMethod = View::class.java.getMethod("clearMiBackgroundBlendColor")

            hookMethod("miuix.nestedheader.widget.NestedHeaderLayout", classLoader, "onFinishInflate") {
                val layout = thisObject as? View ?: return@hookMethod
                markManagedActivity(layout.context, true)
                if (shouldApplyTopBlur(layout.context)) {
                    try {
                        thisObject.callMethod("setOverlayMode", true)
                    } catch (_: Throwable) {
                        // Older versions expose only the backing field.
                    }
                }
            }
            // Wi-Fi and many regular SettingsActivity pages do not use a
            // NestedHeaderLayout. Add a dedicated blur-only child behind the
            // ActionBar content so opacity never affects titles or buttons.
            try {
                hookMethod(
                    "miuix.appcompat.internal.app.widget.ActionBarContainer",
                    classLoader,
                    "onFinishInflate",
                ) {
                    val bar = thisObject as? ViewGroup ?: return@hookMethod
                    if (!isSettingsPage(bar.context)) return@hookMethod
                    markManagedActivity(bar.context, false)
                    ensureActionBarBlurView(bar)
                }
            } catch (error: Throwable) {
                log("[HyperBackground] Action bar blur view unavailable: $error")
            }
            try {
                hookMethod(
                    "miuix.appcompat.internal.app.widget.ActionBarContainer",
                    classLoader,
                    "onLayout",
                    BOOL, INT, INT, INT, INT,
                ) {
                    val bar = thisObject as? ViewGroup ?: return@hookMethod
                    val value = bar.getAdditionalInstanceField(ACTION_BAR_BLUR_VIEW)
                    if (value is View) {
                        value.layout(0, 0, bar.width, bar.height)
                    }
                }
            } catch (error: Throwable) {
                log("[HyperBackground] Action bar blur layout unavailable: $error")
            }
            try {
                hookMethod(
                    "miuix.appcompat.internal.app.widget.ActionBarContainer",
                    classLoader,
                    "applyBlur",
                    BOOL,
                ) {
                    val bar = thisObject as? View ?: return@hookMethod
                    if (!isManagedSettingsPage(bar.context)) return@hookMethod
                    if (shouldApplyTopBlur(bar.context) && args[0] == true) {
                        updateInjectedActionBarBlur(thisObject)
                    } else {
                        clearInjectedActionBarBlur(thisObject)
                    }
                }
            } catch (error: Throwable) {
                log("[HyperBackground] Action bar blur state unavailable: $error")
            }
            hookMethod(
                "miuix.nestedheader.widget.NestedHeaderLayout",
                classLoader,
                "onScrollingProgressUpdated",
                INT,
            ) {
                val layout = thisObject as? View ?: return@hookMethod
                logOnce(thisObject, LOG_SCROLL, "NestedHeaderLayout scrolling callback reached")

                if (!isManagedLayout(thisObject, layout.context)) return@hookMethod
                if (shouldApplyTopBlur(layout.context) && !isOverlayMode(thisObject)) {
                    try {
                        thisObject.callMethod("setOverlayMode", true)
                    } catch (_: Throwable) {
                        // The existing overlay state is checked below.
                    }
                }
                if (!isOverlayMode(thisObject)) return@hookMethod

                val progress = args[0] as Int
                val headerHeight = getIntField(thisObject, "mHeaderTotalHeight")
                val overBg = getField(thisObject, "mOverBgView")
                val blurHelper = getField(thisObject, "mBlurUiHelper")
                if (headerHeight <= 0 || overBg !is View) return@hookMethod

                val overlay: View = overBg
                markManagedActivity(layout.context, true)
                if (!shouldApplyTopBlur(layout.context)) {
                    clearGradientBlur(overlay)
                    setBlurEnabled(thisObject, blurHelper, false)
                    overlay.alpha = 0f
                    overlay.visibility = View.INVISIBLE
                    return@hookMethod
                }
                if (progress >= 0 || layout.top > 0) {
                    clearGradientBlur(overlay)
                    setBlurEnabled(thisObject, blurHelper, false)
                    overlay.alpha = 0f
                    overlay.visibility = View.INVISIBLE
                    return@hookMethod
                }

                val scrollFraction = min(1f, -progress / headerHeight.toFloat())
                setBlurEnabled(thisObject, blurHelper, true)
                clearNativeStickyMask(overlay)
                clearNativeBlurBackground(thisObject, overlay)
                clearMaterialMask(overlay)
                val height = overlay.height
                if (height <= 0) {
                    logOnce(thisObject, LOG_READY, "System blur overlay height is unavailable")
                    return@hookMethod
                }

                val density = overlay.resources.displayMetrics.density
                // 开启"清除顶栏"时复用模糊管线但透明度与强度归零，
                // 视觉上顶栏完全透明，且不产生无意义的模糊渲染开销。
                val clearEnabled = SettingsTopBarClearHook.shouldClear(layout.context)
                val strength = if (clearEnabled) 0 else HookRuntime.preferences().getInt(
                    BackgroundContract.UI_TOP_BLUR_STRENGTH, 10)
                val opacity = if (clearEnabled) 0 else HookRuntime.preferences().getInt(
                    BackgroundContract.UI_TOP_BLUR_OPACITY, 100)
                val blurAlpha = scrollFraction * opacity.coerceIn(0, 100) / 100f
                val peakRadius = min(strength.coerceIn(0, 100) * density, height * 0.5f)
                val radius = peakRadius * scrollFraction
                // HyperOS setBgCommonLinearGradientBlur vertical protocol:
                // startX, startY, startRadius, endX, endY, endRadius.
                val gradient = floatArrayOf(0f, 0f, radius, 0f, height.toFloat(), 0f)
                try {
                    // OS4's gradient API only supplies the parameters. The
                    // background and view blur modes must be enabled separately.
                    setBlurModeMethod?.invoke(overlay, 1)
                    setViewBlurModeMethod?.invoke(overlay, 1)
                    setBlurTypeMethod?.invoke(overlay, 2)
                    setGradientParamsMethod?.invoke(overlay, gradient, 1)
                    overlay.visibility = View.VISIBLE
                    overlay.alpha = blurAlpha
                    logOnce(thisObject, LOG_READY,
                        "System linear gradient blur active, radiusPx=$radius height=$height alpha=$blurAlpha")
                } catch (error: ReflectiveOperationException) {
                    logOnce(thisObject, LOG_READY,
                        "System linear gradient blur invocation failed: $error")
                }
            }
            // HyperOS 4's native black gradient is applied from applyBlur().
            // Clear it after the vendor method completes; changing only the
            // mOverBgView background cannot replace that Canvas-drawn mask.
            hookMethod(
                "miuix.nestedheader.widget.NestedHeaderLayout",
                classLoader,
                "applyBlur",
                BOOL,
            ) {
                val layout = thisObject as? View ?: return@hookMethod
                if (!isManagedLayout(thisObject, layout.context)) return@hookMethod
                if (!shouldApplyTopBlur(layout.context)) return@hookMethod
                val overBg = getField(thisObject, "mOverBgView")
                if (overBg is View) {
                    clearNativeStickyMask(overBg)
                    clearNativeBlurBackground(thisObject, overBg)
                    clearMaterialMask(overBg)
                }
            }
            // HyperOS 4's visible black gradient on Settings pages is
            // painted by the floating ActionBar, not by NestedHeaderLayout.
            // SettingsFragment.setupActionBarOverlayMask() installs an
            // OverlayMaskConfig (floating mask color, alpha factors 0.98 -> 0)
            // and ActionBarContainer draws it on every scroll pass. Skip that
            // paint entirely so only the module's gradient blur stays visible.
            try {
                hookMethod(
                    "miuix.appcompat.internal.app.widget.ActionBarContainer",
                    classLoader,
                    "drawOverlayMaskIfNeeded",
                    Canvas::class.java,
                    before = {
                        val bar = thisObject as? View ?: return@hookMethod
                        if (!isManagedSettingsPage(bar.context)) return@hookMethod
                        if (!shouldApplyTopBlur(bar.context)) {
                            clearInjectedActionBarBlur(thisObject)
                            restoreNativeActionBarBlur(thisObject)
                            return@hookMethod
                        }
                        updateInjectedActionBarBlur(thisObject)
                        logOnce(thisObject, LOG_BAR_MASK,
                            "Action bar overlay mask skipped on Settings page")
                        setResult(null)
                    },
                )
            } catch (error: Throwable) {
                log("[HyperBackground] Action bar mask hook unavailable: $error")
            }
            // The ActionBar owns another fixed 40dp gradient blur. Suppress only
            // that render operation while retaining applyBlur's state callbacks,
            // which keep the stock opaque background hidden.
            try {
                hookMethod(
                    "miuix.appcompat.internal.app.widget.ActionBarContainer",
                    classLoader,
                    "applyVerticalGradientBlurInternal",
                    INT,
                    before = {
                        val bar = thisObject as? View ?: return@hookMethod
                        if (!isManagedSettingsPage(bar.context)) return@hookMethod
                        if (!shouldApplyTopBlur(bar.context)) return@hookMethod
                        clearGradientBlur(bar)
                        clearMaterialMask(bar)
                        thisObject.setAdditionalInstanceField(BAR_BLUR_SUPPRESSED, true)
                        setResult(null)
                    },
                )
            } catch (error: Throwable) {
                log("[HyperBackground] Action bar blur hook unavailable: $error")
            }
            // Both OS4 helpers derive their dark tint from the Pured_Regular
            // material. Prevent every refresh path from applying that tint to
            // views hosted by a managed Settings activity.
            try {
                hookMethod(
                    "miuix.view.MiuiBlurUiHelper",
                    classLoader,
                    "applyColorBlend",
                    before = {
                        val target = getField(thisObject, "mTargetView")
                        if (target is View
                            && isManagedSettingsPage(target.context)
                            && isManagedTopBlurView(target)
                            && shouldApplyTopBlur(target.context)) {
                            clearMaterialMask(target)
                            setResult(null)
                        }
                    },
                )
            } catch (error: Throwable) {
                log("[HyperBackground] Material blend hook unavailable: $error")
            }
            // OS4 paints its built-in solid-color gradient directly in this
            // method. The mask color alone is not enough because the vendor
            // method is still invoked on every draw pass.
            try {
                hookMethod(
                    "miuix.nestedheader.widget.NestedHeaderStickyMaskImpl",
                    classLoader,
                    "drawOverlayMask",
                    Canvas::class.java,
                    before = {
                        val stickyView = getField(thisObject, "mStickyView")
                        if (stickyView is View
                            && isManagedSettingsPage(stickyView.context)
                            && shouldApplyTopBlur(stickyView.context)) {
                            setResult(null)
                        }
                    },
                )
            } catch (error: Throwable) {
                log("[HyperBackground] Native sticky mask hook unavailable: $error")
            }
            log("[HyperBackground] Settings top bar progressive blur hook installed")
        } catch (error: Throwable) {
            log("[HyperBackground] Could not hook settings top bar blur: $error")
            log(error)
        }
    }

    fun markHomeFragment(fragment: Any?) {
        try {
            val activity = fragment?.callMethod("getActivity")
            if (activity !is Activity || activity.javaClass.name != HOME_ACTIVITY) return
            activity.setAdditionalInstanceField(MANAGED_ACTIVITY, true)
            activity.setAdditionalInstanceField(NESTED_ACTIVITY, true)
            val layout = fragment?.getObjectField("mNestedHeaderLayout")
            if (layout != null) {
                layout.setAdditionalInstanceField(HOME_LAYOUT, true)
                log("[HyperBackground] Settings home NestedHeaderLayout marked")
            }
        } catch (error: Throwable) {
            log("[HyperBackground] Could not mark settings home layout: $error")
        }
    }

    private fun clearGradientBlur(overlay: View) {
        try {
            setBlurModeMethod?.invoke(overlay, 0)
            setViewBlurModeMethod?.invoke(overlay, 0)
            setBlurTypeMethod?.invoke(overlay, 0)
        } catch (_: ReflectiveOperationException) {
            // The stock helper below still clears the complete blur state.
        }
    }

    private fun clearMaterialMask(overlay: View) {
        val method = clearBlendColorMethod ?: return
        try {
            method.invoke(overlay)
        } catch (_: ReflectiveOperationException) {
            // Gradient blur remains usable even when this vendor cleanup API is absent.
        }
    }

    private fun isManagedLayout(layout: Any?, context: Context): Boolean =
        layout?.getAdditionalInstanceField(HOME_LAYOUT) == true || isSettingsPage(context)

    private fun isSettingsPage(context: Context): Boolean {
        val activity = findActivity(context) ?: return false
        return BackgroundContract.PACKAGE_SETTINGS == activity.packageName
    }

    private fun isManagedSettingsPage(context: Context): Boolean {
        val activity = findActivity(context) ?: return false
        return BackgroundContract.PACKAGE_SETTINGS == activity.packageName
            && activity.getAdditionalInstanceField(MANAGED_ACTIVITY) == true
    }

    private fun markManagedActivity(context: Context, hasNestedLayout: Boolean) {
        val activity = findActivity(context) ?: return
        if (BackgroundContract.PACKAGE_SETTINGS == activity.packageName) {
            activity.setAdditionalInstanceField(MANAGED_ACTIVITY, true)
            if (hasNestedLayout) {
                activity.setAdditionalInstanceField(NESTED_ACTIVITY, true)
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base: Context = current.baseContext
            if (base === current) break
            current = base
        }
        return null
    }

    private fun isTopBlurPreferenceEnabled(): Boolean = HookRuntime.preferences().getBoolean(
        BackgroundContract.UI_TOP_BLUR_ENABLED, true)

    // 开启"清除顶栏"时复用模糊管线（仅首页），把强度/透明度归零实现完全透明；
    // 其余页面按模糊开关决定是否应用顶栏模糊。
    private fun shouldApplyTopBlur(context: Context): Boolean =
        isTopBlurPreferenceEnabled() || SettingsTopBarClearHook.shouldClear(context)

    private fun isManagedTopBlurView(view: View): Boolean {
        val name = view.javaClass.name
        return name == "miuix.appcompat.internal.app.widget.ActionBarContainer"
            || name == "miuix.nestedheader.widget.NestedHeaderOverlayMaskView"
    }

    private fun ensureActionBarBlurView(bar: ViewGroup) {
        if (bar.getAdditionalInstanceField(ACTION_BAR_BLUR_VIEW) is View) return
        val blurView = View(bar.context)
        blurView.isClickable = false
        blurView.isFocusable = false
        blurView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        blurView.visibility = View.INVISIBLE
        bar.addView(blurView, 0, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        bar.setAdditionalInstanceField(ACTION_BAR_BLUR_VIEW, blurView)
    }

    private fun updateInjectedActionBarBlur(barObject: Any?) {
        val bar = barObject as? ViewGroup ?: return
        val activity = findActivity(bar.context) ?: return

        var blurView = bar.getAdditionalInstanceField(ACTION_BAR_BLUR_VIEW) as? View
        if (blurView == null) {
            ensureActionBarBlurView(bar)
            blurView = bar.getAdditionalInstanceField(ACTION_BAR_BLUR_VIEW) as? View
        }
        if (blurView == null) return

        if (activity.getAdditionalInstanceField(NESTED_ACTIVITY) == true) {
            clearGradientBlur(blurView)
            blurView.visibility = View.INVISIBLE
            return
        }

        val height = bar.height
        if (height <= 0) return
        var maskAlpha = getFloatField(bar, "mMaskAlpha").coerceIn(0f, 1f)
        if (maskAlpha <= 0f && getBooleanField(bar, "mInternalApplyBgBlur")) {
            maskAlpha = 1f
        }
        if (maskAlpha <= 0f) {
            clearGradientBlur(blurView)
            blurView.alpha = 0f
            blurView.visibility = View.INVISIBLE
            return
        }

        // 开启"清除顶栏"时复用模糊管线但透明度与强度归零。
        val clearEnabled = SettingsTopBarClearHook.shouldClear(bar.context)
        val strength = if (clearEnabled) 0 else HookRuntime.preferences().getInt(
            BackgroundContract.UI_TOP_BLUR_STRENGTH, 10)
        val opacity = if (clearEnabled) 0 else HookRuntime.preferences().getInt(
            BackgroundContract.UI_TOP_BLUR_OPACITY, 100)
        val density = bar.resources.displayMetrics.density
        val radius = min(strength.coerceIn(0, 100) * density, height * 0.5f)
        val alpha = maskAlpha * opacity.coerceIn(0, 100) / 100f
        val gradient = floatArrayOf(0f, 0f, radius, 0f, height.toFloat(), 0f)
        try {
            setBlurModeMethod?.invoke(blurView, 1)
            setViewBlurModeMethod?.invoke(blurView, 1)
            setBlurTypeMethod?.invoke(blurView, 2)
            setGradientParamsMethod?.invoke(blurView, gradient, 1)
            blurView.alpha = alpha
            blurView.visibility = View.VISIBLE
            logOnce(bar, LOG_BAR_BLUR,
                "Injected action bar gradient blur active, radiusPx=$radius height=$height alpha=$alpha")
        } catch (error: ReflectiveOperationException) {
            log("[HyperBackground] Could not apply action bar blur: $error")
        }
    }

    private fun clearInjectedActionBarBlur(barObject: Any?) {
        val bar = barObject as? ViewGroup ?: return
        val blurView = bar.getAdditionalInstanceField(ACTION_BAR_BLUR_VIEW) as? View ?: return
        clearGradientBlur(blurView)
        blurView.alpha = 0f
        blurView.visibility = View.INVISIBLE
    }

    private fun restoreNativeActionBarBlur(bar: Any?) {
        if (bar == null) return
        if (bar.getAdditionalInstanceField(BAR_BLUR_SUPPRESSED) != true) return
        bar.setAdditionalInstanceField(BAR_BLUR_SUPPRESSED, false)
        val helper = getField(bar, "mBlurHelper") ?: return
        try {
            helper.callMethod("resetBlurParams")
            helper.callMethod("refreshBlur")
        } catch (error: Throwable) {
            log("[HyperBackground] Could not restore action bar blur: $error")
        }
    }

    private fun getField(instance: Any?, name: String): Any? = try {
        instance?.getObjectField(name)
    } catch (_: Throwable) {
        null
    }

    private fun getBooleanField(instance: Any?, name: String): Boolean {
        val value = getField(instance, name)
        return value is Boolean && value
    }

    private fun clearNativeStickyMask(overlay: View) {
        try {
            // OS4 draws its black gradient in NestedHeaderStickyMaskImpl, not in
            // mOverBgView's background. Make that native paint transparent so the
            // system blur layer below remains visible.
            var mask = getField(overlay, "mStickyMaskImpl")
            if (mask == null) {
                overlay.callMethod("isStickyMaskEnabled")
                mask = getField(overlay, "mStickyMaskImpl")
            }
            if (mask != null) {
                setIntField(mask, "mMaskColor", 0)
                try {
                    overlay.callMethod("setStickyMaskEnabled", false, false)
                } catch (_: Throwable) {
                    // Older builds do not expose the two-argument overload.
                }
                overlay.invalidate()
            }
        } catch (_: Throwable) {
            // OS3 does not expose the sticky mask implementation.
        }
    }

    private fun clearNativeBlurBackground(layout: Any?, overlay: View) {
        try {
            // OS4 stores the original dark blur drawable separately and restores
            // it from mMaskBackgroundInBlur. Remove that drawable before applying
            // the module-owned transparent gradient blur.
            val nativeMask = getField(layout, "mMaskBackgroundInBlur")
            if (nativeMask != null && overlay.background === nativeMask) {
                overlay.background = null
            }
        } catch (_: Throwable) {
            // OS3 has no in-blur mask drawable.
        }
    }

    private fun isOverlayMode(layout: Any?): Boolean {
        try {
            val value = layout?.callMethod("isOverlayMode")
            if (value is Boolean) return value
        } catch (_: Throwable) {
            // HyperOS 3 may only expose the backing field.
        }
        return getBooleanField(layout, "mIsOverlayMode")
    }

    private fun setIntField(instance: Any, name: String, value: Int) {
        try {
            val field = findField(instance.javaClass, name)
            field.setInt(instance, value)
        } catch (error: Throwable) {
            throw NoSuchFieldException(name).initCause(error)
        }
    }

    private fun setBlurEnabled(layout: Any?, blurHelper: Any?, enabled: Boolean) {
        try {
            // OS4 Settings disables this helper after creating the layout. Re-enable it
            // explicitly before applying the custom gradient to mOverBgView.
            layout?.callMethod("setEnableBlur", enabled)
        } catch (_: Throwable) {
            // Fall back to the helper on OS3 builds without the layout method.
        }
        if (blurHelper != null) {
            try {
                blurHelper.callMethod("setEnableBlur", enabled)
                blurHelper.callMethod("applyBlur", enabled)
            } catch (_: Throwable) {
                // A missing vendor helper must not break the scrolling callback.
            }
        }
    }

    private fun getIntField(instance: Any?, name: String): Int {
        val value = getField(instance, name)
        return if (value is Int) value else 0
    }

    private fun getFloatField(instance: Any?, name: String): Float {
        val value = getField(instance, name)
        return if (value is Float) value else 0f
    }

    private fun logOnce(instance: Any?, key: String, message: String) {
        if (instance?.getAdditionalInstanceField(key) == true) return
        instance?.setAdditionalInstanceField(key, true)
        log("[HyperBackground] $message")
    }
}
