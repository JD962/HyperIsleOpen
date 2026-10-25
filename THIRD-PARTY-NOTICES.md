# 第三方组件与许可证声明

HyperIsleOpen 自身的源代码以 [MIT License](LICENSE) 发布。本项目在构建与运行时依赖以下第三方组件，各组件按其上游许可证授权。分发本项目的构建产物（APK）时，须随附本声明。

## 组件清单

| 组件 | 版本 | 许可证 | 源码 |
|---|---|---|---|
| HookTool | 3.2.2 | **LGPL-2.1** | https://github.com/HChenX/HookTool |
| libxposed（api / interface / service） | 102.0.0 | Apache-2.0 | https://github.com/libxposed/api |
| Miuix（ui / icons / preference） | 0.9.4 | Apache-2.0 | https://github.com/compose-miuix-ui/miuix |
| AndroidX（core-ktx / activity-compose / lifecycle） | 见构建脚本 | Apache-2.0 | https://github.com/androidx/androidx |
| Jetpack Compose BOM | 2026.09.00 | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| Material Components | 1.14.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| Kotlin 标准库 | 2.3.20 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| JUnit（仅单元测试，不进入 APK） | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |

## LGPL-2.1 特别声明（HookTool）

- 本项目**未修改** HookTool 的任何源代码，仅以其公开发布的构件（jitpack）作为依赖使用；
- HookTool 受 GNU Lesser General Public License v2.1 约束，许可证全文见 https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt ；
- HookTool 源码获取：https://github.com/HChenX/HookTool （版本 3.2.2）；
- 依据 LGPL-2.1 第 6 条，"使用该库的作品"可以本项目选择的条款（MIT）分发，条件是随附本通知、许可证全文与库源码获取途径——本文件即上述通知。

## Apache-2.0 组件声明

上述 Apache-2.0 组件随 APK 分发时保留其版权与许可声明。相关组件的声明文件包含在其 AAR 构件内，本项目未做修改。
