package com.ciallo.hyperbackground.dynamic.material

import com.ciallo.hyperbackground.appearance.SoftGlassParams
import com.ciallo.hyperbackground.util.getAdditionalInstanceField
import com.ciallo.hyperbackground.util.setAdditionalInstanceField

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-frame lifecycle shared by group drawables that must bypass MIUIX's saveLayerAlpha clip.
 * The card router sends each visible group to the implementation selected by the
 * card background mode, and BaseDecoration's clip hook dispatches through this interface.
 */
internal interface DynamicGroupMaterial {
    fun bindHost(view: View?)
    fun beginFrame()
    fun endFrame()
    fun drawGroup(canvas: Canvas, rect: RectF, path: Path)
    fun dispose()
}

/**
 * 柔光玻璃 card material. Reuses DynamicFrostDrawable's bridge-View RenderNode trick,
 * but the bridge carries HyperOS 4's Bionics material (setMiViewMaterialType + setMiGlass)
 * instead of the Gaussian blur setters. Each visible group owns one bridge; the bridge View
 * never enters the view tree, and the recorded tint color stays controllable by the palette.
 */
internal class DynamicSoftGlassDrawable(
    context: Context,
    private val onFailure: (Throwable) -> Unit,
) : Drawable(), View.OnAttachStateChangeListener, DynamicGroupMaterial {
    private val context = context.applicationContext
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodes = ArrayList<GlassNode>()
    private val retiredNodes = ArrayList<GlassNode>()
    private var host: WeakReference<View>? = null
    private var cursor = 0
    private var config = SoftGlassParams()
    private var density = 1f
    private var failed = false

    fun configure(color: Int, value: SoftGlassParams, density: Float) {
        // The reference maps transparency to shader channel 14 as a percentage scale of the
        // tint alpha; here the tint is drawn by the display list, so scale the paint instead.
        tint.color = materialTintColor(color, value)
        config = value
        this.density = density
    }

    override fun bindHost(view: View?) {
        if (view == null || host?.get() === view) return
        host?.get()?.removeOnAttachStateChangeListener(this)
        releaseNodes()
        host = WeakReference(view)
        view.addOnAttachStateChangeListener(this)
        activateHostBlurSurface(view)
    }

    override fun beginFrame() { cursor = 0 }

    override fun endFrame() {
        // Bionics material state is consumed asynchronously by RenderThread. Clearing nodes
        // that were not visited in this pass can race the previous display list and produces
        // the observed one-frame flashes on otherwise visible cards. Nodes are reset on host
        // detach/dispose; their count is bounded by the number of card groups created here.
    }

    override fun drawGroup(canvas: Canvas, rect: RectF, path: Path) {
                if (rect.isEmpty) return
        val api = glassApi
        if (canvas.isHardwareAccelerated && api != null && !failed) {
            try {
                val requestedHeight = ceil(rect.height()).toInt().coerceAtLeast(1)
                val node = if (cursor < nodes.size && nodes[cursor].canReuse(rect, requestedHeight)) {
                    nodes[cursor]
                } else {
                    val replacement = GlassNode(context, api)
                    if (cursor < nodes.size) {
                        val previous = nodes[cursor]
                        replacement.seedFrom(previous)
                        retiredNodes += previous
                        nodes[cursor] = replacement
                    } else {
                        nodes += replacement
                    }
                    replacement
                }
                cursor++
                node.draw(canvas, rect, path, config, density, tint.color)
                return
            } catch (error: Throwable) {
                failed = true
                releaseNodes()
                onFailure(error)
                // Earlier groups in this display list may reference the discarded nodes.
                host?.get()?.postInvalidateOnAnimation()
            }
        }
        // Missing Bionics APIs and software canvases retain the requested tint and native corners.
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
        nodes.forEach(GlassNode::clear)
        nodes.clear()
        retiredNodes.forEach(GlassNode::clear)
        retiredNodes.clear()
        cursor = 0
    }

    private fun activateHostBlurSurface(view: View) {
        if (view.getAdditionalInstanceField(HOST_SURFACE_ACTIVE) == true) return
        runCatching {
            val intType = Int::class.javaPrimitiveType!!
            View::class.java.getMethod("setMiBackgroundBlurMode", intType).invoke(view, 1)
            View::class.java.getMethod("setMiViewBlurMode", intType).invoke(view, 1)
            View::class.java.getMethod("setMiBackgroundBlurEnhanceFlag", intType, intType)
                .invoke(view, 8192, 12288)
            view.setAdditionalInstanceField(HOST_SURFACE_ACTIVE, true)
        }
    }

    private class GlassNode(context: Context, private val api: GlassApi) {
        // View is only the bridge to HyperOS's Bionics material setters. Its RenderNode is
        // recorded directly into the group's Canvas; the bridge never enters the view tree.
        private val bridge = View(context.applicationContext)
        private val node = api.renderNode.get(bridge) as RenderNode
        private val localPath = Path()
        private val outline = Outline()
        private var width = 0
        private var height = 0
        private var color = 0
        private var radius = -1
        private var material: SoftGlassParams? = null
        private var outlineHeight = 0
        private var outlineInitialized = false
        private var outlineApplied = false
        private var minimumWidth = 0
        private var minimumHeight = 0
        private var active = false

        private var lastTop = Float.NaN
        private var lastBottom = Float.NaN

        fun canReuse(rect: RectF, requestedHeight: Int): Boolean {
            if (lastRequestedHeight == 0) return true
            if (requestedHeight < lastRequestedHeight - 64) return false
            if (lastTop.isNaN() || lastBottom.isNaN()) return true
            // A normal scroll moves both edges by a small, similar amount. A slot that has
            // shifted from one card to another jumps by roughly a whole card height.
            val movementLimit = maxOf(96f, requestedHeight * 0.35f)
            return abs(rect.top - lastTop) <= movementLimit &&
                abs(rect.bottom - lastBottom) <= movementLimit
        }

        private var lastRequestedHeight = 0

        fun seedFrom(previous: GlassNode) {
            localPath.set(previous.localPath)
            outlineHeight = previous.outlineHeight
            outlineInitialized = previous.outlineInitialized
            minimumWidth = previous.width
            minimumHeight = previous.height
        }

        fun draw(canvas: Canvas, rect: RectF, path: Path, config: SoftGlassParams, density: Float, tintColor: Int) {
            val requestedWidth = ceil(rect.width()).toInt().coerceAtLeast(1)
            val requestedHeight = ceil(rect.height()).toInt().coerceAtLeast(1)
            lastRequestedHeight = requestedHeight
            lastTop = rect.top
            lastBottom = rect.bottom
            try {
                val w = maxOf(width, requestedWidth, minimumWidth)
                val h = maxOf(height, requestedHeight, minimumHeight)
                if (width != w || height != h) {
                    // Keep both the bridge's View geometry and its native node in sync.
                    bridge.layout(0, 0, w, h)
                    node.setPosition(0, 0, w, h)
                }
                // System's own sequence (miuix HyperMaterialUtils.applyContainerWithGlass +
                // SystemUI MiBackgroundStyle): mode flags first, then the material type, the
                // glass blur radius, and finally the 42 Bionics shader params.
                val physicalRadius = (config.blurRadiusDp * density).roundToInt().coerceIn(0, 500)
                if (!active || radius != physicalRadius || material != config) {
                    // Mark first so partial setup is also cleared if a vendor call fails.
                    active = true
                    api.backgroundMode.invoke(bridge, 1)
                    api.viewMode.invoke(bridge, 1)
                    api.clearBlend.invoke(bridge)
                    api.materialType.invoke(bridge, 1)
                    api.glassRadius.invoke(bridge, physicalRadius, physicalRadius)
                    api.setGlass.invoke(bridge, customizeParams(baseParams(), config))
                    // Bionics samples the backdrop through this local clip.  The stock
                    // Some host views initialise it to a non-negative RenderNode-local
                    // rectangle.  Leaving it at the default makes a scrolled card inherit the
                    // RecyclerView's negative top and keeps the highlight while disabling the
                    // refraction pass.
                    // GLASS_ENHANCE_FLAG/BLUR_ENHANCE_FLAG_MASK from SystemUI's MiBlurCompat:
                    // Bionics rounding needs flag 8192, Classic would use 4096.
                    api.enhanceFlag.invoke(bridge, 8192, 12288)
                    radius = physicalRadius
                    material = config
                }
                if (width != w || height != h || color != tintColor || !node.hasDisplayList()) {
                    val recording = node.beginRecording(w, h)
                    try { recording.drawColor(tintColor) } finally { node.endRecording() }
                    width = w
                    height = h
                    color = tintColor
                }
                // Always keep the complete card outline in node-local coordinates. When the
                // card top is negative, only the Canvas placement is clamped; clipping or
                // shortening this outline removes Bionics' full-shape refraction input.
                // CardItemDecoration rebuilds the group path from the remaining visible rows.
                // When the first row leaves the viewport, that path becomes shorter even though
                // it is still the same card. Bionics uses the RenderNode outline for refraction;
                // replacing it with the shortened path drops refraction while leaving the edge
                // highlight. Keep the last complete outline whenever the supplied group shrinks.
                val outlineChanged = !outlineInitialized || requestedHeight > outlineHeight
                if (outlineChanged) {
                    localPath.set(path)
                    localPath.offset(-rect.left, -rect.top)
                    outlineHeight = requestedHeight
                    outlineInitialized = true
                }
                if (outlineChanged || !outlineApplied) {
                    outline.setPath(localPath)
                    outline.alpha = 1f
                    node.setOutline(outline)
                    node.setClipToOutline(true)
                    node.setClipToBounds(true)
                    outlineApplied = true
                }
                val checkpoint = canvas.save()
                try {
                    // Keep the same Canvas sequence as the working frost drawable.
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
                runCatching { api.materialType.invoke(bridge, 0) }
                // Do not call setMiGlass with an empty array here. The native JNI entry point
                // accepts exactly 42 floats; any other length logs "setMiGlass jni fail" and
                // returns failure. In particular, this used to happen while a recycled node
                // was still referenced by the display list during a fling, disabling glass for
                // the clipped (top) card. Clearing the material type/modes is sufficient.
                runCatching { api.glassRadius.invoke(bridge, 0, 0) }
                runCatching { api.enhanceFlag.invoke(bridge, 0, 12288) }
                runCatching { api.clearBlend.invoke(bridge) }
                runCatching { api.viewMode.invoke(bridge, 0) }
                runCatching { api.backgroundMode.invoke(bridge, 0) }
            }
            active = false
            radius = -1
            material = null
            outlineHeight = 0
            outlineInitialized = false
            outlineApplied = false
            minimumWidth = 0
            minimumHeight = 0
            lastRequestedHeight = 0
            lastTop = Float.NaN
            lastBottom = Float.NaN
            node.discardDisplayList()
        }
    }

    private class GlassApi(
        val renderNode: Field,
        val backgroundMode: Method,
        val viewMode: Method,
        val clearBlend: Method,
        val glassRadius: Method,
        val materialType: Method,
        val setGlass: Method,
        val enhanceFlag: Method,
    )

    companion object {
        /**
         * setMiViewMaterialType/setMiGlass exist only on HyperOS 4's Bionics builds; their
         * absence marks the whole material unavailable and the card hook stays on frost.
         */
        fun hasBionicsApi(): Boolean = glassApi != null

        /**
         * Full gate the system itself uses before rendering Bionics glass
         * (HyperMaterialUtils.isGlassReady): the hardware property, the background-blur
         * toggle, and the user's material_style choice. Method presence alone is not
         * enough — with the system switch off the setters succeed but render nothing.
         */
        fun isBionicsActive(context: Context): Boolean {
            if (glassApi == null) return false
            val now = SystemClock.elapsedRealtime()
            if (activeCheckedAt != 0L && now - activeCheckedAt < ACTIVE_CHECK_TTL_MS) {
                return bionicsActive
            }
            val resolver = context.contentResolver
            bionicsActive = runCatching {
                bionicMaterialSupported() &&
                    Settings.Secure.getInt(resolver, "background_blur_enable", 0) == 1 &&
                    Settings.Secure.getInt(resolver, "material_style", -1) == 1
            }.getOrDefault(false)
            activeCheckedAt = now
            return bionicsActive
        }

        /** Fresh values for the module log; shows which gate blocks rendering, if any. */
        fun bionicsDiagnostics(context: Context): String = runCatching {
            val resolver = context.contentResolver
            "api=${glassApi != null} bionicProp=${bionicMaterialSupported()} " +
                "blurEnable=${Settings.Secure.getInt(resolver, "background_blur_enable", 0)} " +
                "materialStyle=${Settings.Secure.getInt(resolver, "material_style", -1)}"
        }.getOrDefault("diagnostics unavailable")

        /**
         * Apply the same Bionics material parameters directly to a real, attached widget.
         * The caller paints [materialTintColor] into the widget's native rounded background,
         * matching the display-list tint used by [GlassNode] instead of using shader tint
         * channels 11-14, which compose color and alpha differently.
         */
        fun applyToView(view: View, config: SoftGlassParams, density: Float): Boolean =
            applyMaterial(view, config, density, params = { customizeParams(baseParams(), it) })

        /**
         * Same material, but the palette color travels in the shader tint channels (11-14) and the
         * widget's own fill is muted instead of repainted.
         *
         * Use this for hosts whose background belongs to the system's own material implementation:
         * MIUIX's `SearchViewMaterialImpl` keys its `BackgroundAlphaTarget` on exactly these child
         * backgrounds, animating them to alpha 0 while its glass is on and back to 1 when it is off.
         * Painting a palette color into such a drawable is therefore either swallowed by the zero
         * alpha or left behind as a flat film that replaces the native look - the search
         * box lost its soft glass that way (initial state flat, opened input state still fine).
         * Channel semantics: docs/soft-glass-api.md §5 (11-14 = tint/inner layer).
         *
         * This deliberately does NOT go through [applyMaterial]. That shared path carries beta9's
         * extra constraints - `isAttachedToWindow`/`isHardwareAccelerated` as a hard gate, and
         * [requireAccepted] throwing on any setter that answers `false` before [clearFromView]
         * tears the whole material down again. MIUI's `setMi*` family is not consistent about that
         * return value, and search fragments can inflate their stub before the window is
         * attached, so both constraints turn a healthy apply into a silent no-op or an immediate
         * rollback. beta8 had neither and rendered correctly; the call order below is that version.
         */
        fun applyToView(
            view: View, color: Int, config: SoftGlassParams, density: Float,
            clearBackground: Boolean = true,
        ): Boolean {
            val api = glassApi ?: return false
            if (!isBionicsActive(view.context)) return false
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated) {
                // beta9 added this gate; beta8 had none. Keep the safety check but never let it
                // swallow the apply - queue it for the moment the host actually becomes drawable.
                applyWhenReady(view, color, config, density, clearBackground, READY_RETRIES)
                return false
            }
            return runCatching {
                val radius = (config.blurRadiusDp * density).roundToInt().coerceIn(0, 500)
                api.backgroundMode.invoke(view, 1)
                api.viewMode.invoke(view, 1)
                api.clearBlend.invoke(view)
                api.materialType.invoke(view, 1)
                api.glassRadius.invoke(view, radius, radius)
                api.setGlass.invoke(view, tintedParams(baseParams(), config, color))
                api.enhanceFlag.invoke(view, 8192, 12288)

                // Keep the drawable as the View outline source, but let the shader own the fill.
                if (clearBackground) clearBackgroundFill(view)
                view.invalidate()
                true
            }.getOrDefault(false)
        }

        /**
         * Deferred variant of [applyToView] for hosts that are still being inflated. Waits for the
         * window attach and hardware acceleration separately, then hands over to [applyToView],
         * which will take the real path because both preconditions now hold - no recursion.
         */
        private fun applyWhenReady(
            view: View,
            color: Int,
            config: SoftGlassParams,
            density: Float,
            clearBackground: Boolean,
            attemptsLeft: Int,
        ) {
            if (attemptsLeft <= 0) return
            runCatching {
                if (!view.isAttachedToWindow) {
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            v.removeOnAttachStateChangeListener(this)
                            v.post { applyWhenReady(v, color, config, density, clearBackground, attemptsLeft - 1) }
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    })
                } else if (!view.isHardwareAccelerated) {
                    view.post { applyWhenReady(view, color, config, density, clearBackground, attemptsLeft - 1) }
                } else {
                    applyToView(view, color, config, density, clearBackground)
                }
            }
        }

        private fun applyMaterial(
            view: View,
            config: SoftGlassParams,
            density: Float,
            params: (SoftGlassParams) -> FloatArray,
        ): Boolean {
            val api = glassApi ?: return false
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated || !isBionicsActive(view.context)) return false
            return runCatching {
                val radius = (config.blurRadiusDp * density).roundToInt().coerceIn(0, 500)
                requireAccepted(api.backgroundMode, view, 1)
                requireAccepted(api.viewMode, view, 1)
                requireAccepted(api.clearBlend, view)
                requireAccepted(api.materialType, view, 1)
                requireAccepted(api.glassRadius, view, radius, radius)
                requireAccepted(api.setGlass, view, params(config))
                requireAccepted(api.enhanceFlag, view, 8192, 12288)
                view.invalidate()
                true
            }.onFailure {
                // A setter can fail after earlier calls succeeded. Always undo the partial state
                // before the caller swaps in its transparent fallback.
                clearFromView(view)
            }.getOrDefault(false)
        }

        /**
         * Keep the drawable as the View outline source, but let the shader own the fill. Only the
         * shader-tinted overload uses this: carriers of the card path deliberately paint their own
         * fill (display list or native rounded background) and must keep it.
         */
        private fun clearBackgroundFill(view: View) {
            val background = view.background?.mutate() ?: return
            background.setTint(Color.TRANSPARENT)
            if (background !== view.background) view.background = background
        }

        /** Clear only the native material state; the caller owns/restores the background. */
        fun clearFromView(view: View) {
            val api = glassApi ?: return
            runCatching { api.materialType.invoke(view, 0) }
            runCatching { api.glassRadius.invoke(view, 0, 0) }
            runCatching { api.enhanceFlag.invoke(view, 0, 12288) }
            runCatching { api.clearBlend.invoke(view) }
            runCatching { api.viewMode.invoke(view, 0) }
            runCatching { api.backgroundMode.invoke(view, 0) }
            view.invalidate()
        }

        private fun requireAccepted(method: Method, target: View, vararg args: Any) {
            check(method.invoke(target, *args) != false) { "${method.name} rejected the material" }
        }

        private fun bionicMaterialSupported(): Boolean = runCatching {
            val get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
            get.invoke(null, "persist.sys.bionic_material_supported", "false") == "true"
        }.getOrDefault(false)

        private const val ACTIVE_CHECK_TTL_MS = 3000L

        /**
         * Frames/attaches [applyWhenReady] may spend waiting for a host to become drawable before
         * giving up. 8 covers "inflated but not attached yet" plus a few dropped frames.
         */
        private const val READY_RETRIES = 8
        private const val HOST_SURFACE_ACTIVE = "hyperbackground_glass_host_surface_active"
        @Volatile private var bionicsActive = false
        @Volatile private var activeCheckedAt = 0L

        private val glassApi: GlassApi? by lazy {
            val intType = Int::class.javaPrimitiveType!!
            runCatching {
                GlassApi(
                    View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true },
                    View::class.java.getMethod("setMiBackgroundBlurMode", intType),
                    View::class.java.getMethod("setMiViewBlurMode", intType),
                    View::class.java.getMethod("clearMiBackgroundBlendColor"),
                    View::class.java.getMethod("setMiGlassBlurRadius", intType, intType),
                    View::class.java.getMethod("setMiViewMaterialType", intType),
                    View::class.java.getMethod("setMiGlass", FloatArray::class.java),
                    View::class.java.getMethod("setMiBackgroundBlurEnhanceFlag", intType, intType),
                )
            }.getOrNull()
        }

        /** HyperIsland's expanded-island token baseline; the host process has no token to clone. */
        private fun baseParams(): FloatArray = floatArrayOf(
            0f, 2f, .5f, .8f, .15f, 2.4f, .3f, .2f, 0f, 0f, 0f,
            .06f, .06f, .06f, .6f, .15f, .4f, 1.36f, 1f, 72f, 3.8f,
            80f, 1000f, 1.2f, .6f, -.4f, .6f, -.8f, 1.8f, 1.2f, 1f,
            1.1764706f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )

        private fun customizeParams(source: FloatArray, config: SoftGlassParams): FloatArray {
            val params = source.clone()
            fun scale(index: Int, configured: Double) {
                val original = params[index]
                params[index] = if (original == 0f) configured.toFloat()
                else original * (1f + configured.toFloat() / 100f)
            }
            scale(4, config.softLight)
            params[5] = (1f + config.saturation.toFloat() / 100f).coerceIn(.5f, 1.5f)
            scale(6, config.brightness)
            scale(7, config.darker)
            scale(21, config.edgeThickness)
            scale(24, config.reflection)
            scale(28, config.directionalLightIntensity)
            scale(32, config.refraction)
            scale(33, config.backgroundSaturation)
            scale(34, config.backgroundBrightness)
            scale(35, config.burn)
            if (!config.highlight) {
                params[24] = 0f
                params[28] = 0f
            }
            // Xiaomi's expanded token also mixes a fixed white inner layer through channels
            // 15/16. Keeping it after clearing the RGB tint is what makes the island look
            // opaque gray, so zero it for cards. The palette tint is drawn by the group's
            // display list or the standalone View's native background instead of shader
            // channels 11-14.
            params[11] = 0f
            params[12] = 0f
            params[13] = 0f
            params[14] = 0f
            params[15] = 0f
            params[16] = 0f
            return params
        }

        /**
         * Channels 11-14 carry the palette tint (RGB + alpha, 0..1) for hosts that must keep their
         * native background - the shader owns the fill there instead of a drawable. 15/16 stay
         * cleared so Xiaomi's fixed white inner layer cannot wash the tint out.
         */
        private fun tintedParams(source: FloatArray, config: SoftGlassParams, color: Int): FloatArray =
            customizeParams(source, config).apply {
                val tint = materialTintColor(color, config)
                this[11] = (tint ushr 16 and 0xFF) / 255f
                this[12] = (tint ushr 8 and 0xFF) / 255f
                this[13] = (tint and 0xFF) / 255f
                this[14] = (tint ushr 24 and 0xFF) / 255f
            }

        fun materialTintColor(color: Int, config: SoftGlassParams): Int {
            val sourceAlpha = color ushr 24 and 0xFF
            val alpha = (sourceAlpha / 255f * (1f + config.transparency.toFloat() / 100f))
                .coerceIn(0f, 1f)
            return (color and 0x00FFFFFF) or ((alpha * 255f).roundToInt() shl 24)
        }
    }
}
