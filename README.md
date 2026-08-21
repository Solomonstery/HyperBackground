# HyperBackground

一个面向 HyperOS 设置应用的 LSPosed 背景与外观自定义模块。

> 当前版本：**1.2.2**  
> 主要适配环境：HyperOS 4 / Android 17

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

### 全局背景

- 为普通 Settings 二级页面提供独立全局背景
- 同时支持由手机管家提供的相关设置页面
- 独立图片、透明度、模糊开关与模糊强度
- 不覆盖“设置主页”与“我的设备”两个专用通道

当前作用域包含：

- `com.android.settings`
- `com.miui.securitymanager`
- `com.miui.securitycenter`

### 设置应用外观

可单独控制 `com.android.settings` 的界面模式：

- 跟随系统
- 强制浅色
- 强制深色

该功能通过 Settings 应用自身的日夜模式配置实现，不是简单修改卡片背景颜色。

### 文字颜色

- 独立的文字颜色控制
- 支持持续强制浅色 / 深色文字
- 页面刷新、Preference 重绑后仍会重新应用
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

## 1.2.2 更新内容

- 修复普通 Settings 二级页面全局背景大面积不显示的问题。
- 修复全局背景 View 在页面 `setContentView()` 后被 HyperOS 新布局替换掉，但模块仍错误复用旧 session 的问题。
- 复用背景 session 前会检查背景 View 是否仍挂载在当前页面；如果已失效则自动重建。
- `onResume` 阶段改为在 DecorView 下一帧重新确认和挂载背景，避免过早插入导致被系统布局覆盖。
- 保留 1.2.x 的手机管家跨包背景支持。
- 保持设置主页 / 我的设备 / 全局背景三通道隔离，不互相覆盖。
- 保持原有字体持续强制、设置应用深浅模式、模块背景、Monet 与调色盘功能不变。

## 背景通道

模块现在提供三个互相隔离的背景通道：

1. `home`：设置主页
2. `device`：我的设备
3. `global`：其他 Settings 页面及支持的手机管家设置页面

优先级：

```text
我的设备 > 设置主页 > 全局背景
```

全局背景不会主动覆盖前两个专用通道。

## 使用方法

1. 安装 HyperBackground。
2. 在 LSPosed 中启用模块。
3. 作用域勾选设置和手机管家相关包。
4. 强制停止并重新打开对应应用，或重启设备。
5. 在 HyperBackground 中配置主页、我的设备或全局背景。

切换“设置应用外观”后，建议彻底退出并重新打开设置，使 Activity 重新初始化主题。

## 文件支持

- 设置主页：JPG / PNG / WebP / GIF
- 我的设备：JPG / PNG / WebP / GIF / 动态 WebP / 无声循环 MP4 / WebM
- 全局背景：JPG / PNG / WebP / GIF
- 单个媒体文件最大 200 MB

## Hook 范围

- 设置：`com.android.settings`
- 手机管家相关：`com.miui.securitymanager` / `com.miui.securitycenter`
- 设置主页：`com.android.settings.MiuiSettings`
- 我的设备：`com.android.settings.device.MiuiMyDeviceSettings`

不同 HyperOS / 手机管家版本内部实现可能存在差异，其他系统版本不保证完全兼容。

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

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
