# HyperBackground 1.1.1

面向 HyperOS 4 / Android 17 设置应用的 LSPosed 背景自定义模块。

> 当前维护版本：**1.1.1**。1.1.0 已跳过，不再作为当前构建版本。

## 功能

### 设置主页

- 自定义 JPG / PNG / WebP / GIF 背景
- 独立透明度调节
- 独立背景模糊开关与模糊强度
- 与“我的设备”通道完全独立

### 我的设备

- 独立自定义背景
- 支持图片、GIF / 动态 WebP
- 支持无声循环 MP4 / WebM
- 独立透明度调节
- 独立背景模糊开关与模糊强度
- 保留针对 HyperOS“我的设备”页面的 RuntimeShader 背景处理

## 1.1.1 变更

- **移除全局背景通道**：不再 Hook 通用 Settings 页面，从根本上避免全局背景干涉主页或“我的设备”。
- **双通道隔离**：现在仅保留“设置主页”和“我的设备”，两者配置与 Hook 路径互相独立。
- **可视化主题调色盘**：不再只依赖手动输入 `#RRGGBB`，加入实时颜色预览、HSV 调节、HEX 同步与快捷颜色。
- **Monet 主题色**：模块配置界面可继续根据系统壁纸取色，也可以切换到自定义颜色。
- **强制字体颜色模式**：支持跟随系统 / 强制浅色 / 强制深色。
- **界面优化**：配置页继续采用面向 HyperOS 的卡片式布局。
- **作者信息**：制作者 **苍簇**，作者卡片可跳转酷安主页。

## 通道规则

1. `home`：仅作用于 `com.android.settings.MiuiSettings` 设置主页。
2. `device`：仅作用于 `com.android.settings.device.MiuiMyDeviceSettings` “我的设备”页面。

**1.1.1 不再提供 global / 全局背景通道。**

## 文件支持

- 设置主页：JPG / PNG / WebP / GIF
- 我的设备：图片、GIF / 动态 WebP、无声循环 MP4 / WebM
- 单文件最大 200 MB

## 生效方式

修改配置后重新进入对应 Settings 页面。主页和“我的设备”分别读取自己的背景、透明度和模糊配置。

## Hook 范围

- 设置主页：`com.android.settings.MiuiSettings`
- 我的设备：`com.android.settings.device.MiuiMyDeviceSettings`
- LSPosed 作用域：`com.android.settings`

## 构建

GitHub Actions 当前只构建：

`HyperBackground-v1.1.1-source.zip`

构建环境：

- Java 17
- Android SDK Platform 35
- Android Build Tools 35.0.1

构建产物：

`HyperBackground-v1.1.1.apk`

发布 APK 会校验固定签名证书指纹，防止意外产生不同签名的安装包。

## 作者

**苍簇**

酷安主页：https://www.coolapk.com/u/18795532
