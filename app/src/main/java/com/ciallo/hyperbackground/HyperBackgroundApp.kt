package com.ciallo.hyperbackground

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class HyperBackgroundApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
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

    companion object {
        private const val TAG = "HyperBackground"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

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
}
