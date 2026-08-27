package com.ciallo.hyperbackground;

import android.content.Context;
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
 * 自定义 LOGO：把设置「我的设备 / 关于本机」页的小米 OS LOGO 替换成用户导入的
 * SVG / VectorDrawable / 位图，并按缩放比例显示。仅对 com.android.settings 生效。
 *
 * <p>OS4 的 LOGO 主要不是通过 {@code ImageView.setImageDrawable} 设置的，而是以资源 id
 * 的形式（{@code xiaomi_os_logo*} / {@code provision_os_logo*}）经 {@code Resources.getDrawable}
 * 或 {@code View.setBackgroundResource} 加载。因此需要三条互补路径：
 * <ol>
 *   <li>资源层拦截 {@code Resources/Context.getDrawable*}，命中 LOGO 资源名时直接返回自定义图；</li>
 *   <li>拦截 {@code View.setBackgroundResource}，命中 LOGO 资源名时改用自定义图作背景；</li>
 *   <li>hook {@code ImageView.setImageDrawable/setImageResource} 并在 {@code MiuiMyDeviceSettings}
 *       主动遍历视图树，兜底那些以 id（{@code miui_logo_view}）承载 LOGO 的 ImageView。</li>
 * </ol>
 */
final class LogoOverride {
    // 缓存已解析的 LOGO，避免每次回调都读文件+解析。cacheKey 变化时失效。
    private static volatile Drawable cachedDrawable;
    private static volatile String cachedKey;
    // 记录每个 ImageView 最近一次被本模块替换成的 Drawable，避免 setImageDrawable 递归。
    private static final java.util.Map<ImageView, Drawable> APPLIED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    // 本模块自己解析 LOGO 时会读资源，用它标记以跳过资源层 hook，防止递归。
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();

    private LogoOverride() {}

    static void install(ClassLoader classLoader) {
        hookLogoResources();
        hookBackgroundResource();
        hookImageViewSetters();
        hookDeviceLogoView(classLoader);
    }

    // ---- 路径 1：资源层拦截 Resources/Context.getDrawable* ----

    private static void hookLogoResources() {
        XC_MethodHook resourcesHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Resources)) return;
                if (!(param.args.length > 0 && param.args[0] instanceof Integer)) return;
                Resources resources = (Resources) param.thisObject;
                int resourceId = (Integer) param.args[0];
                Drawable replacement = logoResourceReplacement(resources, resourceId);
                if (replacement != null) param.setResult(replacement);
            }
        };
        String[][] resourcesMethods = {
                {"getDrawable", "int"},
                {"getDrawable", "int", "theme"},
                {"getDrawableForDensity", "int", "int"},
                {"getDrawableForDensity", "int", "int", "theme"},
        };
        for (String[] sig : resourcesMethods) {
            try {
                Object[] args = buildArgs(sig, resourcesHook);
                XposedHelpers.findAndHookMethod(Resources.class, sig[0], args);
            } catch (Throwable ignored) {
                // 该重载在当前系统不存在，忽略。
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Context.class, "getDrawable", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Context)) return;
                            if (!(param.args.length > 0 && param.args[0] instanceof Integer)) return;
                            Context context = (Context) param.thisObject;
                            int resourceId = (Integer) param.args[0];
                            Drawable replacement = logoResourceReplacement(context.getResources(), resourceId);
                            if (replacement != null) param.setResult(replacement);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO context.getDrawable hook failed: " + error);
        }
    }

    /** 按 (方法名, 参数类型...) 拼出 findAndHookMethod 的可变参数，末位补 hook。 */
    private static Object[] buildArgs(String[] sig, XC_MethodHook hook) {
        Object[] args = new Object[sig.length]; // sig[0] 是方法名，其余是参数类型，末位放 hook
        for (int i = 1; i < sig.length; i++) {
            switch (sig[i]) {
                case "int":
                    args[i - 1] = int.class;
                    break;
                case "theme":
                    args[i - 1] = Resources.Theme.class;
                    break;
                default:
                    args[i - 1] = int.class;
            }
        }
        args[sig.length - 1] = hook;
        return args;
    }

    // ---- 路径 2：拦截 View.setBackgroundResource ----

    private static void hookBackgroundResource() {
        try {
            XposedHelpers.findAndHookMethod(
                    View.class, "setBackgroundResource", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;
                            View view = (View) param.thisObject;
                            int resourceId = (Integer) param.args[0];
                            Drawable replacement = logoResourceReplacement(view.getResources(), resourceId);
                            if (replacement != null) {
                                view.setBackground(replacement);
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO setBackgroundResource hook failed: " + error);
        }
    }

    /** 若资源 id 是应替换的 LOGO 资源，返回按缩放包装好的自定义 Drawable，否则 null。 */
    private static Drawable logoResourceReplacement(Resources resources, int resourceId) {
        if (Boolean.TRUE.equals(INTERNAL.get())) return null;
        if (resources == null || resourceId == 0) return null;
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active()) return null;
        String name;
        try {
            name = resources.getResourceEntryName(resourceId);
        } catch (Throwable ignored) {
            return null;
        }
        if (!isLogoResource(name, config.mode)) return null;
        Drawable base = resolveDrawable(resources, config);
        if (base == null) return null;
        return LogoDrawableLoader.withScale(base, config.scale);
    }

    /** 依据模式判断资源名是否为需要替换的小米 OS LOGO。 */
    private static boolean isLogoResource(String name, int mode) {
        if (name == null) return false;
        boolean xiaomi = name.equals("xiaomi_os_logo")
                || name.equals("xiaomi_os_logo_new")
                || name.equals("xiaomi_os_logo_dark")
                || name.startsWith("xiaomi_os_logo");
        boolean provision = name.equals("provision_os_logo")
                || name.equals("provision_os_logo_big")
                || name.equals("provision_os_logo_lite")
                || name.equals("provision_os_logo_small")
                || name.startsWith("provision_os_logo");
        switch (mode) {
            case BackgroundContract.LOGO_MODE_NO_ADVANCED_MATERIAL:
                return xiaomi;
            case BackgroundContract.LOGO_MODE_KEEP_ADVANCED_MATERIAL:
                return provision || xiaomi;
            default:
                return false;
        }
    }

    // ---- 路径 3：ImageView setter + 视图树主动查找（兜底 miui_logo_view）----

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
        Drawable base;
        // 解析 VectorDrawable/位图时会读资源，标记为内部调用以跳过资源层 hook，防止递归。
        INTERNAL.set(Boolean.TRUE);
        try {
            base = LogoDrawableLoader.load(resources, bytes);
        } finally {
            INTERNAL.remove();
        }
        if (base == null) return null;
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
