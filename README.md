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
- 在“关于”页检查 GitHub 正式发布版，下载 APK 并通过 Root 安装

## 构建

使用 JDK 17 和 Android SDK 构建：

```text
./gradlew assembleDebug
```

发布版 APK 需要将签名文件放在 `app/adhosts_release.keystore`，并在构建环境中提供 `ADHOSTS_STORE_PASSWORD`、`ADHOSTS_KEY_PASSWORD` 和可选的 `ADHOSTS_KEY_ALIAS`。签名文件和密码不应提交到仓库。

## 发布应用更新

在 GitHub Releases 创建正式发布版，标签使用 `v<versionName>`（例如 `v2.3.0`），并上传可直接安装的 APK。应用的“检查应用更新”会读取最新正式发布版、比较版本号并展示发布说明。单个 APK 会下载到应用私有缓存，校验包名、版本号、签名以及可用的 SHA-256 摘要后，通过 Root 执行 `pm install -r`，安装后删除临时文件；多个 APK 时打开发布页供用户选择。下载失败或安装失败也会清理临时文件。

新版 APK 应保持应用包名一致，`versionCode` 高于已安装版本，并使用与已安装版本相同的签名证书。为兼容已有安装，2.2.1 沿用原证书。旧签名密钥曾进入仓库历史，存在被滥用风险；后续应规划签名轮换及旧设备迁移。
