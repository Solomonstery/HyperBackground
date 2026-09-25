package com.ciallo.hyperbackground.dynamic.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.TextView
import java.util.WeakHashMap

/** Remove only solid content fills that would cover an already active glass surface. */
internal class DialogContentFills {
    private data class Fill(
        val original: Drawable, val empty: Drawable,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
    )

    private val fills = WeakHashMap<View, Fill>()

    fun update(surface: View) {
        fun clear(view: View) {
            val existing = fills[view]
            if (existing != null && view.background === existing.empty) return
            val background = view.background ?: return
            if (background.isStateful || !isOpaqueSolid(background)) return
            val empty = ColorDrawable(Color.TRANSPARENT)
            val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            fills[view] = Fill(background, empty, padding[0], padding[1], padding[2], padding[3])
            view.background = empty
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
        }

        fun visit(node: View, inList: Boolean) {
            val list = node is AbsListView
            if (list || node is TextView && node !is Button ||
                inList && node is ViewGroup && node !is AbsListView) clear(node)
            val group = node as? ViewGroup ?: return
            for (i in 0 until group.childCount) visit(group.getChildAt(i), inList || list)
        }
        visit(surface, false)
    }

    fun restore() {
        fills.forEach { (view, fill) ->
            if (view.background === fill.empty) {
                view.background = fill.original
                view.setPadding(fill.left, fill.top, fill.right, fill.bottom)
            }
        }
        fills.clear()
    }

    private fun isOpaqueSolid(drawable: Drawable): Boolean {
        val color = when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.color?.defaultColor
            else -> null
        } ?: return false
        return Color.alpha(color) == 255
    }
}
