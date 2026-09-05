package com.ciallo.hyperbackground;

import android.content.Context;

/**
 * "清除顶栏"开关的状态查询。
 *
 * <p>清除效果不再通过独立 hook 强制隐藏顶栏实现，而是复用
 * {@link SettingsTopBarBlurHook} 的模糊管线——开启时仅将该管线的强度与透明度归零，
 * 使顶栏完全透明。本开关对设置各级页面（首页与二级页）统一生效。
 */
final class SettingsTopBarClearHook {

    private SettingsTopBarClearHook() {}

    static boolean isClearEnabled() {
        return HookRuntime.preferences().getBoolean(
                BackgroundContract.UI_TOP_CLEAR_ENABLED, false);
    }

    /** 顶栏模糊 hook 仅在设置进程注册，故此处无需再区分页面，开启即对所有设置页生效。 */
    static boolean shouldClear(Context ignored) {
        return isClearEnabled();
    }
}
