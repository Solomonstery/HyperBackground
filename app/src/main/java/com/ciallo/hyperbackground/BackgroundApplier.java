package com.ciallo.hyperbackground;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class BackgroundApplier {
    // Namespace Xposed additional fields with the stable application id so sessions cannot
    // collide with another module using similar field names inside the hooked process.
    private static final String FIELD_PREFIX = BuildConfig.APPLICATION_ID + ".hook.";
    private static final String HOME_SESSION = FIELD_PREFIX + "home.session";
    private static final String GLOBAL_SESSION = FIELD_PREFIX + "global.session";
    private static final String DEVICE_SESSION = FIELD_PREFIX + "device.session";
    private static final String CONTACTS_SESSION = FIELD_PREFIX + "contacts.session";
    // 拨号盘独立背景层的会话，存到 DialpadLayout 实例上（拨号盘可被反复 inflate/复用）。
    private static final String DIALPAD_SESSION = FIELD_PREFIX + "dialpad.session";
    private static final String CONTACTS_RESCAN = FIELD_PREFIX + "contacts.rescan";
    private static final String CONTACTS_ADAPT_AT = FIELD_PREFIX + "contacts.adapt.at";
    // 清除列表不透明中性色背景前，把原背景存到该 View 的 Xposed 附加字段，便于开关关闭时还原。
    private static final String CONTACTS_BG_SAVED = FIELD_PREFIX + "contacts.bg.saved";
    // 自定义模式把 dialer_background_view 的原生 9-patch 底换成透明前，先存原背景到该字段供切回默认时还原。
    private static final String DIALPAD_BGVIEW_SAVED = FIELD_PREFIX + "dialpad.bgview.saved";
    // 默认模式下承载面板底、可整体调 alpha 的置底纯背景 view（作为 dialpad_container 的第一个子 view）。
    private static final String DIALPAD_PANEL_ALPHA_VIEW = FIELD_PREFIX + "dialpad.panel.alpha.view";
    // 缓存联系人进程内的资源 id（进程内固定），避免每次布局回调都走 getIdentifier 慢查询。-1=未解析。
    private static int contactsBgViewId = -1;
    private static final String DEVICE_ACTIVE = FIELD_PREFIX + "device.active";
    private static final String ORIGINAL_TEXT_COLOR = FIELD_PREFIX + "original.text.color";
    private static final String GLOBAL_DIAGNOSTIC = FIELD_PREFIX + "global.diagnostic";

    private BackgroundApplier() {}

    static void applyHome(Activity activity) {
        if (activity == null) return;
        applyLayer(activity, BackgroundContract.HOME, HOME_SESSION, true);
        applyFontMode(activity);
    }

    static void stopHome(Activity activity) { stopLayer(activity, HOME_SESSION); }

    static void applyGlobal(Activity activity) {
        if (activity == null) return;
        if (shouldSkipGlobal(activity)) {
            diagnostic(activity, "skip " + activity.getClass().getName());
            removeGlobal(activity);
            return;
        }
        applyLayer(activity, BackgroundContract.GLOBAL, GLOBAL_SESSION, false);
    }

    static void stopGlobal(Activity activity) { stopLayer(activity, GLOBAL_SESSION); }

    static void destroyGlobal(Activity activity) { removeGlobal(activity); }

    // 通讯录与拨号主界面（PeopleActivity，拨号盘/联系人共用同一 Activity）独立背景通道。
    static void applyContacts(Activity activity) {
        if (activity == null) return;
        if (!matchesContactsSettings(activity.getClass().getName())) {
            removeContacts(activity);
            return;
        }
        applyLayer(activity, BackgroundContract.CONTACTS, CONTACTS_SESSION, false);
        adaptContactsSurfaces(activity, false);
        installContactsSurfaceRescan(activity);
    }

    // 拨号盘 / 列表适配：
    //  1) 拨号盘背景板（dialer_background_view，背景是 dialer_background_new 9-patch，浅/深色都不透明）
    //     整体设 alpha 让背景透出，同时不碰装数字键的 dialpad_container，保证按键清晰可读。
    //  2) 联系人列表遮挡背景的不透明中性色（黑/白/灰）背景层——深色下现有逻辑已透出，但浅色下列表
    //     条目（HyperCellLayout content_layout）、列表容器（drawer_layout）等会铺满不透明白，挡住背景。
    //     遍历视图树，对「不透明中性色」背景的 View 清除背景（深浅通吃），保留半透明卡片/渐变遮罩不动。
    //     列表条目随 RecyclerView 滚动复用重建，靠常驻布局监听持续补清。
    // throttled=true 时对高频布局回调做 200ms 节流，避免滚动列表时反复无谓执行。
    private static void adaptContactsSurfaces(Activity activity, boolean throttled) {
        try {
            if (throttled) {
                Long last = (Long) XposedHelpers.getAdditionalInstanceField(activity, CONTACTS_ADAPT_AT);
                long now = android.os.SystemClock.uptimeMillis();
                if (last != null && now - last < 200L) return;
                XposedHelpers.setAdditionalInstanceField(activity, CONTACTS_ADAPT_AT, now);
            }

            boolean enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true);

            // 资源 id 进程内固定，只解析一次后缓存复用。
            if (contactsBgViewId == -1) contactsBgViewId = resolveId(activity, "dialer_background_view");

            // 拨号盘的透明度 / 自定义背景全部由 applyDialpadOnInflate（Hook DialpadLayout.onFinishInflate）
            // 在首帧前一次性处理，这里不再逐帧 setAlpha——否则会与 inflate 时的设置反复抢夺、造成闪屏，
            // 且逐帧 setAlpha 也会把自定义模式下归零的原生底又冒出来盖住自定义图。此处仅取 bgView 句柄用于
            // 下面遍历清除时跳过它及其整棵子树（含自定义 media / 9-patch 原生底）。
            View bgView = contactsBgViewId == 0 ? null : activity.findViewById(contactsBgViewId);

            // 只从内容层（android.R.id.content）往下清，绝不碰 DecorView / content_parent 等窗口级
            // 顶层容器——那些不透明中性底是整个窗口的“实底”，抹成透明会让多任务缩放动画时穿透看到
            // 底层桌面/上个应用（频闪），且破坏页面明度层次（观感变暗变平）。
            // 同时跳过拨号盘背景板 bgView 及其子树：它由上面的 setAlpha 专门调透明度，若被通用中性底
            // 清除逻辑把背景换成透明，setAlpha 滑块就再无视觉效果（键盘恒定透明），即透明度调节失效。
            View content = activity.findViewById(android.R.id.content);
            if (content != null) adaptContactsOpaqueSurfaces(content, enabled, content, bgView);

            // 搜索是 Miuix SearchActionMode 拉起的覆盖层（ContactsSearchFragment 的 DispatchFrameLayout），
            // 挂在 DecorView 下、android.R.id.content 之外，故上面按 content 收窄的遍历扫不到它——搜索后
            // 结果列表里某层不透明白容器会挡住背景（上半白、下半透出的分界即源于此）。这里按 Miuix 框架
            // id search_mask 从 DecorView 定位该覆盖层，只清其内部子树（跳过 mask 自身：那是设计用的搜索
            // 遮罩层，且清它无意义），把搜索结果列表里的不透明中性底一并透出。search_mask 未出现时（未进入
            // 搜索）findViewById 返回 null，直接跳过。
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) {
                int maskId = resolveFrameworkId(activity, "search_mask");
                View searchMask = maskId == 0 ? null : decor.findViewById(maskId);
                if (searchMask instanceof ViewGroup) {
                    ViewGroup maskGroup = (ViewGroup) searchMask;
                    for (int i = 0; i < maskGroup.getChildCount(); i++) {
                        adaptContactsOpaqueSurfaces(maskGroup.getChildAt(i), enabled, null, bgView);
                    }
                }
            }
        } catch (Throwable error) { log("adaptContactsSurfaces", error); }
    }

    // 解析 Miuix / 框架层的资源 id（如 search_mask）。这些 id 定义在 miuix appcompat 库里，运行期在
    // 联系人进程内以其包名注册，用 getIdentifier 查询；查不到返回 0。
    private static int resolveFrameworkId(Activity activity, String name) {
        try {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            if (id != 0) return id;
            return activity.getResources().getIdentifier(name, "id", "android");
        } catch (Throwable ignored) { return 0; }
    }

    // 递归遍历：enabled 时把采样为「不透明中性色（黑/白/灰）」的背景替换为透明占位（清前把原背景存到
    // 该 View 的 Xposed 附加字段以便还原），disabled 时还原。只处理不透明中性色，半透明卡片/渐变遮罩
    // （如 #b3000000 输入框、#80ffffff 渐变、#cc000000）不动，保留其层次；全透明背景（#0）本就不挡，跳过。
    // contentRoot 是 android.R.id.content 本身——它是内容层实底，透明化会让整页失去底色、动画时穿透，
    // 故跳过其自身背景，只清它内部的列表条目/容器等中间层。
    // skip 是拨号盘背景板 dialer_background_view——由 setAlpha 专门处理，跳过其自身及整棵子树，
    // 避免其 9-patch 背景被清成透明导致透明度滑块失效。
    // 关键：用透明 ColorDrawable 占位而非 setBackground(null)——列表条目随 RecyclerView 复用滚动，
    // 若清成 null 会失去覆盖整块区域的背景，硬件加速脏区重绘无法擦除上一帧内容而留下残影/拖拽；
    // 保留一个铺满的透明背景即可让绘制系统正常重绘，同时背景仍透出。
    private static void adaptContactsOpaqueSurfaces(View view, boolean enabled, View contentRoot, View skip) {
        if (view == null) return;
        if (view == skip) return;
        if (view != contentRoot) {
        try {
            if (enabled) {
                Drawable bg = view.getBackground();
                // 列表条目随 RecyclerView 复用可能被重新赋上不透明白底：只要当前背景仍是不透明中性色就替换；
                // saved 仅在首次记录原始背景（供还原），后续复用不覆盖它。
                if (bg != null && isOpaqueNeutralSurface(bg)) {
                    Object saved = XposedHelpers.getAdditionalInstanceField(view, CONTACTS_BG_SAVED);
                    if (saved == null) XposedHelpers.setAdditionalInstanceField(view, CONTACTS_BG_SAVED, bg);
                    view.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                }
            } else {
                Object saved = XposedHelpers.getAdditionalInstanceField(view, CONTACTS_BG_SAVED);
                if (saved instanceof Drawable) {
                    view.setBackground((Drawable) saved);
                    XposedHelpers.removeAdditionalInstanceField(view, CONTACTS_BG_SAVED);
                }
            }
        } catch (Throwable ignored) {}
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) adaptContactsOpaqueSurfaces(g.getChildAt(i), enabled, contentRoot, skip);
        }
    }

    // 背景采样为完全不透明（alpha=255）且中性（R≈G≈B，无明显色相）：黑 / 白 / 灰。
    // ColorDrawable 直接读色；其它（GradientDrawable/StateListDrawable/LayerDrawable/9-patch）
    // 渲染 COPY 到 1x1 bitmap 采其合成色，绝不改动原 drawable。
    private static boolean isOpaqueNeutralSurface(Drawable bg) {
        if (bg == null) return false;
        int color;
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
        } else {
            try {
                Drawable.ConstantState state = bg.getConstantState();
                if (state == null) return false;
                Drawable copy = state.newDrawable().mutate();
                // 渲染 8x8 后取中心像素，而非 1x1。9-patch（如分组吸顶头 list_view_item_group_header_bg）
                // 的可拉伸区/内容区划分会让 1x1 采样落到边缘透明 padding 区，深色不透明黑条被误判为透明
                // 而漏清；用稍大的画布取中心点采到真正的填充色，判定才准确。
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                copy.setBounds(0, 0, 8, 8);
                copy.draw(canvas);
                color = bmp.getPixel(4, 4);
                bmp.recycle();
            } catch (Throwable ignored) { return false; }
        }
        if (Color.alpha(color) != 255) return false;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) <= 24;
    }

    // 拨号盘键盘是点击后异步 inflate 的，Activity 生命周期回调抓不到它出现的那一刻；挂一个
    // 常驻的轻量布局监听（带 200ms 节流），在其出现时补设 alpha / 清一次列表底，保证展开即生效。
    private static void installContactsSurfaceRescan(final Activity activity) {
        try {
            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(activity, CONTACTS_RESCAN))) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            final android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer == null || !observer.isAlive()) return;
            android.view.ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                adaptContactsSurfaces(activity, true);
            };
            observer.addOnGlobalLayoutListener(listener);
            // 保存引用，便于 Activity 销毁时摘除，避免监听器悬挂。
            XposedHelpers.setAdditionalInstanceField(activity, CONTACTS_RESCAN, listener);
        } catch (Throwable error) { log("installContactsSurfaceRescan", error); }
    }

    private static void removeContactsSurfaceRescan(Activity activity) {
        try {
            Object listener = XposedHelpers.getAdditionalInstanceField(activity, CONTACTS_RESCAN);
            if (!(listener instanceof android.view.ViewTreeObserver.OnGlobalLayoutListener)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) {
                android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(
                            (android.view.ViewTreeObserver.OnGlobalLayoutListener) listener);
                }
            }
            XposedHelpers.setAdditionalInstanceField(activity, CONTACTS_RESCAN, null);
        } catch (Throwable ignored) { }
    }

    private static int resolveId(Activity activity, String name) {
        try {
            return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        } catch (Throwable ignored) { return 0; }
    }

    static void stopContacts(Activity activity) { stopLayer(activity, CONTACTS_SESSION); }

    static void destroyContacts(Activity activity) { removeContacts(activity); }

    // 拨号盘键盘由 ViewStub 点击后异步 inflate，Activity 生命周期回调抓不到它「刚 inflate、绘制第一帧
    // 之前」的时机，只能靠布局监听在其出现后补设，但布局回调总在绘制后一帧，导致先露出原生不透明底色、
    // 再变半透（先灰后透闪烁）。这里由 Hook DialpadLayout.onFinishInflate（after）在首帧绘制前同步处理。
    //
    // DialpadLayout 实际结构（见 dialer_dialpad.xml）：
    //   DialpadLayout
    //   ├─[0] FrameLayout  dialer_background_view   bg=dialer_background_new （整块拨号盘底，9-patch）
    //   ├─[1] LinearLayout dialpad_container        bg=dialer_background_pad （键盘面板底，含数字键；不透明，盖住[0]）
    //   └─[2] FrameLayout  dialer_input_container   输入框
    // 关键：只把 dialer_background_view 设透明/隐藏并不够——dialpad_container 自己那层不透明的
    // dialer_background_pad 会把下面全挡住（这是之前自定义图“不生效”的根因）。故两层底都要处理。
    //
    //  · 默认模式：dialer_background_view 与 dialpad_container 两层底一起按 opacity 设 alpha，让背景透出，
    //    数字键（dialpad_keys_container 的子 view，各自有 alpha=1）不受容器 alpha 影响仍清晰。
    //  · 自定义模式：把用户选的独立背景（BackgroundMediaView）塞进 dialer_background_view 内铺满，
    //    并把 dialpad_container 的面板底 dialer_background_pad 换成透明占位（存原背景供还原），
    //    让自定义图透出到整个键盘区，与 contacts 整页背景叠加共存。
    // dialpadView 是 DialpadLayout 实例本身。
    static void applyDialpadOnInflate(View dialpadView) {
        if (!(dialpadView instanceof ViewGroup)) return;
        try {
            ViewGroup dialpad = (ViewGroup) dialpadView;
            Context ctx = dialpad.getContext();
            boolean enabled = HookRuntime.preferences().getBoolean(BackgroundContract.CONTACTS_SURFACE_ADAPT, true);
            int opacity = HookRuntime.preferences().getInt(BackgroundContract.CONTACTS_DIALPAD_OPACITY, 60);
            float padAlpha = Math.max(0, Math.min(100, opacity)) / 100f;
            int mode = HookRuntime.preferences().getInt(
                    BackgroundContract.CONTACTS_DIALPAD_BG_MODE, BackgroundContract.CONTACTS_DIALPAD_BG_DEFAULT);

            String pkg = ctx.getPackageName();
            int bgId = ctx.getResources().getIdentifier("dialer_background_view", "id", pkg);
            int containerId = ctx.getResources().getIdentifier("dialpad_container", "id", pkg);
            View bgView = bgId == 0 ? null : dialpad.findViewById(bgId);
            View container = containerId == 0 ? null : dialpad.findViewById(containerId);
            ViewGroup bgHost = bgView instanceof ViewGroup ? (ViewGroup) bgView : dialpad;

            BackgroundContract.Source source = BackgroundContract.query(ctx, BackgroundContract.CONTACTS_DIALPAD);
            // 拨号盘背景仅支持图片：新选图入口已限定 image/*，此处再兜底排除历史遗留的视频配置，
            // 视频源直接回退默认模式、不在拨号盘播放。
            boolean custom = enabled && mode == BackgroundContract.CONTACTS_DIALPAD_BG_CUSTOM
                    && source.exists && !source.isVideo();

            // 先清旧会话：拨号盘复用时避免叠加多层，并还原上次改动的面板底。
            removeDialpadMedia(dialpad);

            if (custom) {
                // 自定义图塞进 dialer_background_view 内铺满（置底、不挡数字键）；原生 9-patch 底随
                // 背景板 alpha 归零而隐去；面板底 dialer_background_pad 换透明占位让图透出。
                BackgroundMediaView media = new BackgroundMediaView(ctx, source);
                // 拨号盘键盘面板不透明度滑块也作用于自定义图：与该图自身 opacity 叠乘，滑块不再失效。
                media.setAlpha((enabled ? padAlpha : 1f) * (source.opacity / 100f));
                // 还原被原生底裁出的顶部圆角（dialer_background_pad 顶角 30dp、底角为直角），否则图是直角矩形。
                clipDialpadCorners(media, ctx);
                bgHost.addView(media, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                XposedHelpers.setAdditionalInstanceField(dialpad, DIALPAD_SESSION, media);
                // 先存原生 9-patch 底（首次），再换透明，让自定义图透出且切回默认能还原原底。
                if (bgView != null) {
                    Drawable orig = bgView.getBackground();
                    if (orig != null && XposedHelpers.getAdditionalInstanceField(bgView, DIALPAD_BGVIEW_SAVED) == null) {
                        XposedHelpers.setAdditionalInstanceField(bgView, DIALPAD_BGVIEW_SAVED, orig);
                    }
                    bgView.setAlpha(1f);
                    bgView.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                }
                clearDialpadPanelBackground(container);
            } else {
                // 默认模式：先复位可能被上次自定义模式改动的背景 / 面板底。
                float a = enabled ? padAlpha : 1f;
                if (bgView != null) {
                    bgView.setAlpha(1f);
                    restoreDialpadBgView(bgView);
                }
                restoreDialpadPanelBackground(container);
                // 面板底 dialer_background_pad 是 9-patch，drawable.setAlpha() 视觉上不生效，导致
                // “面板不透明度”滑块失效。改为：把面板底搬到一个新插入的置底纯背景 view 上，对该 view
                // 本身 setAlpha —— view.setAlpha 确定有效；数字键是 dialpad_container 的子 view、不在
                // 这个背景 view 下，故不受连累（避免 beta5 “数字键也跟着透”的老问题）。
                if (container != null) {
                    container.setAlpha(1f);
                    applyDialpadPanelAlpha(container, a);
                }
                // 诊断：布局稳定后 dump 拨号盘整棵子树的 view + 背景类型 + 尺寸，定位“面板底”真正挂在哪个
                // view（排查默认模式面板不透明度无效）。只在默认模式抓一次。
                dialpad.post(() -> dumpDialpadTree(dialpad, 0));
            }
        } catch (Throwable error) { log("applyDialpadOnInflate", error); }
    }

    // 给自定义背景图裁出与原生面板底一致的圆角：顶部左右 30dp、底部直角。整块拨号盘底本身贴屏幕
    // 下缘、底部无圆角，只需圆顶。用 ViewOutlineProvider + setClipToOutline 硬件裁切，随视图尺寸自适应。
    private static void clipDialpadCorners(View media, Context ctx) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            final float topRadius = 30f * density;
            media.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) return;
                    // 轮廓下沿超出底边 topRadius，使底部两角落在视图外→呈直角，仅保留顶部圆角。
                    outline.setRoundRect(0, 0, w, (int) (h + topRadius), topRadius);
                }
            });
            media.setClipToOutline(true);
        } catch (Throwable ignored) {}
    }

    // 诊断：递归打印拨号盘子树，输出每个 view 的 id 名、类、尺寸、背景 drawable 类型与 alpha，
    // 用于定位“面板底 / 灰底”究竟挂在哪个 view 上（排查默认模式面板不透明度无效）。
    private static void dumpDialpadTree(View view, int depth) {
        if (view == null) return;
        try {
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) indent.append("  ");
            String idName;
            try {
                idName = view.getId() == View.NO_ID ? "no-id"
                        : view.getResources().getResourceEntryName(view.getId());
            } catch (Throwable t) { idName = "id?"; }
            Drawable bg = view.getBackground();
            String bgInfo = "null";
            if (bg != null) {
                String alpha;
                try { alpha = String.valueOf(bg.getAlpha()); } catch (Throwable t) { alpha = "n/a"; }
                bgInfo = bg.getClass().getSimpleName() + "(alpha=" + alpha + ")";
            }
            HookRuntime.log("HyperBG-PANEL " + indent + idName + " " + view.getClass().getSimpleName()
                    + " " + view.getWidth() + "x" + view.getHeight()
                    + " vAlpha=" + view.getAlpha() + " bg=" + bgInfo);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    dumpDialpadTree(group.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {}
    }

    // 默认模式下让「面板不透明度」滑块生效：面板底 dialer_background_pad 是 9-patch，直接对 drawable
    // setAlpha 视觉不生效，故把面板底搬到 dialpad_container 内一个置底的纯背景 view 上，对该 view 本身
    // setAlpha（确定有效）。数字键是 container 的其它子 view、层级在此背景 view 之上，故不受影响。
    private static void applyDialpadPanelAlpha(View container, float alpha) {
        if (!(container instanceof ViewGroup)) return;
        try {
            ViewGroup group = (ViewGroup) container;
            float a = Math.max(0f, Math.min(1f, alpha));
            View panel = null;
            Object saved = XposedHelpers.getAdditionalInstanceField(container, DIALPAD_PANEL_ALPHA_VIEW);
            if (saved instanceof View && ((View) saved).getParent() == group) {
                panel = (View) saved; // 复用已插入的背景 view，避免重复叠加
            }
            if (panel == null) {
                Drawable bg = container.getBackground();
                if (bg == null) return; // 没有面板底可搬，无需处理
                // 记录原面板底供切回还原，再把 container 自身背景清透明、交给背景 view 承载。
                if (XposedHelpers.getAdditionalInstanceField(container, CONTACTS_BG_SAVED) == null) {
                    XposedHelpers.setAdditionalInstanceField(container, CONTACTS_BG_SAVED, bg);
                }
                container.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                panel = new View(container.getContext());
                panel.setBackground(bg);
                group.addView(panel, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                XposedHelpers.setAdditionalInstanceField(container, DIALPAD_PANEL_ALPHA_VIEW, panel);
            }
            panel.setAlpha(a);
        } catch (Throwable ignored) {}
    }

    // 把自定义模式下换成透明的 dialer_background_view 原生 9-patch 底还原回去（若曾保存）。
    private static void restoreDialpadBgView(View bgView) {
        if (bgView == null) return;
        try {
            Object saved = XposedHelpers.getAdditionalInstanceField(bgView, DIALPAD_BGVIEW_SAVED);
            if (saved instanceof Drawable) {
                bgView.setBackground((Drawable) saved);
                XposedHelpers.removeAdditionalInstanceField(bgView, DIALPAD_BGVIEW_SAVED);
            }
        } catch (Throwable ignored) {}
    }

    // 自定义模式下把 dialpad_container 的不透明面板底换成透明占位（首次记录原背景供还原）。
    private static void clearDialpadPanelBackground(View container) {
        if (container == null) return;
        try {
            Drawable bg = container.getBackground();
            if (bg != null) {
                Object saved = XposedHelpers.getAdditionalInstanceField(container, CONTACTS_BG_SAVED);
                if (saved == null) XposedHelpers.setAdditionalInstanceField(container, CONTACTS_BG_SAVED, bg);
                container.setBackground(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
            container.setAlpha(1f);
        } catch (Throwable ignored) {}
    }

    private static void restoreDialpadPanelBackground(View container) {
        if (container == null) return;
        try {
            // 先撤销默认模式插入的置底面板背景 view：移除它并把面板底还给 container 自身。
            Object panel = XposedHelpers.getAdditionalInstanceField(container, DIALPAD_PANEL_ALPHA_VIEW);
            if (panel instanceof View) {
                View pv = (View) panel;
                if (pv.getParent() instanceof ViewGroup) ((ViewGroup) pv.getParent()).removeView(pv);
                XposedHelpers.removeAdditionalInstanceField(container, DIALPAD_PANEL_ALPHA_VIEW);
            }
            Object saved = XposedHelpers.getAdditionalInstanceField(container, CONTACTS_BG_SAVED);
            if (saved instanceof Drawable) {
                container.setBackground((Drawable) saved);
                XposedHelpers.removeAdditionalInstanceField(container, CONTACTS_BG_SAVED);
            }
        } catch (Throwable ignored) {}
    }

    // 移除拨号盘上已叠加的自定义背景层（若有）。原生底 / 面板底的复位由调用方按当前模式重设。
    private static void removeDialpadMedia(ViewGroup dialpad) {
        try {
            Object old = XposedHelpers.getAdditionalInstanceField(dialpad, DIALPAD_SESSION);
            if (old instanceof BackgroundMediaView) {
                BackgroundMediaView media = (BackgroundMediaView) old;
                if (media.getParent() instanceof ViewGroup) ((ViewGroup) media.getParent()).removeView(media);
                media.dispose();
            }
            XposedHelpers.removeAdditionalInstanceField(dialpad, DIALPAD_SESSION);
        } catch (Throwable ignored) {}
    }

    private static void removeContacts(Activity activity) {
        if (activity == null) return;
        try {
            removeContactsSurfaceRescan(activity);
            LayerSession old = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, CONTACTS_SESSION);
            if (old != null) removeLayer(activity, CONTACTS_SESSION, old);
        } catch (Throwable error) { log("removeContacts", error); }
    }

    static void enterDevice(Activity activity) {
        if (activity == null) return;
        XposedHelpers.setAdditionalInstanceField(activity, DEVICE_ACTIVE, Boolean.TRUE);
        removeGlobal(activity);
    }

    static void leaveDevice(Activity activity) {
        if (activity == null) return;
        XposedHelpers.removeAdditionalInstanceField(activity, DEVICE_ACTIVE);
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null && !activity.isFinishing()) decor.post(() -> applyGlobal(activity));
        } catch (Throwable ignored) {}
    }

    private static boolean shouldSkipGlobal(Activity activity) {
        String packageName = activity.getPackageName();
        String className = activity.getClass().getName();

        // Keep permission / authorization / transient confirmation windows fully native.
        if (isSensitiveTransientActivity(className) || isSensitiveTransientWindow(activity)) return true;

        if (BackgroundContract.PACKAGE_SETTINGS.equals(packageName)) {
            if ("com.android.settings.MiuiSettings".equals(className)) return true;
            return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(activity, DEVICE_ACTIVE));
        }

        // Only known full-screen settings surfaces are accepted in cross-package processes.
        // Pairing, login, permission, payment and other transient/sensitive windows stay native.
        if (BackgroundContract.PACKAGE_MILINK.equals(packageName)) {
            return !(className.contains(".ui.connectivitysettings.")
                    || className.equals("com.milink.ui.setting.SettingActivity")
                    || className.endsWith(".NetWorkingActivity"));
        }

        if (BackgroundContract.PACKAGE_PHONE.equals(packageName)) {
            return !matchesPhoneSettings(className);
        }

        if (BackgroundContract.PACKAGE_ACCOUNT.equals(packageName)) {
            return !matchesAccountSettings(className);
        }

        if (BackgroundContract.PACKAGE_THEME_MANAGER.equals(packageName)) {
            return !matchesThemeSettings(className);
        }

        if (BackgroundContract.PACKAGE_HOME.equals(packageName)) {
            return !matchesHomeSettings(className);
        }

        if (BackgroundContract.PACKAGE_SECURITY_CENTER.equals(packageName)) {
            return !matchesSecurityCenterSettings(className);
        }

        if (BackgroundContract.PACKAGE_POWER_KEEPER.equals(packageName)) {
            // PowerKeeper is scoped only after the full-screen/transient-window checks above.
            return false;
        }

        if (BackgroundContract.PACKAGE_MI_SETTINGS.equals(packageName)) {
            return !matchesMiSettings(className);
        }

        // 通讯录与拨号由独立的 contacts 通道处理，global 一律跳过。
        if (BackgroundContract.PACKAGE_CONTACTS.equals(packageName)) {
            return true;
        }

        return true;
    }

    private static boolean isSensitiveTransientWindow(Activity activity) {
        if (activity == null) return false;
        TypedArray a = null;
        try {
            int[] attrs = new int[] { android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating };
            a = activity.obtainStyledAttributes(attrs);
            if (a.getBoolean(0, false) || a.getBoolean(1, false)) return true;
        } catch (Throwable ignored) {
        } finally {
            if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
        }
        try {
            Window w = activity.getWindow();
            if (w != null) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                if (lp != null && (lp.width != android.view.WindowManager.LayoutParams.MATCH_PARENT
                        || lp.height != android.view.WindowManager.LayoutParams.MATCH_PARENT)
                        && lp.width > 0 && lp.height > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isSensitiveTransientActivity(String className) {
        if (className == null) return false;
        String n = className.toLowerCase();
        return n.contains("permissionactivity")
                || n.contains("requirepermission")
                || n.contains("permissiondialog")
                || n.contains("authorization")
                || n.contains("authorize")
                || n.contains("accesscheckactivity")
                || n.contains("confirmcredential")
                || n.contains("credential")
                || n.contains("password")
                || n.contains("pinactivity")
                || n.contains("payment")
                || n.contains("wallet")
                || n.contains("login")
                || n.contains("signin")
                || n.contains("oauth")
                || n.contains("passport")
                || n.contains("emergency")
                || n.contains("dialer")
                || n.contains("incall")
                || n.contains("confirmdialog")
                || n.contains("grant")
                || n.endsWith("ctaactivity")
                || n.contains("transparentactivity")
                || n.contains("dialogactivity");
    }

    private static boolean matchesPhoneSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.startsWith("com.android.phone.settings.")
                || n.contains("setting")
                || n.contains("calloptions")
                || n.contains("callbarringoptions")
                || n.contains("callfeaturessetting")
                || n.contains("callforwardtype")
                || n.contains("callforwardoptions")
                || n.contains("additionalcalloptions")
                || n.endsWith("fivegnrcasettingactivity")
                || n.endsWith("nrdisplayactivity");
    }

    private static boolean matchesAccountSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("accountsettings")
                || n.contains("accountsecurity")
                || n.contains("agreementandprivacy")
                || n.contains("systemadactivity")
                || n.contains("userdetailinfo")
                || n.contains("userphoneinfo")
                || n.contains("devicesettinglist")
                || n.contains("devicedetailinfo")
                || n.contains("snslistactivity")
                || n.contains("snsaccountactivity");
    }

    private static boolean matchesThemeSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("themesettings")
                || n.contains("themepreference")
                || n.contains("themeabout")
                || n.endsWith(".activity.themetabactivity")
                || n.contains("themeandwallpaper")
                || n.contains("wallpapersettings")
                || n.contains("wallpapersubsetting")
                || n.contains("wallpapertabactivity")
                || n.contains("wallpapermiuitab")
                || n.contains("privacysettings")
                || n.contains("authoritymanagement")
                || n.contains("supportthemeactivity")
                || n.contains("personalize")
                || n.contains("aifromsettings");
    }

    private static boolean matchesHomeSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("settingsactivity")
                || n.contains("homesettings")
                || n.contains("launchersettings");
    }

    private static boolean matchesSecurityCenterSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains(".settings.")
                || n.contains("setting")
                || n.contains("power")
                || n.contains("battery")
                || n.contains("autostart")
                || n.contains("appmanager")
                || n.contains("privacy")
                || n.contains("permission")
                || n.contains("networkassistant")
                || n.contains("garbage")
                || n.contains("optimiz");
    }

    private static boolean matchesMiSettings(String className) {
        String n = className == null ? "" : className.toLowerCase();
        return n.contains("healthy")
                || n.contains("usagestat")
                || n.contains("focusmode")
                || n.contains("devicelimit")
                || n.contains("appusage")
                || n.contains("screen")
                || n.contains("settings");
    }

    // 通讯录与拨号的拨号盘/联系人/最近通话主界面统一由 PeopleActivity 承载
    // （TwelveKeyDialer/ContactsFrontDoor 等均为其 alias），只对该主界面注入背景，
    // 天然排除编辑、来电、快速联系卡、权限弹窗等其它页面。
    private static boolean matchesContactsSettings(String className) {
        return className != null && className.equals("com.android.contacts.activities.PeopleActivity");
    }

    private static void removeGlobal(Activity activity) {
        if (activity == null) return;
        try {
            LayerSession old = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, GLOBAL_SESSION);
            if (old != null) removeLayer(activity, GLOBAL_SESSION, old);
        } catch (Throwable error) { log("removeGlobal", error); }
    }

    private static void applyLayer(Activity activity, String slot, String fieldKey, boolean home) {
        try {
            BackgroundContract.Source source = BackgroundContract.query(activity, slot);
            LayerSession old = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, fieldKey);
            if (!source.exists) {
                if (BackgroundContract.GLOBAL.equals(slot)) {
                    diagnostic(activity, "source-missing remote-file=" + BackgroundContract.remoteMediaName(slot));
                }
                if (old != null) removeLayer(activity, fieldKey, old);
                return;
            }

            View contentView = activity.findViewById(android.R.id.content);
            if (!(contentView instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) contentView;
            ViewGroup host = selectLayerHost(activity, content, home);
            boolean transparentTopBar = host != content;

            // Keep the media in android.R.id.content. This is the path already verified on
            // device interconnection. Miuix secondary pages are the one deliberate exception:
            // their expanded action bar is a sibling of android.R.id.content, so the media must
            // sit one level higher in the known ActionBarOverlayLayout to continue behind the
            // status bar, back button and large title. We never promote to DecorView.
            if (old != null && old.media.sourceKey().equals(source.cacheKey())
                    && old.media.getParent() == host && old.observedRoot == host) {
                old.media.onHostResume();
                old.refresh(activity, home);
                return;
            }
            if (old != null) removeLayer(activity, fieldKey, old);
            View originalRoot = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            BackgroundMediaView media = new BackgroundMediaView(activity, source);
            ViewGroup.LayoutParams mediaParams = host instanceof FrameLayout
                    ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

            LayerSession session = new LayerSession(media);
            if (host != content) session.clear(host);
            session.clear(content);
            if (originalRoot != null) session.clear(originalRoot);
            clearNamed(activity, session, "nestedheaderlayout");
            clearNamed(activity, session, "nested_header_layout");
            clearNamed(activity, session, "scroll_headers");
            clearNamed(activity, session, "main_content");
            if (!home) {
                clearNamed(activity, session, "prefs_container");
                clearNamed(activity, session, "preference_recyclerview");
                clearNamed(activity, session, "recycler_view");
                clearNamed(activity, session, "content");
                clearNamed(activity, session, "content_view");
                clearNamed(activity, session, "content_wrapper");
                clearNamed(activity, session, "action_bar_activity_content");
                clearNamed(activity, session, "area_content");
                clearNamed(activity, session, "auto_content");
            }
            host.addView(media, 0, mediaParams);
            session.attach(activity, host, home, transparentTopBar);
            XposedHelpers.setAdditionalInstanceField(activity, fieldKey, session);
            if (BackgroundContract.GLOBAL.equals(slot)) {
                diagnostic(activity, "applied host=" + host.getClass().getName()
                        + " root=" + (originalRoot == null ? "none" : originalRoot.getClass().getName())
                        + " topBar=" + (transparentTopBar ? "transparent" : "content-only")
                        + " activity=" + activity.getClass().getName());
            }
        } catch (Throwable error) { log("applyLayer/" + slot, error); }
    }

    private static ViewGroup selectLayerHost(Activity activity, ViewGroup content, boolean home) {
        // MobileNetworkSettings uses MIUIX ActionBarOverlayLayout for page animation.
        // Keep the custom background outside that animated container so it does not
        // slide together with the page and create a ghost/double-background frame.
        if (activity != null
                && "com.android.phone".equals(activity.getPackageName())
                && "com.android.phone.settings.MobileNetworkSettings".equals(
                        activity.getClass().getName())) {
            try {
                View windowContent = activity.findViewById(android.R.id.content);
                if (windowContent instanceof ViewGroup) {
                    ViewGroup windowHost = (ViewGroup) windowContent;
                    ViewParent parent = windowHost.getParent();

                    if (parent instanceof ViewGroup
                            && parent.getClass().getName().contains("ActionBarOverlayLayout")) {
                        return (ViewGroup) parent;
                    }
                    return windowHost;
                }
            } catch (Throwable error) {
                log("selectLayerHost/MobileNetworkSettings", error);
            }
        }

        if (home || activity == null || content == null) return content;
        try {
            Object parent = content.getParent();
            if (parent instanceof ViewGroup && isMiuixActionBarHost(activity, (ViewGroup) parent)) {
                return (ViewGroup) parent;
            }

            int id = activity.getResources().getIdentifier(
                    "action_bar_overlay_layout", "id", activity.getPackageName());
            View candidate = id == 0 ? null : activity.findViewById(id);
            if (candidate instanceof ViewGroup
                    && candidate != activity.getWindow().getDecorView()
                    && isAncestor((ViewGroup) candidate, content)
                    && isMiuixActionBarHost(activity, (ViewGroup) candidate)) {
                return (ViewGroup) candidate;
            }
        } catch (Throwable ignored) {}
        return content;
    }

    private static boolean isMiuixActionBarHost(Activity activity, ViewGroup view) {
        if (view == null) return false;
        String cls = view.getClass().getName().toLowerCase();
        String idName = resourceEntryName(activity, view);
        return cls.contains("miuix.appcompat.internal.app.widget.actionbaroverlaylayout")
                || cls.contains("miuix.appcompat.internal.app.widget.actionbarmovablelayout")
                || "action_bar_overlay_layout".equals(idName);
    }

    private static boolean isAncestor(ViewGroup ancestor, View child) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static String resourceEntryName(Activity activity, View view) {
        if (activity == null || view == null || view.getId() == View.NO_ID || view.getId() == 0) return "";
        try { return activity.getResources().getResourceEntryName(view.getId()).toLowerCase(); }
        catch (Throwable ignored) { return ""; }
    }

    private static void diagnostic(Activity activity, String message) {
        try {
            Object previous = XposedHelpers.getAdditionalInstanceField(activity, GLOBAL_DIAGNOSTIC);
            if (message.equals(previous)) return;
            XposedHelpers.setAdditionalInstanceField(activity, GLOBAL_DIAGNOSTIC, message);
            XposedBridge.log("[HyperBackground] " + activity.getPackageName() + " " + message);
            BackgroundContract.reportDiagnostic(activity, message);
        } catch (Throwable ignored) {}
    }

    private static void stopLayer(Activity activity, String fieldKey) {
        if (activity == null) return;
        try {
            LayerSession s = (LayerSession) XposedHelpers.getAdditionalInstanceField(activity, fieldKey);
            if (s != null) s.media.onHostStop();
        } catch (Throwable error) { log("stopLayer", error); }
    }

    private static void removeLayer(Activity activity, String fieldKey, LayerSession session) {
        XposedHelpers.removeAdditionalInstanceField(activity, fieldKey);
        ViewGroup parent = session.media.getParent() instanceof ViewGroup ? (ViewGroup) session.media.getParent() : null;
        if (parent != null) parent.removeView(session.media);
        session.media.dispose();
        session.detach();
        session.restore();
    }

    static boolean shouldSuppressDeviceShader(Object fragment) {
        try {
            Context context = contextFromFragment(fragment);
            return context != null && BackgroundContract.query(context, BackgroundContract.DEVICE).exists;
        } catch (Throwable error) { log("shouldSuppressDeviceShader", error); return false; }
    }

    static void applyDevice(Object fragment) {
        if (fragment == null) return;
        try {
            Context context = contextFromFragment(fragment); if (context == null) return;
            Activity activity = activityFromFragment(fragment);
            BackgroundContract.Source source = BackgroundContract.query(context, BackgroundContract.DEVICE);
            DeviceSession old = (DeviceSession) XposedHelpers.getAdditionalInstanceField(fragment, DEVICE_SESSION);
            if (!source.exists) { if (old != null) removeDevice(fragment, old, true); if (activity != null) applyFontMode(activity); return; }
            if (old != null && old.media.sourceKey().equals(source.cacheKey())) { stopOriginalShader(fragment, old.backgroundView); old.media.onHostResume(); if (activity != null) applyFontMode(activity); return; }
            int rememberedVisibility = old != null ? old.originalVisibility : Integer.MIN_VALUE;
            Object field = XposedHelpers.getObjectField(fragment, "mBgEffectView"); if (!(field instanceof View)) return;
            View backgroundView = (View) field; if (!(backgroundView.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) backgroundView.getParent(); BackgroundMediaView media = new BackgroundMediaView(context, source);
            if (old != null) removeDevice(fragment, old, false); stopOriginalShader(fragment, backgroundView);
            int index = parent.indexOfChild(backgroundView); if (index < 0) index = 0;
            try { parent.addView(media, index + 1, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); }
            catch (Throwable e) { media.dispose(); backgroundView.setVisibility(rememberedVisibility != Integer.MIN_VALUE ? rememberedVisibility : View.VISIBLE); throw e; }
            DeviceSession session = new DeviceSession(backgroundView, rememberedVisibility != Integer.MIN_VALUE ? rememberedVisibility : backgroundView.getVisibility(), media);
            backgroundView.setVisibility(View.INVISIBLE); XposedHelpers.setAdditionalInstanceField(fragment, DEVICE_SESSION, session);
            if (activity != null) applyFontMode(activity);
        } catch (Throwable error) { log("applyDevice", error); }
    }

    static void stopDevice(Object fragment) {
        if (fragment == null) return;
        try { DeviceSession s = (DeviceSession) XposedHelpers.getAdditionalInstanceField(fragment, DEVICE_SESSION); if (s != null) s.media.onHostStop(); }
        catch (Throwable error) { log("stopDevice", error); }
    }

    static void destroyDevice(Object fragment) {
        if (fragment == null) return;
        try {
            DeviceSession session = (DeviceSession) XposedHelpers.removeAdditionalInstanceField(fragment, DEVICE_SESSION);
            if (session != null) removeDeviceView(session, false);
        } catch (Throwable error) { log("destroyDevice", error); }
    }

    static void applyFontMode(Activity activity) {
        if (activity == null) return;
        try {
            BackgroundContract.Source s = BackgroundContract.query(activity, BackgroundContract.HOME);
            View root = activity.findViewById(android.R.id.content); if (root == null) return;
            applyTextRecursive(root, s.fontMode);
        } catch (Throwable error) { log("applyFontMode", error); }
    }

    private static void applyTextRecursive(View view, int mode) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Object saved = XposedHelpers.getAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR);
            if (mode == BackgroundContract.FONT_FOLLOW) {
                if (saved instanceof Integer) { tv.setTextColor((Integer) saved); XposedHelpers.removeAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR); }
            } else {
                if (!(saved instanceof Integer)) XposedHelpers.setAdditionalInstanceField(tv, ORIGINAL_TEXT_COLOR, tv.getCurrentTextColor());
                TextColorOverride.apply(tv, mode);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view; for (int i = 0; i < g.getChildCount(); i++) applyTextRecursive(g.getChildAt(i), mode);
        }
    }

    private static void removeDevice(Object fragment, DeviceSession session, boolean restoreBackground) {
        XposedHelpers.removeAdditionalInstanceField(fragment, DEVICE_SESSION); removeDeviceView(session, restoreBackground);
    }
    private static void removeDeviceView(DeviceSession session, boolean restoreBackground) {
        ViewGroup parent = session.media.getParent() instanceof ViewGroup ? (ViewGroup) session.media.getParent() : null;
        if (parent != null) parent.removeView(session.media); session.media.dispose();
        if (restoreBackground) session.backgroundView.setVisibility(session.originalVisibility);
    }
    private static void stopOriginalShader(Object fragment, View backgroundView) {
        try { Object controller = XposedHelpers.getObjectField(fragment, "mBgEffectController"); if (controller != null) XposedHelpers.callMethod(controller, "stop"); } catch (Throwable ignored) {}
        try { backgroundView.setRenderEffect(null); } catch (Throwable ignored) {}
    }
    private static Context contextFromFragment(Object fragment) {
        Object context = XposedHelpers.callMethod(fragment, "getContext"); if (context instanceof Context) return (Context) context;
        Object activity = XposedHelpers.callMethod(fragment, "getActivity"); return activity instanceof Context ? (Context) activity : null;
    }
    private static Activity activityFromFragment(Object fragment) {
        try { Object a = XposedHelpers.callMethod(fragment, "getActivity"); return a instanceof Activity ? (Activity) a : null; } catch (Throwable ignored) { return null; }
    }
    private static void clearNamed(Activity activity, LayerSession session, String name) {
        String packageName = activity.getPackageName();
        int id = activity.getResources().getIdentifier(name, "id", packageName); if (id == 0) return;
        View view = activity.findViewById(id); if (view != null) session.clear(view);
    }
    private static void log(String stage, Throwable error) { XposedBridge.log("[HyperBackground] " + stage + " failed: " + error); XposedBridge.log(error); }

    private static final class LayerSession {
        // Some external-settings pages (security center 应用设置/隐私与安全) build their top/stat
        // cards asynchronously (permission usage is loaded after the first frame), so a single
        // clear at attach/refresh time runs before those opaque neutral panels exist or are
        // measured, leaving black/white blocks until the next onResume. Keep re-clearing on
        // every layout pass for a short budget after the page appears so late-inflated panels
        // are caught without a manual re-entry, then detach the observer to avoid overhead.
        private static final long RESCAN_WINDOW_MS = 2500L;

        final BackgroundMediaView media;
        final List<View> clearedViews = new ArrayList<>();
        final List<Drawable> originalBackgrounds = new ArrayList<>();
        final List<ActionBarSurface> actionBarSurfaces = new ArrayList<>();
        ViewGroup observedRoot;
        boolean homeMode;
        boolean transparentTopBar;
        Window statusBarWindow;
        int originalStatusBarColor;
        boolean statusBarColorSaved;
        Activity observedActivity;
        android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        long rescanDeadline;

        LayerSession(BackgroundMediaView media) { this.media = media; }

        void clear(View view) {
            if (view == null || view == media) return;
            if (!clearedViews.contains(view)) {
                clearedViews.add(view);
                originalBackgrounds.add(view.getBackground());
            }
            if (view.getBackground() != null) view.setBackground(null);
        }

        void attach(final Activity activity, ViewGroup root, boolean home, boolean transparentTopBar) {
            observedRoot = root;
            observedActivity = activity;
            homeMode = home;
            this.transparentTopBar = transparentTopBar;
            if (transparentTopBar) prepareTransparentStatusBar(activity);
            refresh(activity, home);
        }

        void refresh(Activity activity, boolean home) {
            if (home || activity == null || observedRoot == null) return;
            clearPageSurfaces(activity, observedRoot, observedRoot, 0);
            if (transparentTopBar) clearActionBarSurfaces(activity, observedRoot, 0);
            // Reopen the rescan window on every refresh (e.g. returning from a sub-page) so a
            // page re-entered after its cards were recycled is cleaned up again automatically.
            observedActivity = activity;
            rescanDeadline = android.os.SystemClock.uptimeMillis() + RESCAN_WINDOW_MS;
            if (layoutListener == null) installLayoutRescan(activity, observedRoot);
        }

        // Watch layout passes on the observed root: opaque neutral panels created after the
        // first frame (async permission stats etc.) trigger a fresh clear. The listener is
        // self-limiting — it detaches once the rescan window elapses so long-lived pages do
        // not pay for a global-layout callback forever.
        private void installLayoutRescan(final Activity activity, final ViewGroup root) {
            if (root == null) return;
            try {
                final android.view.ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer == null || !observer.isAlive()) return;
                rescanDeadline = android.os.SystemClock.uptimeMillis() + RESCAN_WINDOW_MS;
                layoutListener = () -> {
                    if (observedRoot == null || observedActivity == null) { removeLayoutRescan(); return; }
                    if (observedActivity.isFinishing() || observedActivity.isDestroyed()) { removeLayoutRescan(); return; }
                    clearPageSurfaces(observedActivity, observedRoot, observedRoot, 0);
                    if (transparentTopBar) clearActionBarSurfaces(observedActivity, observedRoot, 0);
                    if (android.os.SystemClock.uptimeMillis() > rescanDeadline) removeLayoutRescan();
                };
                observer.addOnGlobalLayoutListener(layoutListener);
            } catch (Throwable ignored) {}
        }

        private void removeLayoutRescan() {
            if (layoutListener == null) return;
            try {
                ViewGroup root = observedRoot;
                if (root != null) {
                    android.view.ViewTreeObserver observer = root.getViewTreeObserver();
                    if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
                }
            } catch (Throwable ignored) {}
            layoutListener = null;
        }

        void detach() {
            removeLayoutRescan();
            observedRoot = null;
            observedActivity = null;
        }

        void restore() {
            for (int i = 0; i < clearedViews.size(); i++) {
                try { clearedViews.get(i).setBackground(originalBackgrounds.get(i)); } catch (Throwable ignored) {}
            }
            clearedViews.clear();
            originalBackgrounds.clear();
            for (ActionBarSurface state : actionBarSurfaces) {
                try {
                    XposedHelpers.callMethod(state.view, "setPrimaryBackground", state.primaryBackground);
                } catch (Throwable ignored) {}
            }
            actionBarSurfaces.clear();
            if (statusBarColorSaved && statusBarWindow != null) {
                try { statusBarWindow.setStatusBarColor(originalStatusBarColor); } catch (Throwable ignored) {}
            }
            statusBarWindow = null;
            statusBarColorSaved = false;
        }

        private void prepareTransparentStatusBar(Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window == null) return;
                if (!statusBarColorSaved) {
                    statusBarWindow = window;
                    originalStatusBarColor = window.getStatusBarColor();
                    statusBarColorSaved = true;
                }
                window.setStatusBarColor(Color.TRANSPARENT);
            } catch (Throwable ignored) {}
        }

        private void clearActionBarSurfaces(Activity activity, View view, int depth) {
            if (view == null || view == media || depth > 8) return;
            String idName = BackgroundApplier.resourceEntryName(activity, view);
            String cls = view.getClass().getName().toLowerCase();

            boolean actionBarView = containsAny(idName,
                    "action_bar_overlay_layout", "action_bar_container", "action_bar", "app_bar",
                    "collapsing_toolbar", "support_action_bar")
                    || cls.contains("actionbaroverlaylayout")
                    || cls.contains("actionbarmovablelayout")
                    || cls.contains("actionbarcontainer")
                    || cls.contains("appbarlayout")
                    || cls.contains("collapsingtoolbarlayout");
            if (actionBarView) {
                clear(view);
                clearMiuixPrimaryBackground(view, cls);
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    clearActionBarSurfaces(activity, group.getChildAt(i), depth + 1);
                }
            }
        }

        private void clearMiuixPrimaryBackground(View view, String className) {
            if (!className.contains("actionbarcontainer")) return;
            ActionBarSurface saved = null;
            for (ActionBarSurface state : actionBarSurfaces) {
                if (state.view == view) { saved = state; break; }
            }
            try {
                Object current = XposedHelpers.callMethod(view, "getPrimaryBackground");
                if (saved == null) {
                    saved = new ActionBarSurface(view, current instanceof Drawable ? (Drawable) current : null);
                    actionBarSurfaces.add(saved);
                }
                if (current != null) XposedHelpers.callMethod(view, "setPrimaryBackground", (Object) null);
            } catch (Throwable ignored) {}
        }

        private void clearPageSurfaces(Activity activity, View view, View root, int depth) {
            if (view == null || view == media) return;
            if (view.getVisibility() != View.VISIBLE) return;
            if (isPageSurface(activity, view, root, depth)) clear(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) clearPageSurfaces(activity, group.getChildAt(i), root, depth + 1);
            }
        }

        private boolean isPageSurface(Activity activity, View view, View root, int depth) {
            if (view == root) return true;
            Drawable bg = view.getBackground();
            if (bg == null) return false;

            int rootWidth = Math.max(root.getWidth(), activity.getResources().getDisplayMetrics().widthPixels);
            int rootHeight = Math.max(root.getHeight(), activity.getResources().getDisplayMetrics().heightPixels);
            int width = view.getWidth();
            int height = view.getHeight();
            boolean large = width >= (int) (rootWidth * 0.72f) && height >= (int) (rootHeight * 0.32f);

            String idName = resourceEntryName(activity, view.getId());
            String pkg = activity.getPackageName();

            // Device interconnection uses a full-width opaque host surface around the
            // actual cards. Clear only that page-level host; cards keep horizontal margins.
            if ("com.milink.service".equals(pkg)
                    && view instanceof ViewGroup
                    && width >= (int) (rootWidth * 0.965f)
                    && height >= (int) (rootHeight * 0.05f)
                    && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")) {
                return true;
            }

            // The supplied Phone/Account/Theme builds split a Miuix page into several
            // full-width host panels instead of one full-height root. Clear those host
            // panels while retaining inset cards and controls.
            boolean externalSettingsPage = BackgroundContract.PACKAGE_PHONE.equals(pkg)
                    || BackgroundContract.PACKAGE_ACCOUNT.equals(pkg)
                    || BackgroundContract.PACKAGE_THEME_MANAGER.equals(pkg)
                    || BackgroundContract.PACKAGE_SECURITY_CENTER.equals(pkg)
                    || BackgroundContract.PACKAGE_POWER_KEEPER.equals(pkg)
                    || BackgroundContract.PACKAGE_MI_SETTINGS.equals(pkg);
            if (externalSettingsPage
                    && view instanceof ViewGroup
                    && depth <= 8
                    && width >= (int) (rootWidth * 0.94f)
                    && height >= (int) (rootHeight * 0.15f)
                    && !containsAny(idName, "card", "button", "switch", "checkbox", "icon", "image", "banner")) {
                return true;
            }

            // Some external-settings pages (security center 应用设置/隐私与安全) place opaque
            // neutral ColorDrawable panels around their cards (e.g. top_container/top_view,
            // the stat-card ConstraintLayout). In dark mode they read as black blocks and in
            // light mode as white blocks over the wallpaper. Clear only fully-opaque neutral
            // solid colors (black/white/grey); semi-transparent card surfaces (e.g. #24FFFFFF
            // GradientDrawable/CardDrawable) and coloured controls are intentionally kept.
            if (externalSettingsPage
                    && width >= (int) (rootWidth * 0.5f)
                    && height >= (int) (rootHeight * 0.05f)
                    && isOpaqueNeutralColorDrawable(bg)) {
                return true;
            }

            if (!large) return false;

            String cls = view.getClass().getName().toLowerCase();

            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false;
            if (containsAny(cls, "cardview", "button", "switch", "checkbox", "imageview")) return false;

            if (containsAny(idName,
                    "content", "container", "recycler", "list", "prefs", "preference",
                    "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true;
            if (containsAny(cls,
                    "recyclerview", "nestedscrollview", "scrollview", "listview",
                    "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true;

            // HyperOS/MIUIX preference pages often use anonymous FrameLayout/LinearLayout
            // wrappers with a full-page theme surface. Restrict this fallback to very
            // large containers so normal preference cards keep their native backgrounds.
            return view instanceof ViewGroup
                    && width >= (int) (rootWidth * 0.90f)
                    && height >= (int) (rootHeight * 0.62f);
        }

        private String resourceEntryName(Activity activity, int id) {
            if (id == View.NO_ID || id == 0) return "";
            try { return activity.getResources().getResourceEntryName(id).toLowerCase(); }
            catch (Throwable ignored) { return ""; }
        }

        private boolean containsAny(String value, String... needles) {
            if (value == null || value.isEmpty()) return false;
            for (String needle : needles) if (value.contains(needle)) return true;
            return false;
        }

        // True only for a fully-opaque neutral (black/white/grey) solid background.
        // Handles ColorDrawable directly; for any other drawable (LayerDrawable,
        // GradientDrawable, etc.) it samples a *copy* rendered to a 1x1 bitmap so we read
        // the real composited colour without mutating the shared drawable (beta5 broke the
        // grey cards by calling setBounds on the live instance — this copies first).
        // Semi-transparent surfaces (e.g. #24FFFFFF cards) sample with alpha < 255 and are
        // rejected; coloured panels are non-neutral and rejected.
        private boolean isOpaqueNeutralColorDrawable(Drawable bg) {
            if (bg == null) return false;
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                return isOpaqueNeutral(((android.graphics.drawable.ColorDrawable) bg).getColor());
            }
            Integer sampled = sampleDrawableColor(bg);
            return sampled != null && isOpaqueNeutral(sampled);
        }

        // Renders a COPY of the drawable to a 1x1 bitmap and reads the pixel. Never touches
        // the original drawable (no setBounds/draw on the live instance).
        private Integer sampleDrawableColor(Drawable bg) {
            try {
                Drawable.ConstantState state = bg.getConstantState();
                if (state == null) return null;
                Drawable copy = state.newDrawable().mutate();
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                copy.setBounds(0, 0, 1, 1);
                copy.draw(canvas);
                int color = bmp.getPixel(0, 0);
                bmp.recycle();
                return color;
            } catch (Throwable ignored) {
                return null;
            }
        }

        // Fully opaque and neutral (channels close together, no dominant hue): black/white/grey.
        private boolean isOpaqueNeutral(int color) {
            if (Color.alpha(color) != 255) return false;
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            return (max - min) <= 24;
        }
    }
    private static final class ActionBarSurface {
        final View view;
        final Drawable primaryBackground;
        ActionBarSurface(View view, Drawable primaryBackground) {
            this.view = view;
            this.primaryBackground = primaryBackground;
        }
    }
    private static final class DeviceSession {
        final View backgroundView; final int originalVisibility; final BackgroundMediaView media;
        DeviceSession(View backgroundView, int originalVisibility, BackgroundMediaView media) { this.backgroundView = backgroundView; this.originalVisibility = originalVisibility; this.media = media; }
    }
}
