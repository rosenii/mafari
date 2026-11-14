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
// 火狐账号 / Sync：Mozilla android-components（maven.mozilla.org）。默认 "+" 取最新；API 偶尔会变，建议在 gradle.properties 写 fxaComponentsVersion=<版本> 锁定
val fxaComponentsVersion = (project.findProperty("fxaComponentsVersion") as String?) ?: "+"
// application-services（viaduct 等）的版本：默认由 fxaComponentsVersion 推出（去掉 b3 之类的 beta 后缀，如 161.0b3 -> 161.0）；
// fxaComponentsVersion 为 "+" 时同样用 "+"。想固定写 gradle.properties：appServicesVersion=161.0
val appServicesVersion = (project.findProperty("appServicesVersion") as String?)
    ?: if (fxaComponentsVersion == "+") "+" else fxaComponentsVersion.replace(Regex("b\\d+$"), "")
// GeckoView nightly（仅 arm64-v8a）。默认 "+" = 取 Mozilla Maven 上最新的 nightly；
// 要锁定版本（推荐，nightly 的 API 偶尔会改）在 gradle.properties 写 geckoViewVersion=152.0.20260801093011
val geckoViewVersion = (project.findProperty("geckoViewVersion") as String?) ?: "+"
// 构建工具链：Kotlin / Compose Multiplatform / AGP 的版本声明在根 build.gradle.kts（miuix 的在 miuix/gradle/libs.versions.toml），
// Gradle 版本在 gradle/wrapper/gradle-wrapper.properties；升级时请同步这里，仅用于「关于」页展示
val kotlinVersion = "2.4.21"
val composeMultiplatformVersion = "1.12.2"
val agpVersion = "9.5.1"

// 格式：分组|名称|版本;分组|名称|版本…（分组用 ASCII 标识，界面文案由 AboutScreen 映射）
// withGecko：无内核版（lite）不带 GeckoView，不在「依赖版本」里展示它
// withSync：普通 lite 不带 Firefox Sync（application-services），不展示它；gecko / liteSync 带
fun dependencyVersionsOf(withGecko: Boolean, withSync: Boolean): String = listOfNotNull(
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
    if (withGecko) Triple("engine", "GeckoView", if (geckoViewVersion == "+") "nightly（最新）" else geckoViewVersion) else null,
    Triple("engine", "BouncyCastle", bouncyCastleVersion),
    Triple("sync", "OkHttp（WebDAV）", okhttpVersion),
    Triple("sync", "OkHttp Logging", okhttpVersion),
    // JGit 完整版本号带构建时间戳（5.13.3.202401111512-r），界面只展示 5.13.3
    Triple("sync", "JGit（Git）", jgitVersion.substringBefore("-r").substringBeforeLast('.')),
    Triple("sync", "JSch（SSH）", jschVersion),
    if (withSync) Triple("sync", "Firefox Sync（application-services）", if (fxaComponentsVersion == "+") "最新" else fxaComponentsVersion) else null,
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
        versionCode = 20023
        versionName = "v2.1.23"
        // gecko 分支的 CI 构建号（GITHUB_RUN_NUMBER，与预发布 tag「gecko-<n>」一致）；dual 是正式发布，按版本号比较，不写构建号；其它构建为 0
        val geckoBuild = if (System.getenv("GITHUB_REF") == "refs/heads/gecko" || System.getenv("GITHUB_REF_NAME") == "gecko") {
            System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        } else 0
        buildConfigField("int", "GECKO_BUILD", geckoBuild.toString())
        buildConfigField("String", "CRYPTO_ALGORITHMS", "\"$cryptoAlgorithms\"")
    }

    // ---- 三个发布变体（product flavor，engine 维度）----
    //  gecko   ：内嵌 GeckoView（Nightly，仅 arm64-v8a），WebView / Gecko 双内核，带 Firefox 账号同步
    //  lite    ：无内核版，不打包任何浏览器内核，只用系统 WebView；体积小，支持所有 ABI；不带 Firefox 同步
    //  liteSync：lite + Firefox 同步（只构建 arm64：liteSyncArm64，输出名 sync_lite-arm64）
    // 共用同一份源码：lite / liteSync 里 GeckoView 只以 compileOnly 参与编译（见 dependencies），运行时不存在，
    // 共用代码通过 BuildConfig.GECKO_ENABLED（EngineSupport.GECKO_BUNDLED）保证不会走到 Gecko 类；
    // Firefox 同步库（application-services / android-components）同理：lite 只 compileOnly，
    // 共用代码通过 BuildConfig.SYNC_ENABLED（EngineSupport.SYNC_BUNDLED）保证不会走到 Fxa* 类。
    // Gecko 专属资源放在 src/gecko/assets，lite 不会打进包。
    // 第二维度 abi：按架构拆包（各架构一个 APK，而不是一个包塞全部 so）。
    // 构建时请指定具体任务（见 README / release.yml）：gecko 只有 arm64 有意义（GeckoView 只发布 arm64-v8a）
    flavorDimensions += listOf("engine", "abi")
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("arm32") { dimension = "abi"; ndk { abiFilters += "armeabi-v7a" } }
        create("x64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
        create("gecko") {
            dimension = "engine"
            // 可选：-PgeckoAppIdSuffix=.gecko 让两个版本能同时安装（默认不加后缀，保持与历史安装包同一个应用 ID，可直接覆盖升级）
            (project.findProperty("geckoAppIdSuffix") as String?)?.takeIf { it.isNotBlank() }?.let { applicationIdSuffix = it }
            buildConfigField("boolean", "GECKO_ENABLED", "true")
            buildConfigField("boolean", "SYNC_ENABLED", "true")
            buildConfigField("String", "DEP_VERSIONS", "\"${dependencyVersionsOf(true, true)}\"")
            // GeckoView nightly 只发布 arm64-v8a：Gecko 版只构建 geckoArm64；其余 ABI 的设备请用 lite
        }
        create("lite") {
            dimension = "engine"
            buildConfigField("boolean", "GECKO_ENABLED", "false")
            buildConfigField("boolean", "SYNC_ENABLED", "false")
            buildConfigField("String", "DEP_VERSIONS", "\"${dependencyVersionsOf(false, false)}\"")
            // 只有 SQLCipher 带 so；架构由 abi 维度决定：liteArm64 / liteArm32 / liteX64 各出一个包
        }
        create("liteSync") {
            dimension = "engine"
            // 与 lite 相同（无内核、同一个应用 ID，可互相覆盖升级），只多打包 Firefox 同步库；
            // 只构建 liteSyncArm64（不要构建 liteSyncArm32 / liteSyncX64）
            buildConfigField("boolean", "GECKO_ENABLED", "false")
            buildConfigField("boolean", "SYNC_ENABLED", "true")
            buildConfigField("String", "DEP_VERSIONS", "\"${dependencyVersionsOf(false, true)}\"")
        }
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
        // Gecko 的 libxul 等 so 需要解压到磁盘后加载
        jniLibs { useLegacyPackaging = true }
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
    // Firefox 账号登录 + Sync：见下方 syncLibs（gecko / liteSync 打包，普通 lite 仅 compileOnly）
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    testImplementation("junit:junit:4.13.2")
    // 纯 JVM 单元测试里 android.jar 的 org.json 只是桩：密码同步记录格式的测试需要真实实现
    testImplementation("org.json:json:20240303")
    // 历史记录：SQLCipher 加密数据库（HistoryDb，整库 AES-256）；androidx.sqlite 是它的接口依赖
    implementation("net.zetetic:sqlcipher-android:$sqlcipherVersion@aar")
    implementation("androidx.sqlite:sqlite:$sqliteVersion")
    // 第二内核：GeckoView nightly（arm64-v8a）
    // gecko 变体：正常打包；lite 变体：仅 compileOnly（让共用源码里的 Gecko 引用能通过编译），不进 APK
    "geckoImplementation"("org.mozilla.geckoview:geckoview-nightly-arm64-v8a:$geckoViewVersion")
    "liteCompileOnly"("org.mozilla.geckoview:geckoview-nightly-arm64-v8a:$geckoViewVersion")
    "liteSyncCompileOnly"("org.mozilla.geckoview:geckoview-nightly-arm64-v8a:$geckoViewVersion")

    // Firefox 账号登录 + Sync（Rust application-services，含各 ABI 的 libmegazord；APK 会增大约 10–15 MB）
    // gecko / liteSync：正常打包；普通 lite：仅 compileOnly（让共用源码里的 Fxa* 能通过编译），不进 APK
    val syncLibs = listOf(
        "org.mozilla.components:service-firefox-accounts:$fxaComponentsVersion",
        "org.mozilla.components:browser-storage-sync:$fxaComponentsVersion",
        // 密码同步：Rust logins 引擎（SyncableLoginsStorage）及其加密密钥存放（SecureAbove22Preferences）
        "org.mozilla.components:service-sync-logins:$fxaComponentsVersion",
        "org.mozilla.components:lib-dataprotect:$fxaComponentsVersion",
        // 这些是上面两个库的传递依赖（implementation 不会暴露给本模块的编译 classpath），直接用到其类型，需显式声明
        "org.mozilla.components:concept-base:$fxaComponentsVersion",
        // Rust 层（viaduct）本身没有网络栈：必须注册一个 HTTP 后端，否则取 Firefox 账号服务配置 / 登录地址会直接失败
        "org.mozilla.components:concept-fetch:$fxaComponentsVersion",
        // 新版 application-services：用 mozilla.appservices.viaduct.initBackend(Backend) 注册网络后端（见 FxaApi.OkHttpBackend）。
        // 注意是 viaduct，不是 httpconfig：httpconfig 已停更在 63.0.0（带进 Kotlin 1.3 的旧运行时，会触发 Duplicate class）。
        // application-services 版本号与 android-components 的 161.0b3 不同（161.0），可用 appServicesVersion 覆盖
        "org.mozilla.appservices:viaduct:$appServicesVersion",
        // 全局初始化（NSS 等）：application-services v138 起必须在任何 Rust 调用前执行 init_rust_components.initialize()
        "org.mozilla.appservices:init_rust_components:$appServicesVersion",
        "org.mozilla.components:concept-sync:$fxaComponentsVersion",
        "org.mozilla.components:concept-storage:$fxaComponentsVersion",
        "org.mozilla.components:concept-toolbar:$fxaComponentsVersion",
        "org.mozilla.components:support-base:$fxaComponentsVersion",
        "org.mozilla.components:support-ktx:$fxaComponentsVersion",
    )
    syncLibs.forEach {
        "geckoImplementation"(it)
        "liteSyncImplementation"(it)
        "liteCompileOnly"(it)
    }
}

// GeckoView 的 libxul.so 自带 Glean，而 glean-native（Firefox 账号库的传递依赖）也打包了一个同名 libxul.so 桩，
// 两者在 gecko 变体里冲突（mergeNativeLibs: 2 files found with path lib/arm64-v8a/libxul.so）。
// 与 Fenix 一致：gecko 变体排除 glean-native，用 GeckoView 里的；lite 没有 GeckoView，保留 glean-native。
configurations.configureEach {
    if (name.contains("gecko", ignoreCase = true)) exclude(module = "glean-native")
}

// 动态版本（"+"）每 6 小时才重新解析一次，避免每次构建都访问网络
configurations.all {
    resolutionStrategy.cacheDynamicVersionsFor(6, "hours")
}
