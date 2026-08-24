# 私有签名

从 1.3.6 起，Release APK 只通过 GitHub Actions Repository Secrets 注入的 PKCS#12 私钥签名。

仓库和源码归档不得包含 `.jks`、`.keystore`、`.p12`、`.pfx` 或真实密码。构建需要以下环境变量：

- `HYPERBG_KEYSTORE_PATH`
- `HYPERBG_KEYSTORE_PASSWORD`
- `HYPERBG_KEY_ALIAS`
- `HYPERBG_KEY_PASSWORD`

GitHub Actions 使用以下 Repository Secrets：

- `HYPERBG_KEYSTORE_BASE64`
- `HYPERBG_KEYSTORE_PASSWORD`
- `HYPERBG_KEY_ALIAS`
- `HYPERBG_KEY_PASSWORD`

1.3.6 及后续版本的证书 SHA-256 指纹：

`A1:75:5A:BE:D4:13:5A:38:17:B0:D8:76:0F:40:BE:A5:D3:B7:00:1B:08:A8:29:EA:1D:3C:DA:90:FB:D4:4F:4B`

1.3.5 及更早公开源码使用旧签名。首次迁移到 1.3.6 时无法直接覆盖旧签名安装包，需要先备份模块配置并卸载旧版；从 1.3.6 起签名保持不变，可继续覆盖升级。
