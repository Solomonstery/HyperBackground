package com.ciallo.hyperbackground

import android.app.Activity
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.ArrayList
import java.util.IdentityHashMap

object BackgroundApplier {
    // Namespace Xposed additional fields with the stable application id so sessions cannot
    // collide with another module using similar field names inside the hooked process.
    private val FIELD_PREFIX = BuildConfig.APPLICATION_ID + ".hook."
    private val HOME_SESSION = FIELD_PREFIX + "home.session"
    private val GLOBAL_SESSION = FIELD_PREFIX + "global.session"
    private val DEVICE_SESSION = FIELD_PREFIX + "device.session"
    private val CONTACTS_SESSION = FIELD_PREFIX + "contacts.session"
    // 拨号盘独立背景层的会话，存到 DialpadLayout 实例上（拨号盘可被反复 inflate/复用）。
    private val DIALPAD_SESSION = FIELD_PREFIX + "dialpad.session"
    private val CONTACTS_RESCAN = FIELD_PREFIX + "contacts.rescan"
    private val CONTACTS_ADAPT_AT = FIELD_PREFIX + "contacts.adapt.at"
    private val CONTACTS_ADAPT_DIRTY = FIELD_PREFIX + "contacts.adapt.dirty"
    // 清除列表不透明中性色背景前，把原背景存到该 View 的 Xposed 附加字段，便于开关关闭时还原。
    private val CONTACTS_BG_SAVED = FIELD_PREFIX + "contacts.bg.saved"
    // 自定义模式把 dialer_background_view 的原生 9-patch 底换成透明前，先存原背景到该字段供切回默认时还原。
    private val DIALPAD_BGVIEW_SAVED = FIELD_PREFIX + "dialpad.bgview.saved"
    // 默认模式下承载面板底、可整体调 alpha 的置底纯背景 view（作为 dialpad_container 的第一个子 view）。
    private val DIALPAD_PANEL_ALPHA_VIEW = FIELD_PREFIX + "dialpad.panel.alpha.view"
    // 缓存联系人进程内的资源 id（进程内固定），避免每次布局回调都走 getIdentifier 慢查询。-1=未解析。
    private var contactsBgViewId = -1
    private val DEVICE_ACTIVE = FIELD_PREFIX + "device.active"
    private val ORIGINAL_TEXT_COLOR = FIELD_PREFIX + "original.text.color"
    private val GLOBAL_DIAGNOSTIC = FIELD_PREFIX + "global.diagnostic"

    fun applyHome(activity: Activity?) {
        if (activity == null) return
        applyLayer(activity, BackgroundContract.HOME, HOME_SESSION, true)
        applyFontMode(activity)
    }

    fun stopHome(activity: Activity?) {
        stopLayer(activity, HOME_SESSION)
    }

    fun applyGlobal(activity: Activity?) {
        if (activity == null) return
        if (shouldSkipGlobal(activity)) {
            diagnostic(activity, "skip " + activity.javaClass.name)
            removeGlobal(activity)
            return
        }
        applyLayer(activity, BackgroundContract.GLOBAL, GLOBAL_SESSION, false)
    }

    fun stopGlobal(activity: Activity?) {
        stopLayer(activity, GLOBAL_SESSION)
    }

    fun destroyGlobal(activity: Activity?) {
        removeGlobal(activity)
    }

    // 通讯录与拨号主界面（PeopleActivity，拨号盘/联系人共用同一 Activity）独立背景通道。
    fun applyContacts(activity: Activity?) {
        if (activity == null) return
        if (!matchesContactsSettings(activity.javaClass.name)) {
            removeContacts(activity)
            return
        }
        applyLayer(activity, BackgroundContract.CONTACTS, CONTACTS_SESSION, false)
        adaptContactsSurfaces(activity, false)
        installContactsSurfaceRescan(activity)
    }

    // 拨号盘 / 列表适配：
    //  1) 拨号盘背景板（dialer_background_view，背景是 dialer_background_new 9-patch，浅/深色都不透明）
    //     整体设 alpha 让背景透出，同时不碰装数字键的 dialpad_container，保证按键清晰可读。
    //  2) 联系人列表遮挡背景的不透明中性色（黑/白/灰）背景层——深色下现有逻辑已透出，但浅色下列表
    //     条目（HyperCellLayout content_layout）、列表容器（drawer_layout）等会铺满不透明白，挡住背景。
    //     遍历视图树，对「不透明中性色」背景的 View 清除背景（深浅通吃），保留半透明卡片/渐变遮罩不动。
    //     列表条目随 RecyclerView 滚动复用重建，靠常驻布局监听持续补清。
    // throttled=true 时对高频布局回调做 200ms 节流，避免滚动列表时反复无谓执行。
    private fun adaptContactsSurfaces(activity: Activity, throttled: Boolean) {
        try {
            if (throttled) {
                val last = activity.getAdditionalInstanceField(CONTACTS_ADAPT_AT) as? Long
                val now = SystemClock.uptimeMillis()
                if (last != null && now - last < 200L) return
                activity.setAdditionalInstanceField(CONTACTS_ADAPT_AT, now)
            }
            contactsSampledColors.clear()

            val enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true)

            // 资源 id 进程内固定，只解析一次后缓存复用。
            if (contactsBgViewId == -1) contactsBgViewId = resolveId(activity, "dialer_background_view")

            // 拨号盘的透明度 / 自定义背景全部由 applyDialpadOnInflate（Hook DialpadLayout.onFinishInflate）
            // 在首帧前一次性处理，这里不再逐帧 setAlpha——否则会与 inflate 时的设置反复抢夺、造成闪屏，
            // 且逐帧 setAlpha 也会把自定义模式下归零的原生底又冒出来盖住自定义图。此处仅取 bgView 句柄用于
            // 下面遍历清除时跳过它及其整棵子树（含自定义 media / 9-patch 原生底）。
            val bgView = if (contactsBgViewId == 0) null else activity.findViewById<View>(contactsBgViewId)

            // 只从内容层（android.R.id.content）往下清，绝不碰 DecorView / content_parent 等窗口级
            // 顶层容器——那些不透明中性底是整个窗口的“实底”，抹成透明会让多任务缩放动画时穿透看到
            // 底层桌面/上个应用（频闪），且破坏页面明度层次（观感变暗变平）。
            // 同时跳过拨号盘背景板 bgView 及其子树：它由上面的 setAlpha 专门调透明度，若被通用中性底
            // 清除逻辑把背景换成透明，setAlpha 滑块就再无视觉效果（键盘恒定透明），即透明度调节失效。
            val content = activity.findViewById<View>(android.R.id.content)
            if (content != null) adaptContactsOpaqueSurfaces(content, enabled, content, bgView)

            // 联系人详情页（PeopleDetailActivity）的头像虚化底 / 滚动 / 内容容器背景是非中性色
            // （头像模糊或主题色），通用中性底扫描清不掉；这里随重扫描一并清成透明，让模块背景透出。
            if (enabled) {
                clearContactsDetailSurface(activity, "container_layout")
                clearContactsDetailSurface(activity, "zoom_scrollview")
                clearContactsDetailSurface(activity, "content_container")
            }

            // 搜索是 Miuix SearchActionMode 拉起的覆盖层（ContactsSearchFragment 的 DispatchFrameLayout），
            // 挂在 DecorView 下、android.R.id.content 之外，故上面按 content 收窄的遍历扫不到它——搜索后
            // 结果列表里某层不透明白容器会挡住背景（上半白、下半透出的分界即源于此）。这里按 Miuix 框架
            // id search_mask 从 DecorView 定位该覆盖层，只清其内部子树（跳过 mask 自身：那是设计用的搜索
            // 遮罩层，且清它无意义），把搜索结果列表里的不透明中性底一并透出。search_mask 未出现时（未进入
            // 搜索）findViewById 返回 null，直接跳过。
            val decor = activity.window?.decorView
            if (decor != null) {
                val maskId = resolveFrameworkId(activity, "search_mask")
                val searchMask = if (maskId == 0) null else decor.findViewById<View>(maskId)
                if (searchMask is ViewGroup) {
                    for (i in 0 until searchMask.childCount) {
                        adaptContactsOpaqueSurfaces(searchMask.getChildAt(i), enabled, null, bgView)
                    }
                }
            }
        } catch (error: Throwable) {
            log("adaptContactsSurfaces", error)
        }
    }

    // 解析 Miuix / 框架层的资源 id（如 search_mask）。这些 id 定义在 miuix appcompat 库里，运行期在
    // 联系人进程内以其包名注册，用 getIdentifier 查询；查不到返回 0。
    private fun resolveFrameworkId(activity: Activity, name: String): Int {
        return try {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) id else activity.resources.getIdentifier(name, "id", "android")
        } catch (_: Throwable) {
            0
        }
    }

    // 递归遍历：enabled 时把采样为「不透明中性色（黑/白/灰）」的背景替换为透明占位（清前把原背景存到
    // 该 View 的 Xposed 附加字段以便还原），disabled 时还原。只处理不透明中性色，半透明卡片/渐变遮罩
    // （如 #b3000000 输入框、#80ffffff 渐变、#cc000000）不动，保留其层次；全透明背景（#0）本就不挡，跳过。
    // contentRoot 是 android.R.id.content 本身——它是内容层实底，透明化会让整页失去底色、动画时穿透，
    // 故跳过其自身背景，只清它内部的列表条目/容器等中间层。
    // skip 是拨号盘背景板 dialer_background_view——由 setAlpha 专门处理，跳过其自身及整棵子树，
    // 避免其 9-patch 背景被清成透明导致透明度滑块失效。
    // 关键：用透明 ColorDrawable 占位而非 setBackground(null)——列表条目随 RecyclerView 复用滚动，
    // 若清成 null 会失去覆盖整块区域的背景，硬件加速脏区重绘无法擦除上一帧内容而留下残影/拖拽；
    // 保留一个铺满的透明背景即可让绘制系统正常重绘，同时背景仍透出。
    private fun adaptContactsOpaqueSurfaces(view: View?, enabled: Boolean, contentRoot: View?, skip: View?) {
        if (view == null || view === skip) return
        if (view !== contentRoot) {
            try {
                if (enabled) {
                    val bg = view.background
                    if (bg != null && isOpaqueNeutralSurface(bg)) {
                        makeTransparent(bg)
                    }
                } else {
                    restoreTransparent(view.background)
                }
            } catch (_: Throwable) {
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                adaptContactsOpaqueSurfaces(view.getChildAt(i), enabled, contentRoot, skip)
            }
        }
    }

    // 供 View.setBackground hook 回调调用：item 重绑时一定会 setBackground，在调用后立即清除
    // 不透明中性色底色，避免等全局布局/绘制前扫描的延迟白块。只处理联系人 content 子树内、且非
    // 拨号盘背景板的 view；已被存过原始背景（CONTACTS_BG_SAVED 非空）说明之前已清过，跳过。
    fun onViewBackgroundChanged(view: View?) {
        if (view == null) return
        try {
            val activity = findActivity(view.context) ?: return
            if (!matchesContactsSettings(activity.javaClass.name)) return
            // 跳过拨号盘背景板及其子树（由 setAlpha 专门处理）。
            val bgViewId = resolveId(activity, "dialer_background_view")
            if (bgViewId != 0) {
                val bgView = activity.findViewById<View>(bgViewId)
                if (bgView != null && isDescendant(view, bgView)) return
            }
            val bg = view.background
            if (bg != null && isOpaqueNeutralSurface(bg)) {
                makeTransparent(bg)
                view.invalidate()
            }
        } catch (_: Throwable) {
        }
    }

    // 把 drawable 设为完全透明：给 StateListDrawable 的所有状态、LayerDrawable 的所有层都套上
    // colorFilter。只设当前状态的 colorFilter/alpha 会在状态切换后失效（滑动时 pressed 态显示原色）。
    private val transparentFilter = android.graphics.PorterDuffColorFilter(
        0, android.graphics.PorterDuff.Mode.SRC_OUT)

    private fun makeTransparent(bg: android.graphics.drawable.Drawable) {
        try {
            bg.mutate().colorFilter = transparentFilter
            when (bg) {
                is android.graphics.drawable.StateListDrawable -> {
                    for (i in 0 until bg.stateCount) {
                        bg.getStateDrawable(i)?.let { makeTransparent(it) }
                    }
                }
                is android.graphics.drawable.LayerDrawable -> {
                    for (i in 0 until bg.numberOfLayers) {
                        bg.getDrawable(i)?.let { makeTransparent(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreTransparent(bg: android.graphics.drawable.Drawable?) {
        if (bg == null) return
        try {
            bg.mutate().clearColorFilter()
            when (bg) {
                is android.graphics.drawable.StateListDrawable -> {
                    for (i in 0 until bg.stateCount) {
                        restoreTransparent(bg.getStateDrawable(i))
                    }
                }
                is android.graphics.drawable.LayerDrawable -> {
                    for (i in 0 until bg.numberOfLayers) {
                        restoreTransparent(bg.getDrawable(i))
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun findActivity(ctx: android.content.Context?): Activity? {
        var c = ctx
        var depth = 0
        while (c is android.content.ContextWrapper && depth < 10) {
            if (c is Activity) return c
            c = c.baseContext
            depth++
        }
        return null
    }

    private fun isDescendant(child: View, ancestor: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    // 联系人详情页（SubActivity / PeopleDetailActivity）的头像虚化底、滚动容器、内容容器，
    // 其背景往往是头像虚化或主题色而非中性色，通用中性底扫描清不掉；按 id 定位后强制置透明，
    // 首次记录原始背景供适配关闭时还原。
    private fun clearContactsDetailSurface(activity: Activity, name: String) {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        if (id == 0) return
        val view = activity.findViewById<View>(id) ?: return
        val bg = view.background
        if (bg == null) return
        val saved = view.getAdditionalInstanceField(CONTACTS_BG_SAVED + "_" + name)
        if (saved == null) view.setAdditionalInstanceField(CONTACTS_BG_SAVED + "_" + name, bg)
        view.background = ColorDrawable(Color.TRANSPARENT)
    }

    // 缓存联系人进程内 drawable 采样色，避免同一次扫描内对同一 ConstantState 反复创建 8x8 bitmap。
    private val contactsSampledColors = IdentityHashMap<Drawable.ConstantState, Int>()

    // 背景采样为完全不透明（alpha=255）且中性（R≈G≈B，无明显色相）：黑 / 白 / 灰。
    // ColorDrawable 直接读色；其它（GradientDrawable/StateListDrawable/LayerDrawable/9-patch）
    // 渲染 COPY 到 8x8 bitmap 采其合成色，绝不改动原 drawable。
    private fun isOpaqueNeutralSurface(bg: Drawable?): Boolean {
        if (bg == null) return false
        val color: Int
        if (bg is ColorDrawable) {
            color = bg.color
        } else {
            val state = bg.constantState
            val cached = if (state == null) null else contactsSampledColors[state]
            if (cached != null) {
                color = cached
            } else {
                try {
                    if (state == null) return false
                    val copy = state.newDrawable().mutate()
                    val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    copy.setBounds(0, 0, 8, 8)
                    copy.draw(canvas)
                    color = bmp.getPixel(4, 4)
                    bmp.recycle()
                    contactsSampledColors[state] = color
                } catch (_: Throwable) {
                    return false
                }
            }
        }
        if (Color.alpha(color) != 255) return false
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b)) <= 24
    }

    // 拨号盘键盘是点击后异步 inflate 的，Activity 生命周期回调抓不到它出现的那一刻；需要在其出现后
    // 补设 alpha / 清一次列表底。列表项随 RecyclerView 回收重绑会恢复不透明底色，必须在绘制前清除
    // 否则会闪白块；但每帧遍历整棵树又会卡。
    //
    // 双监听器配合：
    //   OnGlobalLayoutListener：布局变化时只设一个 dirty 标记（极轻量，带 16ms 节流防抖）。
    //   OnPreDrawListener：每帧只检查 dirty 标记，为 true 才真正遍历清除，并在绘制前一帧生效
    //   （无闪烁），随后重置标记——没有布局变化时每帧只做一次 boolean 判断，几乎零开销。
    private fun installContactsSurfaceRescan(activity: Activity) {
        try {
            if (activity.getAdditionalInstanceField(CONTACTS_RESCAN) == true) return
            val decor = activity.window?.decorView
            if (decor !is ViewGroup) return
            val observer = decor.viewTreeObserver
            if (!observer.isAlive) return

            val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                if (activity.isFinishing || activity.isDestroyed) return@OnGlobalLayoutListener
                val now = SystemClock.uptimeMillis()
                val last = activity.getAdditionalInstanceField(CONTACTS_ADAPT_AT) as? Long ?: 0L
                if (now - last >= 16L) {
                    activity.setAdditionalInstanceField(CONTACTS_ADAPT_AT, now)
                    activity.setAdditionalInstanceField(CONTACTS_ADAPT_DIRTY, true)
                }
            }
            val preDrawListener = ViewTreeObserver.OnPreDrawListener {
                if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true
                val dirty = activity.getAdditionalInstanceField(CONTACTS_ADAPT_DIRTY) == true
                if (dirty) {
                    activity.setAdditionalInstanceField(CONTACTS_ADAPT_DIRTY, false)
                    adaptContactsSurfaces(activity, false)
                }
                true
            }
            observer.addOnGlobalLayoutListener(globalLayoutListener)
            observer.addOnPreDrawListener(preDrawListener)
            // 用数组保存两个监听器引用，便于销毁时一并摘除。
            activity.setAdditionalInstanceField(CONTACTS_RESCAN, arrayOf(globalLayoutListener, preDrawListener))
        } catch (error: Throwable) {
            log("installContactsSurfaceRescan", error)
        }
    }

    private fun removeContactsSurfaceRescan(activity: Activity) {
        try {
            val saved = activity.getAdditionalInstanceField(CONTACTS_RESCAN)
            if (saved !is Array<*>) return
            val decor = activity.window?.decorView
            if (decor != null) {
                val observer = decor.viewTreeObserver
                if (observer.isAlive) {
                    saved.forEach {
                        when (it) {
                            is ViewTreeObserver.OnGlobalLayoutListener -> observer.removeOnGlobalLayoutListener(it)
                            is ViewTreeObserver.OnPreDrawListener -> observer.removeOnPreDrawListener(it)
                        }
                    }
                }
            }
            activity.setAdditionalInstanceField(CONTACTS_RESCAN, null)
            activity.setAdditionalInstanceField(CONTACTS_ADAPT_DIRTY, null)
        } catch (_: Throwable) {
        }
    }

    private fun resolveId(activity: Activity, name: String): Int {
        return try {
            activity.resources.getIdentifier(name, "id", activity.packageName)
        } catch (_: Throwable) {
            0
        }
    }

    fun stopContacts(activity: Activity?) {
        stopLayer(activity, CONTACTS_SESSION)
    }

    fun destroyContacts(activity: Activity?) {
        removeContacts(activity)
    }

    // 拨号盘键盘由 ViewStub 点击后异步 inflate，Activity 生命周期回调抓不到它「刚 inflate、绘制第一帧
    // 之前」的时机，只能靠布局监听在其出现后补设，但布局回调总在绘制后一帧，导致先露出原生不透明底色、
    // 再变半透（先灰后透闪烁）。这里由 Hook DialpadLayout.onFinishInflate（after）在首帧绘制前同步处理。
    //
    // DialpadLayout 实际结构（见 dialer_dialpad.xml）：
    //   DialpadLayout
    //   ├─[0] FrameLayout  dialer_background_view   bg=dialer_background_new （整块拨号盘底，9-patch）
    //   ├─[1] LinearLayout dialpad_container        bg=dialer_background_pad （键盘面板底，含数字键；不透明，盖住[0]）
    //   └─[2] FrameLayout  dialer_input_container   输入框
    // 关键：只把 dialer_background_view 设透明/隐藏并不够——dialpad_container 自己那层不透明的
    // dialer_background_pad 会把下面全挡住（这是之前自定义图“不生效”的根因）。故两层底都要处理。
    //
    //  · 默认模式：dialer_background_view 与 dialpad_container 两层底一起按 opacity 设 alpha，让背景透出，
    //    数字键（dialpad_keys_container 的子 view，各自有 alpha=1）不受容器 alpha 影响仍清晰。
    //  · 自定义模式：把用户选的独立背景（BackgroundMediaView）塞进 dialer_background_view 内铺满，
    //    并把 dialpad_container 的面板底 dialer_background_pad 换成透明占位（存原背景供还原），
    //    让自定义图透出到整个键盘区，与 contacts 整页背景叠加共存。
    // dialpadView 是 DialpadLayout 实例本身。
    fun applyDialpadOnInflate(dialpadView: View?) {
        if (dialpadView !is ViewGroup) return
        try {
            val dialpad: ViewGroup = dialpadView
            val ctx: Context = dialpad.context
            val enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true)
            val opacity = HookRuntime.preferences().getInt(BackgroundContract.CONTACTS_DIALPAD_OPACITY, 60)
            val padAlpha = opacity.coerceIn(0, 100) / 100f
            val mode = HookRuntime.preferences().getInt(
                BackgroundContract.CONTACTS_DIALPAD_BG_MODE, BackgroundContract.CONTACTS_DIALPAD_BG_DEFAULT)

            val pkg = ctx.packageName
            val bgId = ctx.resources.getIdentifier("dialer_background_view", "id", pkg)
            val containerId = ctx.resources.getIdentifier("dialpad_container", "id", pkg)
            val bgView = if (bgId == 0) null else dialpad.findViewById<View>(bgId)
            val container = if (containerId == 0) null else dialpad.findViewById<View>(containerId)
            val bgHost: ViewGroup = if (bgView is ViewGroup) bgView else dialpad

            val source = BackgroundContract.query(ctx, BackgroundContract.CONTACTS_DIALPAD)
            // 拨号盘背景仅支持图片：新选图入口已限定 image/*，此处再兜底排除历史遗留的视频配置，
            // 视频源直接回退默认模式、不在拨号盘播放。
            val custom = enabled && mode == BackgroundContract.CONTACTS_DIALPAD_BG_CUSTOM
                && source.exists && !source.isVideo()

            // 先清旧会话：拨号盘复用时避免叠加多层，并还原上次改动的面板底。
            removeDialpadMedia(dialpad)

            if (custom) {
                // 自定义图塞进 dialer_background_view 内铺满（置底、不挡数字键）；原生 9-patch 底随
                // 背景板 alpha 归零而隐去；面板底 dialer_background_pad 换透明占位让图透出。
                val media = BackgroundMediaView(ctx, source)
                // 拨号盘键盘面板不透明度滑块也作用于自定义图：与该图自身 opacity 叠乘，滑块不再失效。
                media.alpha = (if (enabled) padAlpha else 1f) * (source.opacity / 100f)
                // 给自定义背景图裁出四角圆角（30dp）：用 BackgroundMediaView 内部 dispatchDraw 自绘裁切，
                // 逐帧按当前尺寸构造路径，不受面板从底部弹出动画的影响。
                val density = ctx.resources.displayMetrics.density
                media.setTopCornerRadius(30f * density)
                bgHost.addView(media, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                dialpad.setAdditionalInstanceField(DIALPAD_SESSION, media)
                // 先存原生 9-patch 底（首次），再换透明，让自定义图透出且切回默认能还原原底。
                if (bgView != null) {
                    val orig = bgView.background
                    if (orig != null && bgView.getAdditionalInstanceField(DIALPAD_BGVIEW_SAVED) == null) {
                        bgView.setAdditionalInstanceField(DIALPAD_BGVIEW_SAVED, orig)
                    }
                    bgView.alpha = 1f
                    bgView.background = ColorDrawable(Color.TRANSPARENT)
                }
                clearDialpadPanelBackground(container)
            } else {
                // 默认模式（beta5 已验证有效的做法）：不透明度直接对拨号盘背景板 dialer_background_view
                // 本身 setAlpha —— 保留它原生的 9-patch 底作为 setAlpha 的作用对象（清除遍历已跳过它及
                // 其子树，见 adaptContactsSurfaces 的 skip，故其底不会被通用中性底清成透明而使滑块失效）。
                // 先撤销上次自定义模式对 bgView / container 的改动，再对 bgView 施加透明度。
                val a = if (enabled) padAlpha else 1f
                restoreDialpadPanelBackground(container)
                container?.alpha = 1f
                if (bgView != null) {
                    restoreDialpadBgView(bgView) // 若曾在自定义模式换成透明底，先还原原生 9-patch 底
                    bgView.alpha = a
                }
            }
        } catch (error: Throwable) {
            log("applyDialpadOnInflate", error)
        }
    }

    // 把自定义模式下换成透明的 dialer_background_view 原生 9-patch 底还原回去（若曾保存）。
    private fun restoreDialpadBgView(bgView: View?) {
        if (bgView == null) return
        try {
            val saved = bgView.getAdditionalInstanceField(DIALPAD_BGVIEW_SAVED)
            if (saved is Drawable) {
                bgView.background = saved
                bgView.removeAdditionalInstanceField(DIALPAD_BGVIEW_SAVED)
            }
        } catch (_: Throwable) {
        }
    }

    // 自定义模式下把 dialpad_container 的不透明面板底换成透明占位（首次记录原背景供还原）。
    private fun clearDialpadPanelBackground(container: View?) {
        if (container == null) return
        try {
            val bg = container.background
            if (bg != null) {
                val saved = container.getAdditionalInstanceField(CONTACTS_BG_SAVED)
                if (saved == null) container.setAdditionalInstanceField(CONTACTS_BG_SAVED, bg)
                container.background = ColorDrawable(Color.TRANSPARENT)
            }
            container.alpha = 1f
        } catch (_: Throwable) {
        }
    }

    private fun restoreDialpadPanelBackground(container: View?) {
        if (container == null) return
        try {
            // 先撤销默认模式插入的置底面板背景 view：移除它并把面板底还给 container 自身。
            val panel = container.getAdditionalInstanceField(DIALPAD_PANEL_ALPHA_VIEW)
            if (panel is View) {
                val parent = panel.parent
                if (parent is ViewGroup) parent.removeView(panel)
                container.removeAdditionalInstanceField(DIALPAD_PANEL_ALPHA_VIEW)
            }
            val saved = container.getAdditionalInstanceField(CONTACTS_BG_SAVED)
            if (saved is Drawable) {
                container.background = saved
                container.removeAdditionalInstanceField(CONTACTS_BG_SAVED)
            }
        } catch (_: Throwable) {
        }
    }

    // 移除拨号盘上已叠加的自定义背景层（若有）。原生底 / 面板底的复位由调用方按当前模式重设。
    private fun removeDialpadMedia(dialpad: ViewGroup) {
        try {
            val old = dialpad.getAdditionalInstanceField(DIALPAD_SESSION)
            if (old is BackgroundMediaView) {
                val parent = old.parent
                if (parent is ViewGroup) parent.removeView(old)
                old.dispose()
            }
            dialpad.removeAdditionalInstanceField(DIALPAD_SESSION)
        } catch (_: Throwable) {
        }
    }

    private fun removeContacts(activity: Activity?) {
        if (activity == null) return
        try {
            removeContactsSurfaceRescan(activity)
            val old = activity.getAdditionalInstanceField(CONTACTS_SESSION) as? LayerSession
            if (old != null) removeLayer(activity, CONTACTS_SESSION, old)
        } catch (error: Throwable) {
            log("removeContacts", error)
        }
    }

    fun enterDevice(activity: Activity?) {
        if (activity == null) return
        activity.setAdditionalInstanceField(DEVICE_ACTIVE, true)
        removeGlobal(activity)
    }

    fun leaveDevice(activity: Activity?) {
        if (activity == null) return
        activity.removeAdditionalInstanceField(DEVICE_ACTIVE)
        try {
            val decor = activity.window?.decorView
            if (decor != null && !activity.isFinishing) decor.post { applyGlobal(activity) }
        } catch (_: Throwable) {
        }
    }

    private fun shouldSkipGlobal(activity: Activity): Boolean {
        val packageName = activity.packageName
        val className = activity.javaClass.name

        // Keep permission / authorization / transient confirmation windows fully native.
        if (isSensitiveTransientActivity(className) || isSensitiveTransientWindow(activity)) return true

        if (BackgroundContract.PACKAGE_SETTINGS == packageName) {
            if ("com.android.settings.MiuiSettings" == className) return true
            return activity.getAdditionalInstanceField(DEVICE_ACTIVE) == true
        }

        // Only known full-screen settings surfaces are accepted in cross-package processes.
        // Pairing, login, permission, payment and other transient/sensitive windows stay native.
        if (BackgroundContract.PACKAGE_MILINK == packageName) {
            return !(className.contains(".ui.connectivitysettings.")
                || className == "com.milink.ui.setting.SettingActivity"
                || className.endsWith(".NetWorkingActivity"))
        }

        if (BackgroundContract.PACKAGE_PHONE == packageName) {
            return !matchesPhoneSettings(className)
        }

        if (BackgroundContract.PACKAGE_ACCOUNT == packageName) {
            return !matchesAccountSettings(className)
        }

        if (BackgroundContract.PACKAGE_THEME_MANAGER == packageName) {
            return !matchesThemeSettings(className)
        }

        if (BackgroundContract.PACKAGE_HOME == packageName) {
            return !matchesHomeSettings(className)
        }

        if (BackgroundContract.PACKAGE_SECURITY_CENTER == packageName) {
            return !matchesSecurityCenterSettings(className)
        }

        if (BackgroundContract.PACKAGE_POWER_KEEPER == packageName) {
            // PowerKeeper is scoped only after the full-screen/transient-window checks above.
            return false
        }

        if (BackgroundContract.PACKAGE_MI_SETTINGS == packageName) {
            return !matchesMiSettings(className)
        }

        // 通讯录与拨号由独立的 contacts 通道处理，global 一律跳过。
        if (BackgroundContract.PACKAGE_CONTACTS == packageName) {
            return true
        }

        return true
    }

    private fun isSensitiveTransientWindow(activity: Activity?): Boolean {
        if (activity == null) return false
        var a: TypedArray? = null
        try {
            val attrs = intArrayOf(android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating)
            a = activity.obtainStyledAttributes(attrs)
            if (a.getBoolean(0, false) || a.getBoolean(1, false)) return true
        } catch (_: Throwable) {
        } finally {
            try {
                a?.recycle()
            } catch (_: Throwable) {
            }
        }
        try {
            val w: Window? = activity.window
            if (w != null) {
                val lp = w.attributes
                if (lp != null && (lp.width != WindowManager.LayoutParams.MATCH_PARENT
                        || lp.height != WindowManager.LayoutParams.MATCH_PARENT)
                    && lp.width > 0 && lp.height > 0
                ) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun isSensitiveTransientActivity(className: String?): Boolean {
        if (className == null) return false
        val n = className.lowercase()
        return n.contains("permissionactivity")
            || n.contains("requirepermission")
            || n.contains("permissiondialog")
            || n.contains("authorization")
            || n.contains("authorize")
            || n.contains("accesscheckactivity")
            || n.contains("confirmcredential")
            || n.contains("credential")
            || n.contains("password")
            || n.contains("pinactivity")
            || n.contains("payment")
            || n.contains("wallet")
            || n.contains("login")
            || n.contains("signin")
            || n.contains("oauth")
            || n.contains("passport")
            || n.contains("emergency")
            || n.contains("dialer")
            || n.contains("incall")
            || n.contains("confirmdialog")
            || n.contains("grant")
            || n.endsWith("ctaactivity")
            || n.contains("transparentactivity")
            || n.contains("dialogactivity")
    }

    private fun matchesPhoneSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.startsWith("com.android.phone.settings.")
            || n.contains("setting")
            || n.contains("calloptions")
            || n.contains("callbarringoptions")
            || n.contains("callfeaturessetting")
            || n.contains("callforwardtype")
            || n.contains("callforwardoptions")
            || n.contains("additionalcalloptions")
            || n.endsWith("fivegnrcasettingactivity")
            || n.endsWith("nrdisplayactivity")
    }

    private fun matchesAccountSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.contains(".settings.")
            || n.contains("accountsettings")
            || n.contains("accountsecurity")
            || n.contains("agreementandprivacy")
            || n.contains("systemadactivity")
            || n.contains("userdetailinfo")
            || n.contains("userphoneinfo")
            || n.contains("devicesettinglist")
            || n.contains("devicedetailinfo")
            || n.contains("snslistactivity")
            || n.contains("snsaccountactivity")
    }

    private fun matchesThemeSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.contains(".settings.")
            || n.contains("themesettings")
            || n.contains("themepreference")
            || n.contains("themeabout")
            || n.endsWith(".activity.themetabactivity")
            || n.contains("themeandwallpaper")
            || n.contains("wallpapersettings")
            || n.contains("wallpapersubsetting")
            || n.contains("wallpapertabactivity")
            || n.contains("wallpapermiuitab")
            || n.contains("privacysettings")
            || n.contains("authoritymanagement")
            || n.contains("supportthemeactivity")
            || n.contains("personalize")
            || n.contains("aifromsettings")
    }

    private fun matchesHomeSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.contains(".settings.")
            || n.contains("settingsactivity")
            || n.contains("homesettings")
            || n.contains("launchersettings")
    }

    private fun matchesSecurityCenterSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.contains(".settings.")
            || n.contains("setting")
            || n.contains("power")
            || n.contains("battery")
            || n.contains("autostart")
            || n.contains("appmanager")
            || n.contains("privacy")
            || n.contains("permission")
            || n.contains("networkassistant")
            || n.contains("garbage")
        // 优化加速（optimiz）有内存圆环、应用列表等自定义视觉，不注入全局背景。
    }

    private fun matchesMiSettings(className: String?): Boolean {
        val n = className?.lowercase() ?: ""
        return n.contains("healthy")
            || n.contains("usagestat")
            || n.contains("focusmode")
            || n.contains("devicelimit")
            || n.contains("appusage")
            || n.contains("screen")
            || n.contains("settings")
    }

    // 通讯录主界面（PeopleActivity）、联系人详情页（SubActivity 承载 ContactDetailAtyFragment /
    // PeopleDetailAtyFragment）以及 PeopleDetailActivity 共用同一 contacts 背景通道；
    // 来电、快速联系卡、权限弹窗等其它页面不在此列。
    private fun matchesContactsSettings(className: String?): Boolean {
        if (className == null) return false
        return className == "com.android.contacts.activities.PeopleActivity" ||
            className == "com.android.contacts.activities.SubActivity" ||
            className == "com.android.contacts.activities.PeopleDetailActivity"
    }

    private fun removeGlobal(activity: Activity?) {
        if (activity == null) return
        try {
            val old = activity.getAdditionalInstanceField(GLOBAL_SESSION) as? LayerSession
            if (old != null) removeLayer(activity, GLOBAL_SESSION, old)
        } catch (error: Throwable) {
            log("removeGlobal", error)
        }
    }

    private fun applyLayer(activity: Activity, slot: String, fieldKey: String, home: Boolean) {
        try {
            val source = BackgroundContract.query(activity, slot)
            val old = activity.getAdditionalInstanceField(fieldKey) as? LayerSession
            if (!source.exists) {
                if (BackgroundContract.GLOBAL == slot) {
                    diagnostic(activity, "source-missing remote-file=" + BackgroundContract.remoteMediaName(slot))
                }
                if (old != null) removeLayer(activity, fieldKey, old)
                return
            }

            val contentView = activity.findViewById<View>(android.R.id.content)
            if (contentView !is ViewGroup) return
            val content: ViewGroup = contentView
            val host = selectLayerHost(activity, content, home)
            val transparentTopBar = host !== content

            // Keep the media in android.R.id.content. This is the path already verified on
            // device interconnection. Miuix secondary pages are the one deliberate exception:
            // their expanded action bar is a sibling of android.R.id.content, so the media must
            // sit one level higher in the known ActionBarOverlayLayout to continue behind the
            // status bar, back button and large title. We never promote to DecorView.
            if (old != null && old.media.sourceKey() == source.cacheKey()
                && old.media.parent === host && old.observedRoot === host
            ) {
                old.media.onHostResume()
                old.refresh(activity, home)
                return
            }
            if (old != null) removeLayer(activity, fieldKey, old)
            val originalRoot = if (content.childCount > 0) content.getChildAt(0) else null
            val media = BackgroundMediaView(activity, source)
            val mediaParams: ViewGroup.LayoutParams = if (host is FrameLayout)
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            else
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            val session = LayerSession(media)
            if (host !== content) session.clear(host)
            session.clear(content)
            if (originalRoot != null) session.clear(originalRoot)
            clearNamed(activity, session, "nestedheaderlayout")
            clearNamed(activity, session, "nested_header_layout")
            clearNamed(activity, session, "scroll_headers")
            clearNamed(activity, session, "main_content")
            if (!home) {
                clearNamed(activity, session, "prefs_container")
                clearNamed(activity, session, "preference_recyclerview")
                clearNamed(activity, session, "recycler_view")
                clearNamed(activity, session, "content")
                clearNamed(activity, session, "content_view")
                clearNamed(activity, session, "content_wrapper")
                clearNamed(activity, session, "action_bar_activity_content")
                clearNamed(activity, session, "area_content")
                clearNamed(activity, session, "auto_content")
                // 联系人详情页（PeopleDetailActivity）专用容器：头像模糊底 / 滚动容器 / 内容容器，
                // 其背景可能是头像虚化或主题色而非中性色，通用中性底扫描清不掉，这里强制透明。
                clearNamed(activity, session, "container_layout")
                clearNamed(activity, session, "zoom_scrollview")
                clearNamed(activity, session, "content_container")
            }
            host.addView(media, 0, mediaParams)
            session.attach(activity, host, home, transparentTopBar)
            activity.setAdditionalInstanceField(fieldKey, session)
            if (BackgroundContract.GLOBAL == slot) {
                diagnostic(activity, "applied host=" + host.javaClass.name
                    + " root=" + (if (originalRoot == null) "none" else originalRoot.javaClass.name)
                    + " topBar=" + (if (transparentTopBar) "transparent" else "content-only")
                    + " activity=" + activity.javaClass.name)
            }
        } catch (error: Throwable) {
            log("applyLayer/$slot", error)
        }
    }

    private fun selectLayerHost(activity: Activity?, content: ViewGroup, home: Boolean): ViewGroup {
        // MobileNetworkSettings uses MIUIX ActionBarOverlayLayout for page animation.
        // Keep the custom background outside that animated container so it does not
        // slide together with the page and create a ghost/double-background frame.
        if (activity != null
            && "com.android.phone" == activity.packageName
            && "com.android.phone.settings.MobileNetworkSettings" == activity.javaClass.name
        ) {
            try {
                val windowContent = activity.findViewById<View>(android.R.id.content)
                if (windowContent is ViewGroup) {
                    val windowHost: ViewGroup = windowContent
                    val parent: ViewParent? = windowHost.parent

                    if (parent is ViewGroup
                        && parent.javaClass.name.contains("ActionBarOverlayLayout")
                    ) {
                        return parent
                    }
                    return windowHost
                }
            } catch (error: Throwable) {
                log("selectLayerHost/MobileNetworkSettings", error)
            }
        }

        if (home || activity == null) return content
        try {
            val parent = content.parent
            if (parent is ViewGroup && isMiuixActionBarHost(activity, parent)) {
                return parent
            }

            val id = activity.resources.getIdentifier(
                "action_bar_overlay_layout", "id", activity.packageName)
            val candidate = if (id == 0) null else activity.findViewById<View>(id)
            if (candidate is ViewGroup
                && candidate !== activity.window?.decorView
                && isAncestor(candidate, content)
                && isMiuixActionBarHost(activity, candidate)
            ) {
                return candidate
            }
        } catch (_: Throwable) {
        }
        return content
    }

    private fun isMiuixActionBarHost(activity: Activity?, view: ViewGroup?): Boolean {
        if (view == null) return false
        val cls = view.javaClass.name.lowercase()
        val idName = resourceEntryName(activity, view)
        return cls.contains("miuix.appcompat.internal.app.widget.actionbaroverlaylayout")
            || cls.contains("miuix.appcompat.internal.app.widget.actionbarmovablelayout")
            || "action_bar_overlay_layout" == idName
    }

    private fun isAncestor(ancestor: ViewGroup, child: View?): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) return true
            val parent: ViewParent? = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }

    private fun resourceEntryName(activity: Activity?, view: View?): String {
        if (activity == null || view == null || view.id == View.NO_ID || view.id == 0) return ""
        return try {
            activity.resources.getResourceEntryName(view.id).lowercase()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun diagnostic(activity: Activity, message: String) {
        try {
            val previous = activity.getAdditionalInstanceField(GLOBAL_DIAGNOSTIC)
            if (message == previous) return
            activity.setAdditionalInstanceField(GLOBAL_DIAGNOSTIC, message)
            log("[HyperBackground] " + activity.packageName + " " + message)
            BackgroundContract.reportDiagnostic(activity, message)
        } catch (_: Throwable) {
        }
    }

    private fun stopLayer(activity: Activity?, fieldKey: String) {
        if (activity == null) return
        try {
            val s = activity.getAdditionalInstanceField(fieldKey) as? LayerSession
            if (s != null) s.media.onHostStop()
        } catch (error: Throwable) {
            log("stopLayer", error)
        }
    }

    private fun removeLayer(activity: Activity, fieldKey: String, session: LayerSession) {
        activity.removeAdditionalInstanceField(fieldKey)
        val parent = session.media.parent as? ViewGroup
        if (parent != null) parent.removeView(session.media)
        session.media.dispose()
        session.detach()
        session.restore()
    }

    fun shouldSuppressDeviceShader(fragment: Any?): Boolean {
        return try {
            val context = contextFromFragment(fragment)
            context != null && BackgroundContract.query(context, BackgroundContract.DEVICE).exists
        } catch (error: Throwable) {
            log("shouldSuppressDeviceShader", error)
            false
        }
    }

    fun applyDevice(fragment: Any?) {
        if (fragment == null) return
        try {
            val context = contextFromFragment(fragment) ?: return
            val activity = activityFromFragment(fragment)
            val source = BackgroundContract.query(context, BackgroundContract.DEVICE)
            val old = fragment.getAdditionalInstanceField(DEVICE_SESSION) as? DeviceSession
            if (!source.exists) {
                if (old != null) removeDevice(fragment, old, true)
                if (activity != null) applyFontMode(activity)
                return
            }
            if (old != null && old.media.sourceKey() == source.cacheKey()) {
                stopOriginalShader(fragment, old.backgroundView)
                old.media.onHostResume()
                if (activity != null) applyFontMode(activity)
                return
            }
            val rememberedVisibility = old?.originalVisibility ?: Int.MIN_VALUE
            val field = fragment.getObjectField("mBgEffectView")
            if (field !is View) return
            val backgroundView: View = field
            if (backgroundView.parent !is ViewGroup) return
            val parent = backgroundView.parent as ViewGroup
            val media = BackgroundMediaView(context, source)
            if (old != null) removeDevice(fragment, old, false)
            stopOriginalShader(fragment, backgroundView)
            var index = parent.indexOfChild(backgroundView)
            if (index < 0) index = 0
            try {
                parent.addView(media, index + 1, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } catch (e: Throwable) {
                media.dispose()
                backgroundView.visibility =
                    if (rememberedVisibility != Int.MIN_VALUE) rememberedVisibility else View.VISIBLE
                throw e
            }
            val session = DeviceSession(
                backgroundView,
                if (rememberedVisibility != Int.MIN_VALUE) rememberedVisibility else backgroundView.visibility,
                media)
            backgroundView.visibility = View.INVISIBLE
            fragment.setAdditionalInstanceField(DEVICE_SESSION, session)
            if (activity != null) applyFontMode(activity)
        } catch (error: Throwable) {
            log("applyDevice", error)
        }
    }

    fun stopDevice(fragment: Any?) {
        if (fragment == null) return
        try {
            val s = fragment.getAdditionalInstanceField(DEVICE_SESSION) as? DeviceSession
            if (s != null) s.media.onHostStop()
        } catch (error: Throwable) {
            log("stopDevice", error)
        }
    }

    fun destroyDevice(fragment: Any?) {
        if (fragment == null) return
        try {
            val session = fragment.removeAdditionalInstanceField(DEVICE_SESSION) as? DeviceSession
            if (session != null) removeDeviceView(session, false)
        } catch (error: Throwable) {
            log("destroyDevice", error)
        }
    }

    fun applyFontMode(activity: Activity?) {
        if (activity == null) return
        try {
            val s = BackgroundContract.query(activity, BackgroundContract.HOME)
            val root = activity.findViewById<View>(android.R.id.content) ?: return
            applyTextRecursive(root, s.fontMode)
        } catch (error: Throwable) {
            log("applyFontMode", error)
        }
    }

    private fun applyTextRecursive(view: View?, mode: Int) {
        if (view is TextView) {
            val saved = view.getAdditionalInstanceField(ORIGINAL_TEXT_COLOR)
            if (mode == BackgroundContract.FONT_FOLLOW) {
                if (saved is Int) {
                    view.setTextColor(saved)
                    view.removeAdditionalInstanceField(ORIGINAL_TEXT_COLOR)
                }
            } else {
                if (saved !is Int) view.setAdditionalInstanceField(ORIGINAL_TEXT_COLOR, view.currentTextColor)
                TextColorOverride.apply(view, mode)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTextRecursive(view.getChildAt(i), mode)
        }
    }

    private fun removeDevice(fragment: Any, session: DeviceSession, restoreBackground: Boolean) {
        fragment.removeAdditionalInstanceField(DEVICE_SESSION)
        removeDeviceView(session, restoreBackground)
    }

    private fun removeDeviceView(session: DeviceSession, restoreBackground: Boolean) {
        val parent = session.media.parent as? ViewGroup
        if (parent != null) parent.removeView(session.media)
        session.media.dispose()
        if (restoreBackground) session.backgroundView.visibility = session.originalVisibility
    }

    private fun stopOriginalShader(fragment: Any?, backgroundView: View) {
        try {
            val controller = fragment?.getObjectField("mBgEffectController")
            if (controller != null) controller.callMethod("stop")
        } catch (_: Throwable) {
        }
        try {
            backgroundView.setRenderEffect(null)
        } catch (_: Throwable) {
        }
    }

    private fun contextFromFragment(fragment: Any?): Context? {
        val context = fragment?.callMethod("getContext")
        if (context is Context) return context
        val activity = fragment?.callMethod("getActivity")
        return if (activity is Context) activity else null
    }

    private fun activityFromFragment(fragment: Any?): Activity? {
        return try {
            fragment?.callMethod("getActivity") as? Activity
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearNamed(activity: Activity, session: LayerSession, name: String) {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        if (id == 0) return
        val view = activity.findViewById<View>(id) ?: return
        session.clear(view)
    }

    private fun log(stage: String, error: Throwable) {
        log("[HyperBackground] " + stage + " failed: " + error)
        log(error)
    }

    private class LayerSession(val media: BackgroundMediaView) {
        // Some external-settings pages (security center 应用设置/隐私与安全) build their top/stat
        // cards asynchronously (permission usage is loaded after the first frame), so a single
        // clear at attach/refresh time runs before those opaque neutral panels exist or are
        // measured, leaving black/white blocks until the next onResume. Keep re-clearing on
        // every layout pass for a short budget after the page appears so late-inflated panels
        // are caught without a manual re-entry, then detach the observer to avoid overhead.
        private companion object {
            const val RESCAN_WINDOW_MS = 2500L
        }

        private val clearedViews = ArrayList<View>()
        private val originalBackgrounds = ArrayList<Drawable>()
        private val actionBarSurfaces = ArrayList<ActionBarSurface>()
        var observedRoot: ViewGroup? = null
            private set
        private var homeMode = false
        private var transparentTopBar = false
        private var statusBarWindow: Window? = null
        private var originalStatusBarColor = 0
        private var statusBarColorSaved = false
        private var observedActivity: Activity? = null
        private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        private var rescanDeadline = 0L
        private var lastRescanAt = 0L
        // 缓存 drawable 采样色，避免同一次扫描内对同一 ConstantState 反复创建 bitmap 采样。
        // 用 ConstantState 做 key：共享状态的 drawable 复用采样结果，ColorDrawable 已直接取色不走缓存。
        private val sampledColors = IdentityHashMap<Drawable.ConstantState, Int>()

        fun clear(view: View?) {
            if (view == null || view === media) return
            if (!clearedViews.contains(view)) {
                clearedViews.add(view)
                originalBackgrounds.add(view.background)
            }
            if (view.background != null) view.background = null
        }

        fun attach(activity: Activity, root: ViewGroup, home: Boolean, transparentTopBar: Boolean) {
            observedRoot = root
            observedActivity = activity
            homeMode = home
            this.transparentTopBar = transparentTopBar
            if (transparentTopBar) prepareTransparentStatusBar(activity)
            refresh(activity, home)
        }

        fun refresh(activity: Activity, home: Boolean) {
            val root = observedRoot
            if (home || root == null) return
            sampledColors.clear()
            clearPageSurfaces(activity, root, root, 0)
            if (transparentTopBar) clearActionBarSurfaces(activity, root, 0)
            // Reopen the rescan window on every refresh (e.g. returning from a sub-page) so a
            // page re-entered after its cards were recycled is cleaned up again automatically.
            observedActivity = activity
            rescanDeadline = SystemClock.uptimeMillis() + RESCAN_WINDOW_MS
            if (layoutListener == null) installLayoutRescan(activity, root)
        }

        // Watch layout passes on the observed root: opaque neutral panels created after the
        // first frame (async permission stats etc.) trigger a fresh clear. The listener is
        // self-limiting — it detaches once the rescan window elapses so long-lived pages do
        // not pay for a global-layout callback forever.
        private fun installLayoutRescan(activity: Activity, root: ViewGroup?) {
            if (root == null) return
            try {
                val observer = root.viewTreeObserver
                if (!observer.isAlive) return
                rescanDeadline = SystemClock.uptimeMillis() + RESCAN_WINDOW_MS
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    val observed = observedRoot
                    val observedAct = observedActivity
                    if (observed == null || observedAct == null) {
                        removeLayoutRescan()
                        return@OnGlobalLayoutListener
                    }
                    if (observedAct.isFinishing || observedAct.isDestroyed) {
                        removeLayoutRescan()
                        return@OnGlobalLayoutListener
                    }
                    // 200ms 节流：页面加载时会触发多次 layout pass，每次都全树遍历开销很大。
                    // 合并高频回调，只在节流窗口到期时执行一次补扫。
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRescanAt < 200L) return@OnGlobalLayoutListener
                    lastRescanAt = now
                    clearPageSurfaces(observedAct, observed, observed, 0)
                    if (transparentTopBar) clearActionBarSurfaces(observedAct, observed, 0)
                    if (now > rescanDeadline) removeLayoutRescan()
                }
                layoutListener = listener
                observer.addOnGlobalLayoutListener(listener)
            } catch (_: Throwable) {
            }
        }

        private fun removeLayoutRescan() {
            val listener = layoutListener ?: return
            try {
                val root = observedRoot
                if (root != null) {
                    val observer = root.viewTreeObserver
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
                }
            } catch (_: Throwable) {
            }
            layoutListener = null
        }

        fun detach() {
            removeLayoutRescan()
            observedRoot = null
            observedActivity = null
        }

        fun restore() {
            for (i in clearedViews.indices) {
                try {
                    clearedViews[i].background = originalBackgrounds[i]
                } catch (_: Throwable) {
                }
            }
            clearedViews.clear()
            originalBackgrounds.clear()
            for (state in actionBarSurfaces) {
                try {
                    state.view.callMethod("setPrimaryBackground", state.primaryBackground)
                } catch (_: Throwable) {
                }
            }
            actionBarSurfaces.clear()
            val window = statusBarWindow
            if (statusBarColorSaved && window != null) {
                try {
                    window.statusBarColor = originalStatusBarColor
                } catch (_: Throwable) {
                }
            }
            statusBarWindow = null
            statusBarColorSaved = false
        }

        private fun prepareTransparentStatusBar(activity: Activity) {
            try {
                val window: Window = activity.window ?: return
                if (!statusBarColorSaved) {
                    statusBarWindow = window
                    originalStatusBarColor = window.statusBarColor
                    statusBarColorSaved = true
                }
                window.statusBarColor = Color.TRANSPARENT
            } catch (_: Throwable) {
            }
        }

        private fun clearActionBarSurfaces(activity: Activity, view: View?, depth: Int) {
            if (view == null || view === media || depth > 8) return
            val idName = BackgroundApplier.resourceEntryName(activity, view)
            val cls = view.javaClass.name.lowercase()

            val actionBarView = containsAny(
                idName,
                "action_bar_overlay_layout", "action_bar_container", "action_bar", "app_bar",
                "collapsing_toolbar", "support_action_bar")
                || cls.contains("actionbaroverlaylayout")
                || cls.contains("actionbarmovablelayout")
                || cls.contains("actionbarcontainer")
                || cls.contains("appbarlayout")
                || cls.contains("collapsingtoolbarlayout")
            if (actionBarView) {
                clear(view)
                clearMiuixPrimaryBackground(view, cls)
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    clearActionBarSurfaces(activity, view.getChildAt(i), depth + 1)
                }
            }
        }

        private fun clearMiuixPrimaryBackground(view: View, className: String) {
            if (!className.contains("actionbarcontainer")) return
            var saved: ActionBarSurface? = null
            for (state in actionBarSurfaces) {
                if (state.view === view) {
                    saved = state
                    break
                }
            }
            try {
                val current = view.callMethod("getPrimaryBackground")
                if (saved == null) {
                    saved = ActionBarSurface(view, current as? Drawable)
                    actionBarSurfaces.add(saved)
                }
                if (current != null) view.callMethod("setPrimaryBackground", null)
            } catch (_: Throwable) {
            }
        }

        private fun clearPageSurfaces(activity: Activity, view: View?, root: View, depth: Int) {
            if (view == null || view === media) return
            if (view.visibility != View.VISIBLE) return
            if (isPageSurface(activity, view, root, depth)) clear(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    clearPageSurfaces(activity, view.getChildAt(i), root, depth + 1)
                }
            }
        }

        private fun isPageSurface(activity: Activity, view: View, root: View, depth: Int): Boolean {
            if (view === root) return true
            val bg = view.background ?: return false

            val rootWidth = maxOf(root.width, activity.resources.displayMetrics.widthPixels)
            val rootHeight = maxOf(root.height, activity.resources.displayMetrics.heightPixels)
            val width = view.width
            val height = view.height
            val large = width >= (rootWidth * 0.72f).toInt() && height >= (rootHeight * 0.32f).toInt()

            val idName = resourceEntryName(activity, view.id)
            val pkg = activity.packageName

            // Device interconnection uses a full-width opaque host surface around the
            // actual cards. Clear only that page-level host; cards keep horizontal margins.
            if ("com.milink.service" == pkg
                && view is ViewGroup
                && width >= (rootWidth * 0.965f).toInt()
                && height >= (rootHeight * 0.05f).toInt()
                && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")
            ) {
                return true
            }

            // The supplied Phone/Account/Theme builds split a Miuix page into several
            // full-width host panels instead of one full-height root. Clear those host
            // panels while retaining inset cards and controls.
            val externalSettingsPage = BackgroundContract.PACKAGE_PHONE == pkg
                || BackgroundContract.PACKAGE_ACCOUNT == pkg
                || BackgroundContract.PACKAGE_THEME_MANAGER == pkg
                || BackgroundContract.PACKAGE_SECURITY_CENTER == pkg
                || BackgroundContract.PACKAGE_POWER_KEEPER == pkg
                || BackgroundContract.PACKAGE_MI_SETTINGS == pkg
            if (externalSettingsPage
                && view is ViewGroup
                && depth <= 8
                && width >= (rootWidth * 0.94f).toInt()
                && height >= (rootHeight * 0.15f).toInt()
                && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")
            ) {
                return true
            }

            // Some external-settings pages (security center 应用设置/隐私与安全) place opaque
            // neutral ColorDrawable panels around their cards (e.g. top_container/top_view,
            // the stat-card ConstraintLayout). In dark mode they read as black blocks and in
            // light mode as white blocks over the wallpaper. Clear only fully-opaque neutral
            // solid colors (black/white/grey); semi-transparent card surfaces (e.g. #24FFFFFF
            // GradientDrawable/CardDrawable) and coloured controls are intentionally kept.
            if (externalSettingsPage
                && width >= (rootWidth * 0.5f).toInt()
                && height >= (rootHeight * 0.05f).toInt()
                && isOpaqueNeutralColorDrawable(bg)
            ) {
                return true
            }

            if (!large) return false

            val cls = view.javaClass.name.lowercase()

            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false
            if (containsAny(cls, "cardview", "button", "switch", "checkbox", "imageview")) return false

            if (containsAny(idName,
                    "content", "container", "recycler", "list", "prefs", "preference",
                    "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true
            if (containsAny(cls,
                    "recyclerview", "nestedscrollview", "scrollview", "listview",
                    "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true

            // HyperOS/MIUIX preference pages often use anonymous FrameLayout/LinearLayout
            // wrappers with a full-page theme surface. Restrict this fallback to very
            // large containers so normal preference cards keep their native backgrounds.
            return view is ViewGroup
                && width >= (rootWidth * 0.90f).toInt()
                && height >= (rootHeight * 0.62f).toInt()
        }

        private fun resourceEntryName(activity: Activity, id: Int): String {
            if (id == View.NO_ID || id == 0) return ""
            return try {
                activity.resources.getResourceEntryName(id).lowercase()
            } catch (_: Throwable) {
                ""
            }
        }

        private fun containsAny(value: String?, vararg needles: String): Boolean {
            if (value.isNullOrEmpty()) return false
            for (needle in needles) if (value.contains(needle)) return true
            return false
        }

        // True only for a fully-opaque neutral (black/white/grey) solid background.
        // Handles ColorDrawable directly; for any other drawable (LayerDrawable,
        // GradientDrawable, etc.) it samples a *copy* rendered to a 1x1 bitmap so we read
        // the real composited colour without mutating the shared drawable (beta5 broke the
        // grey cards by calling setBounds on the live instance — this copies first).
        // Semi-transparent surfaces (e.g. #24FFFFFF cards) sample with alpha < 255 and are
        // rejected; coloured panels are non-neutral and rejected.
        private fun isOpaqueNeutralColorDrawable(bg: Drawable?): Boolean {
            if (bg == null) return false
            if (bg is ColorDrawable) {
                return isOpaqueNeutral(bg.color)
            }
            // 非 ColorDrawable 需渲染采样：按 ConstantState 缓存，同一扫描内不重复创建 bitmap。
            val state = bg.constantState
            val cached = if (state == null) null else sampledColors[state]
            val color: Int
            if (cached != null) {
                color = cached
            } else {
                val sampled = sampleDrawableColor(bg) ?: return false
                color = sampled
                if (state != null) sampledColors[state] = color
            }
            return isOpaqueNeutral(color)
        }

        // Renders a COPY of the drawable to a 1x1 bitmap and reads the pixel. Never touches
        // the original drawable (no setBounds/draw on the live instance).
        private fun sampleDrawableColor(bg: Drawable): Int? {
            return try {
                val state = bg.constantState ?: return null
                val copy = state.newDrawable().mutate()
                val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                copy.setBounds(0, 0, 1, 1)
                copy.draw(canvas)
                val color = bmp.getPixel(0, 0)
                bmp.recycle()
                color
            } catch (_: Throwable) {
                null
            }
        }

        // Fully opaque and neutral (channels close together, no dominant hue): black/white/grey.
        private fun isOpaqueNeutral(color: Int): Boolean {
            if (Color.alpha(color) != 255) return false
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val max = maxOf(r, maxOf(g, b))
            val min = minOf(r, minOf(g, b))
            return max - min <= 24
        }
    }

    private class ActionBarSurface(val view: View, val primaryBackground: Drawable?)

    private class DeviceSession(
        val backgroundView: View,
        val originalVisibility: Int,
        val media: BackgroundMediaView,
    )
}
