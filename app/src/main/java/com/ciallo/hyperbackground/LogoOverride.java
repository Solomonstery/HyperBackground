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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 自定义 LOGO：把设置「我的设备」页的小米 OS LOGO 替换成用户导入的 SVG / VectorDrawable / 位图。
 *
 * <p>本类忠实照搬 HyperChanger 的实现（{@code SettingsAppearanceApplier} 的 LOGO 部分
 * 与 {@code SettingsDeviceModule} 的 LOGO hook），仅把配置读取接到本项目的
 * {@link BackgroundContract}/{@link HookRuntime}，其余逻辑与原版一致。
 *
 * <p>与原版一致的是：所有 hook 都通过 libxposed 的 {@code hook(method).intercept{}}
 * （本项目封装为 {@link HookRuntime#hook}）完成，可以在 {@code Resources.getDrawable} 等
 * 方法返回前直接替换返回值——这是 LOGO 能被真正替换掉的关键，与传统 XposedHelpers 的
 * {@code afterHookedMethod} 观察式回调不同。
 *
 * <p>三种模式（对齐原版 {@link BackgroundContract#LOGO_MODE_SYSTEM} 等）：
 * <ul>
 *   <li>系统默认：不替换，恢复原图与材质；</li>
 *   <li>不保留高级材质：换图 + 向上清除 4 层父视图的材质背景；</li>
 *   <li>保留高级材质：换图 + 保留父视图材质背景（建议导入透明矢量图）。</li>
 * </ul>
 */
final class LogoOverride {
    private static final String TAG = "HyperBackground";
    private static final String MY_DEVICE_SETTINGS = "com.android.settings.device.MiuiMyDeviceSettings";

    // 每个 fragment/activity 一个 LogoSession，记录原图与被清除的材质背景以便还原（对齐原版 logoSessions）。
    private static final Map<Object, LogoSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    // 本模块主动 setImageDrawable / 解析素材时置位，用于跳过自身触发的 hook（对齐原版 internalLogo）。
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<>();

    private LogoOverride() {}

    static void install(ClassLoader classLoader) {
        installPersistentLogoHooks();
        installLogoResourceHooks();
        installDeviceLifecycleHooks(classLoader);
    }

    // ---- 生命周期 hook：MiuiMyDeviceSettings.onViewCreated/onResume 后主动应用 LOGO ----
    // 对齐原版 installAppearanceHooks 中 device 分支 + hookLifecycle 的 applyLogo 调用。

    private static void installDeviceLifecycleHooks(ClassLoader classLoader) {
        Class<?> device;
        try {
            device = classLoader.loadClass(MY_DEVICE_SETTINGS);
        } catch (Throwable error) {
            HookRuntime.log("LOGO could not load " + MY_DEVICE_SETTINGS, error);
            return;
        }
        for (Method method : device.getDeclaredMethods()) {
            if (method.getName().equals("onViewCreated") && method.getParameterCount() == 2) {
                hookApplyLogo(method);
            }
        }
        // onResume/onCreate 可能声明在父类，遍历自身+父类（对齐原版 allMethods）。
        Class<?> current = device;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if ((name.equals("onResume") && method.getParameterCount() == 0)
                        || (name.equals("onCreate") && method.getParameterCount() == 1)) {
                    hookApplyLogo(method);
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void hookApplyLogo(Method method) {
        try {
            HookRuntime.hook(method, new HookRuntime.LegacyMethodHook() {
                @Override public void after(HookRuntime.LegacyHookParam param) {
                    if (param.thisObject != null) applyLogo(param.thisObject);
                }
            });
        } catch (Throwable ignored) {
            // 单个方法 hook 失败不影响其它方法。
        }
    }

    /** 主动查找 LOGO 视图，按模式换图并处理材质背景。owner 为 fragment 或 activity（对齐原版 applyLogo）。 */
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
            Drawable drawable = LogoDrawableLoader.load(target.getResources(), readRemoteBytes(config));
            if (drawable == null) {
                HookRuntime.log("LOGO drawable load returned null mime=" + config.mime);
                return;
            }
            LogoSession session = existing;
            if (session == null) {
                session = new LogoSession(target);
                SESSIONS.put(owner, session);
            }
            applyLogoDrawable(target, drawable, config.scale);
            session.applyMaterialPolicy(config.mode == BackgroundContract.LOGO_MODE_NO_ADVANCED_MATERIAL);
        } catch (Throwable error) {
            HookRuntime.log("LOGO apply failed", error);
        }
    }

    private static Context ownerContext(Object owner) {
        if (owner instanceof Activity) return (Activity) owner;
        try {
            Object context = owner.getClass().getMethod("getContext").invoke(owner);
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
            Object view = owner.getClass().getMethod("getView").invoke(owner);
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把自定义图设进 LOGO ImageView，复位后按比例缩放（对齐原版 applyLogoDrawable）。 */
    private static void applyLogoDrawable(ImageView view, Drawable drawable, int scalePercent) {
        INTERNAL.set(Boolean.TRUE);
        try {
            view.setVisibility(View.VISIBLE);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setImageDrawable(drawable);
            float scale = clampScale(scalePercent) / 100f;
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

    // ---- 持久化 setter hook：ImageView.setImageDrawable/setImageResource ----
    // 对齐原版 installPersistentLogoHooks。

    private static void installPersistentLogoHooks() {
        try {
            Method setImageDrawable = ImageView.class.getMethod("setImageDrawable", Drawable.class);
            HookRuntime.hook(setImageDrawable, new HookRuntime.LegacyMethodHook() {
                @Override public void before(HookRuntime.LegacyHookParam param) {
                    if (!(param.thisObject instanceof ImageView)) return;
                    Drawable replacement = logoReplacement((ImageView) param.thisObject);
                    // 换成自定义图后继续原方法（对齐原版 chain.proceedWith(arrayOf(replacement))）。
                    if (replacement != null) param.args[0] = replacement;
                }
            });

            Method setImageResource = ImageView.class.getMethod("setImageResource", int.class);
            HookRuntime.hook(setImageResource, new HookRuntime.LegacyMethodHook() {
                @Override public void before(HookRuntime.LegacyHookParam param) {
                    if (!(param.thisObject instanceof ImageView)) return;
                    ImageView view = (ImageView) param.thisObject;
                    Drawable replacement = logoReplacement(view);
                    // 命中则改走 applyLogoDrawable，并阻止原资源赋图（对齐原版返回 null）。
                    if (replacement != null) {
                        applyLogoDrawable(view, replacement, currentScale());
                        param.setResult(null);
                    }
                }
            });
            HookRuntime.log("Installed persistent Settings logo replacement hooks");
        } catch (Throwable error) {
            HookRuntime.log("Could not hook Settings logo setters", error);
        }
    }

    /** 命中 LOGO ImageView 且已启用替换时返回自定义图，否则 null（对齐原版 logoReplacement）。 */
    private static Drawable logoReplacement(ImageView view) {
        if (Boolean.TRUE.equals(INTERNAL.get())) return null;
        if (!BackgroundContract.PACKAGE_SETTINGS.equals(view.getContext().getPackageName())) return null;
        String idName;
        try {
            idName = view.getResources().getResourceEntryName(view.getId()).toLowerCase();
        } catch (Throwable ignored) {
            idName = "";
        }
        if (!idName.equals("miui_logo_view") && !idName.contains("logo")) return null;
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active()) return null;
        return LogoDrawableLoader.load(view.getResources(), readRemoteBytes(config));
    }

    // ---- 资源层 hook：Resources/Context.getDrawable* 与 View.setBackgroundResource ----
    // 对齐原版 installLogoResourceHooks。

    private static void installLogoResourceHooks() {
        try {
            Method setBackgroundResource = View.class.getMethod("setBackgroundResource", int.class);
            HookRuntime.hook(setBackgroundResource, new HookRuntime.LegacyMethodHook() {
                @Override public void before(HookRuntime.LegacyHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    View view = (View) param.thisObject;
                    int resourceId = (Integer) param.args[0];
                    Drawable replacement = logoResourceReplacement(view.getContext(), view.getResources(), resourceId);
                    if (replacement != null) {
                        view.setBackground(replacement);
                        param.setResult(null);
                    }
                }
            });

            hookDrawableGetter(Resources.class.getMethod("getDrawable", int.class));
            hookDrawableGetter(Resources.class.getMethod("getDrawable", int.class, Resources.Theme.class));
            hookDrawableGetter(Resources.class.getMethod("getDrawableForDensity", int.class, int.class));
            hookDrawableGetter(Resources.class.getMethod(
                    "getDrawableForDensity", int.class, int.class, Resources.Theme.class));

            Method contextGetDrawable = Context.class.getMethod("getDrawable", int.class);
            HookRuntime.hook(contextGetDrawable, new HookRuntime.LegacyMethodHook() {
                @Override public void before(HookRuntime.LegacyHookParam param) {
                    if (!(param.thisObject instanceof Context)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    Context context = (Context) param.thisObject;
                    int resourceId = (Integer) param.args[0];
                    Drawable replacement = logoResourceReplacement(context, context.getResources(), resourceId);
                    if (replacement != null) param.setResult(replacement);
                }
            });
            HookRuntime.log("Installed Settings logo resource replacement hooks");
        } catch (Throwable error) {
            HookRuntime.log("Could not hook Settings logo resource access", error);
        }
    }

    private static void hookDrawableGetter(Method method) {
        try {
            HookRuntime.hook(method, new HookRuntime.LegacyMethodHook() {
                @Override public void before(HookRuntime.LegacyHookParam param) {
                    if (!(param.thisObject instanceof Resources)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    Resources resources = (Resources) param.thisObject;
                    int resourceId = (Integer) param.args[0];
                    Drawable replacement = logoResourceReplacement(null, resources, resourceId);
                    if (replacement != null) param.setResult(replacement);
                }
            });
        } catch (Throwable ignored) {
            // 该重载在当前系统不存在，忽略。
        }
    }

    /**
     * 资源 id 命中 LOGO 资源名时返回按缩放包装的自定义图，否则 null（对齐原版 logoResourceReplacementInternal）。
     * context 允许为 null（Resources.getDrawable 场景），此时用资源包名做校验。
     */
    private static Drawable logoResourceReplacement(Context context, Resources resources, int resourceId) {
        if (Boolean.TRUE.equals(INTERNAL.get())) return null;
        if (resources == null || resourceId == 0) return null;
        if (context != null && !BackgroundContract.PACKAGE_SETTINGS.equals(context.getPackageName())) return null;
        String packageName;
        try {
            packageName = resources.getResourcePackageName(resourceId);
        } catch (Throwable ignored) {
            packageName = null;
        }
        if (packageName != null && !BackgroundContract.PACKAGE_SETTINGS.equals(packageName)) return null;
        String name;
        try {
            name = resources.getResourceEntryName(resourceId).toLowerCase();
        } catch (Throwable ignored) {
            return null;
        }
        BackgroundContract.LogoConfig config = safeConfig();
        if (config == null || !config.active() || !isLogoResource(name, config.mode)) return null;
        Drawable base = LogoDrawableLoader.load(resources, readRemoteBytes(config));
        if (base == null) {
            HookRuntime.log("LOGO resource replacement load failed name=" + name);
            return null;
        }
        return LogoDrawableLoader.withScale(base, config.scale);
    }

    /** 依据模式判断资源名是否为需要替换的小米 OS LOGO（名称集与原版 isLogoResource 对齐）。 */
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

    // ---- 共用：读取素材 + 配置 ----

    private static int currentScale() {
        BackgroundContract.LogoConfig config = safeConfig();
        return config == null ? 100 : config.scale;
    }

    private static int clampScale(int scalePercent) {
        return Math.max(50, Math.min(200, scalePercent));
    }

    private static byte[] readRemoteBytes(BackgroundContract.LogoConfig config) {
        if (config == null) return null;
        ParcelFileDescriptor descriptor = null;
        // 读取跨进程文件不涉及资源 hook，但解析 VectorDrawable 会调 Resources.getDrawable，
        // LogoDrawableLoader.load 内部已在解析期间由调用方通过 INTERNAL 保护；这里仅负责读字节。
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

    /** 记录 LOGO 原始状态并按模式清除/恢复父视图材质背景（对齐原版 LogoSession）。 */
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
