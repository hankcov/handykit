# ADR 0001：构建工具链版本

- 日期：2026-11-27
- 状态：**已验证**——`assembleDebug` 成功产出 APK

## 最终决定

| 组件 | 版本 | 依据 |
|---|---|---|
| Android Gradle Plugin | 9.1.1 | 最新稳定 AndroidX 库要求 AGP 9.1.0+ |
| Gradle | 9.3.1 | AGP 9.1.1 的最低及默认版本 |
| Kotlin（AGP 内置） | 2.2.10 | AGP 9.1.1 内置，不单独应用 KGP |
| Compose BOM | 2025.06.01（Compose 1.8） | 2026.11 版（Compose 1.13）用 Kotlin 2.4 编译，与内置 2.2 冲突 |
| core-ktx | 1.16.0 | 与 Kotlin 2.2 兼容 |
| lifecycle | 2.9.4 | 同上 |
| activity-compose | 1.10.1 | 同上 |
| navigation-compose | 2.9.1 | 同上 |
| datastore-preferences | 1.1.7 | 同上 |
| kotlinx-serialization-json | 1.8.1 | 同上 |
| kotlinx-coroutines-android | 1.10.2 | 同上 |
| compileSdk | 37（平台目录 37.2） | Compose 及最新库要求 API 37+ |
| targetSdk | 36 | 保持技术设计基线，不一次性引入新运行时行为 |
| minSdk | 26 | 两个迁入项目的最低版本 |
| JDK | 21 | 本机 D:/software/jdk-21 |

## 变更历史与踩坑记录

1. **初版 AGP 8.13.2 + compileSdk 36**：首次编译失败。Gradle 明确报出 core-ktx 1.19.1、lifecycle 2.11.1、compose-ui 1.13.0 要求 compileSdk 37 与 AGP 9.1.0+。
2. **过渡开关方案**：升到 AGP 9.1.1 但设 `android.newDsl=false` / `android.builtInKotlin=false` 保留旧脚本写法。编译长期卡在 `kspDebugKotlin` / `compileDebugKotlin`，线程栈显示 Gradle 在 `DefaultBuildControllers.awaitCompletion` 等待子构建，未收敛。
3. **内置 Kotlin 方案**：移除 `org.jetbrains.kotlin.android` 插件，只保留 `kotlin-compose`（传递固定 KGP 版本）。编译推进到真实代码错误，证明工具链正确。
4. **stdlib 版本冲突**：Compose 2026.11（1.13）传递 kotlin-stdlib 2.4.10，编译器 2.2.0 无法读取 metadata 2.4。尝试把 KGP 提到 2.4.10，与 AGP 9.1 内置版本冲突、daemon 反复重启。最终将 Compose BOM 降到 2025.06.01，并把 AndroidX 库对齐到 Kotlin 2.2 时代版本。
5. **代码错误**：`NavOrderStore` 把 `Application` 扩展用在 `Context` 上，改为 `context.applicationContext as Application`。之后编译成功。

## 工程实践记录

- Room 与 KSP 在骨架阶段移除：没有任何 `@Entity`，提前引入只增加构建负担。定义实体时再加回。
- SDK 与 Gradle 均装在用户目录 `D:/software/`，无需管理员权限。SDK 27M 的 platform 37.2 需手动下载解压（sdkmanager 12.0 读旧仓库索引，看不到 API 37 包）。
- Gradle 发行版走腾讯云镜像；官方 services.gradle.org 重定向 GitHub releases，本机下载不稳定。
- daemon 反复切换版本会留下卡死的 Gradle 进程，编译异常慢时先清理 java 进程。

## 验证证据

`./gradlew assembleDebug`（2026-11-27）：BUILD SUCCESSFUL，36 tasks，1m34s。产物 `app-debug.apk` 18MB，`aapt dump badging` 确认 package=com.handykit.app、label=手边、minSdk=26、targetSdk=36、compileSdk=37。

已知非阻断警告：AGP 9 DSL 的 `android {}` 扩展访问在 AGP 10 会移除；`Icons.AutoMirrored` 已在本轮修正。这些不阻断当前构建。

升级到 AGP 10 前需单独做 DSL 迁移并新建 ADR。
