package com.ciallo.hyperbackground

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.FileNotFoundException
import java.lang.reflect.Executable

object HookRuntime {
    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var prefsRef: SharedPreferences? = null

    internal fun initialize(value: XposedModule, prefs: SharedPreferences) {
        module = value
        prefsRef = prefs
    }

    fun preferences(): SharedPreferences {
        val value = prefsRef ?: throw IllegalStateException("Hook preferences are not initialized")
        return value
    }

    @Throws(FileNotFoundException::class)
    fun openRemoteFile(name: String): ParcelFileDescriptor {
        val value = module ?: throw FileNotFoundException("Hook module is not initialized")
        return value.openRemoteFile(name)
    }

    fun log(message: String) {
        module?.log(Log.INFO, "HyperBackground", message)
    }

    fun log(message: String, error: Throwable) {
        module?.log(Log.ERROR, "HyperBackground", message, error)
    }

    internal fun hook(executable: Executable, callback: LegacyMethodHook) {
        val value = module ?: throw IllegalStateException("Hook module is not initialized")
        value.hook(executable).intercept { chain ->
            val param = LegacyHookParam(chain)
            callback.before(param)
            val result = if (param.hasResult) param.result else chain.proceed(param.args)
            param.setResultFromOriginal(result)
            callback.after(param)
            param.result
        }
    }

    abstract class LegacyMethodHook {
        @Throws(Throwable::class)
        open fun before(param: LegacyHookParam) {}

        @Throws(Throwable::class)
        open fun after(param: LegacyHookParam) {}
    }

    class LegacyHookParam internal constructor(chain: XposedInterface.Chain) {
        val thisObject: Any? = chain.thisObject
        val args: Array<Any?> = chain.args.toTypedArray()

        var result: Any? = null
            private set

        var hasResult: Boolean = false
            private set

        fun setResult(value: Any?) {
            result = value
            hasResult = true
        }

        internal fun setResultFromOriginal(value: Any?) {
            result = value
        }
    }
}
