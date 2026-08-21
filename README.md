# HyperBackground 1.1.0

面向 HyperOS 4 / Android 17 设置应用的 LSPosed 背景模块。

## 1.1.0 新增

- **设置主页独立通道**：主页图片、透明度、模糊开关和模糊强度均单独保存。
- **全局设置背景通道**：作用于其他 Settings 页面，明确排除设置主页与“我的设备”。
- **“我的设备”继续保持独立通道**：保留原 RuntimeShader 抑制/恢复逻辑，并同样支持透明度与模糊。
- **透明度调节**：0–100%。
- **背景模糊**：独立开关，0–80 强度，Android 12+ 使用 RenderEffect。
- **强制字体颜色**：跟随系统 / 强制浅色 / 强制深色。
- **MIUIX 风格配置页**：卡片式布局、圆角控件、浅色 HyperOS 风格。
- **Monet 主题色**：配置页默认从系统壁纸主色自动取色。
- **调色盘**：可输入 `#RRGGBB` 自定义模块主题色，使用自定义颜色时自动关闭 Monet。
- **作者卡片**：制作者“苍簇”，点击打开酷安主页。

## 三通道规则

1. `home`：只作用于 `com.android.settings.MiuiSettings`。
2. `global`：作用于其他 `SettingsActivity` 页面，但在“我的设备”Fragment 激活时自动撤下。
3. `device`：只作用于 `com.android.settings.device.MiuiMyDeviceSettings`，不会被全局背景覆盖。

因此主页和全局是完全独立的配置，不存在“全局图覆盖主页图”的优先级问题。

## 文件支持

- 主页：JPG / PNG / WebP / GIF。
- 全局：JPG / PNG / WebP / GIF。
- 我的设备：图片、GIF / 动态 WebP、无声循环 MP4 / WebM。
- 单文件最大 200 MB。

## 生效方式

配置修改后重新进入对应 Settings 页面即可。透明度、模糊、文字模式都会进入背景缓存键，返回设置页面时会重新读取配置。

## Hook 说明

- 首页：`com.android.settings.MiuiSettings`。
- 全局：`com.android.settings.SettingsActivity#onResume/onStop`。
- 我的设备：`com.android.settings.device.MiuiMyDeviceSettings`。
- 模块仅作用域 `com.android.settings`。

## 本地构建

项目需要 Android SDK Platform 35、Build Tools 35.0.1、Java 17。

```sh
chmod +x build.sh
./build.sh
```

输出：`dist/HyperBackground-v1.1.0.apk`。
