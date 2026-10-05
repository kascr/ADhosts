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

