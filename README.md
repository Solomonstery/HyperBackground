<div align="center">

<img src="docs/icon.png" width="180" alt="HyperBackground" />

# HyperBackground

### 为 HyperOS 3/4 打造的系统背景与外观自定义模块

[![release](https://img.shields.io/github/v/release/Solomonstery/HyperBackground?include_prereleases&label=release&color=blue)](https://github.com/Solomonstery/HyperBackground/releases)
[![downloads](https://img.shields.io/github/downloads/Solomonstery/HyperBackground/total?label=downloads&color=brightgreen)](https://github.com/Solomonstery/HyperBackground/releases)
[![license](https://img.shields.io/badge/license-MIT-orange)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Framework](https://img.shields.io/badge/Framework-LSPosed-8A2BE2)
![ROM](https://img.shields.io/badge/ROM-HyperOS%203%2F4-orange)
![Build](https://img.shields.io/badge/Build-Compose-4285F4)

</div>

---

HyperBackground 是一个面向 HyperOS 的 LSPosed 背景与外观自定义模块，用于为系统设置及部分 HyperOS 系统应用提供统一、可配置的自定义背景体验。

> 当前最新发行版：**1.4.2**
>
> 包名：`com.ciallo.hyperbackground`
>
> 最低 Android 版本：**Android 13 / API 33**
>
> 当前开发基线：**HyperOS 4 / Android 17**
>
> 配置界面：**Kotlin + Jetpack Compose + Miuix KMP**
>
> Hook 框架：**LibXposed API 102**

## 功能

### 背景自定义

- 设置主页、我的设备、全局背景、通讯录与拨号四套独立背景通道，选图方式一致。
- 设置主页支持独立图片，不强制继承全局背景。
- “我的设备”支持图片、GIF、动态 WebP、MP4 和 WebM，并可恢复系统 RuntimeShader 动态背景。
- 各套背景均支持透明度、模糊开关和模糊强度。
- 全局背景覆盖 Settings 普通二级页面，并扩展到设备互联、电话设置、小米账号、主题壁纸、系统桌面、手机管家、省电管理及健康使用手机等已适配页面。
- MIUIX 二级页面支持透明顶栏与连续背景显示。
- 对移动网络 `MobileNetworkSettings` 使用独立的背景宿主处理，避免背景被 MIUIX 页面转场容器一同移动。
- 登录、授权、锁屏凭据、支付、拨号、紧急呼叫及浮动窗口保持系统原样。

### 通讯录与拨号

- 独立背景通道，通过 Hook `com.android.contacts` 注入，仅作用于拨号盘 / 联系人主界面，不影响详情、编辑等二级页面。
- 「拨号盘与列表」适配：清除联系人列表、字母分组吸顶头等不透明中性底透出背景，深浅色行为一致，半透明层与彩色控件保留。
- 拨号盘独立背景：可单独为拨号盘键盘区导入图片，与联系人整页背景叠加共存；支持等比缩放（1–200%）、纵向定位、四角圆角裁切，以及默认模式下键盘面板不透明度独立调节。
- 「通讯录颜色」独立深浅色控件，与全局强制深浅色独立并存。

### 自定义我的设备

- 「自定义我的设备」入口取代原「我的设备」通道，内含设备界面样式、动态背景与自定义 LOGO。
- 设备界面样式支持「系统默认 / 样式1（教程卡）/ 样式2（鸿蒙卡）」；样式1、样式2 可分别导入机型图片、背景图片、LOGO，并逐项调节缩放、偏移、模糊、行间距、对齐与文案等参数。
- 「动态背景」通道复用带预览的背景选图，静态背景支持透明度调节。
- 系统默认样式下支持自定义 LOGO：可选「不保留 / 保留高级材质（导入 SVG/XML）」并调节缩放。
- 所有选图入口统一为带预览对话框，可在对话框内预览、换图、清除。

### 设备信息覆盖

- 覆盖设置各页面显示的手机型号、处理器、运行内存、电池、屏幕、分辨率、摄像头、系统 / Android 版本、存储、内核、基带、硬件等参数，仅改变设置页显示，不修改任何系统属性。

### 主题与外观

- 支持强制设置文字明暗模式与 Settings 应用深浅模式。
- 模块界面支持 Monet 壁纸取色、12 色预设、HSV 调节，以及 `#RRGGBB` / `#AARRGGBB` 手动输入。
- 模块外观支持独立背景、背景透明度、模糊和卡片透明度。

### 其它

- 提供 Hook 读取记录，用于确认目标进程是否已执行 Hook 并读取全局背景。
- 关于页提供版本检查、版本说明、更新日志、制作者信息、酷安和 GitHub 入口。

## 1.4.2

汇总 1.4.2-beta1 至 beta11 全部测试线，作为稳定版发布：

- 新增「通讯录与拨号」独立背景通道、拨号盘与列表适配、拨号盘独立背景（缩放 / 定位 / 圆角 / 不透明度）及「通讯录颜色」独立深浅色控件。
- 移植自 HyperChanger：新增「自定义我的设备」（系统默认 / 教程卡 / 鸿蒙卡三种样式）、自定义 LOGO（支持 SVG/XML 高级材质）与「设备信息覆盖」（仅改变设置页显示）。
- 「动态背景」并入「自定义我的设备」，所有选图入口统一为带预览对话框。
- 升级 LibXposed API 至 102，原有背景注入体系不受影响。

> 完整历史更新记录见仓库中的 [CHANGELOG.md](CHANGELOG.md)。当前 HyperOS 桌面二级设置页由 Flutter/Rust 渲染，模块的 View 树背景注入对其无效；模块仅确保旧版桌面设置背景原本能生效的路径不被破坏。

## 当前作用域

- `com.android.settings`
- `com.milink.service`
- `com.android.phone`
- `com.xiaomi.account`
- `com.android.thememanager`
- `com.miui.home`
- `com.miui.securitycenter`
- `com.miui.powerkeeper`
- `com.xiaomi.misettings`
- `com.android.contacts`

普通 Settings 二级页面使用全局背景；设置主页和“我的设备”分别由独立通道控制，通讯录与拨号由 `com.android.contacts` 通道控制。跨包作用域只处理已识别的全屏设置页面，敏感或临时窗口保持系统原样。

## 安装

1. 从 [GitHub Releases](https://github.com/Solomonstery/HyperBackground/releases) 下载 APK。
2. 安装后在 LSPosed 中启用 HyperBackground。
3. 勾选模块声明的作用域，并结束对应应用进程或重启设备。
4. 打开 HyperBG 配置背景、透明度、模糊和外观选项。

## 签名

从 1.3.6 起，Release APK 使用 GitHub Actions Secrets 注入的私有 PKCS#12 密钥签名，私钥和密码不会进入仓库或源码 ZIP。

1.3.6 起使用的证书 SHA-256：

`A1:75:5A:BE:D4:13:5A:38:17:B0:D8:76:0F:40:BE:A5:D3:B7:00:1B:08:A8:29:EA:1D:3C:DA:90:FB:D4:4F:4B`

由于 1.3.5 及更早版本使用旧签名，首次升级到 1.3.6 或更高版本需要备份配置并卸载旧版；1.3.6 之后的同签名发行版可以覆盖升级。

## 构建环境

- Java 17
- Android SDK 37
- Android Build Tools 36.0.0
- Gradle 9.7.0
- Android Gradle Plugin 9.3.1
- Kotlin 2.4.10
- Compose BOM 2026.08.00
- Miuix KMP 0.9.3

Release 本地构建需要提供：

- `HYPERBG_KEYSTORE_PATH`
- `HYPERBG_KEYSTORE_PASSWORD`
- `HYPERBG_KEY_ALIAS`
- `HYPERBG_KEY_PASSWORD`

执行 `./build.sh`，产物位于 `dist/HyperBackground-v<version>.apk`。

## 仓库分支

- `main`：当前可直接构建的源码。
- `source-archives`：历史源码 ZIP；独立构建链选择版本号最高的源码包。

## 作者与反馈

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
- Issues：https://github.com/Solomonstery/HyperBackground/issues

## 致谢

本次 1.4.0 的 UI 与框架重构，得到了 **芥子**（[@1812z](https://github.com/1812z)）的大力支持，在此致以诚挚感谢。

## License

本项目的许可证见仓库中的 [LICENSE](LICENSE) 文件。
