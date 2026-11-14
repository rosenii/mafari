# mafari proguard rules
-dontwarn org.jetbrains.compose.**
-keep class dev.dsh.miuixbrowser.** { *; }
# GeckoView：JNI 与反射会按名字找类，混淆 / 裁剪会直接崩
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
# BouncyCastle（加密引擎只用轻量 API，无反射；忽略它可选依赖的告警）
-dontwarn org.bouncycastle.**
# OkHttp（WebDAV 同步）：可选的 TLS 提供者 / 平台类告警
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# JGit（Git 同步）：可选的 SSH / GSS / JMX / 日志后端，不用到
-dontwarn org.eclipse.jgit.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
-dontwarn com.googlecode.javaewah.**
# JGit / JSch 大量按名字反射：JGitText 的字段由 NLS 按名字赋值，JSch 的算法类按配置里的类名 Class.forName；
# 混淆或裁剪会让 Git 同步在 release 包里失效，整体保留（体积代价可接受）
-keep class org.eclipse.jgit.** { *; }
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# SQLCipher（历史记录库）：JNI 按名字回调 Java 类，混淆会直接崩
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# Firefox 账号 / Sync（application-services：UniFFI + JNA 靠反射加载）
-keep class mozilla.appservices.** { *; }
-keep class mozilla.components.service.fxa.** { *; }
-keep class mozilla.components.browser.storage.sync.** { *; }
-keep class mozilla.components.concept.fetch.** { *; }
-keep class mozilla.components.lib.fetch.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
