package com.ciallo.hyperbackground

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage || !BackgroundContract.isSupportedPackage(param.packageName)) return
        val preferences = getRemotePreferences(BackgroundContract.PREFS)
        HookRuntime.initialize(this, preferences)
        configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            TextColorOverride.invalidateConfig()
        }
        preferences.registerOnSharedPreferenceChangeListener(configListener)
        log(
            Log.INFO, "HyperBackground",
            "Injected package=${param.packageName} version=${BuildConfig.VERSION_NAME}",
        )
        SettingsBackgroundHook.install(param.packageName, param.defaultClassLoader)
    }
}
