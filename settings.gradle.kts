pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView（Mozilla 官方 Maven，nightly 与 release 都在这里）
        maven("https://maven.mozilla.org/maven2") {
            content { includeGroupByRegex("org\\.mozilla.*") }
        }
    }
}

// miuix 上游源码：由 tools/miuix-sync.sh 拉取到 third_party/miuix（版本见 miuix.ref，本地改造见 tools/miuix-patches），
// 再通过 includeBuild 把 Maven 坐标替换为源码工程，无需 publishToMavenLocal。
val miuixDir = file("third_party/miuix")
if (!miuixDir.resolve("settings.gradle.kts").exists()) {
    throw GradleException("缺少 third_party/miuix，请先运行 tools/miuix-sync.sh")
}
includeBuild(miuixDir) {
    dependencySubstitution {
        substitute(module("top.yukonga.miuix.kmp:miuix-core-android")).using(project(":miuix-core"))
        substitute(module("top.yukonga.miuix.kmp:miuix-ui-android")).using(project(":miuix-ui"))
        substitute(module("top.yukonga.miuix.kmp:miuix-preference-android")).using(project(":miuix-preference"))
        substitute(module("top.yukonga.miuix.kmp:miuix-icons-android")).using(project(":miuix-icons"))
        substitute(module("top.yukonga.miuix.kmp:miuix-glass-icons-android")).using(project(":miuix-glass-icons"))
        substitute(module("top.yukonga.miuix.kmp:miuix-squircle-android")).using(project(":miuix-squircle"))
        substitute(module("top.yukonga.miuix.kmp:miuix-blur-android")).using(project(":miuix-blur"))
        substitute(module("top.yukonga.miuix.kmp:miuix-material-android")).using(project(":miuix-material"))
        substitute(module("top.yukonga.miuix.kmp:miuix-glass-android")).using(project(":miuix-glass"))
        substitute(module("top.yukonga.miuix.kmp:miuix-nav-android")).using(project(":miuix-nav"))
    }
}

rootProject.name = "MiuixBrowser"
include(":app")
