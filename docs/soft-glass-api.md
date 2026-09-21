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
系统控制中心（`SecondaryPanelAnimatorBase.applySdfOptimize`）还会按控件实际尺寸调
`setMiGlassSdfMaxSizeCompat(view, width, height)` 优化 SDF 精度，属可选优化。

### 4.2 清除（对齐 `MiuiBlurUtils.clearBlurConfig`）

```kotlin
view.setMiViewMaterialType(0)                 // 回 Classic
view.setMiGlass(floatArrayOf())               // 空数组清 shader 参数（系统同款做法）
view.setMiGlassBlurRadius(0, 0)
view.setMiBackgroundBlurEnhanceFlag(0, 12288)
view.clearMiBackgroundBlendColor()
view.setMiViewBlurMode(0)
view.setMiBackgroundBlurMode(0)
```

## 5. 42 参数数组语义

来自 `MiBackgroundStyle.calculateGlassParams` 的逐索引赋值 + `BionicsToken` 的 getter 命名，
默认值取 `DEFAULT_GLASS_TOKEN`（控制中心主面板默认观感）：

| 索引 | 名称 | 默认值 | 说明 |
|---|---|---|---|
| 0–3 | luminanceValue0..3 | 0.67 / 0.16 / 0.09 / 0.0 | 亮度分段曲线控制点 |
| 4 | luminanceAmount | 0.24 | 亮度调整总量 |
| 5 | saturation | 1.4 | 饱和度系数 |
| 6 | brightness | -0.02 | 亮度偏移 |
| 7 | darker | 0.3 | 压暗强度 |
| 8–9 | darkerRange0/1 | 0.6 / 1.0 | 压暗作用区间 |
| 10 | innerBottom | 0.03 | 内层底部 |
| 11–13 | r / g / b | 1.0 / 1.0 / 1.0 | 染色 RGB（0..1） |
| 14 | alpha | 0.1 | 染色 alpha |
| 15 | innerColorWhite | 0.2 | 内层白色（岛上有灰感来源，卡片建议 0） |
| 16 | innerColorMix | 0.3 | 内层混色（同上，卡片建议 0） |
| 17 | colorPow | 1.0 | 染色幂次 |
| 18 | overallAlpha | 1.0 | 整体 alpha |
| 19 | shapeEdgePx | 72.0 | 形状边缘宽度（px） |
| 20 | shapeEdgePow | 3.8 | 边缘衰减幂 |
| 21 | shapeThicknessPx | 80.0 | 边缘厚度（px） |
| 22 | shapeReflectOffsetPx | 800.0 | 反射偏移（px） |
| 23 | reflectionLighten | 1.2 | 反射提亮 |
| 24 | reflectionStrength | 1.0 | 反射强度 |
| 25–27 | directionalLightDirX/Y/Z | -0.4 / 0.6 / -0.8 | 定向光方向向量 |
| 28 | directionalLightIntensity | 1.4 | 定向光强度 |
| 29 | directionalLightOppositeIntensity | 0.7 | 对侧光强度 |
| 30 | directionalLightAngleRange | 0.8 | 定向光角度范围 |
| 31 | directionalLightEdgePow | 1.15 | 定向光边缘幂 |
| 32 | refractIOR | 4.0 | 折射率 |
| 33 | bgColorSaturation | 2.0 | 背景采样饱和度 |
| 34 | bgColorBrightness | 0.0 | 背景采样亮度 |
| 35 | burn | 0.0 | 灼烧 |
| 36 | unShade | 0.0 | 去阴影 |
| 37–41 | lightCenterFall / CenterStrength / CenterPeak / RingStrength0 / RingStrength1 | 0.0 | 按压光效（默认 0；按压时 = 按压进度 × LightParams + 默认值） |

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
6. **载体必须是真实 View**：材质状态挂在 View 的 RenderNode 上。不在视图树里的 View 也能
   生效（本项目 "bridge View" 方案：每分组一个隐藏 bridge View，取其 `mRenderNode`
   直接录制进分组 Canvas），但 bridge 的 layout 尺寸要与分组同步，且注意 4 的离屏问题。
7. **清理要成对**：切回 Classic 前先 `setMiViewMaterialType(0)` + 空 `setMiGlass`，否则残留
   材质状态可能让该 View 后续高斯模糊也异常。

## 9. 本项目落地架构

```
SettingsCardBackgroundHook (路由)
  └─ mode == CARD_BACKGROUND_SOFT_GLASS(2) && groupClip 可用 && isBionicsActive
       → SettingsSoftGlassDrawable (SettingsGroupMaterial 实现)
            ├─ SoftGlassConfig：暴露 blurRadiusDp + 材质微调参数
            ├─ GlassNode×N：bridge View(mRenderNode) + Bionics 序列 + tint display list
            └─ 降级链：API 缺失/开关关闭 → 磨砂(Gaussian)；调用失败 → 纯 tint
```

- 分组绘制经 `SettingsGroupMaterial.drawGroup(canvas, rect, path)` 派发，
  `BaseDecoration.clipDrawableRoundRect` 的 hook 分流到材质实现，绕过 `saveLayerAlpha`。
- 玻璃模式复用磨砂色板（明/暗 frost 色 + 0..80dp 模糊滑条，×density 映射到
  `setMiGlassBlurRadius` 的物理像素）。
- 42 参数基线取 HyperIsland 灵动岛展开态 token（`baseParams()`），`customizeParams()`
  按 `SoftGlassConfig` 缩放，并将 15/16 通道（岛特有的白内层）清零、11–14 通道映射
  调色板 tint。
- 诊断日志：`Card material branch: soft glass (api=? bionicProp=? blurEnable=? materialStyle=?)`，
  一次分支切换打一条，直接看出卡在哪个开关。
