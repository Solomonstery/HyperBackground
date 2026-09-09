package com.ciallo.hyperbackground

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class HyperBackgroundApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        // 每次进程启动按当前随机背景设置校正开机接收器启用状态。
        updateBootReceiverState(this)
    }

    companion object {
        private const val TAG = "HyperBackground"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        /**
         * 按随机背景总开关与触发模式启用/禁用 [BootRandomReceiver]。
         * 仅在「开机」或「手动+开机」模式下启用，避免「仅手动」模式下开机被系统无谓拉起进程。
         * 在 Application 启动及设置项变更后调用。
         */
        fun updateBootReceiverState(context: Context) {
            val config = ConfigManager.get(context)
            val enabled = config.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false)
            val mode = config.getInt(BackgroundContract.UI_RANDOM_BG_MODE, BackgroundContract.RANDOM_BG_MODE_MANUAL)
            val bootWanted = enabled && (
                mode == BackgroundContract.RANDOM_BG_MODE_BOOT ||
                    mode == BackgroundContract.RANDOM_BG_MODE_BOTH
                )
            val component = ComponentName(context, BootRandomReceiver::class.java)
            val target = if (bootWanted) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    component, target, PackageManager.DONT_KILL_APP,
                )
            }
        }

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }

        /**
         * 模块是否已被 LSPosed 激活。
         *
         * 通过 XposedService 是否绑定判断——只有被 LSPosed 框架真正加载并授权的模块，
         * XposedServiceHelper 才会回调 onServiceBind。无需 hook 模块自身，也不依赖
         * 模块自身是否在作用域列表中。
         */
        @JvmStatic
        fun isModuleActive(): Boolean = xposedService != null
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "Xposed service bound api=${service.apiVersion}")
        xposedService = service
        Thread({ ConfigManager.get(this).syncToRemote(service) }, "HyperBackground-Sync").start()
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService != service) return
        xposedService = null
        listeners.forEach { it(null) }
    }
}
