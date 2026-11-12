# mafari proguard rules
-dontwarn org.jetbrains.compose.**
-keep class dev.dsh.miuixbrowser.** { *; }
# GeckoView：JNI 与反射会按名字找类，混淆 / 裁剪会直接崩
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
# BouncyCastle（加密引擎只用轻量 API，无反射；忽略它可选依赖的告警）
-dontwarn org.bouncycastle.**
