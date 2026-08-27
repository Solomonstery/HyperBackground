package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义 LOGO：把设置「我的设备」页的小米 OS LOGO 替换成用户导入的 SVG / VectorDrawable / 位图。
 *
 * <p>实现完整对齐 HyperChanger 的三种模式，核心是 {@link LogoSession}：
 * LOGO 就是 {@code miui_logo_view}（ImageView），它自身承载图片，其父视图链上带「高级材质」背景。
 * <ul>
 *   <li>系统默认：不替换，还原原图与材质；</li>
 *   <li>不保留高级材质：换图 + 向上清除 4 层父视图的材质背景；</li>
 *   <li>保留高级材质：换图 + 保留父视图材质背景（因此建议导入透明矢量图）。</li>
 * </ul>
 *
 * <p>三条互补路径：
 * <ol>
 *   <li>主路径 {@link #applyLogo}：在 {@code MiuiMyDeviceSettings} 生命周期后主动查找 LOGO 并按模式处理；</li>
 *   <li>{@code ImageView.setImageDrawable/setImageResource} hook：兜底 LOGO 被重新赋图的情况；</li>
 *   <li>{@code Resources/Context.getDrawable*} 与 {@code View.setBackgroundResource} hook：
 *       拦截以资源 id 加载的 LOGO（{@code xiaomi_os_logo*} / {@code provision_os_logo*}）。</li>
 * </ol>
 */
final class LogoOverride {
    private static final String MY_DEVICE_SETTINGS = "com.android.settings.device.MiuiMyDeviceSettings";

    // 每个 fragment/activity 一个 LogoSession，记录原图与被清除的材质背景以便还原。
    private static final Map<Object, LogoSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    // 本模块主动 setImageDrawable / 解析素材时置位，用于跳过自身触发的 hook，防止递归。
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();
    // 缓存已解析的 LOGO，cacheKey 变化时失效。
    private static volatile Drawable cachedDrawable;
    private static volatile String cachedKey;

    private LogoOverride() {}

    static void install(ClassLoader classLoader) {
        hookLogoResources();
        hookBackgroundResource();
        hookImageViewSetters();
        hookDeviceLifecycle(classLoader);
    }

    // ---- 主路径：MiuiMyDeviceSettings 生命周期后主动查找并应用 ----

    private static void hookDeviceLifecycle(ClassLoader classLoader) {
        XC_MethodHook fragmentHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject != null) applyLogo(param.thisObject);
            }
        };
        try {
            Class<?> device = XposedHelpers.findClass(MY_DEVICE_SETTINGS, classLoader);
            XposedHelpers.findAndHookMethod(
                    device, "onViewCreated", View.class, android.os.Bundle.class, fragmentHook);
            try {
                XposedHelpers.findAndHookMethod(device, "onResume", fragmentHook);
            } catch (Throwable ignored) {
                // onResume 不存在时忽略，onViewCreated 已足够。
            }
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO device lifecycle hook failed: " + error);
        }
    }

    /** 主动查找 LOGO 视图，按模式换图并处理材质背景。key 为 fragment 或 activity。 */
    private static void applyLogo(Object owner) {
        try {
            Context context = ownerContext(owner);
            View root = ownerRoot(owner);
            if (context == null || root == null) return;
            if (!BackgroundContract.PACKAGE_SETTINGS.equals(context.getPackageName())) return;
            BackgroundContract.LogoConfig config = safeConfig();
            LogoSession existing = SESSIONS.get(owner);
            ImageView target = existing != null ? existing.view : findLogoView(context, root);
            if (target == null) return;
            if (config == null || !config.active()) {
                if (existing != null) existing.restore();
                SESSIONS.remove(owner);
                return;
            }
            Drawable drawable = resolveDrawable(target.getResources(), config);
            if (drawable == null) return;
            LogoSession session = existing;
            if (session == null) {
                session = new LogoSession(target);
                SESSIONS.put(owner, session);
            }
            applyLogoDrawable(target, drawable, config.scale);
            session.applyMaterialPolicy(config.mode == BackgroundContract.LOGO_MODE_NO_ADVANCED_MATERIAL);
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO apply failed: " + error);
        }
    }

    private static Context ownerContext(Object owner) {
        if (owner instanceof Activity) return (Activity) owner;
        try {
            Object context = XposedHelpers.callMethod(owner, "getContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View ownerRoot(Object owner) {
        if (owner instanceof Activity) {
            return ((Activity) owner).getWindow() != null
                    ? ((Activity) owner).getWindow().getDecorView() : null;
        }
        try {
            Object view = XposedHelpers.callMethod(owner, "getView");
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把自定义图设进 LOGO ImageView，复位后按比例缩放。用 INTERNAL 防止 setImageDrawable hook 递归。 */
    private static void applyLogoDrawable(ImageView view, Drawable drawable, int scalePercent) {
        INTERNAL.set(Boolean.TRUE);
        try {
            view.setVisibility(View.VISIBLE);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setImageDrawable(drawable);
            float scale = Math.max(50, Math.min(200, scalePercent)) / 100f;
            view.setScaleX(scale);
            view.setScaleY(scale);
        } finally {
            INTERNAL.remove();
        }
    }

    private static ImageView findLogoView(Context context, View root) {
        int exactId = context.getResources().getIdentifier("miui_logo_view", "id", context.getPackageName());
        if (exactId != 0) {
            View exact = root.findViewById(exactId);
            if (exact instanceof ImageView) return (ImageView) exact;
        }
        ImageView best = null;
        int bestScore = 0;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof ImageView) {
                int score = logoScore(context, (ImageView) view);
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

    private static int logoScore(Context context, ImageView view) {
        int id = view.getId();
        if (id == View.NO_ID) return 0;
        String name;
        try {
            name = context.getResources().getResourceEntryName(id).toLowerCase();
        } catch (Throwable ignored) {
            return 0;
        }
        if (name.equals("miui_logo_view")) return 100;
        if (name.contains("logo")) return 80;
        if (name.contains("device") && name.contains("image")) return 50;
        return 0;
    }

    // ---- 路径 2：ImageView setter hook ----

    private static void hookImageViewSetters() {
        try {
            XposedHelpers.findAndHookMethod(
                    ImageView.class, "setImageDrawable", Drawable.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(INTERNAL.get())) return;
                            if (!(param.thisObject instanceof ImageView)) return;
                            ImageView view = (ImageView) param.thisObject;
                            Drawable replacement = logoReplacement(view);
                            if (replacement != null) applyLogoDrawable(view, replacement, currentScale());
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    ImageView.class, "setImageResource", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(INTERNAL.get())) return;
                            if (!(param.thisObject instanceof ImageView)) return;
                            ImageView view = (ImageView) param.thisObject;
                            Drawable replacement = logoReplacement(view);
                            if (replacement != null) applyLogoDrawable(view, replacement, currentScale());
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("[HyperBackground] LOGO ImageView hook failed: " + error);
        }
    }

    /** 命中 LOGO ImageView 且已启用替换时返回自定义图，否则 null。 */
    private static Drawable logoReplacement(ImageView view) {
        if (Boolean.TRUE.equals(INTERNAL.get())) return null;
        if (!BackgroundContract.PACKAGE_SETTINGS.equals(view.getContext().getPackageName())) return null;
        int id = view.getId();
        if (id == View.NO_ID) return null;
        String name;
        try {
            name = view.getResources().getResourceEntryName(id).toLowerCase();
        } catch (Throwable ignored) {
            return null;
        }
        if (!name.equals("miui_logo_view") && !name.contains("logo")) return null;
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active()) return null;
        return resolveDrawable(view.getResources(), config);
    }

    // ---- 路径 3：资源层拦截 ----

    private static void hookLogoResources() {
        XC_MethodHook resourcesHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Resources)) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources resources = (Resources) param.thisObject;
                int resourceId = (Integer) param.args[0];
                Drawable replacement = logoResourceReplacement(resources, resourceId);
                if (replacement != null) param.setResult(replacement);
            }
        };
        hookResourcesMethod("getDrawable", resourcesHook, int.class);
        hookResourcesMethod("getDrawable", resourcesHook, int.class, Resources.Theme.class);
        hookResourcesMethod("getDrawableForDensity", resourcesHook, int.class, int.class);
        hookResourcesMethod("getDrawableForDensity", resourcesHook, int.class, int.class, Resources.Theme.class);
        try {
            XposedHelpers.findAndHookMethod(
                    Context.class, "getDrawable", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Context)) return;
                            if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
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

    private static void hookResourcesMethod(String method, XC_MethodHook hook, Object... paramTypes) {
        try {
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = hook;
            XposedHelpers.findAndHookMethod(Resources.class, method, args);
        } catch (Throwable ignored) {
            // 该重载在当前系统不存在，忽略。
        }
    }

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

    /** 资源 id 命中 LOGO 资源名时返回按缩放包装的自定义图，否则 null。 */
    private static Drawable logoResourceReplacement(Resources resources, int resourceId) {
        if (Boolean.TRUE.equals(INTERNAL.get())) return null;
        if (resources == null || resourceId == 0) return null;
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active()) return null;
        String name;
        try {
            name = resources.getResourceEntryName(resourceId).toLowerCase();
        } catch (Throwable ignored) {
            return null;
        }
        if (!isLogoResource(name, config.mode)) return null;
        Drawable base = resolveDrawable(resources, config);
        if (base == null) return null;
        return LogoDrawableLoader.withScale(base, config.scale);
    }

    /** 依据模式判断资源名是否为需要替换的小米 OS LOGO（名称集与 HyperChanger 对齐）。 */
    private static boolean isLogoResource(String name, int mode) {
        if (name == null) return false;
        boolean xiaomi = name.equals("xiaomi_os_logo")
                || name.equals("xiaomi_os_logo_new")
                || name.equals("xiaomi_os_logo_new_lite");
        boolean provision = name.equals("provision_os_logo")
                || name.equals("provision_os_logo_big")
                || name.equals("provision_os_logo_lite")
                || name.equals("provision_os_logo_small");
        switch (mode) {
            case BackgroundContract.LOGO_MODE_NO_ADVANCED_MATERIAL:
                return xiaomi;
            case BackgroundContract.LOGO_MODE_KEEP_ADVANCED_MATERIAL:
                return provision || xiaomi;
            default:
                return false;
        }
    }

    // ---- 共用：解析素材 + 配置 ----

    private static int currentScale() {
        BackgroundContract.LogoConfig config = safeConfig();
        return config == null ? 100 : config.scale;
    }

    private static Drawable resolveDrawable(Resources resources, BackgroundContract.LogoConfig config) {
        String key = config.cacheKey();
        Drawable cached = cachedDrawable;
        if (cached != null && key.equals(cachedKey)) return cached;
        byte[] bytes = readRemoteBytes(config);
        if (bytes == null) return null;
        Drawable base;
        // 解析 VectorDrawable / 位图会读资源，标记为内部调用以跳过资源层 hook，防止递归。
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

    /** 记录 LOGO 原始状态并按模式清除/恢复父视图材质背景。对齐 HyperChanger 的 LogoSession。 */
    private static final class LogoSession {
        final ImageView view;
        private final Drawable originalDrawable;
        private final float originalScaleX;
        private final float originalScaleY;
        private final List<MaterialLayer> materialViews = new ArrayList<>();
        private boolean materialCleared;

        LogoSession(ImageView view) {
            this.view = view;
            this.originalDrawable = view.getDrawable();
            this.originalScaleX = view.getScaleX();
            this.originalScaleY = view.getScaleY();
        }

        /** removeMaterial 为 true（不保留高级材质）时向上清除 4 层父视图背景，否则恢复。 */
        void applyMaterialPolicy(boolean removeMaterial) {
            if (!removeMaterial) {
                restoreMaterial();
                return;
            }
            if (materialCleared) return;
            View current = view;
            for (int i = 0; i < 4 && current != null; i++) {
                if (current != view) {
                    materialViews.add(new MaterialLayer(current, current.getBackground()));
                    current.setBackground(null);
                }
                current = current.getParent() instanceof View ? (View) current.getParent() : null;
            }
            materialCleared = true;
        }

        private void restoreMaterial() {
            for (int i = materialViews.size() - 1; i >= 0; i--) {
                MaterialLayer layer = materialViews.get(i);
                layer.target.setBackground(layer.background);
            }
            materialViews.clear();
            materialCleared = false;
        }

        void restore() {
            INTERNAL.set(Boolean.TRUE);
            try {
                view.setImageDrawable(originalDrawable);
            } finally {
                INTERNAL.remove();
            }
            view.setScaleX(originalScaleX);
            view.setScaleY(originalScaleY);
            restoreMaterial();
        }
    }

    private static final class MaterialLayer {
        final View target;
        final Drawable background;

        MaterialLayer(View target, Drawable background) {
            this.target = target;
            this.background = background;
        }
    }
}
