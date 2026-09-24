package com.ciallo.hyperbackground.dynamic.popup

import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import java.lang.reflect.Modifier

internal object DynamicPopupBackground {
    fun cornerRadius(drawable: Drawable?): Float? {
        if (drawable?.javaClass?.name != "miuix.smooth.SmoothContainerDrawable2") return null
        return runCatching {
            val method = runCatching { drawable.javaClass.getMethod("getCornerRadius") }.getOrNull()
                ?: drawable.javaClass.declaredMethods.single { candidate ->
                    !Modifier.isStatic(candidate.modifiers) && candidate.parameterCount == 0 &&
                        candidate.returnType == Float::class.javaPrimitiveType
                }
            (method.invoke(drawable) as Number).toFloat()
        }.getOrNull()
    }

    fun clearFill(drawable: Drawable): Boolean {
        if (drawable.javaClass.name != "miuix.smooth.SmoothContainerDrawable2") return false
        return runCatching {
            val fill = ColorDrawable(android.graphics.Color.TRANSPARENT)
            fill.bounds = drawable.bounds
            setChildDrawable(drawable, fill)
            true
        }.getOrDefault(false)
    }

    private fun setChildDrawable(container: Drawable, child: Drawable) {
        val method = runCatching {
            container.javaClass.getMethod("setChildDrawable", Drawable::class.java)
        }.getOrNull() ?: container.javaClass.declaredMethods.single { candidate ->
            !Modifier.isStatic(candidate.modifiers) && candidate.name != "invalidateDrawable" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(Drawable::class.java))
        }
        method.invoke(container, child)
    }

    private fun childDrawable(container: Drawable): Drawable? = runCatching {
        val getter = runCatching { container.javaClass.getMethod("getChildDrawable") }.getOrNull()
            ?: container.javaClass.declaredMethods.single { candidate ->
                !Modifier.isStatic(candidate.modifiers) && candidate.parameterCount == 0 &&
                    candidate.returnType == Drawable::class.java
            }
        getter.invoke(container) as? Drawable
    }.getOrNull()

    fun create(original: Drawable?, context: Context, palette: DynamicMaterialPalette): Drawable? {
        if (!palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.POPUP)) return null
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
            val child = childDrawable(clone)
            if (child != null) {
                runCatching {
                    val fill = ColorDrawable(color)
                    fill.bounds = child.bounds
                    setChildDrawable(clone, fill)
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
