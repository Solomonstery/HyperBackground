package com.ciallo.hyperbackground;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public final class HookEntry extends XposedModule {
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener configListener;

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage() || !BackgroundContract.isSupportedPackage(param.getPackageName())) return;
        android.content.SharedPreferences preferences = getRemotePreferences(BackgroundContract.PREFS);
        HookRuntime.initialize(this, preferences);
        configListener = (prefs, key) -> TextColorOverride.invalidateConfig();
        preferences.registerOnSharedPreferenceChangeListener(configListener);
        log(Log.INFO, "HyperBackground", "Injected package=" + param.getPackageName()
                + " version=" + BuildConfig.VERSION_NAME);
        SettingsBackgroundHook.install(param.getPackageName(), param.getDefaultClassLoader());
    }
}
