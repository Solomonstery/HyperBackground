# Xposed entry point is loaded by class name from assets/xposed_init, so R8 must not rename/remove it.
-keep class com.ciallo.hyperbackground.SettingsBackgroundHook { *; }

# Android creates these from the manifest.
-keep class com.ciallo.hyperbackground.ConfigActivity { *; }
-keep class com.ciallo.hyperbackground.BackgroundProvider { *; }

# Preserve annotations/signatures used by Compose and Android runtime metadata.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
