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
