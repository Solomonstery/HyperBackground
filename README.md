# HyperBackground

一个面向 HyperOS 设置应用的 LSPosed 背景与外观自定义模块。

> 当前版本：**1.1.3**  
> 主要适配环境：HyperOS 4 / Android 17  
> LSPosed 作用域：`com.android.settings`

## 功能

### 设置主页背景

- 自定义 JPG / PNG / WebP / GIF 背景
- 独立透明度调节
- 独立背景模糊开关
- 独立模糊强度调节
- 与“我的设备”背景完全独立

### 我的设备背景

- 独立自定义背景
- 支持 JPG / PNG / WebP / GIF
- 支持动态 WebP
- 支持无声循环 MP4 / WebM
- 独立透明度调节
- 独立背景模糊开关与强度
- 针对 HyperOS“我的设备”页面进行独立处理

### 设置应用外观

可单独控制 `com.android.settings` 的界面模式：

- 跟随系统
- 强制浅色
- 强制深色

该功能通过 Settings 应用自身的日夜模式配置实现，不是简单修改卡片背景颜色。

> “设置应用外观”和模块已有的文字颜色控制是两套独立功能。强制 Settings 浅色/深色不会直接修改文字颜色选项的配置值。

### 文字颜色

- 保留独立的文字颜色控制
- 可用于自定义背景下改善文字可读性
- 与“设置应用外观”分开配置

### 模块自身界面

- 跟随系统 / 浅色 / 深色
- 模块本体支持自定义背景
- 模块背景透明度调节
- 背景模糊开关与模糊强度
- Monet 壁纸取色
- 可视化 HSV 主题调色盘
- HEX / 色相 / 饱和度 / 明度联动
- 自定义模块图标
- 作者信息、酷安主页及 GitHub 仓库入口

## 1.1.3 更新内容

- 新增 **“设置应用外观”**：可让 Settings 单独跟随系统、强制浅色或强制深色。
- Settings 外观切换改为应用级日夜模式处理，不直接遍历或染色 HyperOS 卡片 View。
- Settings 外观与原有文字颜色控制彻底分离。
- 移除实验性的 HyperOS Logo / Logo 自定义文字功能。
- 保持“设置主页”和“我的设备”两个背景通道互相隔离。
- 修正源码构建所需的 Xposed API 编译声明。
- 统一版本信息为 `versionName=1.1.3`、`versionCode=4`。

## 背景通道

模块目前只保留两个 Settings 背景通道：

1. `home`：设置主页
2. `device`：我的设备

不提供全局背景通道，避免全局 Hook 对主页和“我的设备”造成交叉干涉。

## 使用方法

1. 安装 HyperBackground。
2. 在 LSPosed 中启用模块。
3. 作用域勾选 **设置（`com.android.settings`）**。
4. 强制停止并重新打开设置，或重启设备。
5. 打开 HyperBackground 配置需要的背景、透明度、模糊和外观选项。

切换“设置应用外观”后，建议彻底退出并重新打开设置，使 Activity 重新初始化主题。

## 文件支持

- 设置主页：JPG / PNG / WebP / GIF
- 我的设备：JPG / PNG / WebP / GIF / 动态 WebP / 无声循环 MP4 / WebM
- 单个媒体文件最大 200 MB

## Hook 范围

- 包名：`com.android.settings`
- 设置主页：`com.android.settings.MiuiSettings`
- 我的设备：`com.android.settings.device.MiuiMyDeviceSettings`

不同 HyperOS 版本内部实现可能存在差异，因此其他 Android / HyperOS 大版本不保证完全兼容。

## 自动构建与 Release

源码包使用：

```text
HyperBackground-vX.Y.Z-source.zip
```

GitHub Actions 会自动选择仓库中版本号最高的源码包，并执行：

```text
源码包完整性检查
→ 解压并识别工程目录
→ Java 17 / Android SDK 35 构建
→ 校验 APK 实际 versionName
→ 校验固定签名证书
→ 上传 Actions Artifact
→ 创建或更新 GitHub Release
```

只有源码包版本与 APK 内部真实版本一致，并且签名校验通过后才会进入发布流程。

## 构建环境

- Java 17
- Android SDK Platform 35
- Android Build Tools 35.0.1

## 说明

这是针对 HyperOS Settings 的 Hook 模块。建议升级前保留上一版本 APK；如果新系统更新导致某个页面失效，请附带 HyperOS / Android 版本以及 LSPosed 日志反馈。

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
