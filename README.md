# HyperBackground

HyperOS 设置背景与外观自定义 LSPosed 模块。

> 当前开发版本：**1.3.3-test**
> UI 架构：**Kotlin + Jetpack Compose + Miuix KMP**  
> Hook 核心：沿用 1.2.3 已验证逻辑

## 1.3.3-test

- 完整移除失效的“主题通道实验”，包括配置界面、Provider 字段、Hook 入口与实现类。
- 全局背景继续通过 Activity 生命周期与页面根容器处理，不再拦截系统主题属性。
- 内置于 `com.android.settings` 的普通二级页继续共用全局背景；跳转到其他应用的页面需要分别加入 LSPosed 作用域并适配其布局。
- 包名与固定签名保持不变，可直接覆盖安装 1.3.2-test。

## 1.3.2-test

- 模块无自定义背景时，底色改为跟随 MIUIX 浅色 / 深色主题，不再固定灰黑。
- 新增“卡片透明度”，只调整模块卡片 surface 的透明度；原“背景透明度”明确改名为“背景图透明度”。
- HyperBG 大标题下新增“一言”区域，点击可刷新。
- 默认一言 API：`https://uapis.cn/api/v1/saying`，默认读取字段：`text`。
- 一言支持自定义 API 和 JSON 点路径字段（例如 `data.text`）；若 API 直接返回纯文本，可将读取字段留空。
- 包名与签名保持不变。

## 1.3.1

这是一次配置界面的大重构，视觉和交互路线参考 KernelSU Manager 当前的 MIUIX 实现。

- 配置 Activity 从 Java View 手写界面迁移为 Kotlin `ComponentActivity` + Compose。
- 使用 `top.yukonga.miuix.kmp` 的 `MiuixTheme`、`Scaffold`、`TopAppBar`、`Card`、`Slider`、`TabRow`、`SwitchPreference` 等组件。
- UI 图标切换为 Miuix Icons，并为三类背景使用对应图标。
- 浅色 / 深色 / 跟随系统统一由 `ThemeController` 管理。
- Monet 壁纸取色和自定义主题色继续保留，并直接驱动 MIUIX 主题。
- 设置主页 / 我的设备 / 全局背景三通道重新组织为 MIUIX 卡片。
- 保留媒体选择、透明度、模糊开关和模糊强度。
- “设置应用外观”和“文字颜色”继续保持两套独立配置。
- 模块自身自定义背景、透明度、模糊继续保留。
- 作者卡保留苍簇、酷安和 GitHub 入口。
- 固定签名文件继续沿用，避免升级安装时签名变化。
- Xposed Hook / Provider / BackgroundApplier 等稳定核心没有随 UI 重构改写。

## 当前作用域

- `com.android.settings`
- `com.milink.service`

## 背景通道

1. `home`：设置主页
2. `device`：我的设备
3. `global`：普通 Settings 页面及已验证的设备互联页面

## 全局覆盖边界

- `com.android.settings` 进程内的页面由同一套通用 Hook 处理，不需要逐个 Fragment 加入作用域。
- “设备互联”实际位于 `com.milink.service`，已加入作用域并验证。
- 双卡与移动网络、桌面、系统个性化、壁纸、小米账号以及部分特色功能会启动其他软件；这些页面不会天然继承 Settings 的背景，需要取得对应 APK 后单独适配。
- 权限确认、授权、锁屏凭据、浮动对话框等敏感或临时窗口会保持系统原样。

## 构建

1. Java 17
2. Android SDK 37
3. Gradle 9.7
4. AGP 9.3.1
5. Kotlin 2.4.10
6. Compose BOM 2026.08.00
7. Miuix KMP 0.9.3

运行：

```bash
./build.sh
```

`build.sh` 会在需要时下载 Gradle 发行版，然后执行 `:app:assembleRelease`。产物输出到：

```text
dist/HyperBackground-v1.3.3-test.apk
```

历史版本源码 ZIP 已集中保存在 [`source-archives`](https://github.com/Solomonstery/HyperBackground/tree/source-archives) 分支；`main` 只保留当前可构建源码，GitHub Actions 直接从仓库根目录构建。

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
