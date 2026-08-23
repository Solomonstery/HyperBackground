# HyperBackground 源码归档

此分支只保存 HyperBackground 的历史源码 ZIP 与 ZIP 构建链。

项目介绍、当前正式版功能、安装方式和本地构建说明请查看 [`main`](https://github.com/Solomonstery/HyperBackground/tree/main) 分支。

## 归档规则

- 源码包命名为 `HyperBackground-v<version>-source.zip`。
- 每个 ZIP 必须包含完整 Android 工程、`build.sh` 和固定签名文件。
- 历史源码包保留在本分支，不放回 `main`。
- 同一版本需要修订时覆盖对应 ZIP，不重复堆放同名副本。

## ZIP 构建链

1. GitHub Actions 从本分支选择版本号最高的源码 ZIP。
2. 校验压缩包并识别根目录或 `HyperBackground/` 包裹目录。
3. 使用 Java 17、Android SDK 和源码内 `build.sh` 构建 Release APK。
4. 校验固定签名证书。
5. 上传 Actions 产物并同步到 GitHub Releases。
6. 根据 APK 实际版本生成发行说明；非正式版本自动标记为 Pre-release。

## 作者

**苍簇**

- 酷安：https://www.coolapk.com/u/18795532
- GitHub：https://github.com/Solomonstery/HyperBackground
