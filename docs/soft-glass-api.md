# HyperOS 4 柔光玻璃（Bionics Soft Glass）API 调用文档

> 逆向来源：系统界面（SystemUI/MIUIX）APK 中的 `miuix.core.util.HyperMaterialUtils`、
> `miuix.core.util.MiuiBlurUtils`、`miui.systemui.util.MiBackgroundStyle`、
> `miui.systemui.util.MiBlurCompat`、`miui.systemui.ui.MaterialModeRepository`。
> 本项目落地实现：`app/src/main/java/com/ciallo/hyperbackground/appearance/SettingsCardSoftGlassHook.kt`。

## 1. 材质体系概览

HyperOS 4 的背景材质分三条路径：

| 路径 | 判定 | 模糊半径 API |
|---|---|---|
| Classic（经典高斯） | `material_style == 0` | `setMiBackgroundBlurRadius` |
| **Bionics（柔光玻璃）** | `material_style == 1` | **`setMiGlassBlurRadius`** |
| Disabled | `material_style == -1` | 不渲染任何材质 |

关键区别：Bionics 不是"高斯模糊 + 混色"的叠加，而是独立的 42 参数 shader 材质
（`setMiGlass(float[])`），模糊半径也走独立的 View 级 API `setMiGlassBlurRadius(small, big)`。
**在 Bionics 路径上调用 `setMiBackgroundBlurRadius` 不会生效**——这是本材质最常见的接入错误。

## 2. 前置开关（缺一不可）

系统在渲染前通过 `HyperMaterialUtils.isGlassReady(context)` 做三重门控。
**所有 setter 在开关关闭时仍会"成功返回"，但 framework 静默不渲染**——症状是只剩普通绘制内容
（如 tint 色），无任何模糊/玻璃。

| 开关 | 读取方式 | 含义 |
|---|---|---|
| 硬件支持 | 系统属性 `persist.sys.bionic_material_supported == "true"` | SoC/GPU 支持 Bionics 材质 |
| 背景模糊总开关 | `Settings.Secure.getInt(resolver, "background_blur_enable", 0) == 1` | 系统模糊总开关 |
| 材质风格 | `Settings.Secure.getInt(resolver, "material_style", -1) == 1` | 用户选了"柔光玻璃"（壁纸与个性化 → 材质风格） |

另有 `MaterialMode` 枚举值：`Disabled = -1`、`Classic = 0`、`Bionics = 1`
（`MaterialModeRepository.KEY_MATERIAL_STYLE = "material_style"`，按 userId 读取 `getIntForUser`）。

```kotlin
// 活性检查（参考实现，带 TTL 缓存避免每帧读 Settings）
fun isBionicsActive(context: Context): Boolean =
    systemProp("persist.sys.bionic_material_supported") == "true" &&
    Settings.Secure.getInt(context.contentResolver, "background_blur_enable", 0) == 1 &&
    Settings.Secure.getInt(context.contentResolver, "material_style", -1) == 1
```

## 3. View 级隐藏 API 清单

全部定义在 `android.view.View` 上（framework 扩展），通过反射调用：

| 方法 | 签名 | 作用 |
|---|---|---|
| `setMiBackgroundBlurMode` | `(int)` | 背景模糊模式：0 关 / 1 开 |
| `setMiViewBlurMode` | `(int)` | View 模糊模式：0 关 / 1 开 |
| `setMiViewMaterialType` | `(int)` | **材质类型：0 Classic / 1 Bionics**，切到 Bionics 的总开关 |
| `setMiGlassBlurRadius` | `(int small, int big)` | 玻璃模糊半径（物理像素，双半径供 shader 混合） |
| `setMiGlass` | `(float[] params)` | **42 参数 Bionics shader 数组**，材质核心 |
| `setMiBackgroundBlurEnhanceFlag` | `(int flag, int mask)` | 增强 flag：4096 = BLUR、8192 = GLASS、mask = 12288 |
| `clearMiBackgroundBlendColor` | `()` | 清除 Classic 路径的混色配置（切材质前清理） |
| `setMiBackgroundBlurRadius` | `(int)` | Classic 高斯半径（0..400）。**Bionics 路径不用** |

MIUIX 侧还有一层无反射封装 `MiuiBlurUtils`（`setGlass`/`setMiGlassBlurRadius`/`setMiViewMaterialType`
等），以及系统级入口 `HyperMaterialUtils.applyContainerWithGlass(view, GlassConfig)`。

## 4. 标准调用序列

### 4.1 应用（对齐 `HyperMaterialUtils.applyContainerWithGlass` + `MiBackgroundStyle`）

```kotlin
// 前置：isBionicsActive(context) == true，否则整个序列不要执行（执行了也不渲染）
view.setMiBackgroundBlurMode(1)               // 1. 背景模糊模式
view.setMiViewBlurMode(1)                     // 2. View 模糊模式
view.clearMiBackgroundBlendColor()            // 3. 清掉 Classic 混色残留
view.setMiViewMaterialType(1)                 // 4. 切 Bionics 材质
view.setMiGlassBlurRadius(smallPx, bigPx)     // 5. 玻璃模糊半径（物理像素）
view.setMiGlass(params42)                     // 6. 42 参数 shader 数组
view.setMiBackgroundBlurEnhanceFlag(8192, 12288) // 7. GLASS 圆角增强（Classic 是 4096）
```

顺序有意义：mode flag → material type → 半径 → 参数 → 增强 flag。
系统控制中心还会按真实挂载控件驱动 SDF 尺寸。对 RecyclerView decoration 或 detached
RenderNode 不要主动调用 SDF setter；它会和 RenderThread 的形状更新不同步，导致闪帧或整块
玻璃失效。

### 4.2 清除（对齐 `MiuiBlurUtils.clearBlurConfig`）

```kotlin
view.setMiViewMaterialType(0)                 // 关闭 Bionics 材质
// 不要传空数组：libhwui 的 JNI 入口要求长度严格为 42，其他长度会打印
// “setMiGlass jni fail”。清理时只恢复 material/mode/radius/flag。
view.setMiGlassBlurRadius(0, 0)
view.setMiBackgroundBlurEnhanceFlag(0, 12288)
view.clearMiBackgroundBlendColor()
view.setMiViewBlurMode(0)
view.setMiBackgroundBlurMode(0)
```

## 5. 42 参数数组语义

来自 `MiBackgroundStyle.calculateGlassParams` 的逐索引赋值 + `BionicsToken` 的 getter 命名，
默认值和索引必须以目标 ROM 的 `MiBackgroundStyle`/`BionicsToken` 为准。不同 HyperOS
版本不能直接互换 token。当前项目使用的 42 个结构基线是：

```text
0, 2, 0.5, 0.8, 0.15, 2.4, 0.3, 0.2, 0, 0, 0,
0.06, 0.06, 0.06, 0.6, 0.15, 0.4, 1.36, 1, 72, 3.8,
80, 1000, 1.2, 0.6, -0.4, 0.6, -0.8, 1.8, 1.2, 1,
1.1764706, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0
```

项目会按配置调整部分通道，并将 11–16 清零，避免控制中心专用的白色内层污染卡片。

| 索引 | 名称/用途 | 当前项目基线 | 说明 |
|---|---|---|---|
| 0–3 | 亮度曲线 | 0 / 2 / 0.5 / 0.8 | ROM 相关，勿按旧表硬编码 |
| 4 | softLight | 0.15 | 项目调参通道 |
| 5 | saturation | 2.4 | 项目基线 |
| 6–7 | brightness / darker | 0.3 / 0.2 | 项目基线 |
| 8–10 | 内层辅助 | 0 / 0 / 0 | 项目基线 |
| 11–16 | tint/inner layer | 0 | 卡片实现清零 |
| 17–20 | color/shape | 1.36 / 1 / 72 / 3.8 | 形状和颜色基线 |
| 21–24 | edge/reflection | 80 / 1000 / 1.2 / 0.6 | 边缘与反射 |
| 25–31 | directional light | -0.4 / 0.6 / -0.8 / 1.8 / 1.2 / 1 / 1.1764706 | 定向光 |
| 32 | refraction | 0 | 由配置调整；不是通用 IOR 定义 |
| 33–35 | background/burn | 0 / 0 / 0 | 背景采样与灼烧 |
| 36–41 | reserved/light | 0 | 预留，勿随意修改 |

按压 LightParams 参考：主面板 `MAIN_PANEL_LIGHT_PARAMS = (1.3, 1.4, 0.08, 0.08, 0.08)`，
编辑面板 `EDIT_PANEL_LIGHT_PARAMS = (0.4, 0.9, 0.01, 0.01, 0.01)`。

系统内置 Token 变体（供调参参考，均在 `MiBackgroundStyle`）：

| Token | 用途 |
|---|---|
| `DEFAULT_GLASS_TOKEN` | 控制中心主面板默认 |
| `ACTIVATED_GLASS_TOKEN` | 按压/激活态（定向光 3.0、染色 1.4） |
| `RESTRICTED_GLASS_TOKEN` | 受限场景 |
| `VOLUMPANEL_*`（3 个） | 音量面板 收起关闭/收起展开/展开 |
| `BRIGHTNESS_WINDOW_GLASS_TOKEN` | 亮度窗口 |

## 6. 模糊半径参考值（物理像素）

| 场景 | small | big |
|---|---|---|
| 系统默认 | 110 | 110 |
| 音量条（收起） | 50 | 500 |
| 音量条（展开） | 100 | 500 |
| 亮度窗口 | 50 | 500 |

## 7. 增强 Flag

`MiBackgroundStyle.disableBlurEnhanceFlag / enableBlurEnhanceFlag`：

```java
BLUR_ENHANCE_FLAG  = 4096;  // Classic 圆角增强
GLASS_ENHANCE_FLAG = 8192;  // Bionics 圆角增强
MASK               = 12288; // 两 flag 的掩码
enable:  setMiBackgroundBlurEnhanceFlag(flag, 12288)
disable: setMiBackgroundBlurEnhanceFlag(0, 12288)
```

Bionics 模式下圆角（`MiBlurCompat.setBlurOutlineRoundRect`）自动用 8192。
若玻璃圆角/边缘不跟随 outline，检查是否漏了这个 flag。

## 8. 接入陷阱清单

1. **`setMiBackgroundBlurRadius` 不属于玻璃路径**——玻璃半径必须 `setMiGlassBlurRadius(small, big)`。
2. **方法存在 ≠ 可渲染**：`material_style != 1` 时所有 setter 静默成功但不渲染。必须做
   `isBionicsActive` 门控（硬件属性 + 两个 Secure 设置），失败回退 Classic 路径。
3. **`isBionicsActive` 不要每帧查**：读 `Settings.Secure` 有 binder 开销，TTL 缓存（本项目 3s）
   或注册 ContentObserver。
4. **离屏层会杀死背景采样**：目标 View 的 display list 里若出现 `saveLayer`/
   `saveLayerAlpha`（MIUIX `BaseDecoration.clipDrawableRoundRect` 就有），玻璃采不到真实
   背景。本项目 hook 该方法绕过离屏层，直接在原 Canvas 上按分组 path 裁剪绘制。
5. **软件绘制不生效**：`canvas.isHardwareAccelerated == false` 时直接走 tint 兜底。
6. **detached bridge 不能激活窗口 surface**：桥接 View 可以承载 RenderNode 材质，但它不在
   View 树中，不能让 VRI 注册模糊 surface。必须先对真实挂载的宿主 View 执行一次
   `setMiBackgroundBlurMode(1)`、`setMiViewBlurMode(1)` 和 GLASS flag；本项目在
   `bindHost()` 中完成。
7. **不要把 `setMiGlassClip` 当作通用修复接口**：不同 ROM 的签名和坐标语义不同，系统日志
   中的值通常是窗口坐标。只有确认目标 framework 的签名、坐标系和时序后才能调用。
8. **清理要成对**：切回 Classic 前恢复 material type、glass radius、enhance flag、blend
   color 和两个 mode。不要用错误长度的 `setMiGlass` 清理参数。

## 9. 本项目落地架构

```
SettingsCardBackgroundHook (路由)
  └─ mode == CARD_BACKGROUND_SOFT_GLASS(2) && groupClip 可用 && isBionicsActive
       → SettingsSoftGlassDrawable (SettingsGroupMaterial 实现)
            ├─ SoftGlassConfig：暴露 blurRadiusDp + 材质微调参数
            ├─ GlassNode×N：真实宿主先激活 surface；bridge View(mRenderNode) + Bionics 序列
            │             + tint display list
            └─ 降级链：API 缺失/开关关闭 → 磨砂(Gaussian)；调用失败 → 纯 tint
```

- 分组绘制经 `SettingsGroupMaterial.drawGroup(canvas, rect, path)` 派发，
  `clipDrawableRoundRect` 的 hook 分流到材质实现，绕过 `saveLayerAlpha`。该方法按**签名**
  `(Canvas, RectF, Path, Drawable)` 定位而非按名字（压缩过的 apk 里它叫 `g`，见 §13）。
- 玻璃模式复用磨砂色板（明/暗 frost 色 + 0..80dp 模糊滑条，×density 映射到
  `setMiGlassBlurRadius` 的物理像素）。
- 42 参数使用项目 `baseParams()` 基线，`customizeParams()` 按 `SoftGlassConfig` 缩放，并将
  11–16 通道清零，避免控制中心专用内层和 tint 通道污染卡片；卡片颜色由 display list
  自身绘制。
- 诊断日志：`Card material branch: soft glass (api=? bionicProp=? blurEnable=? materialStyle=?)`，
  一次分支切换打一条，直接看出卡在哪个开关。

## 10. 独立卡片 vs 列表行：防止柔光玻璃重复应用

`SettingsCardBackgroundHook` 的独立卡片路由按资源 id 匹配（`view_corner`、`device_basic_layout`…），
但同一个 id 在不同状态下的角色并不相同。蓝牙是最典型的例子：
`BluetoothDevicePreference` 对**已保存设备**和**可用设备**用的是同一个布局
`preference_bt_icon_corner`（内含 `view_corner` CardView + `view_high_light_root`），
只是 `onBindViewHolder` 按配对状态分支：

| 状态 | `view_corner` 边距 | `view_high_light_root` 背景 | 角色 |
|---|---|---|---|
| 已配对 / 已保存（`mCachedBondState == 12` 或 GATT 已配对） | `preference_bt_custom_margin_*` | 保留 `ConnectPreferenceHelper` 写入的高亮层 | 独立卡片 |
| 可用设备、`PreviouslyConnectedDevice`、无障碍列表 | 全部清零 | 绑定末尾 `setBackground(null)` 就地清空 | 分组卡里的普通行 |

判定规则（`standaloneTarget` → `bluetoothRowOwnsSurface`）：

- 行**保留**自有高亮层 → 独立卡片，套用所选材质；
- 行的高亮层**被清空** → 它是外层 MIUIX 分组卡面之上的普通行，材质由分组卡提供，不再叠加。

误把列表行当卡片处理的症状就是「列表背景一层柔光玻璃 + 每一行再一层」的重复应用
（可用设备列表）。失去卡片身份的旧行由 `releaseStandalone` 撤回材质，且**不回填**已保存的
高亮层——原生表面以系统最后一次绑定写入的状态为准。每轮绑定
（`settings-cards:bluetooth-row-bound`）都会刷新高亮层记录，避免把列表行误判成卡片。

## 11. 通用独立卡片路由：内容驱动，不再逐个场景配 id

`STANDALONE_CARD_IDS` 这种「资源 id 白名单」每加一个页面都要重新适配，而且换个 apk 就完全失效。
现在独立卡片路由在 id 快路径之后还挂了一条**内容驱动**的通用路径
（`appearance/CardSurfaceDetector.kt`），判定只看一件事：**这一行画出了什么样的面**。

该通用路径有两档作用域（`CardSurfaceDetector.SurfaceScope`），由「接管全部跟随主题的面」
开关选择：

| 档位 | 接管对象 | 核心判据 |
|---|---|---|
| `CARD`（开关关闭） | 只接管独立卡片 | 形状是卡片 + 面确实画出了东西（下表） |
| `SURFACE`（开关开启，默认） | 卡片 + 按钮 + 徽标 + 分段底 + 一切跟随深浅色的面 | 填充色是**随主题翻转的中性色**（见 §11.3） |

下表是 `CARD` 档的判定顺序；`SURFACE` 档沿用第 1–3 条与第 6 条，把第 4 / 5 条换成 §11.3 的中性色检验。

`standaloneTarget()` 的判定顺序：

1. `palette.enabled == false` → 直接 `null`（关闭功能时零成本，不做任何树遍历）。
2. 已知 id 快路径（`settingsSpecific` 时）：蓝牙 `view_corner` 走 [§10](#10-独立卡片-vs-列表行防止柔光玻璃重复应用) 的身份判定，
   `device_basic_layout` 等直接命中。
3. 通用路径 `CardSurfaceDetector.looksLikeStandaloneCard()`：

| # | 条件 | 作用 |
|---|---|---|
| 1 | 宽 ≥ 56dp 且高 ≥ 40dp | 排除图标、徽标、开关、按钮。布局尚未完成时（attach / inflate 阶段宽高为 0）会早退，由 §11.2 的 `onSizeChanged` 补判 |
| 2 | `view.background != null` | **没有自己的背景 → 是分组卡上的普通行，永不接管** |
| 3 | 名称/类名不含负向词 | `icon/button/switch/checkbox/divider/progress/tab/chip/scrim/mask…` |
| 4 | 形状是卡片 | **先把容器型 drawable 展平到叶子**（`selector` / `layer` / `inset` / `ripple`），再判 `roundrect` / `GradientDrawable` 圆角、outline 半径，或父级是圆角卡片容器 |
| 5 | 面确实画出了东西 | 按展平后的叶子取最大不透明度（`CardView` 以 `getCardBackgroundColor()` 为准）；≥ `190` 视为不透明，**形状已确认是卡片时放宽到 ≥ `24`** |
| 6 | 同一条目内没有更外层的卡片面 | 避免「卡片里再套一层卡片」重复叠材质 |

> 第 4 / 5 步的顺序很关键：**先判形状、再判不透明度**。HyperOS 的卡底（`hp_card_bg_no_shadow_normal`、
> `miuix_*_card_group_background`）与 MIUIX 分组卡色本身就是半透明的（如 `#24FFFFFF`）。
> 若沿用「不透明才算卡片」的老门槛，省电与电池页的 `LinearLayout` 卡片会被整片漏掉。

4. 再经 `insideManagedCard()` 拦一道：祖先（≤4 层）id 命中 `STANDALONE_CARD_IDS` 的直接拒绝。
   蓝牙卡片真正带面的是子层 `view_high_light_root`，不拦它就会和 `view_corner` 各套一层。

命中后统一走已有的 `applyStandalone()` 管线：克隆原生背景 → 按色板着色（保留原生圆角）→ 按模式
叠柔光玻璃 / 磨砂 / 纯色。因此**任何页面、任何应用里「自己带面」的卡片都会自动获得材质，
而没有背景的列表行永远不会被套上材质**，不需要再维护黑名单。

### 11.1 诊断：这张卡为什么没拿到材质

判定入口是 `CardSurfaceDetector.probe(view, scope)`：命中返回 `null`，否则返回被拒的 `REASON_*`
常量（`too-small` / `no-background` / `transparent-surface` / `negative-name` /
`not-card-shaped` / `outer-card-surface` / `not-theme-surface` / `page-surface`）。
`SettingsCardBackgroundHook.standaloneTarget()` 把结果写进日志：

| 日志行 | 含义 |
|---|---|
| `Standalone matched key=auto:<资源名> class=… id=… size=…` | `CARD` 档已接管（**在别的应用里看到这行就说明通用路由生效了**） |
| `Standalone matched translucent-card alpha=…` | 按「形状是卡片 + 半透明面」接管（alpha 24–190）。用来回看有没有把半透明遮罩误当成卡片 |
| `Standalone matched-surface key=…` | `SURFACE` 档已接管（按钮 / 徽标 / 分段底也走这条） |
| `Standalone matched-surface fill=#AARRGGBB` | 同上，并给出判定所依据的那个填充色。回看「这颗按钮凭什么被判成跟随主题的面」看这行 |
| `Standalone near-miss not-card-shaped …` | 自己有面但形状判定不认为是卡片（**漏判，最值得看**） |
| `Standalone near-miss outer-card-surface …` | 祖先里有更外层的卡片面，被当成卡片内容跳过（**漏判，最值得看**） |
| `Standalone near-miss inside-managed-card …` | 祖先 id 命中 `STANDALONE_CARD_IDS`，属专属路由属地 |

尺寸不足、没有自己的背景这类早退不打日志（那是正确行为）。日志按「原因 + 类名 + 资源名」去重，
每类视图最多一行、单进程总量封顶 60 行，因此不会在列表页刷屏。

### 11.2 容器型 drawable 展平 + 布局完成时机

这两个细节决定了通用路由在「非设置」应用里到底能不能落地。

**a) 展平容器 drawable。** MIUI / HyperOS 的卡片底几乎都是容器型 drawable。省电与电池页的卡片是
`selector_battery_card_bg`（`StateListDrawable`）→ `hp_card_bg_no_shadow_normal`（`layer-list`：
一层全宽底色 + 一层带圆角的卡片色）。只看最外层会同时丢掉**圆角**（判成不是卡片）和**填充色**
（拿不到真正的卡面色）。`CardSurfaceDetector.leaves()` 递归拆到底：

| 容器 | 处理 |
|---|---|
| `StateListDrawable` | 反射 `getStateCount` / `getStateDrawable` 取全部状态层（跨版本可见性不一致，允许失败） |
| `LayerDrawable` | 逐层 `getDrawable(i)` |
| `InsetDrawable` | 取 `getDrawable()` |
| `RippleDrawable` | **只取第 0 层（内容层）**；第 1 层是涟漪蒙版，不透明涟漪色会把透明内容误判成「有面」 |

容器解析不出内容层时退回它自身，至少保留原有的不透明度判断。递归深度上限 `MAX_DRAWABLE_DEPTH = 6`。

**b) 在布局完成时补判。** `dispatchAttachedToWindow` 与 `setBackground` 触发时视图的 `width/height`
仍是 `0`，`too-small` 早退会让通用路由形同虚设。因此额外 hook `View.onSizeChanged`：它正是布局把
真实尺寸写回 View 的那一刻，此时 `newWidth > 0` 才补跑一次 `applyStandalone()`。为控制成本，只对
「已追踪的视图」或「自己有 background 的视图」再走完整判定——绝大多数视图没有自己的背景，先按最
便宜的条件筛掉。

### 11.3 `SURFACE` 档：怎样判断「这个面有没有读深浅色」

用户要的语义是「**只要有读取深浅色模式的背景，统统替换掉**，包括按钮、card、徽标」。
最直白的实现是看这个资源有没有 `-night` 变体，但那需要把同一个资源在两个 `Configuration`
下各解析一次，对每个视图都做一遍代价无法接受。改用**行为代理**——跟随主题的表面在解析结果上
必然满足两条：

1. **中性**：色度低（白 / 黑 / 灰），不带品牌色；
2. **在当前主题那一侧**：浅色主题下页面是亮的，跟随主题的面必然是亮的；深色主题反之。

关键在于**半透明面要按当前主题的页面底色合成后再判**。HyperOS 的卡底（`#24FFFFFF`
这类）在深浅两个主题下 RGB 都是白，直接看色值会得出「不跟随主题」的错误结论；
合成之后：叠在深色页上仍是深色面、叠在浅色页上仍是浅色面，结论就正确了。

```kotlin
val ratio = alpha / 255f
val base  = if (night) 0f else 255f          // 当前主题的页面底色极性
val r = (color shr 16 and 0xFF) * ratio + base * (1f - ratio)   // g / b 同理
chroma    = max(r, g, b) - min(r, g, b)      // > 24 → 品牌色，出局
luminance = (0.2126r + 0.7152g + 0.0722b) / 255f
night → luminance ≤ 0.40 ; day → luminance ≥ 0.62
```

| 常量 | 值 | 作用 |
|---|---|---|
| `MIN_THEME_SURFACE_ALPHA` | 24 | 忽略完全透明与几乎看不见的叠加层 |
| `NEUTRAL_CHROMA_MAX` | 24 | 色度上限。红点 / 品牌色按钮 / 彩色标签在这里出局 |
| `LIGHT_SURFACE_MIN_LUMINANCE` | 0.62 | 浅色主题下必须够亮 |
| `DARK_SURFACE_MAX_LUMINANCE` | 0.40 | 深色主题下必须够暗 |
| `THEME_MIN_WIDTH_DP` / `THEME_MIN_HEIGHT_DP` | 8 / 8 | 够到徽标，比 `CARD` 档的 56 / 40 宽得多 |
| `PAGE_COVER_RATIO` | 0.85 | 铺满根视图这个比例以上 → 是整页背景，交给整页通道 |
| `SAME_SURFACE_COVER_RATIO` | 0.95 | 子视图几乎铺满父级且填充色相同 → 同一个面的内层重绘 |

配套的三处行为差异：

- **负向词换了一版**（`THEME_NEGATIVE_HINTS`）。`badge` / `button` / `chip` / `tab` 正是这一档要
  接管的东西，全部移除；留下 `icon` / `avatar` / `thumb` / `divider` / `scrim` / `mask` /
  `switch` / `checkbox` / `radio` / `track` / `overlay` / `dim` / `image` / `photo` 这类
  「本来就不该被重新上色」的。
- **去重语义不同**。`CARD` 档拦掉任何更外层的卡片面（避免卡片里再套卡片）；`SURFACE` 档只拦
  **同一个面**——背景是同一个 drawable 实例，或子视图几乎铺满父级且填充色相同。卡片上的按钮 /
  徽标必须放行，否则「按钮也接管」永远做不到。
- **回退不同**。透明回退是给设置里的卡片用的（卡片消失、露出整页背景即可）；`SURFACE` 档接管的
  可能是按钮 / 徽标，抹成透明等于把它们删掉，因此改回退到所选浅 / 深色板的实色填充。

**能力边界**：这一档解决的仍然是「哪个 View 是一块面、这块面跟不跟主题」，是运行时视图树问题；
静态搜 dex／按方法体内容匹配（DexKit 那一类）对它没有帮助——DexKit 只在「找出被 R8 压掉名字的
分组装饰器方法」这一半有用（见 §13）。另外，贴图型按钮（`BitmapDrawable` / `NinePatchDrawable`）
拿不到可读填充色，一律跳过，不做重新上色。

### 其它应用的复用

`SettingsCardBackgroundHook.install(..., packageName, settingsSpecific = false)` 可以整体装到别的
作用域应用：分组路由（`CardItemDecoration` / `PreferenceFragment$FrameDecoration`）本来就是包无关的，
独立卡片走上面的通用判定。`SettingsBackgroundHook.install()` 在 `CARD_MATERIAL_PACKAGES`
（米联、电话、小米账号、主题商店、安全中心、电量与性能、小米设置）里安装这条通用路由，
设置进程仍由 `SettingsDeviceModule` 单独安装，避免同一个 object 重复 hook。

### 作用域：为什么要「模块清单 + 用户勾选」两层

作用域**由用户在管理器里勾选**，`META-INF/xposed/scope.list` 只是「推荐作用域」，不会强制生效，
也没有「全部应用」这种通配写法（LSPosed 只对 `system` / `android` 两个名字做系统框架映射）。
所以覆盖范围是两层的乘积：

1. **用户勾选**决定哪些进程会被注入（想全量测试就在管理器里把作用域勾成「全部应用」，改完重启设备）；
2. **模块侧**决定「被注入的进程里装不装」：`SettingsBackgroundHook.installCardMaterial()`。

`CARD_MATERIAL_ALL_APPLICATIONS = true` 时第 2 层放开到全部应用，但仍排除
`CARD_MATERIAL_EXCLUDED_PACKAGES`：

| 排除项 | 原因 |
|---|---|
| 通讯录 `com.android.contacts`、短信 `com.android.mms` | 有各自的整页背景通道，两边都 hook `View.setBackground`，会互相拆图层 |
| 设置 `com.android.settings` | 由 `SettingsDeviceModule` 单独安装（重复安装会重复 hook） |
| 模块自身 | UI 是 Compose + Miuix，不需要被自己的路由改写 |

清单之外的应用只装**卡片 + 弹窗材质**两条通用路由，不装背景 / 主题 / 文字色等与应用强绑定的管线
（那些管线在没适配过的应用里没有意义，还可能改坏界面）：入口是 `HookEntry.onPackageLoaded()` →
`SettingsBackgroundHook.installCardMaterialOnly()`。
日志里出现 `Installed generic standalone card material routing for <pkg>` 即表示该进程已接入。

置 `false` 即回到只覆盖 `CARD_MATERIAL_PACKAGES` 的保守行为。

## 12. 弹窗材质：MIUIX 自带玻璃 + 三道门

MIUIX 弹窗（设置右上角溢出菜单 `ImmersionMenuPopupWindowImpl`、`PopupMenuWindow`、
上下文菜单、下拉、`PopupView`、`HyperPopupWindow`）**本来就有**窗口玻璃实现，
不需要我们另画一层：

```java
// miuix.popupwidget.widget.PopupWindow.prepareMaterial()
if (isMaterialEnabled()) {
    mContentView.getBackground().setAlpha(0);          // 内容层背景交给材质
    MiuiBlurUtils.setPassWindowBlurEnabled(view, true); // 打开窗口模糊面
    MiuiBlurUtils.setBackgroundOnlyBlur(view, radius);  // maskBlur = 60
    MiuiBlurUtils.setViewBlurMode(view, 1);
    MiuiBlurUtils.setBackgroundBlendConfig(view, ...);  // ColorBlendToken.Pured_Thin_Glass_*
    HyperBloomStrokeUtils.setBloomStrokeConfig(view, ...);
    MiShadowUtils.setShadowConfig(view, ...);
    // 挂载时：MiuiBlurUtils.setMiBlurWinType(view, 1)
}
```

材质 token 是 `PopupWindow.PopupView_Glass_Light/Dark`（`MaterialToken.Builder(30, "popupview-glass", …)`），
在 `material_style == 1` 的机器上渲染出来就是系统柔光玻璃。

它被三道门挡住，所以设置里默认看不到玻璃：

| 门 | 位置 | 默认值 |
|---|---|---|
| 主题属性 `menuBlurOptions` | `MenuBlurUtils.isMenuBlurOptionEnabled(ctx, n)` → `setMaterialEnabled(bool)` | 设置主题没开这个位 |
| 系统模糊总开关 | `HyperMaterialUtils.isFeatureEnable(ctx)` | 跟随「背景模糊」 |
| 包名白名单 | `MiuiBlurUtils.isPassWindowBlurWhitelisted(view, pkg)` | 框架侧配置 |

`SettingsPopupGlassHook`（`appearance/SettingsPopupGlassHook.kt`）只做一件事：在
「模块卡片材质 = 柔光玻璃」且 `SettingsSoftGlassDrawable.isBionicsActive(ctx)` 成立时，
覆盖 `isMaterialEnabled()` 返回 `true`，把三道门一起放行。**为什么必须同时满足后一个条件**：
`isBionicsActive` 里包含 `background_blur_enable == 1` 与 `material_style == 1`，
不成立时系统拿不出玻璃但 `prepareMaterial` 依然会把内容层背景 alpha 归零——弹窗会变成没有底。
所以宁可不接管。

外加一层兜底：`prepareMaterial()` 返回后检查系统是否真的接管
（`View.getPassWindowBlurEnabled()` 为真，或内容层背景 alpha == 0）。没接管时保留原生底，
用项目自己的柔光玻璃叠一层（`applyToView(view, config, density)`，不清除原生填充），
渲染不出来时外观与原生一致，不会把弹窗弄透明。

弹窗跟随卡片的浅色/深色色板与材质参数（`SettingsCardBackgroundHook.currentMaterial()`），
不引入独立开关；配置为「纯色」模式时弹窗保持原生外观。

## 13. 被 R8 压缩的应用：分组卡片按「结构」而不是「名字」适配

安全中心（`com.miui.securitycenter`）的「省电与电池 → 耗电详情」是真机验证里第一个暴露问题的场景。
它是 `PreferenceScreen`（`pc_power_usage_details.xml`），分组卡由 `PreferenceFragment` 的
`FrameDecoration` 绘制——**和设置里是同一套 MIUIX 代码**，但类名与方法名被 R8 压成了短名：

| 未压缩（设置自带 miuix） | 压缩后（安全中心） |
|---|---|
| `miuix.recyclerview.card.CardItemDecoration` | `miuix.recyclerview.card.f` |
| `miuix.preference.PreferenceFragment$FrameDecoration` | `miuix.preference.PreferenceFragment$f` |
| `calculateGroupRectAndDraw`（每帧绘制入口） | `mo44927f` |
| `clipDrawableRoundRect`（`saveLayerAlpha` 裁剪入口） | `g` |
| `mGroupDrawable` / `mCardGroupBackground` / `mPaint` | `f41500p` / `f40800s` / … |

**症状**：卡片颜色改得动、柔光上不去。原因是两条路径的成本不同——

```kotlin
val useGlass = colors.mode == CARD_BACKGROUND_SOFT_GLASS
    && groupClipAvailable                        // ← 字面名找不到裁剪方法 → 恒 false
    && SettingsSoftGlassDrawable.isBionicsActive(context)
val useFrost = !useGlass && colors.mode != CARD_BACKGROUND_COLOR && groupClipAvailable
```

纯色分支不依赖 `groupClipAvailable`，所以颜色永远生效；而磨砂 / 柔光都必须先挂上
`(Canvas, RectF, Path, Drawable)` 这个裁剪方法（用来绕开 `saveLayerAlpha`，见 §8 陷阱 4），
按字面名 `loadClass` 在压缩 apk 里直接抛 `ClassNotFoundException`，整条静默失效。

**修法：结构发现**（`SettingsCardBackgroundHook.installDiscoveredDecorations` / `installDecorationHooks`）：

| 步骤 | 依据 |
|---|---|
| 定位分组装饰器 | 锚点用**公开名保留**的 `miuix.preference.PreferenceFragment`（MIUIX 公开 API 不被混淆），它就是它内部那个 `RecyclerView.ItemDecoration`（`decorationType.isAssignableFrom(inner)`）。列表型分组卡则由 hook `RecyclerView.addItemDecoration` 在运行时刻捕获 |
| 排除普通装饰器 | 必须能沿继承链找到 `void (Canvas, RectF, Path, Drawable)` 的裁剪方法（`clipMethodOf`）。普通 `ItemDecoration`（分隔线）同样可能有 `Drawable` 字段和 `Canvas` 开头的方法，但**没有这个裁剪入口**，靠它排除 |
| 定位每帧绘制方法 | 该类的非抽象、参数表以 `Canvas` 开头的实例方法；优先 `parameterCount == 4 && View.isAssignableFrom(参数[1])`（`Canvas, RecyclerView, State, Adapter`），没有才退而挂所有 `Canvas` 开头的方法 |
| 定位字段 | 只看类型不看名字：类或父类里第一个类型正好是 `Drawable` 的字段即分组 drawable；`Paint` 字段可有可无 |
| 定位工厂 | 返回 `Drawable` 且只吃一个 `Context` 参数的实例方法；找不到就由绘制 hook 兜底重读 |
| 去重 | 裁剪方法按 `类名#方法名#参数表` 去重，字面名路径与结构发现路径不会重复挂同一个方法 |

三个来源（字面名 / 结构锚点 / 运行时 `addItemDecoration`）汇总进 `decorationClasses`，
`routingAvailable` 与 `groupClipAvailable` 只要任一来源成功即为真。绘制 hook 内的归属判定也从
名字改成类型：`access.drawable.declaringClass.isInstance(owner)`。

> 关键结论：**只要目标应用用的是 MIUIX 分组卡（公开名 `PreferenceFragment` / `RecyclerView` 保留），
> 无论它怎样混淆内部实现，都可以靠结构发现接上柔光玻璃。** 名字会变、结构不会变。

