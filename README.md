# ADhosts

ADhosts 是使用 Magisk、KernelSU 或 APatch 模块管理 Android Hosts 的应用，支持订阅源、手动规则、规则合并与 DNS 切换。

## 使用要求

- Android 10 或更高版本（API 29+）
- 可用的 Root 管理器及 Root 授权

## 主要功能

- 为每个订阅保存独立规则文件，启停时移动文件并重建 Hosts
- 下载、合并和去重多个 Hosts 订阅，手动规则优先
- 手动添加和编辑 Hosts 规则
- 导入、导出订阅与手动规则
- 开启或关闭 Hosts 拦截，以及选择 DNS

## 构建

使用 JDK 17 和 Android SDK 构建：

```text
./gradlew assembleDebug
```

发布版 APK 需要将签名文件放在 `app/adhosts_release.keystore`，并在构建环境中提供 `ADHOSTS_STORE_PASSWORD`、`ADHOSTS_KEY_PASSWORD` 和可选的 `ADHOSTS_KEY_ALIAS`。签名文件和密码不应提交到仓库。
