# Loaded by libxposed from META-INF/xposed/java_init.list. Its class name must
# remain exactly the same in release builds.
-keep class com.ciallo.hyperbackground.HookEntry {
    public <init>();
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}

# Android instantiates these classes from the manifest. Keeping their names
# explicitly also makes the service-binding and settings entry points stable.
-keep class com.ciallo.hyperbackground.HyperBackgroundApp { *; }
-keep class com.ciallo.hyperbackground.ui.MainActivity { *; }

# Preserve metadata used by Compose, Android and libxposed API method shapes.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod
