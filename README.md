# HyperBackground

HyperBackground 是一个面向 HyperOS 的 LSPosed 背景与外观自定义模块，用于为系统设置及部分 HyperOS 系统应用提供统一、可配置的自定义背景体验。

> 当前最新发行版：**1.4.0**
>
> 包名：`com.ciallo.hyperbackground`
>
> 最低 Android 版本：**Android 13 / API 33**
>
> 当前开发基线：**HyperOS 4 / Android 17**
>
> 配置界面：**Kotlin + Jetpack Compose + Miuix KMP**
>
> Hook 框架：**LibXposed API 101**

## 功能

- 设置主页、我的设备、全局背景三套独立背景通道。
- 设置主页支持独立图片，不强制继承全局背景。
- “我的设备”支持图片、GIF、动态 WebP、MP4 和 WebM，并可恢复系统 RuntimeShader 动态背景。
- 三套背景均支持透明度、模糊开关和模糊强度。
- 全局背景覆盖 Settings 普通二级页面，并扩展到设备互联、电话设置、小米账号、主题壁纸、系统桌面、手机管家、省电管理及健康使用手机等已适配页面。
- MIUIX 二级页面支持透明顶栏与连续背景显示。
- 对移动网络 `MobileNetworkSettings` 使用独立的背景宿主处理，避免背景被 MIUIX 页面转场容器一同移动。
- 登录、授权、锁屏凭据、支付、拨号、紧急呼叫及浮动窗口保持系统原样。
- 支持强制设置文字明暗模式与 Settings 应用深浅模式。
- 模块界面支持 Monet 壁纸取色、12 色预设、HSV 调节，以及 `#RRGGBB` / `#AARRGGBB` 手动输入。
- 模块外观支持独立背景、背景透明度、模糊和卡片透明度。
- 提供 Hook 读取记录，用于确认目标进程是否已执行 Hook 并读取全局背景。
- 关于页提供版本检查、版本说明、制作者信息、酷安和 GitHub 入口。

## 1.4.0

本版本为一次结构性重构：

- 使用 Miuix 风格重构模块配置界面。
- 迁移到 LibXposed API 101 实现 Hook 入口与运行时。

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

普通 Settings 二级页面使用全局背景；设置主页和“我的设备”分别由独立通道控制。跨包作用域只处理已识别的全屏设置页面，敏感或临时窗口保持系统原样。

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

## License

本项目的许可证见仓库中的 [LICENSE](LICENSE) 文件。
