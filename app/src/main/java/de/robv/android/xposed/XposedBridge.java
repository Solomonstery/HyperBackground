package de.robv.android.xposed;

import com.ciallo.hyperbackground.HookRuntime;

public final class XposedBridge {
    private XposedBridge() {}
    public static void log(String message) { HookRuntime.log(message); }
    public static void log(Throwable error) { HookRuntime.log(error.toString(), error); }
}
