package com.ciallo.hyperbackground.dynamic.popup

import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette

internal object DynamicPopupBackground {
    fun clearFill(drawable: Drawable): Boolean {
        if (drawable.javaClass.name != "miuix.smooth.SmoothContainerDrawable2") return false
        return runCatching {
            val fill = ColorDrawable(android.graphics.Color.TRANSPARENT)
            fill.bounds = drawable.bounds
            drawable.javaClass.getMethod("setChildDrawable", Drawable::class.java)
                .invoke(drawable, fill)
            true
        }.getOrDefault(false)
    }

    fun create(original: Drawable?, context: Context, palette: DynamicMaterialPalette): Drawable? {
        if (!palette.enabledFor(HookRuntime.targetPackage) || !palette.popup) return null
        val dark = !palette.darkFollowsLight && context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = if (palette.mode == CARD_BACKGROUND_COLOR) {
            if (dark) palette.dark else palette.light
        } else {
            if (dark) palette.darkFrost else palette.lightFrost
        }
        val clone = runCatching {
            original?.constantState?.newDrawable(context.resources, context.theme)?.mutate()
                ?: original?.constantState?.newDrawable()?.mutate()
        }.getOrNull()
        // MIUIX often supplies a runtime drawable with no ConstantState. SmoothFrameLayout2
        // and DialogParentPanel2 clip their own draw pass to the original rounded outline.
        if (clone == null) return ColorDrawable(color)
        if (clone.javaClass.name == "miuix.smooth.SmoothContainerDrawable2") {
            // The wrapper delegates painting to its child; tinting the wrapper alone can leave
            // the inner opaque fill untouched on different MIUIX builds.
            val child = runCatching { clone.javaClass.getMethod("getChildDrawable").invoke(clone) as? Drawable }.getOrNull()
            if (child != null) {
                runCatching {
                    val fill = ColorDrawable(color)
                    fill.bounds = child.bounds
                    clone.javaClass.getMethod("setChildDrawable", Drawable::class.java)
                        .invoke(clone, fill)
                }.onSuccess {
                    clone.alpha = 255
                    return clone
                }
            }
        }
        return clone.let { drawable ->
            runCatching {
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.alpha = 255
                drawable
            }.getOrNull()
        }
    }
}
