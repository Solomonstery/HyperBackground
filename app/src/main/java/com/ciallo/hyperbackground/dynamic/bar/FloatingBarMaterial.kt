package com.ciallo.hyperbackground.dynamic.bar

import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.dynamic.material.DynamicFrostDrawable
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable

internal object FloatingBarMaterial {
    fun signature(view: View, palette: DynamicMaterialPalette): Int {
        if (!palette.enabled || !palette.floatingBar || palette.mode == CARD_BACKGROUND_COLOR) return 0
        return 31 * palette.hashCode() +
            (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }

    fun background(original: Drawable?, context: Context, palette: DynamicMaterialPalette): Drawable? {
        if (!palette.enabled || !palette.floatingBar || original == null) return null
        val color = color(context, palette)
        return runCatching {
            val copy = original.constantState?.newDrawable(context.resources, context.theme)?.mutate()
                ?: return@runCatching null
            if (original.javaClass.name == "miuix.smooth.SmoothContainerDrawable2") {
                // Replace the inner fill, retaining MIUIX's smooth outline and shadow.
                val child = ColorDrawable(color).apply { bounds = copy.bounds }
                copy.javaClass.getMethod("setChildDrawable", Drawable::class.java).invoke(copy, child)
            } else {
                copy.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
            copy
        }.getOrNull()
    }

    fun apply(view: View, palette: DynamicMaterialPalette): Boolean {
        if (!palette.enabled || !palette.floatingBar) return false
        val dark = isDark(view.context, palette)
        return when (palette.mode) {
            CARD_BACKGROUND_FROST -> DynamicFrostDrawable.applyToView(
                view, if (dark) palette.darkBlur else palette.lightBlur, view.resources.displayMetrics.density,
            )
            CARD_BACKGROUND_SOFT_GLASS -> DynamicSoftGlassDrawable.applyToView(
                view, if (dark) palette.darkGlass else palette.lightGlass, view.resources.displayMetrics.density,
            )
            else -> false
        }
    }

    fun clear(view: View) {
        DynamicFrostDrawable.clearFromView(view)
        DynamicSoftGlassDrawable.clearFromView(view)
    }

    private fun color(context: Context, palette: DynamicMaterialPalette): Int {
        val dark = isDark(context, palette)
        return when (palette.mode) {
            CARD_BACKGROUND_FROST -> if (dark) palette.darkFrost else palette.lightFrost
            CARD_BACKGROUND_SOFT_GLASS -> DynamicSoftGlassDrawable.materialTintColor(
                if (dark) palette.darkFrost else palette.lightFrost,
                if (dark) palette.darkGlass else palette.lightGlass,
            )
            else -> if (dark) palette.dark else palette.light
        }
    }

    private fun isDark(context: Context, palette: DynamicMaterialPalette): Boolean =
        !palette.darkFollowsLight && context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
