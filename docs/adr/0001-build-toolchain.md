# ADR 0001：构建工具链版本

- 日期：2026-11-27
- 状态：已选定，待首次构建验证

## 决定

| 组件 | 版本 | 依据 |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | 8.x 最终稳定版；支持 compileSdk 36，含 Kotlin 2.3 的 R8 修复 |
| Gradle | 8.13 | AGP 8.13 要求的最低及默认版本 |
| Kotlin Gradle Plugin | 2.2.10 | AGP 8.13 默认 KGP；满足 Compose 2026.11 要求的 Kotlin 2.2+ |
| Compose BOM | 2026.11.01 | 2026 年 11 月稳定版，Compose 1.13 / Material 3 1.5.0 |
| compileSdk / targetSdk | 36 | 技术设计基线；Compose 2026.11 标注需 37.1，见下 |
| minSdk | 26 | 两个迁入项目的最低版本 |
| JDK | 21 | 本机 D:/software/jdk-21；Gradle 8.13 支持 JDK 21 |

## 理由与放弃项

- **不选 AGP 9.x**：9.0 起启用新 DSL 与内置 Kotlin，`org.jetbrains.kotlin.android` 不再兼容，旧 Variant API 被移除，第三方插件适配仍在进行。此为个人项目，无需承担迁移成本。
- **compileSdk 36 与 Compose BOM 的张力**：Compose 2026.11.01 官方说明要求 compileSdk 37.1。首次构建时若出现 API 级别错误，优先将 compileSdk 提到 37.1 并更新本 ADR，而不是降级 Compose BOM 到旧版。targetSdk 维持 36，避免行为变更一次性引入。
- **JDK 不在 PATH**：本机 `JAVA_HOME=D:/software/jdk-21` 已设，但 `java` 不在 PATH；通过 Gradle `org.gradle.java.home` 或构建时显式指定，不修改系统 PATH。

## 验证状态

尚未构建。首次 `./gradlew assembleDebug` 成功并记录实际版本解析结果后，状态改为已验证。SDK 需安装到用户目录（无管理员权限）。
