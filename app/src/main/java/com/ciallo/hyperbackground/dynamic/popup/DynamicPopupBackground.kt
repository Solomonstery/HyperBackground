package com.ciallo.hyperbackground.dynamic.popup

import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette

internal object DynamicPopupBackground {
    fun create(original: Drawable?, context: Context, palette: DynamicMaterialPalette): Drawable? {
        if (!palette.enabled || !palette.popup || original == null) return null
        val dark = !palette.darkFollowsLight && context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = if (dark) palette.dark else palette.light
        return runCatching {
            original.constantState?.newDrawable(context.resources, context.theme)?.mutate()
                ?: original.constantState?.newDrawable()?.mutate()
        }.getOrNull()?.let { drawable ->
            runCatching {
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable
            }.getOrNull()
        }
    }
}
