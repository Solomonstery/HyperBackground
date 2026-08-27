package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.FileInputStream;
import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义 LOGO：把设置「我的设备 / 关于本机」页的 LOGO ImageView 替换成用户导入的
 * SVG / VectorDrawable / 位图，并按缩放比例显示。仅对 com.android.settings 生效。
 *
 * <p>两条互补路径：
 * <ol>
 *   <li>全局 hook {@link ImageView#setImageDrawable}/{@code setImageResource}，任何被设图的
 *       LOGO 控件都会被回替；</li>
 *   <li>在 {@code MiuiMyDeviceSettings.onViewCreated} 主动遍历视图树找到 LOGO 控件应用。</li>
 * </ol>
 */
final class LogoOverride {
    // 缓存已解析的 LOGO，避免每次回调都读文件+解析。cacheKey 变化时失效。
    private static volatile Drawable cachedDrawable;
    private static volatile String cachedKey;
    // 记录每个 ImageView 最近一次被本模块替换成的 Drawable，避免 setImageDrawable 递归。
    private static final java.util.Map<ImageView, Drawable> APPLIED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private LogoOverride() {}

    static void install(ClassLoader classLoader) {
        hookImageViewSetters();
        hookDeviceLogoView(classLoader);
    }

    private static void hookImageViewSetters() {
        try {
            XposedHelpers.findAndHookMethod(
                    ImageView.class, "setImageDrawable", Drawable.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof ImageView) applyToView((ImageView) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    ImageView.class, "setImageResource", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof ImageView) applyToView((ImageView) param.thisObject);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO ImageView hook failed: " + error);
        }
    }

    private static void hookDeviceLogoView(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.device.MiuiMyDeviceSettings", classLoader,
                    "onViewCreated", View.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof View) {
                                applyToTree((View) param.args[0]);
                            }
                        }
                    });
        } catch (Throwable error) {
            // 生命周期 hook 已由 SettingsBackgroundHook 覆盖，这里失败不致命。
            XposedBridge.log("[HyperBackground] LOGO device hook failed: " + error);
        }
    }

    /** 从视图树根开始查找 LOGO 控件并替换。 */
    private static void applyToTree(View root) {
        ImageView target = findLogoView(root);
        if (target != null) applyToView(target);
    }

    private static void applyToView(ImageView view) {
        if (view == null || !isLogoView(view)) return;
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active()) return;
        Drawable drawable = resolveDrawable(view.getResources(), config);
        if (drawable == null) return;
        // 避免无限递归：setImageDrawable 会再次触发 hook，用记录表判断是否已是本次替换结果。
        if (APPLIED.get(view) == drawable && view.getDrawable() == drawable) return;
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        APPLIED.put(view, drawable);
        view.setImageDrawable(drawable);
        float scale = config.scale / 100f;
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    private static boolean isLogoView(ImageView view) {
        if (!BackgroundContract.PACKAGE_SETTINGS.equals(view.getContext().getPackageName())) return false;
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(id);
            return name != null && (name.equals("miui_logo_view") || name.toLowerCase().contains("logo"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ImageView findLogoView(View root) {
        if (root == null) return null;
        ImageView best = null;
        int bestScore = 0;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof ImageView) {
                int score = scoreLogoView((ImageView) view);
                if (score > bestScore) {
                    bestScore = score;
                    best = (ImageView) view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return best;
    }

    private static int scoreLogoView(ImageView view) {
        int id = view.getId();
        if (id == View.NO_ID) return 0;
        try {
            String name = view.getResources().getResourceEntryName(id);
            if (name == null) return 0;
            String lower = name.toLowerCase();
            if (lower.equals("miui_logo_view")) return 100;
            if (lower.contains("logo")) return 80;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Drawable resolveDrawable(Resources resources, BackgroundContract.LogoConfig config) {
        String key = config.cacheKey();
        Drawable cached = cachedDrawable;
        if (cached != null && key.equals(cachedKey)) return cached;
        byte[] bytes = readRemoteBytes(config);
        if (bytes == null) return null;
        Drawable base = LogoDrawableLoader.load(resources, bytes);
        if (base == null) return null;
        // View 已经自带 scaleX/Y 缩放，这里不再包一层，直接给原始 Drawable。
        cachedDrawable = base;
        cachedKey = key;
        return base;
    }

    private static byte[] readRemoteBytes(BackgroundContract.LogoConfig config) {
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = config.openFile();
            if (descriptor == null) return null;
            try (InputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
                return out.toByteArray();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static BackgroundContract.LogoConfig safeConfig() {
        try {
            return BackgroundContract.queryLogo();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
