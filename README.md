# HyperBackground

面向 HyperOS 4 / Android 17 设置应用的 LSPosed 背景自定义模块。

> 当前开发版本：**1.1.2**  
> 1.1.0 已跳过；后续构建与发布由 GitHub Actions 自动选择仓库中版本号最高的源码包。

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

### 模块界面

- 跟随系统 / 浅色 / 深色三种界面模式
- 模块本体支持自定义背景
- 模块背景拥有独立透明度、模糊开关与模糊强度
- Monet 壁纸取色
- 可视化 HSV 主题调色盘
- HEX、色相、饱和度、明度与颜色预览实时同步
- 应用自定义主题色后保留当前滚动位置
- 自定义模块图标与作者头像

## 1.1.2 变更

- 修正 APK 实际版本号：`versionName=1.1.2`、`versionCode=3`。
- 修复自定义主题色后配置页强制跳回顶部的问题。
- 改进可视化调色盘，相关文字与预览会随当前颜色同步变化。
- 增加模块本体浅色、深色与跟随系统模式。
- 增加模块本体自定义背景、透明度及模糊控制。
- 使用新的自定义模块图标，并同步用于作者头像。
- 作者区域保留酷安入口，并增加 GitHub 仓库跳转入口。
- 继续保持“设置主页 / 我的设备”双通道隔离，不恢复全局背景。

## 通道规则

1. `home`：仅作用于 `com.android.settings.MiuiSettings` 设置主页。
2. `device`：仅作用于 `com.android.settings.device.MiuiMyDeviceSettings` “我的设备”页面。

模块不提供 `global` 全局背景通道，避免全局 Hook 干涉两个独立页面。

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

## 自动构建与发布

仓库根目录中的源码包统一命名为：

```text
HyperBackground-vX.Y.Z-source.zip
```

GitHub Actions 会自动扫描这些源码包，并使用版本号排序选择**版本号最高**的一份进行构建。例如同时存在：

```text
HyperBackground-v1.1.1-source.zip
HyperBackground-v1.1.2-source.zip
```

则自动构建 `1.1.2`。

构建流程：

```text
选择最新源码包
→ ZIP 完整性校验
→ 解压源码
→ Java 17 / Android SDK 35 编译
→ 检查 APK 实际 versionName
→ 校验固定签名证书
→ 上传 Actions Artifact
→ 自动创建或更新 GitHub Release
```

如果源码包文件名版本与 APK 内部实际 `versionName` 不一致，构建会直接失败，不会发布错误版本。

构建成功后会同时生成：

- Actions Artifact：`HyperBackground-vX.Y.Z`
- APK：`HyperBackground-vX.Y.Z.apk`
- GitHub Release：`vX.Y.Z`

Release 已配置 `contents: write` 权限，可由 Actions 自动创建并附加 APK。

## 构建环境

- Java 17
- Android SDK Platform 35
- Android Build Tools 35.0.1

## 签名

自动构建会校验固定签名证书 SHA-256 指纹，防止误用其他证书生成无法覆盖安装的 APK。

## 作者

**苍簇**

- 酷安主页：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
