plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 依赖版本：统一在此声明 ----
// 既用于下方 dependencies，也会写进 BuildConfig.DEP_VERSIONS，供「关于」页的「依赖版本」展示（改版本只需改这里）。
val miuixVersion = "0.9.4"                 // miuix 源码在 ./miuix，由 includeBuild 参与构建，此处为其版本号
val backdropVersion = "2.0.1"
val activityComposeVersion = "1.13.0"
val coreKtxVersion = "1.19.1"
val webkitVersion = "1.18.0"
val credentialsVersion = "1.6.0"
val browserVersion = "1.10.0"
// 历史 / 书签 / 脚本的加密库：SQLCipher（AES-256 整库加密）及其接口依赖 androidx.sqlite
val sqlcipherVersion = "4.14.1"
val sqliteVersion = "2.4.0"
// 加密引擎：ML-KEM-768（FIPS 203）与 X25519 用 BouncyCastle 轻量 API（需 ≥ 1.79 才有 ML-KEM）
val bouncyCastleVersion = "1.82"
val okhttpVersion = "5.5.1"
// Git 同步：JGit 5.13 是最后一个面向 Java 8 的版本，Android 上可用（6.x 起要求 Java 11）
val jgitVersion = "5.13.3.202401111512-r"
// Deploy key（SSH）：JGit 的 JSch 传输 + mwiede 维护的 JSch 分支（同包名直接替换，支持 ed25519 / rsa-sha2；原版 0.1.55 连 GitHub 已不可用）
val jschVersion = "0.2.21"
val navigationEventVersion = "1.2.0"
// 构建工具链：Kotlin / Compose Multiplatform / AGP 的版本声明在根 build.gradle.kts（miuix 的在 miuix/gradle/libs.versions.toml），
// Gradle 版本在 gradle/wrapper/gradle-wrapper.properties；升级时请同步这里，仅用于「关于」页展示
val kotlinVersion = "2.4.21"
val composeMultiplatformVersion = "1.12.2"
val agpVersion = "9.5.1"

// 格式：分组|名称|版本;分组|名称|版本…（分组用 ASCII 标识，界面文案由 AboutScreen 映射）
val dependencyVersions = listOf(
    Triple("ui", "miuix-glass", miuixVersion),
    Triple("ui", "Backdrop", backdropVersion),
    Triple("ui", "Compose Multiplatform", composeMultiplatformVersion),
    Triple("androidx", "Activity Compose", activityComposeVersion),
    Triple("androidx", "Core KTX", coreKtxVersion),
    Triple("androidx", "WebKit", webkitVersion),
    Triple("androidx", "Credentials", credentialsVersion),
    Triple("androidx", "Browser", browserVersion),
    Triple("androidx", "NavigationEvent", navigationEventVersion),
    Triple("androidx", "SQLite", sqliteVersion),
    Triple("storage", "SQLCipher", sqlcipherVersion),
    Triple("engine", "BouncyCastle", bouncyCastleVersion),
    Triple("sync", "OkHttp（WebDAV）", okhttpVersion),
    Triple("sync", "OkHttp Logging", okhttpVersion),
    // JGit 完整版本号带构建时间戳（5.13.3.202401111512-r），界面只展示 5.13.3
    Triple("sync", "JGit（Git）", jgitVersion.substringBefore("-r").substringBeforeLast('.')),
    Triple("sync", "JSch（SSH）", jschVersion),
    Triple("build", "Kotlin", kotlinVersion),
    Triple("build", "Android Gradle Plugin", agpVersion),
    Triple("build", "Gradle", gradle.gradleVersion),
).joinToString(";") { (group, name, version) -> "$group|$name|$version" }

// 加密算法：与 CipherSuite.kt / Backup.kt 实际使用的算法保持一致；格式同上，版本为算法标准 / 参数版本，库版本取自 bouncyCastleVersion
val cryptoAlgorithms = listOf(
    Triple("kem", "ML-KEM-768（默认）", "FIPS 203"),
    Triple("kem", "ML-KEM-1024", "FIPS 203"),
    Triple("kem", "X25519", "RFC 7748"),
    Triple("sym", "AES-256-GCM", "SP 800-38D"),
    Triple("kdf", "HKDF-SHA-384 / 512", "RFC 5869"),
    Triple("kdf", "Argon2id（备份口令）", "v1.3 · RFC 9106"),
    Triple("lib", "BouncyCastle", bouncyCastleVersion),
).joinToString(";") { (group, name, version) -> "$group|$name|$version" }

android {
    namespace = "dev.dsh.miuixbrowser"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.dsh.miuixbrowser"
        minSdk = 33
        targetSdk = 37
        versionCode = 2100
        versionName = "v2.1.0-stable"
        buildConfigField("String", "DEP_VERSIONS", "\"$dependencyVersions\"")
        buildConfigField("String", "CRYPTO_ALGORITHMS", "\"$cryptoAlgorithms\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        val keystorePath = System.getenv("MAFARI_KEYSTORE")
        if (keystorePath != null && rootProject.file(keystorePath).exists()) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("MAFARI_STORE_PASS")
                keyAlias = System.getenv("MAFARI_KEY_ALIAS")
                keyPassword = System.getenv("MAFARI_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{DEPENDENCIES,INDEX.LIST}"
            excludes += "/META-INF/versions/**/module-info.class"
            // JGit 各模块（core / ssh.jsch）都带 Eclipse OSGi 元数据，同名冲突；运行时用不到
            excludes += "/plugin.properties"
            excludes += "/about.html"
            excludes += "/.api_description"
        }
    }
}


dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-glass-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-glass-icons-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:$miuixVersion")
    // Kyant0/AndroidLiquidGlass（KMP 版 2.x，Android 变体）
    implementation("io.github.kyant0:backdrop-android:$backdropVersion")

    implementation("androidx.activity:activity-compose:$activityComposeVersion")
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("androidx.webkit:webkit:$webkitVersion")
    // Passkey：WebView 通过 Credential Manager 调起系统 / 密码管理器的 Passkey 界面
    implementation("androidx.credentials:credentials:$credentialsVersion")
    implementation("androidx.credentials:credentials-play-services-auth:$credentialsVersion")
    implementation("androidx.browser:browser:$browserVersion")
    implementation("androidx.navigationevent:navigationevent-compose:$navigationEventVersion")
    // 后量子混合加密引擎（CryptoEngine.kt）
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")
    // WebDAV 同步（PROPFIND / MKCOL 等扩展方法 HttpURLConnection 不支持）
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    // 日志拦截器：仅 debug 构建在 WebDavClient 里启用（脱敏 Authorization，不记录请求体）
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")
    // Git 同步（纯 Java 实现，无需系统 git）
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:$jgitVersion") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation("com.github.mwiede:jsch:$jschVersion")
    testImplementation("junit:junit:4.13.2")
    // 历史记录：SQLCipher 加密数据库（HistoryDb，整库 AES-256）；androidx.sqlite 是它的接口依赖
    implementation("net.zetetic:sqlcipher-android:$sqlcipherVersion@aar")
    implementation("androidx.sqlite:sqlite:$sqliteVersion")
}

