package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SettingsThemeOverride {
    private SettingsThemeOverride() {}

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "attachBaseContext",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context base = (Context) param.args[0];
                            if (base == null) return;

                            int mode = BackgroundContract.query(base, BackgroundContract.HOME).settingsThemeMode;
                            if (mode == BackgroundContract.SETTINGS_THEME_FOLLOW) return;

                            Configuration config = new Configuration(base.getResources().getConfiguration());
                            int night = mode == BackgroundContract.SETTINGS_THEME_DARK
                                    ? Configuration.UI_MODE_NIGHT_YES
                                    : Configuration.UI_MODE_NIGHT_NO;
                            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                            param.args[0] = base.createConfigurationContext(config);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] Settings theme override hook failed: " + error);
            XposedBridge.log(error);
        }
    }
}
