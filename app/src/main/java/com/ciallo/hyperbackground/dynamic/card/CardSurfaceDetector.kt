package com.ciallo.hyperbackground.dynamic.card

import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * 内容驱动的「独立卡片」判定：不看资源 id、不看第三方类名，只看一个视图**自己画出了什么面**。
 *
 * 这条判定替代了旧的 `STANDALONE_CARD_IDS` 资源名白名单，所以换页面、换 apk、换机型都不需要再适配。
 * 规则来自蓝牙那一课（见 docs/soft-glass-api.md §10）：
 * `BluetoothDevicePreference` 对已保存设备和可用设备复用同一个布局 `preference_bt_icon_corner`，
 * 两者的区别不是 id，而是 `ConnectPreferenceHelper` 写进 `view_high_light_root` 的那层不透明高亮面——
 * 已保存设备保留它（那一行自己就是卡片），可用设备行在绑定末尾被 `setBackground(null)` 清空
 * （它只是外层分组卡上的普通行，材质由分组卡提供）。
 *
 * 检查链刻意按「先便宜、后昂贵」排列，绝大多数视图在第一、二步就退出：
 *
 * 1. 尺寸够（[MIN_WIDTH_DP] × [MIN_HEIGHT_DP]）；
 * 2. 视图自己有 background（没有 → 列表行，永远不碰）；
 * 3. 名字 / 类名不带负向关键词；
 * 4. 形状是卡片 —— **先判形状再判不透明度**，形状确认后允许半透明卡面
 *    （HyperOS 的 `hp_card_bg`、MIUIX 分组卡色本身就是半透明）；
 * 5. 同一条目内没有更外层的卡片面（避免「卡片里再套一层卡片」重复叠材质）。
 */
internal object CardSurfaceDetector {

    /** 通用路由的 key 前缀，避免与旧的字面 id key 混用（便于在日志里一眼分辨走的是哪条路）。 */
    const val GENERIC_KEY_PREFIX = "dyn:"

    /** 表面最低不透明度（0..255）。低于该值且形状不是卡片时视为遮罩 / 选中态。 */
    private const val MIN_SURFACE_ALPHA = 190

    /**
     * 形状已经确认是圆角卡片时的最低不透明度。MIUIX / HyperOS 的分组卡与 `hp_card_bg`
     * 卡片底可以是半透明的（例如 `#24FFFFFF`），用 [MIN_SURFACE_ALPHA] 会把它们全部漏掉。
     */
    private const val MIN_SHAPE_SURFACE_ALPHA = 24

    /** 卡片最小宽 / 高（dp）。小于该尺寸的基本是图标、徽标、按钮。 */
    private const val MIN_WIDTH_DP = 56
    private const val MIN_HEIGHT_DP = 40

    /** 向上找「更外层卡片面」时最多看几层，避免长链上反复判定。 */
    private const val MAX_ANCESTOR_DEPTH = 6

    /** 向上找「圆角卡片容器」时最多看几层（子视图本身通常是圆角面的内容层）。 */
    private const val MAX_SHAPE_ANCESTOR_DEPTH = 3

    /** 拆 drawable 容器（selector / layer / inset / ripple）时的最大嵌套层数。 */
    private const val MAX_DRAWABLE_DEPTH = 6

    /** 资源名 / 类名里出现这些词的视图一律不是卡片。 */
    private val NEGATIVE_NAME_HINTS = listOf(
        "icon", "badge", "avatar", "button", "switch", "checkbox", "radio",
        "divider", "indicator", "progress", "seekbar", "loading", "tab", "chip",
        "dot", "thumb", "cursor", "handle", "scrim", "mask", "ripple",
        "toast", "tooltip", "snackbar", "bubble", "arrow", "shadow",
    )

    /** 类名里出现这些词的就是卡片类容器本身。 */
    private val CARD_CLASS_HINTS = listOf(
        "cardview", "smoothframelayout", "roundrectdrawable",
    )

    /** 未命中的原因（诊断日志用，全部是常量，热路径上零分配）。 */
    const val REASON_TOO_SMALL = "too-small"
    const val REASON_NO_BACKGROUND = "no-background"
    const val REASON_TRANSPARENT_SURFACE = "transparent-surface"
    const val REASON_NEGATIVE_NAME = "negative-name"
    const val REASON_NOT_CARD_SHAPED = "not-card-shaped"
    const val REASON_OUTER_CARD_SURFACE = "outer-card-surface"
    const val REASON_GROUP_LIST_ROW = "group-list-row"

    /**
     * 半透明卡片被接管时的回调（诊断用，可能为 null）。宿主把它接到去重日志上，
     * 于是「哪些半透明面被当成卡片了」在真机上可直接回看。
     */
    var onTranslucentCard: ((View, Int) -> Unit)? = null

    /** 该视图是否是一个「自己带面」的独立卡片。 */
    fun looksLikeStandaloneCard(view: View): Boolean = probe(view) == null

    /**
     * 与 [looksLikeStandaloneCard] 是同一套判定，多带一个「被哪条规则拒掉」的结果：
     * 命中返回 `null`，否则返回上面某个 `REASON_*` 常量。只用于诊断日志。
     */
    fun probe(view: View): String? {
        val density = view.resources.displayMetrics.density
        if (view.width < (MIN_WIDTH_DP * density).roundToInt() ||
            view.height < (MIN_HEIGHT_DP * density).roundToInt()
        ) {
            return REASON_TOO_SMALL
        }
        // MIUIX preference cells and CardStateDrawable rows are recycled list items.
        // Their background/foreground can look rounded, but applying a separate blur to
        // each row makes the group flash as cells are rebound during scrolling.
        if (isGroupedListRow(view)) return REASON_GROUP_LIST_ROW
        if (isEmptyCardInGroup(view)) return REASON_GROUP_LIST_ROW
        // 1) 没有自己的背景 → 它是外层分组卡上的普通行，材质由分组卡提供，绝不叠加。
        val background = view.background ?: return REASON_NO_BACKGROUND
        if (hasNegativeNameHint(view)) return REASON_NEGATIVE_NAME
        // 2) 必须是卡片形状。前景按压效果不能证明该行自身是一张卡片。
        if (!isCardShaped(view, background)) return REASON_NOT_CARD_SHAPED
        val alpha = strongestSurfaceAlpha(view, background)
        if (alpha < MIN_SHAPE_SURFACE_ALPHA) return REASON_TRANSPARENT_SURFACE
        if (alpha < MIN_SURFACE_ALPHA) onTranslucentCard?.invoke(view, alpha)
        // 3) 同一条目内已经有更外层的卡片面 → 这一层是它的内容，不重复上材质。
        if (hasOuterCardSurface(view)) return REASON_OUTER_CARD_SURFACE
        return null
    }

    private fun isGroupedListRow(view: View): Boolean {
        var node: View? = view
        for (depth in 0 until MAX_ANCESTOR_DEPTH) {
            val current = node ?: break
            if (current.javaClass.name == "miuix.flexible.view.HyperCellLayout") return true
            if (current.javaClass.name.contains("RecyclerView")) break
            node = current.parent as? View
        }
        val stateRow = view.foreground?.javaClass?.name ==
            "com.miui.support.drawable.CardStateDrawable"
        if (!stateRow) return false
        var parent = view.parent as? View
        repeat(MAX_ANCESTOR_DEPTH) {
            val current = parent ?: return false
            if (current.javaClass.name.contains("RecyclerView")) return true
            parent = current.parent as? View
        }
        return false
    }

    /** A transparent card shell (or its row root) inside a decorated list is not a second card.
     * Wi-Fi retains a highlighted child even for disconnected rows; when the shell fills the
     * entire row it still belongs to the group. Inset cards with their own bounds remain cards.
     */
    private fun isEmptyCardInGroup(view: View): Boolean {
        val group = view as? ViewGroup ?: return false
        fun groupShell(card: View, row: View): Boolean {
            if (cardBackgroundAlpha(card) != 0) return false
            val content = card as? ViewGroup ?: return false
            val hasSurface = (0 until content.childCount).any { index ->
                val background = content.getChildAt(index).background
                background != null && surfaceAlpha(background) >= MIN_SHAPE_SURFACE_ALPHA
            }
            if (!hasSurface) return true
            // A row-sized transparent CardView merely carries the row's connected-state
            // drawable. Inset CardViews are independent surfaces even within a group list.
            val params = card.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && (params.leftMargin > 0 || params.rightMargin > 0 ||
                    params.topMargin > 0 || params.bottomMargin > 0)) return false
            var left = 0
            var top = 0
            var node: View = card
            while (node !== row) {
                left += node.left
                top += node.top
                node = node.parent as? View ?: return false
            }
            return card.width > 0 && card.height > 0 &&
                left == 0 && top == 0 && card.width == row.width && card.height == row.height
        }
        val row = generateSequence<View>(view) { it.parent as? View }
            .take(MAX_ANCESTOR_DEPTH)
            .firstOrNull { (it.parent as? View)?.javaClass?.name?.contains("RecyclerView") == true }
            ?: return false
        val isShell = groupShell(view, row)
        val isRowWithShell = view === row && cardBackgroundAlpha(view) < 0 &&
            (0 until group.childCount).any { index -> groupShell(group.getChildAt(index), row) }
        // The stateful highlight is itself a full-bleed child of the transparent shell.
        // Otherwise it can be picked up independently even when the shell was rejected.
        val shell = view.parent as? View
        val isShellContent = shell != null && groupShell(shell, row) &&
            view.left == 0 && view.top == 0 &&
            view.width == shell.width && view.height == shell.height
        if (!isShell && !isRowWithShell && !isShellContent) return false
        var parent: View? = view.parent as? View
        repeat(MAX_ANCESTOR_DEPTH) {
            val current = parent ?: return false
            if (current.javaClass.name.contains("RecyclerView")) {
                return DynamicCardMaterialHook.hasGroupDecoration(current)
            }
            parent = current.parent as? View
        }
        return false
    }

    /**
     * 「自己有面、尺寸也够，只是被后面某条规则拦下」——这类视图最值得看日志：
     * 尺寸不足 / 无背景这类早退通常是正确行为（不打日志），而这里返回 true 的原因
     * 往往意味着漏判，需要宿主记进诊断日志定位。
     */
    fun isNearMiss(reason: String): Boolean =
        reason == REASON_NOT_CARD_SHAPED ||
            reason == REASON_OUTER_CARD_SURFACE ||
            reason == REASON_TRANSPARENT_SURFACE ||
            reason == REASON_NEGATIVE_NAME

    /** 稳定的路由 key：优先资源名，取不到就用类名（跨进程 / 跨 apk 都成立）。 */
    fun key(view: View): String {
        val name = runCatching {
            if (view.id == View.NO_ID || view.id == 0) null
            else view.resources.getResourceEntryName(view.id)
        }.getOrNull()
        return GENERIC_KEY_PREFIX + (name ?: view.javaClass.name)
    }

    // ---------------------------------------------------------------- 表面不透明度

    /**
     * 视图背景里最不透明的一层。
     * CardView 以 `getCardBackgroundColor()` 为准——蓝牙的 `view_corner` 就是
     * `app:cardBackgroundColor="@android:color/transparent"`，只有子层高亮面才是真表面。
     */
    fun strongestSurfaceAlpha(view: View, background: Drawable): Int {
        val cardAlpha = cardBackgroundAlpha(view)
        val backgroundAlpha = surfaceAlpha(background)
        return if (cardAlpha >= 0) maxOf(cardAlpha, backgroundAlpha) else backgroundAlpha
    }

    /** CardView 的卡片底色 alpha（0..255）；不是 CardView 时返回 -1 表示「不适用」。 */
    private fun cardBackgroundAlpha(view: View): Int {
        if (CARD_CLASS_HINTS.none { view.javaClass.name.lowercase().contains(it) }) return -1
        val list = runCatching {
            view.javaClass.getMethod("getCardBackgroundColor").invoke(view) as? ColorStateList
        }.getOrNull() ?: return -1
        return list.defaultColor ushr 24 and 0xFF
    }

    /** 背景里最不透明的一层（把 selector / layer / inset / ripple 拆到底后再取）。 */
    fun surfaceAlpha(drawable: Drawable?): Int =
        withLeaves(drawable) { parts -> parts.maxOfOrNull(::leafAlpha) ?: 0 }

    // `Drawable.getOpacity()` 在 API 35 被标记废弃，但它仍是「这个 drawable 会不会画出东西」
    // 唯一可用的判据；换成别的属性会改变语义（例如把全透明的 BitmapDrawable 当成有效面）。
    @Suppress("DEPRECATION")
    private fun leafAlpha(drawable: Drawable): Int {
        val declared = drawable.alpha and 0xFF
        return when (drawable) {
            is ColorDrawable -> (drawable.color ushr 24 and 0xFF) * declared / 255
            else -> if (drawable.opacity == PixelFormat.TRANSPARENT) 0 else declared
        }
    }

    // ---------------------------------------------------------------- drawable 展平

    /**
     * 把一个背景拆成它真正会画出来的若干叶子 drawable。
     *
     * 这一层是通用适配的关键：MIUI / HyperOS 的卡片底几乎都是容器型 drawable，
     * 例如省电与电池的 `selector_battery_card_bg` → `hp_card_bg_no_shadow_normal`
     * （layer-list：一层全宽的底色 + 一层带圆角的卡片色），
     * 只看最外层既拿不到圆角（会判成「不是卡片」），也拿不到真正的填充色。
     */
    private fun leaves(drawable: Drawable?, depth: Int, out: MutableList<Drawable>) {
        if (drawable == null || depth > MAX_DRAWABLE_DEPTH) return
        val before = out.size
        when (drawable) {
            is StateListDrawable -> stateDrawables(drawable).forEach { leaves(it, depth + 1, out) }
            is LayerDrawable -> (0 until drawable.numberOfLayers).forEach { index ->
                leaves(runCatching { drawable.getDrawable(index) }.getOrNull(), depth + 1, out)
            }
            is InsetDrawable -> leaves(drawable.drawable, depth + 1, out)
            // ripple 的遮罩层是纯装饰，只有第 0 层是真正的内容，取它才不会把遮罩当成卡面。
            is RippleDrawable -> leaves(runCatching { drawable.getDrawable(0) }.getOrNull(), depth + 1, out)
            else -> Unit
        }
        // 一个叶子都没拆出来（图片 / 渐变 / 纯 path 底）→ 容器本身就算叶子。
        if (out.size == before) out.add(drawable)
    }

    /**
     * 展平缓冲区按线程复用：`setBackground` / `onSizeChanged` 每次都要拆 drawable，
     * 每帧为每个视图新建一个 ArrayList 会白白制造 GC 压力。
     *
     * **不可重入**：`block` 里不要再调 [withLeaves]，否则内层 `clear()` 会把外层正在遍历的列表清空。
     */
    private val leavesScratch: ThreadLocal<MutableList<Drawable>> =
        ThreadLocal.withInitial { ArrayList<Drawable>(8) }

    private inline fun <T> withLeaves(drawable: Drawable?, block: (MutableList<Drawable>) -> T): T {
        // `ThreadLocal.withInitial(...).get()` 在 K2 下被当成可空平台类型，显式兜一次底：
        // 只有首次调用（initialValue 被跳过）才可能为空，正常路径零分配。
        val parts: MutableList<Drawable> = leavesScratch.get() ?: ArrayList<Drawable>(8).also {
            leavesScratch.set(it)
        }
        parts.clear()
        leaves(drawable, 0, parts)
        return block(parts)
    }

    /**
     * `StateListDrawable` 的各状态层。`getStateCount` / `getStateDrawable`
     * 在不同系统版本上的可见性不一致，因此用反射并允许失败。
     */
    private fun stateDrawables(drawable: StateListDrawable): List<Drawable> = runCatching {
        val count = drawable.javaClass.getMethod("getStateCount").invoke(drawable) as Int
        if (count <= 0) return@runCatching emptyList()
        val getter = drawable.javaClass.getMethod(
            "getStateDrawable",
            Int::class.javaPrimitiveType!!,
        )
        (0 until count).mapNotNull { getter.invoke(drawable, it) as? Drawable }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- 形状

    /**
     * 曲线半径。`ViewOutlineProvider.getOutline` 与 `Drawable.getOutline` **都是 void**，
     * 不能把它们的返回值当结果——必须调用后读回填的 `Outline.radius`，
     * 而且 provider 与 background 各用一个独立 `Outline`，否则会互相污染。
     */
    private fun outlineRadiusOf(drawable: Drawable): Float = runCatching {
        val outline = Outline()
        drawable.getOutline(outline)
        outline.radius
    }.getOrDefault(0f)

    private fun outlineRadiusOf(view: View): Float = runCatching {
        val provider = view.outlineProvider ?: return@runCatching 0f
        val outline = Outline()
        provider.getOutline(view, outline)
        outline.radius
    }.getOrDefault(0f)

    /** 叶子 drawable 的圆角半径。`GradientDrawable` 优先读自己的 cornerRadius。 */
    private fun leafCornerRadius(drawable: Drawable): Float {
        val gradient = if (drawable is GradientDrawable) {
            runCatching { drawable.cornerRadius }.getOrDefault(0f)
        } else {
            0f
        }
        return maxOf(gradient, outlineRadiusOf(drawable))
    }

    private fun isCardShaped(view: View, background: Drawable): Boolean {
        val className = view.javaClass.name.lowercase()
        if (CARD_CLASS_HINTS.any(className::contains)) return true
        if (outlineRadiusOf(view) > 0f) return true
        val rounded = withLeaves(background) { shapes ->
            shapes.any { shape ->
                shape.javaClass.name.lowercase().contains("roundrect") || leafCornerRadius(shape) > 0f
            }
        }
        if (rounded) return true
        // 自己的背景看不出圆角（例如内容层被重写成纯色）→ 父级是圆角卡片容器也算。
        return hasRoundedCardAncestor(view)
    }

    /** 祖先里有圆角卡片容器（或卡片类）→ 这一层是它的内容层。 */
    private fun hasRoundedCardAncestor(view: View): Boolean {
        var parent: View? = view.parent as? View
        var depth = 0
        while (parent != null && depth < MAX_SHAPE_ANCESTOR_DEPTH) {
            val candidate = parent
            if (CARD_CLASS_HINTS.any(candidate.javaClass.name.lowercase()::contains)) return true
            val background = candidate.background
            if (background != null &&
                withLeaves(background) { shapes -> shapes.any { leafCornerRadius(it) > 0f } }
            ) {
                return true
            }
            parent = candidate.parent as? View
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------- 去重与排除

    /** 同一条目里更外层已经有「自己带面」的卡片 → 这一层是它的内容，不重复上材质。 */
    private fun hasOuterCardSurface(view: View): Boolean {
        var parent: View? = view.parent as? View
        var depth = 0
        while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
            val candidate = parent
            val background = candidate.background
            if (background != null &&
                surfaceAlpha(background) >= MIN_SHAPE_SURFACE_ALPHA &&
                isCardShaped(candidate, background)
            ) {
                return true
            }
            // 到了列表容器就不必再往上：更外层是页面，不是卡片。
            if (candidate is ViewGroup && candidate.javaClass.name.contains("RecyclerView")) return false
            parent = candidate.parent as? View
            depth++
        }
        return false
    }

    private fun hasNegativeNameHint(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        if (NEGATIVE_NAME_HINTS.any(className::contains)) return true
        val resourceName = runCatching {
            if (view.id == View.NO_ID || view.id == 0) null
            else view.resources.getResourceEntryName(view.id).lowercase()
        }.getOrNull() ?: return false
        return NEGATIVE_NAME_HINTS.any(resourceName::contains)
    }
}
