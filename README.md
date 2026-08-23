# HyperBackground

HyperOS 设置背景与外观自定义 LSPosed 模块。

> 当前正式版：**1.3.1**
>
> 适配基线：**HyperOS 4 / Android 17**
> 配置界面：**Kotlin + Jetpack Compose + Miuix KMP**

## 正式版 1.3.1

- 使用 MIUIX 风格配置界面，支持浅色、深色和跟随系统。
- 设置主页、我的设备、全局背景三套独立通道。
- 设置主页支持独立图片，不强制继承全局背景。
- 我的设备支持图片、GIF、动态 WebP、MP4 和 WebM，并可恢复系统 RuntimeShader 动态背景。
- 三套背景均支持透明度、模糊开关和模糊强度。
- 支持强制设置文字明暗模式与 Settings 应用深浅模式。
- 模块界面支持 Monet 壁纸取色和自定义调色盘。
- 保留系统卡片、文字、图标和布局结构，关闭功能时不主动干预系统外观。
- 使用固定签名，可在后续版本中直接覆盖安装。

## 当前作用域

- `com.android.settings`
- `com.milink.service`

普通 Settings 二级页面使用全局背景；设置主页和“我的设备”分别由独立通道控制。权限确认、账号验证、锁屏凭据和浮动对话框等敏感窗口保持系统原样。

## 安装

1. 从 [GitHub Releases](https://github.com/Solomonstery/HyperBackground/releases) 下载 APK。
2. 安装后在 LSPosed 中启用模块。
3. 勾选模块列出的作用域并结束对应应用进程。
4. 打开 HyperBG 配置图片、透明度、模糊和外观选项。

## 构建环境

- Java 17
- Android SDK 37
- Gradle 9.7.0
- Android Gradle Plugin 9.3.1
- Kotlin 2.4.10
- Compose BOM 2026.08.00
- Miuix KMP 0.9.3

本地构建：

```bash
./build.sh
```

产物位于 `dist/HyperBackground-v<version>.apk`。

## 仓库分支

- `main`：当前可直接构建的仓库根源码。
- `source-archives`：历史源码 ZIP；构建链自动选择版本号最高的源码包。

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
