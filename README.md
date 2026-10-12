# ADhosts

ADhosts 是使用 Magisk、KernelSU 或 APatch 模块管理 Android Hosts 的应用，支持订阅源、手动规则、规则合并与 DNS 切换。

## 使用要求

- Android 10 或更高版本（API 29+）
- 可用的 Root 管理器及 Root 授权
- KernelSU 3+ 和 APatch 11219+ 需要启用兼容的 MetaModule；安装或更新挂载模块后需要重启

## 主要功能

- 为每个订阅保存独立规则文件，启停时移动文件并重建 Hosts
- 下载、合并和去重多个 Hosts 订阅，手动规则优先
- 使用 MT 管理器编辑独立的手动规则，保存返回后校验并合并
- 导入、导出订阅与手动规则
- 开启或关闭 Hosts 拦截，以及选择 DNS
## 构建

使用 JDK 17 和 Android SDK 构建：

```text
./gradlew assembleDebug
```

