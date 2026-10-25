# HyperIsleOpen · 超级岛通行证

一个基于 [libxposed](https://github.com/libxposed)（API 102）的 Xposed 模块（LSPosed 作用域模式），面向 **MIUI / HyperOS** 的两项增强：

1. **剔除小米超级岛上岛限制**（作用域 `com.android.systemui`）——解除 DynamicIsland 插件对焦点通知的云端白名单/签名校验，黑白名单规则回归用户本地掌控；
2. **实况通知上岛**（作用域 `com.spark.noticeflow`）——将实况通知内嵌 HyperNotification SDK 生成的实况强制映射到**系统原生超级岛**，关闭 Shizuku 断网绕过，全程走原生焦点通知通道。

## 功能特性

- **六道门禁剔除**：对 `miui.systemui.notification.NotificationSettingsManager` 的焦点通知门禁逐一 hook（纯包名判定，无签名校验），bypass 开关可即时启停；
- **SDK 映射**：强制 hyper 引擎模式与原生路由（`shouldUseHyper` / `isHyperAvailable` / `hasFocusPermission`），`postWithBypassIfReady` 改为强制直发；
- **黑白名单**：白/黑名单相互独立、可叠加、各自开关；支持「个性名单」（JSON 文件）与全局名单并集生效；
- **配置热更新**：广播通道 + `LIST_PUSH` 权威同步协议——指纹（FNV-1a）校验闭环、推送带前台优先级抗 MIUI Greezer cached 拦截、3s 超时兜底直推；
- **自检探测**：QUERY/STATUS 心跳回报注入明细，超时分级判定（已加载/进程存活/无法判定三态），不把「延迟」误报成「未激活」。

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 15（API 35）/ Android 16（API 36） |
| ROM | MIUI / HyperOS（实证：Redmi Note 14 Pro+ 5G，HyperOS OS3.0.303.0.WOPCNXM） |
| 框架 | LSPosed（Zygisk），API ≥ 101 |
| 作用域 | `com.android.systemui`、`com.spark.noticeflow`（实况通知可选，未安装时自动跳过相关项） |

## 构建

```bash
# 调试构建（无需签名配置）
./gradlew assembleDebug

# 单元测试（47 项）
./gradlew testDebugUnitTest

# release 构建
./gradlew assembleRelease
```

签名说明：仓库**不含**签名密钥。`app/qingyun.jks` 存在且 `~/.gradle/gradle.properties` 中配置了 `signing.storePassword` / `signing.keyAlias` / `signing.keyPassword` 时，release 自动签名；否则产出未签名包，放入自有 jks 后重构建即可。

## 发布流程

源码与构建产物统一经发布脚本上传（tag 名称即版本名）：

```bash
./release.sh 1.0.24          # 正式版：tag=v1.0.24，Release 标记 Latest
./release.sh 1.0.24-beta1    # 测试版：tag=v1.0.24-beta1，Release 标记 Pre-release
```

脚本自动完成：versionCode +1 → 提交 → 打 tag → 单元测试 → release 构建 → 推送源码与 tag → 创建 GitHub Release 并上传 APK。需 `gh` CLI 已授权；签名属性置于用户级 `~/.gradle/gradle.properties`（不入库）。

## 目录结构

```
app/src/main/java/com/island/pass/
├── data/          # 配置模型、广播通道、名单同步协议（ListSync/ListSyncCoordinator）
├── hook/          # 双作用域 Hook 实现（SystemUiHooker / NoticeFlowHooker）
├── ui/            # Compose + Miuix 设置界面
└── IslandModuleEntry.kt   # libxposed 模块入口
```

## 免责声明

本项目仅供学习与研究 Xposed 模块开发与 Android 系统机制之用。Hook 系统组件存在稳定性风险，请自行评估后使用；因使用本模块导致的任何问题，作者不承担任何责任。

## 协议

本项目自身代码以 [MIT](LICENSE) © 2026 QingYun 发布。

依赖的第三方组件按其上游许可证授权，其中 **HookTool 为 LGPL-2.1**（未修改，仅作依赖使用）。完整的组件清单、许可证与源码链接见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
