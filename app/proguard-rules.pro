# Loaded by libxposed from META-INF/xposed/java_init.list. Its class name must
# remain exactly the same in release builds.
-keep class com.ciallo.hyperbackground.HookEntry {
    public <init>();
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}

# Second Xposed entry, also referenced by name in java_init.list. Without this
# keep, R8 strips/renames it (only text-file reference), so LSPosed silently
# skips it and all device-card / device-info features fail. Name must stay exact.
-keep class com.ciallo.hyperbackground.appearance.SettingsDeviceModule {
    public <init>();
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}

# Android instantiates these classes from the manifest. Keeping their names
# explicitly also makes the service-binding and settings entry points stable.
-keep class com.ciallo.hyperbackground.HyperBackgroundApp { *; }
-keep class com.ciallo.hyperbackground.ui.MainActivity { *; }
# ContentProvider declared in the manifest; cross-process image / config reads
# go through its authority, so its name must stay exact.
-keep class com.ciallo.hyperbackground.appearance.SettingsAppearanceProvider { *; }

# Preserve metadata used by Compose, Android and libxposed API method shapes.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod
