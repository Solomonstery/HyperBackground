package de.robv.android.xposed;

import com.ciallo.hyperbackground.HookRuntime;

public abstract class XC_MethodHook extends HookRuntime.LegacyMethodHook {
    @Override public final void before(HookRuntime.LegacyHookParam param) throws Throwable {
        beforeHookedMethod(new MethodHookParam(param));
    }

    @Override public final void after(HookRuntime.LegacyHookParam param) throws Throwable {
        afterHookedMethod(new MethodHookParam(param));
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        private final HookRuntime.LegacyHookParam delegate;
        public final Object thisObject;
        public final Object[] args;

        MethodHookParam(HookRuntime.LegacyHookParam delegate) {
            this.delegate = delegate;
            this.thisObject = delegate.thisObject;
            this.args = delegate.args;
        }

        public Object getResult() { return delegate.getResult(); }
        public void setResult(Object value) { delegate.setResult(value); }
    }
}
