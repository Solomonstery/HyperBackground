# HyperBackground

HyperOS 设置背景与外观自定义 LSPosed 模块。

> 当前正式版：**1.3.6**
>
> 适配基线：**HyperOS 4 / Android 17**
> 配置界面：**Kotlin + Jetpack Compose + Miuix KMP**

## 正式版 1.3.6

- 设置主页、我的设备、全局背景三套独立通道。
- 设置主页支持独立图片，不强制继承全局背景。
- “我的设备”支持图片、GIF、动态 WebP、MP4 和 WebM，并可恢复系统 RuntimeShader 动态背景。
- 三套背景均支持透明度、模糊开关和模糊强度。
- 全局背景扩展到设置二级页、设备互联、电话设置、小米账号设置、主题壁纸设置、桌面设置、手机管家和省电管理页面。
- 登录、授权、锁屏凭据、支付、拨号、紧急呼叫及浮动窗口保持系统原样。
- 支持强制设置文字明暗模式与 Settings 应用深浅模式。
- 模块界面支持 Monet 壁纸取色、12 色预设、HSV 调节，以及 `#RRGGBB` / `#AARRGGBB` 手动输入。
- 修复自定义主题色始终偏蓝的问题。
- 模块外观支持独立背景、背景透明度、模糊和卡片透明度。
- 关于页提供正式版检查、版本说明、制作者苍簇、酷安和 GitHub 入口。
- 背景页不再显示冗余的项目介绍卡片。

## 当前作用域

- `com.android.settings`
- `com.milink.service`
- `com.android.phone`
- `com.xiaomi.account`
- `com.android.thememanager`
- `com.miui.home`
- `com.miui.securitycenter`
- `com.miui.powerkeeper`

普通 Settings 二级页面使用全局背景；设置主页和“我的设备”分别由独立通道控制。跨包作用域只处理已识别的全屏设置页面，敏感或临时窗口保持原样。

## 安装

1. 从 [GitHub Releases](https://github.com/Solomonstery/HyperBackground/releases) 下载 APK。
2. 安装后在 LSPosed 中启用模块。
3. 勾选模块列出的作用域并结束对应应用进程。
4. 打开 HyperBG 配置图片、透明度、模糊和外观选项。

## 私有签名迁移

从 1.3.6 起，Release APK 使用 GitHub Actions Secrets 注入的私有 PKCS#12 密钥签名，私钥和密码不进入仓库或源码 ZIP。

1.3.6 证书 SHA-256：

`A1:75:5A:BE:D4:13:5A:38:17:B0:D8:76:0F:40:BE:A5:D3:B7:00:1B:08:A8:29:EA:1D:3C:DA:90:FB:D4:4F:4B`

由于 1.3.5 及更早版本使用旧签名，首次升级到 1.3.6 需要备份配置并卸载旧版；从 1.3.6 起可继续覆盖升级。

## 构建环境

- Java 17
- Android SDK 37
- Android Build Tools 36.0.0
- Gradle 9.7.0
- Android Gradle Plugin 9.3.1
- Kotlin 2.4.10
- Compose BOM 2026.08.00
- Miuix KMP 0.9.3

本地构建前需提供以下环境变量：

- `HYPERBG_KEYSTORE_PATH`
- `HYPERBG_KEYSTORE_PASSWORD`
- `HYPERBG_KEY_ALIAS`
- `HYPERBG_KEY_PASSWORD`

执行 `./build.sh`，产物位于 `dist/HyperBackground-v<version>.apk`。

## 仓库分支

- `main`：当前可直接构建的目录源码。
- `source-archives`：历史源码 ZIP；其独立构建链选择版本号最高的源码包。

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
