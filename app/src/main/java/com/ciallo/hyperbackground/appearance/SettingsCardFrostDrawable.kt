package com.ciallo.hyperbackground.appearance

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A marker for MIUIX's non-ColorDrawable group path. The decoration supplies its exact clip path.
 * Each visible group owns a RenderNode, so changing the next group's bounds never moves an
 * earlier group already recorded in RecyclerView's display list. No list item is reparented.
 */
internal class SettingsCardFrostDrawable(
    context: Context,
    private val onFailure: (Throwable) -> Unit,
) : Drawable(), View.OnAttachStateChangeListener, SettingsGroupMaterial {
    private val context = context.applicationContext
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodes = ArrayList<FrostNode>()
    private var host: WeakReference<View>? = null
    private var cursor = 0
    private var blurRadius = 0
    private var failed = false

    fun configure(color: Int, radiusDp: Int, density: Float) {
        tint.color = color
        // The target's MiuiBlurUtils accepts a physical radius in the range 0..400.
        blurRadius = (radiusDp.coerceIn(0, 80) * density).roundToInt().coerceIn(0, 400)
    }

    override fun bindHost(view: View?) {
        if (view == null || host?.get() === view) return
        host?.get()?.removeOnAttachStateChangeListener(this)
        releaseNodes()
        host = WeakReference(view)
        view.addOnAttachStateChangeListener(this)
    }

    override fun beginFrame() { cursor = 0 }

    override fun endFrame() {
        // Clear render-thread blur state for groups that scrolled out of view.
        for (index in cursor until nodes.size) nodes[index].clear()
        while (nodes.size > maxOf(cursor, 16)) nodes.removeAt(nodes.lastIndex).clear()
    }

    override fun drawGroup(canvas: Canvas, rect: RectF, path: Path) {
        if (rect.isEmpty) return
        if (canvas.isHardwareAccelerated && blurRadius > 0 && !failed) {
            try {
                val node = if (cursor < nodes.size) nodes[cursor] else FrostNode(context).also(nodes::add)
                cursor++
                node.draw(canvas, rect, path, blurRadius, tint.color)
                return
            } catch (error: Throwable) {
                failed = true
                releaseNodes()
                onFailure(error)
                // Earlier groups in this display list may reference the discarded nodes.
                host?.get()?.postInvalidateOnAnimation()
            }
        }
        // Missing vendor APIs and software canvases retain the requested tint and native corners.
        canvas.drawPath(path, tint)
    }

    override fun draw(canvas: Canvas) {
        // Safe fallback if a different MIUIX implementation bypasses the group-clip hook.
        canvas.drawRect(bounds, tint)
    }

    override fun setAlpha(alpha: Int) { tint.alpha = alpha.coerceIn(0, 255) }
    override fun setColorFilter(filter: ColorFilter?) { tint.colorFilter = filter }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onViewAttachedToWindow(view: View) { view.invalidate() }
    override fun onViewDetachedFromWindow(view: View) { releaseNodes() }

    override fun dispose() {
        host?.get()?.removeOnAttachStateChangeListener(this)
        host = null
        releaseNodes()
    }

    private fun releaseNodes() {
        nodes.forEach(FrostNode::clear)
        nodes.clear()
        cursor = 0
    }

    private class FrostNode(context: Context) {
        // View is only the bridge to HyperOS's proven View blur setters. Its RenderNode is
        // recorded directly into the group's Canvas; the bridge never enters the view tree.
        private val bridge = View(context.applicationContext)
        private val api = blurApi
        private val node = api.renderNode.get(bridge) as RenderNode
        private val localPath = Path()
        private val outline = Outline()
        private var width = 0
        private var height = 0
        private var color = 0
        private var radius = -1
        private var active = false

        fun draw(canvas: Canvas, rect: RectF, path: Path, blurRadius: Int, tintColor: Int) {
            val w = ceil(rect.width()).toInt().coerceAtLeast(1)
            val h = ceil(rect.height()).toInt().coerceAtLeast(1)
            try {
                if (width != w || height != h) {
                    // Keep both the bridge's View geometry and its native node in sync.
                    bridge.layout(0, 0, w, h)
                    node.setPosition(0, 0, w, h)
                }
                if (!active || radius != blurRadius) {
                    // Mark first so partial setup is also cleared if a vendor call fails.
                    active = true
                    api.backgroundMode.invoke(bridge, 1)
                    api.viewMode.invoke(bridge, 1)
                    api.radius.invoke(bridge, blurRadius)
                    radius = blurRadius
                }
                if (width != w || height != h || color != tintColor || !node.hasDisplayList()) {
                    val recording = node.beginRecording(w, h)
                    try { recording.drawColor(tintColor) } finally { node.endRecording() }
                    width = w
                    height = h
                    color = tintColor
                }
                localPath.set(path)
                localPath.offset(-rect.left, -rect.top)
                outline.setPath(localPath)
                outline.alpha = 1f
                node.setOutline(outline)
                node.setClipToOutline(true)
                node.setClipToBounds(true)
                val checkpoint = canvas.save()
                try {
                    // No saveLayer: an offscreen layer would hide the real backdrop from native blur.
                    canvas.clipPath(path)
                    canvas.translate(rect.left, rect.top)
                    canvas.drawRenderNode(node)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            } catch (error: Throwable) {
                clear()
                throw error
            }
        }

        fun clear() {
            if (active) {
                runCatching { api.radius.invoke(bridge, 0) }
                runCatching { api.viewMode.invoke(bridge, 0) }
                runCatching { api.backgroundMode.invoke(bridge, 0) }
            }
            active = false
            radius = -1
            node.discardDisplayList()
        }
    }

    private class BlurApi(val renderNode: Field, val backgroundMode: Method, val viewMode: Method, val radius: Method)

    private class DirectBlurApi(
        val backgroundMode: Method,
        val viewMode: Method,
        val radius: Method,
        val clearBlend: Method,
        val enhanceFlag: Method,
    )

    companion object {
        /** Apply the classic blur branch to a real attached card View. */
        fun applyToView(view: View, radiusDp: Int, density: Float): Boolean {
            val api = directBlurApi ?: return false
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated) return false
            val enabled = runCatching {
                Settings.Secure.getInt(view.context.contentResolver, "background_blur_enable", 0) == 1
            }.getOrDefault(false)
            if (!enabled) return false
            return runCatching {
                val radius = (radiusDp.coerceIn(0, 80) * density).roundToInt().coerceIn(0, 400)
                requireAccepted(api.backgroundMode, view, 1)
                requireAccepted(api.viewMode, view, 1)
                requireAccepted(api.clearBlend, view)
                requireAccepted(api.radius, view, radius)
                requireAccepted(api.enhanceFlag, view, 4096, 12288)
                view.invalidate()
                true
            }.onFailure { clearFromView(view) }.getOrDefault(false)
        }

        /** Clear only the classic native blur state; the caller restores the drawable. */
        fun clearFromView(view: View) {
            val api = directBlurApi ?: return
            runCatching { api.radius.invoke(view, 0) }
            runCatching { api.enhanceFlag.invoke(view, 0, 12288) }
            runCatching { api.clearBlend.invoke(view) }
            runCatching { api.viewMode.invoke(view, 0) }
            runCatching { api.backgroundMode.invoke(view, 0) }
            view.invalidate()
        }

        private fun requireAccepted(method: Method, target: View, vararg args: Any) {
            check(method.invoke(target, *args) != false) { "${method.name} rejected the material" }
        }

        private val blurApi: BlurApi by lazy {
            val intType = Int::class.javaPrimitiveType!!
            BlurApi(
                View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true },
                View::class.java.getMethod("setMiBackgroundBlurMode", intType),
                View::class.java.getMethod("setMiViewBlurMode", intType),
                View::class.java.getMethod("setMiBackgroundBlurRadius", intType),
            )
        }

        private val directBlurApi: DirectBlurApi? by lazy {
            val intType = Int::class.javaPrimitiveType!!
            runCatching {
                DirectBlurApi(
                    View::class.java.getMethod("setMiBackgroundBlurMode", intType),
                    View::class.java.getMethod("setMiViewBlurMode", intType),
                    View::class.java.getMethod("setMiBackgroundBlurRadius", intType),
                    View::class.java.getMethod("clearMiBackgroundBlendColor"),
                    View::class.java.getMethod("setMiBackgroundBlurEnhanceFlag", intType, intType),
                )
            }.getOrNull()
        }
    }
}
