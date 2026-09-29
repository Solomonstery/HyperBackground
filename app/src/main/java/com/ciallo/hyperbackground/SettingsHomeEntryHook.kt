package com.ciallo.hyperbackground

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.ciallo.hyperbackground.util.getIntFieldOrNull
import com.ciallo.hyperbackground.util.getLongFieldOrNull
import com.ciallo.hyperbackground.util.hookMethod
import com.ciallo.hyperbackground.util.log
import com.ciallo.hyperbackground.util.setIntField
import com.ciallo.hyperbackground.util.setLongField
import com.ciallo.hyperbackground.util.setObjectField

/**
 * 在 HyperOS 设置首页插入一条模块入口。
 *
 * 原理：hook `com.android.settings.MiuiSettings#updateHeaderList(List)`——厂商在这里构建完
 * 首页 Header 列表后，我们往列表里插入一条指向模块 MainActivity 的 Header。图标不往系统设置包里
 * 塞资源，而是写一个占位资源 id（[FAKE_ICON_RES_ID]），再用 `Resources.getDrawable` hook 把这个 id
 * 换成模块自己的应用图标。
 *
 * 开关、插入位置与「是否与相邻项同组」都实时读模块配置（libxposed remote prefs），
 * 因此改完配置重启设置应用（或重新进入设置首页触发 buildHeaders）即可生效。
 */
object SettingsHomeEntryHook {
    private const val HEADER_HOST_CLASS = "com.android.settings.MiuiSettings"
    private const val HEADER_CLASS =
        "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header"

    /** 模块条目的 Header id，取 "hyprbg" 的 ASCII，避免与系统 Header id 冲突。 */
    private const val HEADER_ID = 0x687970726267L

    /**
     * 占位图标资源 id。
     *
     * 包段 0x7e 是系统保留的动态段，type=0x00 也不是合法资源类型，因此这个 id 在任何 Resources
     * 里都取不到真实资源，只会被本模块的 `getDrawable` hook 拦下、替换成模块自己的应用图标。
     *
     * ⚠️ 必须与其它模块用不同的占位 id。同一个进程里每个模块都会 hook `Resources.getDrawable`，
     * 命中同一个 id 时只有最外层那个 hook 生效——例如 HyperIsland 用的是 0x7e00f001，
     * 复用它会让本模块插入的入口显示成 HyperIsland 的图标。
     */
    private const val FAKE_ICON_RES_ID = 0x7e00b401

    private const val FIELD_ID = "id"
    private const val FIELD_TITLE = "title"
    private const val FIELD_ICON_RES = "iconRes"
    private const val FIELD_INTENT = "intent"
    private const val FIELD_GROUP_ID = "groupId"

    // 插入位置锚点条目：顶部=「我的设备」之后，中部=桌面设置之后，底部=应用定时/特殊功能之后。
    private val ANCHORS_TOP = arrayOf("my_device")
    private val ANCHORS_MIDDLE = arrayOf("launcher_settings")
    private val ANCHORS_BOTTOM = arrayOf("app_timer", "other_special_feature_settings")

    @Volatile
    private var iconState: Drawable.ConstantState? = null
    private var iconHookInstalled = false

    fun install(classLoader: ClassLoader) {
        try {
            installIconHook()
            hookMethod(HEADER_HOST_CLASS, classLoader, "updateHeaderList", List::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                val headers = args.getOrNull(0) as? MutableList<Any> ?: return@hookMethod
                runCatching { insertEntry(activity, classLoader, headers) }
                    .onFailure { log("[HyperBackground] settings entry insert failed: $it") }
            }
            log("[HyperBackground] Installed settings home entry hook")
        } catch (error: Throwable) {
            log("[HyperBackground] Could not hook settings home entry: $error")
            log(error)
        }
    }

    private fun insertEntry(activity: Activity, classLoader: ClassLoader, headers: MutableList<Any>) {
        val prefs = HookRuntime.preferences()
        if (!prefs.getBoolean(BackgroundContract.UI_SETTINGS_ENTRY_ENABLED, true)) return
        // 幂等保护：列表重建后 id 不会残留，这里只是防止同一列表被重复插入。
        if (headers.any { it.headerId() == HEADER_ID }) return

        val context: Context = activity.baseContext ?: activity
        val headerClass = Class.forName(HEADER_CLASS, false, classLoader)
        val header = headerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        header.setLongField(FIELD_ID, HEADER_ID)
        header.setObjectField(FIELD_TITLE, moduleLabel(context))
        header.setIntField(FIELD_ICON_RES, FAKE_ICON_RES_ID)
        header.setObjectField(FIELD_INTENT, launchIntent())
        prepareModuleIcon(context)

        val position = findInsertPosition(prefs, context, headers).coerceIn(0, headers.size)
        if (prefs.getBoolean(BackgroundContract.UI_SETTINGS_ENTRY_SAME_GROUP, true)) {
            inheritAdjacentGroupId(headers, header, position)
        }
        headers.add(position, header)
        log("[HyperBackground] Inserted settings home entry at $position")
    }

    private fun moduleLabel(context: Context): CharSequence = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0))
    }.getOrDefault("HyperBG")

    private fun launchIntent(): Intent = Intent().apply {
        setClassName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.ui.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // MIUIX 页面识别该 extra 后在标题栏显示返回箭头。
        putExtra("isDisplayHomeAsUpEnabled", true)
    }

    /** 按配置里的位置找到锚点条目，插入到它后面；锚点缺失时退化为列表末尾（超长列表则限制在 25 项内）。 */
    private fun findInsertPosition(
        prefs: SharedPreferences,
        context: Context,
        headers: List<Any>,
    ): Int {
        val anchorNames = when (
            prefs.getString(
                BackgroundContract.UI_SETTINGS_ENTRY_POSITION,
                BackgroundContract.SETTINGS_ENTRY_POSITION_TOP,
            )
        ) {
            BackgroundContract.SETTINGS_ENTRY_POSITION_MIDDLE -> ANCHORS_MIDDLE
            BackgroundContract.SETTINGS_ENTRY_POSITION_BOTTOM -> ANCHORS_BOTTOM
            else -> ANCHORS_TOP
        }
        val res = context.resources
        val anchors = anchorNames
            .map { res.getIdentifier(it, "id", context.packageName) }
            .filter { it != 0 }
            .map { it.toLong() }
            .toSet()
        if (anchors.isNotEmpty()) {
            headers.forEachIndexed { index, header ->
                if (header.headerId() in anchors) return index + 1
            }
        }
        return if (headers.size > 25) 25 else headers.size
    }

    /** Header 的 id 字段（long），字段缺失时用 [Long.MIN_VALUE] 兜底以便安全参与比较。 */
    private fun Any.headerId(): Long = getLongFieldOrNull(FIELD_ID) ?: Long.MIN_VALUE

    /** 同组：继承插入位置前一条 Header 的 groupId，让入口落在同一张卡片里而不是新起一组。 */
    private fun inheritAdjacentGroupId(headers: List<Any>, header: Any, position: Int) {
        if (headers.isEmpty()) return
        val neighbour = headers[if (position > 0) position - 1 else 0]
        val groupId = neighbour.getIntFieldOrNull(FIELD_GROUP_ID) ?: return
        header.setIntField(FIELD_GROUP_ID, groupId)
    }

    /**
     * 载入模块自己的应用图标（不是任何参考工程里的资源）。
     *
     * 优先用 `PackageManager.getApplicationIcon` 取模块自身图标；取不到时再用
     * `createPackageContext` 直接读模块的 launcher drawable 兜底。两者都失败则返回 null，
     * hook 里会退化成系统 info 图标，但绝不会借用别的模块的图标。
     */
    private fun prepareModuleIcon(context: Context) {
        if (iconState != null) return
        iconState = moduleIconDrawable(context)?.constantState
    }

    private fun moduleIconDrawable(context: Context): Drawable? {
        runCatching {
            context.packageManager.getApplicationIcon(BuildConfig.APPLICATION_ID)
        }.getOrNull()?.let { return it }

        return runCatching {
            val moduleContext = context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val id = moduleContext.resources.getIdentifier(
                "app_icon",
                "drawable",
                BuildConfig.APPLICATION_ID,
            )
            if (id != 0) moduleContext.resources.getDrawable(id, moduleContext.theme) else null
        }.getOrNull()
    }

    /**
     * hook 所有 `getDrawable(int, ...)` / `getDrawableForDensity(int, ...)` 重载：设置首页用
     * `ImageView.setImageResource(header.iconRes)` 渲染条目图标，把占位 id 映射成模块应用图标即可，
     * 无需往系统设置包里放资源。
     *
     * 只认本模块自己的 [FAKE_ICON_RES_ID]，其它 id（包括别的模块的占位 id）一律放行，
     * 避免抢掉或覆盖其它模块注入的图标。
     */
    private fun installIconHook() {
        if (iconHookInstalled) return
        val overloads = Resources::class.java.declaredMethods.filter { method ->
            (method.name == "getDrawable" || method.name == "getDrawableForDensity") &&
                method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        }
        overloads.forEach { method ->
            hookMethod(method, before = {
                if (args.getOrNull(0) != FAKE_ICON_RES_ID) return@hookMethod
                val icon = iconState?.newDrawable()
                    ?: (thisObject as? Resources)?.let { resources ->
                        runCatching {
                            resources.getDrawable(android.R.drawable.ic_dialog_info, null)
                        }.getOrNull()
                    }
                if (icon != null) setResult(icon)
            })
        }
        iconHookInstalled = overloads.isNotEmpty()
        log("[HyperBackground] Hooked Resources.getDrawable for the settings entry icon")
    }
}
