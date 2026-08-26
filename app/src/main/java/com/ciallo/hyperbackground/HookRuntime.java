package com.ciallo.hyperbackground;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.lang.reflect.Executable;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class HookRuntime {
    private static volatile XposedModule module;
    private static volatile SharedPreferences preferences;

    private HookRuntime() {}

    static void initialize(XposedModule value, SharedPreferences prefs) {
        module = value;
        preferences = prefs;
    }

    public static SharedPreferences preferences() {
        SharedPreferences value = preferences;
        if (value == null) throw new IllegalStateException("Hook preferences are not initialized");
        return value;
    }

    public static ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        XposedModule value = module;
        if (value == null) throw new FileNotFoundException("Hook module is not initialized");
        return value.openRemoteFile(name);
    }

    public static void log(String message) {
        XposedModule value = module;
        if (value != null) value.log(Log.INFO, "HyperBackground", message);
    }

    public static void log(String message, Throwable error) {
        XposedModule value = module;
        if (value != null) value.log(Log.ERROR, "HyperBackground", message, error);
    }

    public static void hook(Executable executable, LegacyMethodHook callback) {
        XposedModule value = module;
        if (value == null) throw new IllegalStateException("Hook module is not initialized");
        value.hook(executable).intercept(chain -> {
            LegacyHookParam param = new LegacyHookParam(chain);
            callback.before(param);
            Object result;
            if (param.hasResult()) {
                result = param.getResult();
            } else {
                result = chain.proceed(param.args);
            }
            param.setResultFromOriginal(result);
            callback.after(param);
            return param.getResult();
        });
    }

    public abstract static class LegacyMethodHook {
        public void before(LegacyHookParam param) throws Throwable {}
        public void after(LegacyHookParam param) throws Throwable {}
    }

    public static final class LegacyHookParam {
        public final Object thisObject;
        public final Object[] args;
        private Object result;
        private boolean hasResult;

        LegacyHookParam(XposedInterface.Chain chain) {
            this.thisObject = chain.getThisObject();
            List<Object> values = chain.getArgs();
            this.args = values.toArray(new Object[0]);
        }

        public Object getResult() { return result; }
        public boolean hasResult() { return hasResult; }
        public void setResult(Object value) { result = value; hasResult = true; }
        void setResultFromOriginal(Object value) { result = value; }
    }
}
