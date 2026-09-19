package com.ciallo.hyperbackground

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.view.View
import com.ciallo.hyperbackground.appearance.APPEARANCE_SLOT_DEVICE
import com.ciallo.hyperbackground.appearance.SETTINGS_APPEARANCE_PREFERENCES
import com.ciallo.hyperbackground.appearance.SettingsAppearanceSources
import com.ciallo.hyperbackground.appearance.SettingsBackgroundView
import java.util.Collections
import java.util.WeakHashMap

object SettingsBackgroundHook {
    private val PENDING_GLOBAL: MutableMap<Activity, Runnable> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmStatic
    fun install(packageName: String?, classLoader: ClassLoader) {
        val settings = BackgroundContract.PACKAGE_SETTINGS == packageName
        val contacts = BackgroundContract.PACKAGE_CONTACTS == packageName
        val mms = BackgroundContract.PACKAGE_MMS == packageName

        hookGlobalActivities()
        hookInstrumentationLifecycle()
        hookKnownPackageLifecycle(packageName, classLoader)

        // 主题（深浅色）与文字色强制对所有支持的作用域进程生效，不再局限于设置进程，
        // 这样应用详情页等由其它进程提供的页面也能被强制控制。
        SettingsThemeOverride.install(packageName)
        TextColorOverride.install()

        if (settings) {
            SettingsSearchMaskOverride.install(classLoader)
            SettingsTopBarBlurHook.install(classLoader)
            // 清除顶栏不再独立 hook，由 SettingsTopBarBlurHook 复用模糊管线（透明度归零）实现。
            hookHomeActivity(classLoader)
            hookHomeFragment(classLoader)
            hookDeviceFragment(classLoader)
            hookApplicationPreload()
        }

        if (contacts) {
            hookContactsActivity(classLoader)
            hookDialpadLayout(classLoader)
            hookContactsViewBackground()
        }

        if (mms) {
            hookMmsHomeActivities(classLoader)
            hookMmsChatActivities(classLoader)
            hookMmsViewBackground()
        }
    }

    // 联系人列表项随 RecyclerView 回收重绑（拨号盘输入过滤、快速滚动）会重新 setBackground
    // 恢复不透明底色，等全局布局/绘制前扫描会有延迟白块。直接 hook View.setBackground，在设置后
    // 立即清除不透明中性色底色——只在背景变化时触发，比每帧遍历轻量，且无延迟。
    private fun hookContactsViewBackground() {
        try {
            val callback: HookRuntime.LegacyHookParam.() -> Unit = cb@{
                val view = thisObject as? View ?: return@cb
                val newBg = args[0]
                // 我们自己设置的透明占位 ColorDrawable，跳过避免递归。
                if (newBg is android.graphics.drawable.ColorDrawable &&
                    newBg.color == android.graphics.Color.TRANSPARENT
                ) return@cb
                BackgroundApplier.onViewBackgroundChanged(view)
            }
            hookMethod(View::class.java, "setBackground", android.graphics.drawable.Drawable::class.java, after = callback)
            hookMethod(View::class.java, "setBackgroundDrawable", android.graphics.drawable.Drawable::class.java, after = callback)
        } catch (error: Throwable) {
            // View.setBackground 在所有进程都存在，但只在联系人进程调用 BackgroundApplier；
            // 其它进程走到 onViewBackgroundChanged 里会因 ctx 不匹配直接 return，无副作用。
        }
    }

    private fun hookInstrumentationLifecycle() {
        try {
            hookMethod(Instrumentation::class.java, "callActivityOnCreate", Activity::class.java, Bundle::class.java) {
                val activity = args[0] as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
            hookMethod(Instrumentation::class.java, "callActivityOnResume", Activity::class.java) {
                val activity = args[0] as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
        } catch (error: Throwable) {
            logHookError("Instrumentation lifecycle", error)
        }
    }

    private fun hookGlobalActivities() {
        try {
            hookMethod(Activity::class.java, "onCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
            hookMethod(Activity::class.java, "onPostCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
            hookMethod(Activity::class.java, "onResume") {
                val activity = thisObject as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
            hookMethod(Activity::class.java, "onContentChanged") {
                val activity = thisObject as? Activity ?: return@hookMethod
                applyGlobalNow(activity)
            }
            hookMethod(Activity::class.java, "onStop") {
                val activity = thisObject as? Activity ?: return@hookMethod
                BackgroundApplier.stopGlobal(activity)
            }
            hookMethod(Activity::class.java, "onDestroy") {
                val activity = thisObject as? Activity ?: return@hookMethod
                BackgroundApplier.destroyGlobal(activity)
            }
        } catch (error: Throwable) {
            logHookError("Global Activities", error)
        }
    }

    private fun hookKnownPackageLifecycle(packageName: String?, classLoader: ClassLoader) {
        var className: String? = null
        when (packageName) {
            BackgroundContract.PACKAGE_PHONE ->
                className = "com.android.phone.settings.BaseActivity"
            BackgroundContract.PACKAGE_ACCOUNT ->
                className = "com.xiaomi.account.ui.BaseActivity"
            BackgroundContract.PACKAGE_THEME_MANAGER ->
                className = "com.android.thememanager.basemodule.base.AbstractBaseActivity"
        }
        if (className == null) return
        try {
            hookMethod(className, classLoader, "onCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                scheduleGlobal(activity)
            }
            log("[HyperBackground] precise lifecycle hook=$className")
        } catch (error: Throwable) {
            // The launcher settings class can be supplied by a shared native runtime and
            // may not declare onCreate itself. Framework lifecycle hooks remain active.
            logHookError("precise lifecycle $className", error)
        }
    }

    // 内容层刚 inflate 完成（onContentChanged）时同步挂背景，赶在第一帧绘制之前，
    // 避免先绘制原生底色、再于下一帧 post 补背景造成的黑/白闪。挂载失败时退回异步兜底。
    private fun applyGlobalNow(activity: Activity) {
        if (activity.isFinishing) return
        try {
            BackgroundApplier.applyGlobal(activity)
        } catch (_: Throwable) {
            scheduleGlobal(activity)
        }
    }

    private fun scheduleGlobal(activity: Activity) {
        if (activity.isFinishing) return
        try {
            val decor = activity.window?.decorView ?: return
            synchronized(PENDING_GLOBAL) {
                if (PENDING_GLOBAL.containsKey(activity)) return
                val task = Runnable {
                    synchronized(PENDING_GLOBAL) { PENDING_GLOBAL.remove(activity) }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        BackgroundApplier.applyGlobal(activity)
                    }
                }
                PENDING_GLOBAL[activity] = task
                decor.post(task)
            }
        } catch (_: Throwable) {
            BackgroundApplier.applyGlobal(activity)
        }
    }

    private fun hookContactsActivity(classLoader: ClassLoader) {
        // 通讯录主界面（PeopleActivity）与联系人详情页（SubActivity / PeopleDetailActivity）共用 contacts 背景通道。
        val classNames = arrayOf(
            "com.android.contacts.activities.PeopleActivity",
            "com.android.contacts.activities.SubActivity",
            "com.android.contacts.activities.PeopleDetailActivity",
        )
        classNames.forEach { className ->
            try {
                hookMethod(className, classLoader, "onCreate", Bundle::class.java) {
                    val activity = thisObject as? Activity ?: return@hookMethod
                    BackgroundApplier.applyContacts(activity)
                }
                hookMethod(className, classLoader, "onResume") {
                    val activity = thisObject as? Activity ?: return@hookMethod
                    BackgroundApplier.applyContacts(activity)
                }
                hookMethod(className, classLoader, "onContentChanged") {
                    val activity = thisObject as? Activity ?: return@hookMethod
                    BackgroundApplier.applyContacts(activity)
                }
                hookMethod(className, classLoader, "onStop") {
                    val activity = thisObject as? Activity ?: return@hookMethod
                    BackgroundApplier.stopContacts(activity)
                }
                hookMethod(className, classLoader, "onDestroy") {
                    val activity = thisObject as? Activity ?: return@hookMethod
                    BackgroundApplier.destroyContacts(activity)
                }
                log("[HyperBackground] Installed contacts $className background hooks")
            } catch (error: Throwable) {
                logHookError(className, error)
            }
        }
    }

    // 短信主页通道：会话列表、验证码/推广分类列表（FlatMessageListActivity）、短信内部全部设置页。
    private val MMS_HOME_ACTIVITIES = arrayOf(
        "com.android.mms.ui.MmsTabActivity",
        "com.android.mms.ui.FlatMessageListActivity",
        // 短信内部设置页（背景跟随短信主页）
        "com.android.mms.ui.MessagingPreferenceActivity",
        "com.android.mms.ui.MessagingAdvancedPreferenceActivity",
        "com.android.mms.ui.SmartMessagePreferencesActivity",
        "com.android.mms.ui.AdSettingsPreferenceActivity",
        "com.android.mms.ui.AiSummaryPreferenceActivity",
        "com.android.mms.ui.PrivacyPolicyPreferenceActivity",
        "com.android.mms.ui.PrivatePreferenceActivity",
        "com.android.mms.ui.MxPreferenceActivity",
        "com.android.mms.ui.MultiSimPreferenceAcitvity",
        "com.android.mms.ui.SelectCardPreferenceActivity",
        "com.android.mms.ui.SelectCardListPreferenceActivity",
        "com.android.mms.ui.AuthorityManagementActivity",
        "com.android.mms.ui.RcsAuthorityManagementActivity",
        "com.xiaomi.rcs.ui.RcsSettingPreferenceActivity",
        "com.xiaomi.rcs.ui.RcsPrivacyPolicyPreferenceActivity",
        "com.xiaomi.rcs.ui.ChatbotPermissionSettingsActivity",
    )

    // 短信聊天通道：会话详情页（单/多收件人、RCS 机器人、拦截会话）与新建短信页。
    private val MMS_CHAT_ACTIVITIES = arrayOf(
        "com.android.mms.ui.activity.phone.activity.SingleRecipientConversationActivity",
        "com.android.mms.ui.activity.phone.activity.MultipleRecipientsConversationActivityPhone",
        "com.android.mms.ui.activity.phone.activity.RcsChatbotConversationActivityPhone",
        "com.android.mms.ui.activity.phone.activity.NewMessageActivity",
        "com.android.mms.ui.BlockedConversationActivity",
    )

    private fun hookMmsHomeActivities(classLoader: ClassLoader) {
        hookMmsActivityGroup(
            classLoader, MMS_HOME_ACTIVITIES, "home",
            apply = { BackgroundApplier.applyMmsHome(it) },
            stop = { BackgroundApplier.stopMmsHome(it) },
            destroy = { BackgroundApplier.destroyMmsHome(it) },
        )
    }

    private fun hookMmsChatActivities(classLoader: ClassLoader) {
        hookMmsActivityGroup(
            classLoader, MMS_CHAT_ACTIVITIES, "chat",
            apply = { BackgroundApplier.applyMmsChat(it) },
            stop = { BackgroundApplier.stopMmsChat(it) },
            destroy = { BackgroundApplier.destroyMmsChat(it) },
        )
    }

    private fun hookMmsActivityGroup(
        classLoader: ClassLoader,
        classNames: Array<String>,
        tag: String,
        apply: (Activity) -> Unit,
        stop: (Activity) -> Unit,
        destroy: (Activity) -> Unit,
    ) {
        classNames.forEach { className ->
            // 大量短信 Activity 不重写生命周期方法，findMethod 会沿继承链解析到共同祖先的同一个 Method，
            // hook 会以拦截器链形式全部挂载。必须用「实例类名 == 当前绑定类名」守卫，
            // 否则主页组/聊天组回调会对同一个 Activity 实例交叉触发，两个通道互相拆图层导致背景串台。
            fun targetOrNull(param: HookRuntime.LegacyHookParam): Activity? {
                val activity = param.thisObject as? Activity ?: return null
                return if (activity.javaClass.name == className) activity else null
            }
            try {
                hookMethod(className, classLoader, "onCreate", Bundle::class.java) {
                    targetOrNull(this)?.let(apply)
                }
                hookMethod(className, classLoader, "onResume") {
                    targetOrNull(this)?.let(apply)
                }
                hookMethod(className, classLoader, "onContentChanged") {
                    targetOrNull(this)?.let(apply)
                }
                hookMethod(className, classLoader, "onStop") {
                    targetOrNull(this)?.let(stop)
                }
                hookMethod(className, classLoader, "onDestroy") {
                    targetOrNull(this)?.let(destroy)
                }
                log("[HyperBackground] Installed mms-$tag $className background hooks")
            } catch (error: Throwable) {
                logHookError("mms-$tag $className", error)
            }
        }
    }

    // 会话列表项随 RecyclerView 回收重绑会重新 setBackground 恢复纯白 selector 底，
    // hook View.setBackground 在设置后立即清除；回调内按当前 Activity 类名过滤，聊天页不处理。
    private fun hookMmsViewBackground() {
        try {
            val callback: HookRuntime.LegacyHookParam.() -> Unit = cb@{
                val view = thisObject as? View ?: return@cb
                val newBg = args[0]
                if (newBg is android.graphics.drawable.ColorDrawable &&
                    newBg.color == android.graphics.Color.TRANSPARENT
                ) return@cb
                BackgroundApplier.onMmsViewBackgroundChanged(view)
            }
            hookMethod(View::class.java, "setBackground", android.graphics.drawable.Drawable::class.java, after = callback)
            hookMethod(View::class.java, "setBackgroundDrawable", android.graphics.drawable.Drawable::class.java, after = callback)
        } catch (_: Throwable) {
        }
    }

    // 拨号盘键盘容器 DialpadLayout 在 onFinishInflate 时（其子 view 已 findViewById 完毕、绘制第一帧之前）
    // 同步处理拨号盘背景（默认设 alpha / 自定义叠加独立背景图），根除“先露原生底色再变透”的先灰后透闪烁。
    private fun hookDialpadLayout(classLoader: ClassLoader) {
        val className = "com.android.contacts.dialer.view.DialpadLayout"
        try {
            hookMethod(className, classLoader, "onFinishInflate") {
                val view = thisObject as? View ?: return@hookMethod
                BackgroundApplier.applyDialpadOnInflate(view)
            }
            log("[HyperBackground] Installed DialpadLayout background hook")
        } catch (error: Throwable) {
            logHookError("DialpadLayout", error)
        }
    }

    private fun hookHomeActivity(classLoader: ClassLoader) {
        try {
            hookMethod("com.android.settings.MiuiSettings", classLoader, "onCreate", Bundle::class.java) {
                val activity = thisObject as? Activity ?: return@hookMethod
                BackgroundApplier.applyHome(activity)
            }
            hookMethod("com.android.settings.MiuiSettings", classLoader, "onResume") {
                val activity = thisObject as? Activity ?: return@hookMethod
                BackgroundApplier.applyHome(activity)
            }
            hookMethod("com.android.settings.MiuiSettings", classLoader, "onStop") {
                val activity = thisObject as? Activity ?: return@hookMethod
                BackgroundApplier.stopHome(activity)
            }
        } catch (error: Throwable) {
            logHookError("MiuiSettings", error)
        }
    }

    private fun hookHomeFragment(classLoader: ClassLoader) {
        try {
            hookMethod(
                "com.android.settings.SettingsFragment",
                classLoader,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
            ) {
                val activity = thisObject!!.callMethod("getActivity")
                if (activity is Activity && activity.javaClass.name == "com.android.settings.MiuiSettings") {
                    SettingsTopBarBlurHook.markHomeFragment(thisObject)
                    BackgroundApplier.applyHome(activity)
                }
            }
        } catch (error: Throwable) {
            logHookError("SettingsFragment", error)
        }
    }

    private fun hookDeviceFragment(classLoader: ClassLoader) {
        val className = "com.android.settings.device.MiuiMyDeviceSettings"
        try {
            hookMethod(className, classLoader, "startRuntimeShader", Boolean::class.javaPrimitiveType!!,
                before = {
                    if (BackgroundApplier.shouldSuppressDeviceShader(thisObject)) setResult(null)
                })

            hookMethod(className, classLoader, "onViewCreated", View::class.java, Bundle::class.java) {
                val a = thisObject!!.callMethod("getActivity")
                if (a is Activity) BackgroundApplier.enterDevice(a)
                BackgroundApplier.applyDevice(thisObject)
            }

            hookMethod(className, classLoader, "setDeviceShaderBackground") {
                BackgroundApplier.applyDevice(thisObject)
            }

            hookMethod(className, classLoader, "onResume") {
                val a = thisObject!!.callMethod("getActivity")
                if (a is Activity) BackgroundApplier.enterDevice(a)
                BackgroundApplier.applyDevice(thisObject)
            }

            hookMethod(className, classLoader, "onStop") {
                BackgroundApplier.stopDevice(thisObject)
            }

            hookMethod(className, classLoader, "onDestroy") {
                val a = thisObject!!.callMethod("getActivity")
                BackgroundApplier.destroyDevice(thisObject)
                if (a is Activity) BackgroundApplier.leaveDevice(a)
            }
        } catch (error: Throwable) {
            logHookError("MiuiMyDeviceSettings", error)
        }
    }

    private fun logHookError(target: String, error: Throwable) {
        log("[HyperBackground] Could not hook $target: $error")
        log(error)
    }

    // 预加载：hook Application.onCreate，进程启动后立即异步解码「我的设备」背景图入缓存，
    // 使用户进入页面时第一帧即可命中缓存同步显示自定义背景，消除黑帧。
    // 仅当用户在模块设置里开启了「背景预加载」开关时生效；视频背景不预加载（需 SurfaceTexture）。
    private fun hookApplicationPreload() {
        try {
            hookMethod(Application::class.java, "onCreate") {
                val app = thisObject as? Application ?: return@hookMethod
                val context = app as Context
                val prefs = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES) ?: return@hookMethod
                if (!prefs.getBoolean("device_background_preload", false)) return@hookMethod
                Thread {
                    runCatching {
                        val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE)
                        if (source.exists && !source.isVideo) {
                            SettingsBackgroundView.preload(context, source)
                        }
                    }.onFailure { error -> logHookError("preload device background", error) }
                }.start()
            }
            log("[HyperBackground] Installed Application.onCreate preload hook")
        } catch (error: Throwable) {
            logHookError("Application.onCreate preload", error)
        }
    }
}
