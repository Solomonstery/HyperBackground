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
  `BaseDecoration.clipDrawableRoundRect` 的 hook 分流到材质实现，绕过 `saveLayerAlpha`。
- 玻璃模式复用磨砂色板（明/暗 frost 色 + 0..80dp 模糊滑条，×density 映射到
  `setMiGlassBlurRadius` 的物理像素）。
- 42 参数使用项目 `baseParams()` 基线，`customizeParams()` 按 `SoftGlassConfig` 缩放，并将
  11–16 通道清零，避免控制中心专用内层和 tint 通道污染卡片；卡片颜色由 display list
  自身绘制。
- 诊断日志：`Card material branch: soft glass (api=? bionicProp=? blurEnable=? materialStyle=?)`，
  一次分支切换打一条，直接看出卡在哪个开关。
