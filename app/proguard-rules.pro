# mafari proguard rules
-dontwarn org.jetbrains.compose.**
-keep class dev.dsh.miuixbrowser.** { *; }
# GeckoView：JNI 与反射会按名字找类，混淆 / 裁剪会直接崩
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
# 普通 lite 里 Firefox 同步库只 compileOnly（不进 APK），共用源码里的 Fxa* 引用的 mozilla.* 类在 R8 里不存在，忽略告警
-dontwarn mozilla.**
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
# 扫一扫：ML Kit（内置版条码识别）靠 manifest 里的组件注册表 + 反射初始化，CameraX 内部也有反射；保留避免 release 里被裁掉 / 改名
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
# Legado 书源引擎：Rhino 按名字反射 Java 类（书源 JS 里的 Packages.java.* / android.*），jsoup 的可选依赖告警
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.w3c.dom.**
-dontwarn re2j.**
-dontwarn com.google.re2j.**

# Legado 书源脚本里的 java / cookie / source / cache 对象、请求返回值、book / chapter 等由 Rhino 按名字反射调用其 public 方法：
# release 里不能被 R8 改名 / 裁掉（否则脚本会报「Java 类 xx 没有名为 yy 的公共实例字段或方法」「找不到函数 raw」之类）
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoJavaBridge { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoCookieBridge { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoSourceBridge { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoResponse { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoRaw* { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoSymmetricCrypto { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoBook { public *; }
-keep class dev.dsh.miuixbrowser.books.data.legado.LegadoChapter { public *; }
