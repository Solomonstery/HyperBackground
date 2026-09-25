package com.ciallo.hyperbackground.dialpad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.BuildConfig
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.util.getAdditionalInstanceField
import com.ciallo.hyperbackground.util.removeAdditionalInstanceField
import com.ciallo.hyperbackground.util.setAdditionalInstanceField
import com.ciallo.hyperbackground.util.log

/**
 * DialpadLayout is inflated by a ViewStub. The hook applies this underlay before its first draw;
 * the activity refresh path reuses the same session when the page resumes.
 * The native panel supplies the geometry (including 9-patch shadow padding), while the separate
 * layer carries the backdrop/image and tint below the keys. Keep the original background for
 * restoration when the feature is disabled or its configuration changes.
 */
internal object DialpadBackgroundController {
    private val sessionKey = BuildConfig.APPLICATION_ID + ".hook.dialpad.session"
    private val configKey = BuildConfig.APPLICATION_ID + ".hook.dialpad.config"
    private val panelBackgroundKey = BuildConfig.APPLICATION_ID + ".hook.contacts.bg.saved"

    fun refresh(activity: Activity) {
        val id = try {
            activity.resources.getIdentifier("dialer_background_view", "id", activity.packageName)
        } catch (_: Throwable) {
            0
        }
        if (id == 0) return
        var view = activity.findViewById<View>(id)?.parent as? View
        while (view != null) {
            if (view.javaClass.name == "com.android.contacts.dialer.view.DialpadLayout") {
                apply(view)
                return
            }
            view = view.parent as? View
        }
    }

    fun apply(dialpadView: View?) {
        val dialpad = dialpadView as? ViewGroup ?: return
        try {
            val ctx = dialpad.context
            val enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true)
            val opacity = HookRuntime.preferences().getInt(BackgroundContract.CONTACTS_DIALPAD_OPACITY, 60)
            val padAlpha = opacity.coerceIn(0, 100) / 100f
            val mode = HookRuntime.preferences().getInt(
                BackgroundContract.CONTACTS_DIALPAD_BG_MODE, BackgroundContract.CONTACTS_DIALPAD_BG_DEFAULT)

            val bgId = ctx.resources.getIdentifier("dialer_background_view", "id", ctx.packageName)
            val containerId = ctx.resources.getIdentifier("dialpad_container", "id", ctx.packageName)
            val bgView = if (bgId == 0) null else dialpad.findViewById<View>(bgId)
            val container = if (containerId == 0) null else dialpad.findViewById<View>(containerId)
            // The native 9-patch's bounds and padding define the opaque panel inside its shadow.
            val panel = bgView ?: container ?: dialpad
            bgView?.setAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD, true)
            container?.setAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD, true)

            val source = BackgroundContract.query(ctx, BackgroundContract.CONTACTS_DIALPAD)
            val custom = enabled && mode == BackgroundContract.CONTACTS_DIALPAD_BG_CUSTOM &&
                source.exists && !source.isVideo()
            val key = "$enabled:$opacity:$mode:${source.cacheKey()}"
            val old = dialpad.getAdditionalInstanceField(sessionKey) as? DialpadLayerSession
            if (old?.matches(dialpad, panel, bgView) == true &&
                dialpad.getAdditionalInstanceField(configKey) == key) {
                old.onHostResume()
                return
            }

            removeMedia(dialpad)
            restorePanelBackground(container)
            container?.alpha = 1f
            if (!enabled) return
            val layer = if (custom) {
                DialpadImageView(ctx, source, padAlpha)
            } else {
                DialpadBackdropView(
                    ctx,
                    bgView?.background ?: container?.background,
                    padAlpha,
                    if (source.blurEnabled) source.blurRadius else 0,
                )
            }
            val session = DialpadLayerSession(dialpad, panel, bgView, layer)
            dialpad.setAdditionalInstanceField(sessionKey, session)
            session.attach()
            clearPanelBackground(container)
            dialpad.setAdditionalInstanceField(configKey, key)
        } catch (error: Throwable) {
            log("[HyperBackground] applyDialpadOnInflate failed: $error")
            log(error)
        }
    }

    private fun setBackgroundPreservingPadding(view: View, drawable: Drawable) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }

    private fun clearPanelBackground(container: View?) {
        if (container == null) return
        try {
            val bg = container.background
            if (bg != null) {
                if (container.getAdditionalInstanceField(panelBackgroundKey) == null) {
                    container.setAdditionalInstanceField(panelBackgroundKey, bg)
                }
                setBackgroundPreservingPadding(container, ColorDrawable(Color.TRANSPARENT))
            }
            container.alpha = 1f
        } catch (_: Throwable) {
        }
    }

    private fun restorePanelBackground(container: View?) {
        if (container == null) return
        try {
            val saved = container.getAdditionalInstanceField(panelBackgroundKey)
            if (saved is Drawable) {
                setBackgroundPreservingPadding(container, saved)
                container.removeAdditionalInstanceField(panelBackgroundKey)
            }
            restoreColorFilter(container.background)
        } catch (_: Throwable) {
        }
    }

    private fun restoreColorFilter(bg: Drawable?) {
        if (bg == null) return
        try {
            bg.mutate().clearColorFilter()
            when (bg) {
                is android.graphics.drawable.StateListDrawable ->
                    for (i in 0 until bg.stateCount) restoreColorFilter(bg.getStateDrawable(i))
                is android.graphics.drawable.LayerDrawable ->
                    for (i in 0 until bg.numberOfLayers) restoreColorFilter(bg.getDrawable(i))
            }
        } catch (_: Throwable) {
        }
    }

    private fun removeMedia(dialpad: ViewGroup) {
        try {
            (dialpad.getAdditionalInstanceField(sessionKey) as? DialpadLayerSession)?.dispose()
            dialpad.removeAdditionalInstanceField(sessionKey)
            dialpad.removeAdditionalInstanceField(configKey)
        } catch (_: Throwable) {
        }
    }
}
