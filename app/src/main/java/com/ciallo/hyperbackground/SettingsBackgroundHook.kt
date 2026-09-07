package com.ciallo.hyperbackground

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

object SettingsBackgroundHook {
    private val PENDING_GLOBAL: MutableMap<Activity, Runnable> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmStatic
    fun install(packageName: String?, classLoader: ClassLoader) {
        val settings = BackgroundContract.PACKAGE_SETTINGS == packageName
        val contacts = BackgroundContract.PACKAGE_CONTACTS == packageName

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
        }

        if (contacts) {
            hookContactsActivity(classLoader)
            hookDialpadLayout(classLoader)
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
        val className = "com.android.contacts.activities.PeopleActivity"
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
            log("[HyperBackground] Installed contacts PeopleActivity background hooks")
        } catch (error: Throwable) {
            logHookError("PeopleActivity", error)
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
            hookMethod(className, classLoader, "startRuntimeShader", Boolean::class.javaPrimitiveType!!) {
                if (BackgroundApplier.shouldSuppressDeviceShader(thisObject)) setResult(null)
            }

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
}
