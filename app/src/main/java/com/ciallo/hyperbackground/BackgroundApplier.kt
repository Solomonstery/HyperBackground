package com.ciallo.hyperbackground

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
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
import android.widget.ImageView
import android.widget.TextView
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_GLOBAL_WALLPAPER
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_LAYOUT_CLEANUP
import com.ciallo.hyperbackground.appearance.SETTINGS_APPEARANCE_PREFERENCES
import com.ciallo.hyperbackground.dialpad.DialpadBackgroundController
import com.ciallo.hyperbackground.dialpad.DialpadBackdropView
import com.ciallo.hyperbackground.util.callMethod
import com.ciallo.hyperbackground.util.getAdditionalInstanceField
import com.ciallo.hyperbackground.util.getObjectField
import com.ciallo.hyperbackground.util.removeAdditionalInstanceField
import com.ciallo.hyperbackground.util.setAdditionalInstanceField
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
    private val MMS_SESSION = FIELD_PREFIX + "mms.session"
    private val MMS_CHAT_SESSION = FIELD_PREFIX + "mms.chat.session"
    private val CONTACTS_RESCAN = FIELD_PREFIX + "contacts.rescan"
    private val CONTACTS_ADAPT_AT = FIELD_PREFIX + "contacts.adapt.at"
    private val CONTACTS_ADAPT_DIRTY = FIELD_PREFIX + "contacts.adapt.dirty"
    private val MMS_RESCAN = FIELD_PREFIX + "mms.rescan"
    private val MMS_ADAPT_AT = FIELD_PREFIX + "mms.adapt.at"
    private val MMS_ADAPT_DIRTY = FIELD_PREFIX + "mms.adapt.dirty"
    // 联系人详情页容器背景的原值。
    private val CONTACTS_BG_SAVED = FIELD_PREFIX + "contacts.bg.saved"
    // 缓存联系人进程内的资源 id（进程内固定），避免每次布局回调都走 getIdentifier 慢查询。-1=未解析。
    private var contactsBgViewId = -1
    private val DEVICE_ACTIVE = FIELD_PREFIX + "device.active"
    private val ORIGINAL_TEXT_COLOR = FIELD_PREFIX + "original.text.color"
    private val GLOBAL_DIAGNOSTIC = FIELD_PREFIX + "global.diagnostic"

    /**
     * 「全局壁纸」通道的开关快照：组件作用域的总开关 + 软件作用域的整包/按组件禁用集合。
     * 读的是 settings_appearance 的远端 SharedPreferences，跨进程读取开销大，所以进程内缓存，
     * 并挂变更监听刷新（与动态材质同一套键）。
     */
    private data class GlobalWallpaperConfig(
        val enabled: Boolean = true,
        val cleanupEnabled: Boolean = false,
        val disabledPackages: Set<String> = emptySet(),
        val disabledComponents: Set<String> = emptySet(),
    )

    @Volatile
    private var globalWallpaperCache: GlobalWallpaperConfig? = null
    private var globalWallpaperPrefs: SharedPreferences? = null

    private val globalWallpaperListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == null || key == KEY_COMPONENT_GLOBAL_WALLPAPER ||
                key == KEY_COMPONENT_LAYOUT_CLEANUP ||
                key == KEY_APP_SCOPE_DISABLED || key == KEY_APP_COMPONENT_DISABLED
            ) {
                globalWallpaperCache = readGlobalWallpaper(prefs)
            }
        }

    private fun readGlobalWallpaper(prefs: SharedPreferences) = GlobalWallpaperConfig(
        enabled = prefs.getBoolean(KEY_COMPONENT_GLOBAL_WALLPAPER, true),
        cleanupEnabled = prefs.getBoolean(KEY_COMPONENT_LAYOUT_CLEANUP, false),
        disabledPackages = prefs.getStringSet(KEY_APP_SCOPE_DISABLED, emptySet())
            ?.toSet().orEmpty(),
        disabledComponents = prefs.getStringSet(KEY_APP_COMPONENT_DISABLED, emptySet())
            ?.toSet().orEmpty(),
    )

    private fun globalWallpaperConfig(): GlobalWallpaperConfig {
        globalWallpaperCache?.let { return it }
        val prefs = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
            ?: return GlobalWallpaperConfig()
        val loaded = readGlobalWallpaper(prefs)
        if (globalWallpaperPrefs == null) {
            globalWallpaperPrefs = prefs
            prefs.registerOnSharedPreferenceChangeListener(globalWallpaperListener)
        }
        globalWallpaperCache = loaded
        return loaded
    }

    /**
     * 「全局壁纸」是否对该进程生效：组件作用域总开关 → 软件作用域整包开关 → 该包该组件的单独开关。
     * 包名为空（框架进程未上报）时只受总开关约束。
     */
    private fun globalWallpaperAllowedFor(packageName: String?): Boolean {
        val config = globalWallpaperConfig()
        if (!config.enabled) return false
        val pkg = packageName ?: return true
        if (pkg in config.disabledPackages) return false
        return ComponentKeys.encode(ComponentKeys.GLOBAL_WALLPAPER, pkg) !in config.disabledComponents
    }

    private fun layoutCleanupAllowedFor(packageName: String): Boolean {
        val config = globalWallpaperConfig()
        return config.cleanupEnabled && config.enabled && packageName !in config.disabledPackages &&
            ComponentKeys.encode(ComponentKeys.GLOBAL_WALLPAPER, packageName) !in config.disabledComponents &&
            ComponentKeys.encode(ComponentKeys.LAYOUT_CLEANUP, packageName) !in config.disabledComponents
    }

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
        DialpadBackgroundController.refresh(activity)
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

            // 拨号盘的透明度 / 自定义背景全部由 DialpadBackgroundController（Hook DialpadLayout.onFinishInflate）
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
        if (view == null || view === skip ||
            view.getAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD) == true ||
            isTransientPopup(view)) return
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

    // StickyRecyclerHeadersDecoration 将字母分组标题缓存为独立 View，直接绘制到
    // BaseRecyclerView 的 Canvas；它不是列表子 View，常规递归和 setBackground 回调都找不到。
    fun adaptContactsPinnedHeader(header: View) {
        if (header.javaClass.name != "com.android.contacts.list.ContactListPinnedHeaderView") return
        val activity = findActivity(header.context) ?: return
        if (activity.packageName != BackgroundContract.PACKAGE_CONTACTS ||
            !matchesContactsSettings(activity.javaClass.name)) return
        val enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true)
        // 只处理该标题自身及其文字底色，不触及 RecyclerView 或其它悬浮窗。
        adaptContactsOpaqueSurfaces(header, enabled, null, null)
    }

    // 供 View.setBackground hook 回调调用：item 重绑时一定会 setBackground，在调用后立即清除
    // 不透明中性色底色，避免等全局布局/绘制前扫描的延迟白块。只处理联系人 content 子树内、且非
    // 拨号盘背景板的 view；它由独立的拨号盘控制器管理，不参与列表背景透明化。
    fun onViewBackgroundChanged(view: View?) {
        if (view == null || view.getAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD) == true) return
        try {
            if (!HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true)) return
            val activity = findActivity(view.context) ?: return
            if (!matchesContactsSettings(activity.javaClass.name)) return
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            if (!isDescendant(view, content) || isTransientPopup(view)) return
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

    // 短信（com.android.mms）两条背景通道：
    // 主页 MMS：会话列表、验证码/推广分类列表、短信内部各设置页共用；
    // 聊天 MMS_CHAT：会话详情页（单/多收件人、RCS 机器人、拦截会话）与新建短信页独立。
    fun applyMmsHome(activity: Activity?) {
        if (activity == null) return
        removeMmsChat(activity)
        applyLayer(activity, BackgroundContract.MMS, MMS_SESSION, false)
        if (isMmsListActivity(activity.javaClass.name)) {
            adaptMmsListSurfaces(activity, false)
            installMmsSurfaceRescan(activity)
        }
    }

    fun applyMmsChat(activity: Activity?) {
        if (activity == null) return
        removeMmsHome(activity)
        // 聊天页未单独设置背景时跟随短信主页图（主页也无图则 applyLayer 不渲染）。
        val slot = if (BackgroundContract.query(activity, BackgroundContract.MMS_CHAT).exists) {
            BackgroundContract.MMS_CHAT
        } else {
            BackgroundContract.MMS
        }
        applyLayer(activity, slot, MMS_CHAT_SESSION, false)
    }

    // 列表类主页：列表项 selector 是纯白实底，需要中性色递归清除 + 重扫监听。
    private fun isMmsListActivity(className: String?) = className in MMS_LIST_ACTIVITIES

    private val MMS_LIST_ACTIVITIES = hashSetOf(
        "com.android.mms.ui.MmsTabActivity",
        // 验证码/推广/通知分类列表（y2 Fragment 宿主），列表项同为白底 ConversationListItem。
        "com.android.mms.ui.FlatMessageListActivity",
    )

    // 会话列表页：ConversationListItem 的 selector 底是纯白实底（miuix_appcompat_white），
    // 复用联系人的不透明中性色递归清除方案（colorFilter 透明，不替换 background 保留 selector/padding），
    // 但跳过 FAB 子树。仅作用于列表页；聊天页绝不走这里——收件气泡本身就是中性白（#ffffff/#f2f2f2）。
    private fun adaptMmsListSurfaces(activity: Activity, throttled: Boolean) {
        try {
            if (throttled) {
                val last = activity.getAdditionalInstanceField(MMS_ADAPT_AT) as? Long
                val now = SystemClock.uptimeMillis()
                if (last != null && now - last < 200L) return
                activity.setAdditionalInstanceField(MMS_ADAPT_AT, now)
            }
            contactsSampledColors.clear()
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            val fabId = resolveId(activity, "fab")
            val fab = if (fabId == 0) null else activity.findViewById<View>(fabId)
            adaptContactsOpaqueSurfaces(content, true, content, fab)
        } catch (error: Throwable) {
            log("adaptMmsListSurfaces", error)
        }
    }

    // 与 installContactsSurfaceRescan 同构：OnGlobalLayout 只打 dirty 标，OnPreDraw 为 true
    // 才真正遍历清除（绘制前一帧生效，无闪白），无布局变化时每帧只有一次 boolean 判断。
    private fun installMmsSurfaceRescan(activity: Activity) {
        try {
            if (activity.getAdditionalInstanceField(MMS_RESCAN) == true) return
            val decor = activity.window?.decorView
            if (decor !is ViewGroup) return
            val observer = decor.viewTreeObserver
            if (!observer.isAlive) return

            val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                if (activity.isFinishing || activity.isDestroyed) return@OnGlobalLayoutListener
                val now = SystemClock.uptimeMillis()
                val last = activity.getAdditionalInstanceField(MMS_ADAPT_AT) as? Long ?: 0L
                if (now - last >= 16L) {
                    activity.setAdditionalInstanceField(MMS_ADAPT_AT, now)
                    activity.setAdditionalInstanceField(MMS_ADAPT_DIRTY, true)
                }
            }
            val preDrawListener = ViewTreeObserver.OnPreDrawListener {
                if (activity.isFinishing || activity.isDestroyed) return@OnPreDrawListener true
                if (activity.getAdditionalInstanceField(MMS_ADAPT_DIRTY) == true) {
                    activity.setAdditionalInstanceField(MMS_ADAPT_DIRTY, false)
                    adaptMmsListSurfaces(activity, false)
                }
                true
            }
            observer.addOnGlobalLayoutListener(globalLayoutListener)
            observer.addOnPreDrawListener(preDrawListener)
            activity.setAdditionalInstanceField(MMS_RESCAN, arrayOf(globalLayoutListener, preDrawListener))
        } catch (error: Throwable) {
            log("installMmsSurfaceRescan", error)
        }
    }

    private fun removeMmsSurfaceRescan(activity: Activity) {
        try {
            val saved = activity.getAdditionalInstanceField(MMS_RESCAN)
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
            activity.setAdditionalInstanceField(MMS_RESCAN, null)
            activity.setAdditionalInstanceField(MMS_ADAPT_DIRTY, null)
        } catch (_: Throwable) {
        }
    }

    fun stopMmsHome(activity: Activity?) {
        stopLayer(activity, MMS_SESSION)
    }

    fun stopMmsChat(activity: Activity?) {
        stopLayer(activity, MMS_CHAT_SESSION)
    }

    fun destroyMmsHome(activity: Activity?) {
        removeMmsHome(activity)
    }

    fun destroyMmsChat(activity: Activity?) {
        removeMmsChat(activity)
    }

    private fun removeMmsHome(activity: Activity?) {
        if (activity == null) return
        try {
            removeMmsSurfaceRescan(activity)
            val old = activity.getAdditionalInstanceField(MMS_SESSION) as? LayerSession
            if (old != null) removeLayer(activity, MMS_SESSION, old)
        } catch (error: Throwable) {
            log("removeMmsHome", error)
        }
    }

    private fun removeMmsChat(activity: Activity?) {
        if (activity == null) return
        try {
            val old = activity.getAdditionalInstanceField(MMS_CHAT_SESSION) as? LayerSession
            if (old != null) removeLayer(activity, MMS_CHAT_SESSION, old)
        } catch (error: Throwable) {
            log("removeMmsChat", error)
        }
    }

    // View.setBackground hook 回调（短信进程）：列表项随 RecyclerView 回收重绑会恢复 selector
    // 纯白底，在 setBackground 后立即清除，避免等绘制前扫描的延迟白块。
    fun onMmsViewBackgroundChanged(view: View?) {
        if (view == null) return
        try {
            val activity = findActivity(view.context) ?: return
            if (!isMmsListActivity(activity.javaClass.name)) return
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            if (!isDescendant(view, content) || isTransientPopup(view)) return
            val bg = view.background
            if (bg != null && isOpaqueNeutralSurface(bg)) {
                makeTransparent(bg)
                view.invalidate()
            }
        } catch (_: Throwable) {
        }
    }

    private fun isTransientPopup(view: View): Boolean {
        // PopupWindow and Dialog have their own window, but can share the Activity Context.
        // Some MIUIX panels are hosted in the Activity window instead; exclude those too.
        var node: View? = view
        while (node != null) {
            val type = node.javaClass.name
            if (type == "miuix.appcompat.internal.widget.DialogParentPanel2" ||
                type == "miuix.popupwidget.widget.PopupView" ||
                type == "android.widget.PopupWindow\$PopupDecorView" ||
                (type == "miuix.smooth.SmoothFrameLayout2" && isMmsListPopup(node))
            ) return true
            node = node.parent as? View
        }
        return false
    }

    private fun isMmsListPopup(frame: View): Boolean {
        val group = frame as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            val spring = group.getChildAt(i) as? ViewGroup ?: continue
            if (spring.javaClass.name != "miuix.springback.view.SpringBackLayout") continue
            for (j in 0 until spring.childCount) {
                if (spring.getChildAt(j) is android.widget.ListView) return true
            }
        }
        return false
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

        // 「全局壁纸」总控整条 GLOBAL 通道：组件作用域一处（全局）、软件作用域每包一处。
        // 关闭即该包所有大页面退回原生底；默认开启，所以默认行为与之前一致。
        if (!globalWallpaperAllowedFor(packageName)) return true

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

        // 短信由独立的 mms 通道处理，global 一律跳过。
        if (BackgroundContract.PACKAGE_MMS == packageName) {
            return true
        }

        // 通用通道：以上「单独适配过规则」的进程之外，一律交给结构判定——只要当前窗口是
        // 全屏且已承载内容的大页面就套用全局背景（见 isGenericFullScreenPage）。
        // 这是 dynamic 那套「不看包名、只看结构」的思路在背景通道上的落地：新增一个应用
        // 不必再往这里补关键词表，把它加进 LSPosed 作用域即可。
        return !isGenericFullScreenPage(activity)
    }

    /**
     * 通用页面判定：与 dynamic 的 CardSurfaceDetector 同源——不看包名、不看资源 id，
     * 只看窗口结构本身。这里判的是「当前 Activity 是不是一个全屏、已承载内容的大页面」：
     *
     *  1. 窗口必须是不透明的全屏窗口（宽高均为 MATCH_PARENT）。浮窗、半透明、对话框形态
     *     （权限 / 支付 / 登录 / 凭据 / 选择器）在 [isSensitiveTransientWindow] 里已排除，
     *     这里再兜一次，保证本判定自成闭环、可独立复用。
     *  2. 内容层 android.R.id.content 存在，且至少有一个可见子视图——空窗口、纯骨架、
     *     无内容的中转 Activity 不挂。
     *
     * 刻意不卡「内容量出来多大」：onContentChanged 时尺寸还是 0，卡尺寸会让首帧挂不上、
     * 退化成 post 异步补挂，于是先绘制原生底色再补背景、闪一下。宁可让空页面也挂上背景
     * （随后 inflate 的内容会盖住它，无副作用），也不牺牲首帧无闪。
     */
    private fun isGenericFullScreenPage(activity: Activity?): Boolean {
        if (activity == null) return false
        try {
            val lp = activity.window?.attributes ?: return false
            if (lp.width != WindowManager.LayoutParams.MATCH_PARENT
                || lp.height != WindowManager.LayoutParams.MATCH_PARENT
            ) {
                return false
            }
        } catch (_: Throwable) {
            return false
        }
        val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return false
        for (i in 0 until content.childCount) {
            if (content.getChildAt(i).visibility == View.VISIBLE) return true
        }
        return false
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
            if (old != null && !old.media.loadFailed && old.media.sourceKey() == source.cacheKey()
                && old.media.parent === host && (old.observedRoot === host || !old.media.isReady)
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
            host.addView(media, 0, mediaParams)
            activity.setAdditionalInstanceField(fieldKey, session)
            media.onReady = {
                if (!activity.isDestroyed && media.parent === host) {
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
                    session.attach(activity, host, home, transparentTopBar)
                }
            }
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
            com.ciallo.hyperbackground.util.log("[HyperBackground] " + activity.packageName + " " + message)
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
        val session = fragment?.getAdditionalInstanceField(DEVICE_SESSION) as? DeviceSession ?: return false
        return session.media.hasRenderedFrame && !session.media.loadFailed &&
            session.media.isAttachedToWindow && session.media.parent === session.backgroundView.parent
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
            if (old != null && !old.media.loadFailed && old.media.sourceKey() == source.cacheKey()
                && old.media.parent != null && old.media.parent === old.backgroundView.parent
                && fragment.getObjectField("mBgEffectView") === old.backgroundView
            ) {
                if (old.media.hasRenderedFrame) {
                    old.backgroundView.visibility = View.INVISIBLE
                    stopOriginalShader(fragment, old.backgroundView)
                }
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
            if (rememberedVisibility != Int.MIN_VALUE) {
                backgroundView.visibility = rememberedVisibility
            }
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
            fragment.setAdditionalInstanceField(DEVICE_SESSION, session)
            media.onFirstFrame = {
                if (fragment.getAdditionalInstanceField(DEVICE_SESSION) === session &&
                    !media.loadFailed && media.parent === parent && backgroundView.parent === parent) {
                    backgroundView.visibility = View.INVISIBLE
                    stopOriginalShader(fragment, backgroundView)
                }
            }
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
        com.ciallo.hyperbackground.util.log("[HyperBackground] " + stage + " failed: " + error)
        com.ciallo.hyperbackground.util.log(error)
    }

    private class LayerSession(val media: BackgroundMediaView) {
        private companion object {
            const val RESCAN_WINDOW_MS = 2500L
        }

        private val clearedViews = ArrayList<View>()
        private val originalBackgrounds = ArrayList<Drawable>()
        private val clearedImages = ArrayList<ImageView>()
        private val originalImages = ArrayList<Drawable>()
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
            if (activity.packageName == BackgroundContract.PACKAGE_SETTINGS ||
                !layoutCleanupAllowedFor(activity.packageName)) {
                removeLayoutRescan()
                return
            }
            sampledColors.clear()
            clearPageSurfaces(activity, root, root, 0)
            if (transparentTopBar) clearActionBarSurfaces(activity, root, 0)
            observedActivity = activity
            rescanDeadline = SystemClock.uptimeMillis() + RESCAN_WINDOW_MS
            if (layoutListener == null) installLayoutRescan(root)
        }

        private fun installLayoutRescan(root: ViewGroup) {
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val observed = observedRoot
                val activity = observedActivity
                if (observed == null || activity == null || activity.isFinishing || activity.isDestroyed ||
                    !layoutCleanupAllowedFor(activity.packageName)) {
                    removeLayoutRescan()
                    return@OnGlobalLayoutListener
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastRescanAt < 200L) return@OnGlobalLayoutListener
                lastRescanAt = now
                sampledColors.clear()
                clearPageSurfaces(activity, observed, observed, 0)
                if (transparentTopBar) clearActionBarSurfaces(activity, observed, 0)
                if (now > rescanDeadline) removeLayoutRescan()
            }
            layoutListener = listener
            observer.addOnGlobalLayoutListener(listener)
        }

        private fun removeLayoutRescan() {
            val listener = layoutListener ?: return
            val observer = observedRoot?.viewTreeObserver
            if (observer != null && observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            layoutListener = null
        }

        fun detach() {
            removeLayoutRescan()
            observedRoot = null
            observedActivity = null
        }

        fun restore() {
            for (i in clearedImages.indices) {
                try {
                    clearedImages[i].setImageDrawable(originalImages[i])
                } catch (_: Throwable) {
                }
            }
            clearedImages.clear()
            originalImages.clear()
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
            if (view == null || view === media ||
                view.getAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD) == true ||
                activity.packageName == BackgroundContract.PACKAGE_PHONE && isTransientPopup(view)) return
            if (activity.packageName == BackgroundContract.PACKAGE_MMS) {
                val idName = resourceEntryName(activity, view.id)
                if (idName == "message_list" || idName == "message_list_animator" ||
                    idName == "bottom_panel") return
            }
            if (view.visibility != View.VISIBLE) return
            if (view is ImageView && isPageImage(activity, view, root)) clearPageImage(view)
            if (isPageSurface(activity, view, root, depth)) clear(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    clearPageSurfaces(activity, view.getChildAt(i), root, depth + 1)
                }
            }
        }

        private fun isPageImage(activity: Activity, view: ImageView, root: View): Boolean {
            if (view.drawable == null) return false
            val width = maxOf(root.width, activity.resources.displayMetrics.widthPixels)
            val height = maxOf(root.height, activity.resources.displayMetrics.heightPixels)
            if (view.width < width * 0.72f || view.height < height * 0.32f) return false
            val name = resourceEntryName(activity, view.id)
            return containsAny(name, "background", "wallpaper", "backdrop", "mask", "surface") ||
                (view.width >= width * 0.90f && view.height >= height * 0.62f)
        }

        private fun clearPageImage(view: ImageView) {
            if (!clearedImages.contains(view)) {
                clearedImages.add(view)
                originalImages.add(view.drawable)
            }
            view.setImageDrawable(null)
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

            if ("com.milink.service" == pkg
                && view is ViewGroup
                && width >= (rootWidth * 0.965f).toInt()
                && height >= (rootHeight * 0.05f).toInt()
                && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")
            ) return true

            if (isCardView(view)) return false
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
            ) return true

            if (externalSettingsPage
                && width >= (rootWidth * 0.5f).toInt()
                && height >= (rootHeight * 0.05f).toInt()
                && isOpaqueNeutralColorDrawable(bg)
            ) return true

            if (!large) return false

            // A large ImageView with a neutral opaque background is a valid surface;
            // clear only its background, never its image drawable.
            if (view is ImageView) return isOpaqueNeutralColorDrawable(bg)

            val cls = view.javaClass.name.lowercase()
            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false
            if (containsAny(cls, "cardview", "button", "switch", "checkbox")) return false

            if (containsAny(idName,
                    "content", "container", "recycler", "list", "prefs", "preference",
                    "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true
            if (containsAny(cls,
                    "recyclerview", "nestedscrollview", "scrollview", "listview",
                    "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true

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

        // 沿继承链识别 androidx CardView（含各厂商子类，如 com.miui.support.cardview.CardView），
        // 不直接引用 androidx.cardview，避免为一个判断引入编译期依赖。
        private fun isCardView(view: View): Boolean {
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if ("androidx.cardview.widget.CardView" == type.name) return true
                type = type.superclass
            }
            return false
        }

        // Sample a copy so the live drawable bounds are not changed.
        private fun isOpaqueNeutralColorDrawable(bg: Drawable?): Boolean {
            if (bg == null) return false
            if (bg is ColorDrawable) return isOpaqueNeutral(bg.color)
            val state = bg.constantState
            val cached = if (state == null) null else sampledColors[state]
            val color = if (cached != null) cached else {
                val sampled = sampleDrawableColor(bg) ?: return false
                if (state != null) sampledColors[state] = sampled
                sampled
            }
            return isOpaqueNeutral(color)
        }

        private fun sampleDrawableColor(bg: Drawable): Int? {
            return try {
                val copy = (bg.constantState ?: return null).newDrawable().mutate()
                val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                copy.setBounds(0, 0, 1, 1)
                copy.draw(Canvas(bmp))
                val color = bmp.getPixel(0, 0)
                bmp.recycle()
                color
            } catch (_: Throwable) {
                null
            }
        }

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
