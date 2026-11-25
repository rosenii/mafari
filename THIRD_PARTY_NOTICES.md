# 第三方声明 / THIRD PARTY NOTICES

Mafari 自有代码的版权与许可见根目录 `licence`（源码可见、保留所有权利）。
本文件列出本软件包含、链接或在构建中使用的第三方组件。这些组件**不适用** `licence`，而是各自适用其原始许可证；
各许可证全文见 `third-party-licenses/`。

Mafari's own code is covered by `licence` (source-available, all rights reserved). The components below are **not** covered by it;
each remains under its original license, whose full text is in `third-party-licenses/`.

---

## 1. 随安装包分发或链接的组件 / Components distributed or linked

下表是 `app/build.gradle.kts` 里声明的运行时依赖（版本以该文件为准，不在此重复，避免过期）。
「gecko / sync_lite / lite」三个版本打包的组件不完全相同：GeckoView 只在 gecko 版本；Firefox Sync 相关库只在 gecko 与 sync_lite 版本；lite 版本两者都不打包（仅编译期引用）。

| 组件名称 / Component | 版权声明 / Copyright Notice | 许可证 / License | 来源链接 / Source |
|---|---|---|---|
| miuix（含 miuix-glass、glass-icons 等；本仓库对其打了补丁，见 tools/miuix-patches） | Copyright (c) compose-miuix-ui contributors | Apache-2.0 | https://github.com/compose-miuix-ui/miuix |
| Backdrop / AndroidLiquidGlass（io.github.kyant0:backdrop；其 catalog 示例代码被移植进 InteractiveHighlight.kt、DampedDragAnimation.kt，液态开关部分在 LiquidGlassComponents.kt） | Copyright (c) Kyant | Apache-2.0 | https://github.com/Kyant0/AndroidLiquidGlass |
| Kyant Shapes（Backdrop 的传递依赖） | Copyright (c) Kyant | Apache-2.0 | https://github.com/Kyant0/Shapes |
| Kotlin / kotlinx.coroutines | Copyright (c) JetBrains s.r.o. and Kotlin contributors | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Compose Multiplatform / Jetpack Compose | Copyright (c) JetBrains s.r.o.; The Android Open Source Project | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| AndroidX：Activity、Core KTX、WebKit、Credentials、Browser、CameraX、NavigationEvent、Lifecycle、SQLite、Media3（ExoPlayer / HLS） | Copyright (c) The Android Open Source Project | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| OkHttp（含 logging-interceptor）、Okio | Copyright (c) Square, Inc. | Apache-2.0 | https://github.com/square/okhttp |
| JavaEWAH（JGit 的传递依赖） | Copyright (c) Daniel Lemire, Cliff Moon, Veronika Zenz and contributors | Apache-2.0 | https://github.com/lemire/javaewah |
| JNA（Mozilla application-services 的传递依赖；Apache-2.0 / LGPL-2.1 双许可，此处按 Apache-2.0 使用） | Copyright (c) Todd Fast, Timothy Wall and contributors | Apache-2.0 OR LGPL-2.1 | https://github.com/java-native-access/jna |
| SQLCipher for Android（net.zetetic:sqlcipher-android） | Copyright (c) ZETETIC LLC | BSD-style（见 third-party-licenses/SQLCipher-BSD-style.txt） | https://github.com/sqlcipher/sqlcipher-android |
| Bouncy Castle（bcprov-jdk18on） | Copyright (c) The Legion of the Bouncy Castle Inc. | MIT-style（Bouncy Castle License） | https://www.bouncycastle.org/ |
| Eclipse JGit（org.eclipse.jgit、org.eclipse.jgit.ssh.jsch） | Copyright (c) Eclipse Foundation, Inc. and contributors | EDL-1.0（BSD-3-Clause） | https://github.com/eclipse-jgit/jgit |
| JSch（com.github.mwiede:jsch，JCraft JSch 的维护分支） | Copyright (c) JCraft, Inc.; mwiede/jsch contributors | BSD-3-Clause 风格 | https://github.com/mwiede/jsch |
| SLF4J API（JGit 的传递依赖） | Copyright (c) QOS.ch Sarl and contributors | MIT | https://www.slf4j.org/ |
| GeckoView（仅 gecko 版本；libxul.so 内还含 Gecko 自带的众多第三方组件，其声明见 Mozilla 发布的 about:license） | Copyright (c) Mozilla Foundation and contributors | MPL-2.0 | https://www.mozilla.org/MPL/2.0/ · https://firefox-source-docs.mozilla.org/mobile/android/geckoview/ |
| Mozilla android-components 与 application-services（Firefox 帐户 / Sync / 登录同步 / viaduct / init_rust_components / concept-* / support-* / lib-dataprotect；gecko 与 sync_lite 版本）及其传递的 Glean | Copyright (c) Mozilla Foundation and contributors | MPL-2.0 | https://github.com/mozilla/application-services · https://github.com/mozilla-mobile/firefox-android |
| Google ML Kit Barcode Scanning（com.google.mlkit:barcode-scanning，内置模型） | Copyright (c) Google LLC | ML Kit Terms of Service（专有，非开源；见 third-party-licenses/MLKit-Terms.md） | https://developers.google.com/ml-kit/terms |

### 许可证全文 / License texts

| 许可证 | 文件 |
|---|---|
| Apache License 2.0 | `third-party-licenses/Apache-2.0.txt` |
| Mozilla Public License 2.0 | `third-party-licenses/MPL-2.0.txt` |
| SQLCipher（BSD 风格） | `third-party-licenses/SQLCipher-BSD-style.txt` |
| Eclipse Distribution License 1.0（JGit） | `third-party-licenses/JGit-EDL-1.0.txt` |
| JSch（BSD-3-Clause 风格） | `third-party-licenses/JSch-BSD-3-Clause.txt` |
| Bouncy Castle License（MIT 风格） | `third-party-licenses/BouncyCastle-MIT.txt` |
| MIT（SLF4J） | `third-party-licenses/MIT-slf4j.txt` |
| ML Kit 服务条款（专有） | `third-party-licenses/MLKit-Terms.md` |

---

## 2. 源码层面的第三方来源 / Third-party code inside this repository

以下文件含有来自第三方的代码或资源，**不适用** `licence`，沿用来源的许可证（Apache-2.0）；文件内保留各自的来源注释和版权声明：

| 文件 | 来源 | 许可证 |
|---|---|---|
| `ui/InteractiveHighlight.kt` | 移植自 Kyant0/AndroidLiquidGlass catalog 示例（仅改包名） | Apache-2.0 |
| `ui/DampedDragAnimation.kt` | 移植自 Kyant0/AndroidLiquidGlass catalog 的 DampedDragAnimation | Apache-2.0 |
| `ui/LiquidGlassComponents.kt` 中的液态开关（LiquidToggle） | 直接移植自 Kyant0/AndroidLiquidGlass catalog；该文件其余部分为本项目自有代码 | Apache-2.0（移植部分） |
| `ui/OnboardingBackground.kt` | 着色器与配色改自 miuix 示例的 BgEffect | Apache-2.0 |
| `ui/icons/Favorites.kt`、`Settings.kt`、`WorldClock.kt` | 拷自 miuix-glass-icons，Copyright 2026, compose-miuix-ui contributors | Apache-2.0 |
| `tools/miuix-patches/*.patch` | 针对 miuix 上游源码的补丁（补丁所改动的 miuix 代码仍属 miuix） | Apache-2.0 |
| `gradlew`、`gradlew.bat`、`gradle/wrapper/` | Gradle Wrapper | Apache-2.0 |

（路径均相对 `app/src/main/java/dev/dsh/miuixbrowser/`，`tools/`、`gradle*` 相对仓库根目录。）

构建时 `tools/miuix-sync.sh` 会把 miuix 上游源码拉到 `third_party/miuix` 并打补丁后参与构建；该目录**不属于**本仓库内容，其许可证以上游仓库为准。

## 3. 运行时下载、不随包分发 / Fetched at runtime, not bundled

- **Floccus**：`FloccusPatcher` 在用户操作时从 Floccus 官方 GitHub Release 下载拓展并在用户设备上本地改写，Mafari 的安装包与仓库都不含其代码。Floccus 以 MPL-2.0 发布（https://github.com/floccusaddon/floccus）。
- **用户安装的拓展 / 用户脚本**：版权与许可归各自作者，Mafari 不分发它们。

## 4. 仅致谢，未包含代码 / Acknowledgements (no code included)

- `mafari_reader.js` 的阅读模式提取逻辑沿用了 Mozilla Readability 的启发式思路（Apache-2.0，https://github.com/mozilla/readability），按注释为自行实现的简化版。若其中有直接拷贝的代码，需要另行补充 Readability 的版权声明。

## 5. 仅用于构建 / 测试，不进入安装包 / Build and test only

Android Gradle Plugin、Gradle、Kotlin 编译器与 Compose 编译器插件、JUnit 4（EPL-1.0）、`org.json`（JSON License，仅测试）等只在开发与构建期使用，不随安装包分发。

---

## 6. 分发安装包时的义务提示 / Obligations when shipping the APK

1. **Apache-2.0 组件**：保留许可证文本与版权声明；上游若带 NOTICE 文件，需在分发物中一并保留其内容。
2. **MPL-2.0 组件（GeckoView、android-components、application-services、Glean）**：Mafari 没有修改这些组件的源码；若将来修改了 MPL 覆盖的文件，需以 MPL-2.0 向接收者提供这些被修改文件的源码。上游源码获取方式见上表链接。
3. **BSD / MIT / EDL 组件**：在文档或「关于」页中保留版权声明和许可证文本（即本文件与 `third-party-licenses/`）。
4. **ML Kit** 与 **Google Play 服务**（`credentials-play-services-auth` 会引入）是专有软件，受 Google 条款约束；它们不在 `licence` 与任何开源许可证之内。
5. 建议在应用「关于」页提供入口，展示本文件内容。

## 7. 说明与核对 / Notes

- 版权人和年份按各上游仓库或包元数据整理；个别条目（如 SQLCipher、JSch、JGit、Bouncy Castle、SLF4J）未写具体年份，因为上游随版本变化，请以所用版本的上游 LICENSE 文件为准，发版前核对一次。
- 依赖版本更新、增删依赖时，请同步更新本文件和 `licence` 第五节的表。
- 本文件是整理参考，不构成法律意见。
