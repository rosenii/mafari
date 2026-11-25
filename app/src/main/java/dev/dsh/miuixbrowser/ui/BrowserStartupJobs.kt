/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.browser.UpdateDownload
import dev.dsh.miuixbrowser.browser.UpdatePhase
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.backup.BackupAuto
import dev.dsh.miuixbrowser.browser.UpdateChecker
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.dsh.miuixbrowser.script.ExtensionStore


/** 进程启动后的后台任务：Gecko 拓展定期更新、去广告初始化 / 订阅更新、推荐脚本引导安装与脚本更新、加密备份自动推送（从 BrowserScreen 拆出） */
@Composable
internal fun BrowserStartupJobs(
    settings: AppSettings,
    context: android.content.Context,
    extensions: ExtensionStore,
) {
    // Gecko 拓展定期检查更新：应用在前台、Gecko 模式下，启动后稍等一会儿检查一次，之后每 6 小时看一次是否到点
    LaunchedEffect(settings.geckoMode) {
        if (!settings.geckoMode) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        while (true) {
            runCatching { GeckoExtensionStore.maybeAutoUpdate(context) }
            kotlinx.coroutines.delay(6 * 3_600_000L)
        }
    }

    // 旧版本放在脚本列表里的去广告订阅 → 迁移到「去广告」页；之后按设置的间隔在后台更新订阅
    remember {
        AdBlock.init(context)
        AdBlock.migrateLegacy(extensions.legacyAdSubs.toList())
        extensions.commitLegacyMigration()
        true
    }
    // Gecko 模式下去广告用不上：不下载订阅（省流量 / CPU / 内存）；切回 WebView 时 settings.geckoMode 变化会重新触发
    LaunchedEffect(settings.geckoMode, settings.pageEnhancements) { if (!settings.geckoMode && settings.pageEnhancements) AdBlock.autoUpdateIfDue() }

    // 首次打开 app：自动拉取并安装推荐脚本（每个只自动装一次，失败的下次启动重试）；之后按设置的间隔后台自动更新脚本
    // 网页增强关着（WebView）：脚本不运行，也不去拉推荐脚本 / 检查脚本更新；Gecko 的脚本不受网页增强管，照旧
    LaunchedEffect(settings.geckoMode, settings.pageEnhancements) {
        if (!settings.geckoMode && !settings.pageEnhancements) return@LaunchedEffect
        runCatching { extensions.bootstrapRecommended() }
        runCatching { extensions.autoUpdateIfDue() }
    }
    // 按设置的间隔把加密备份自动推送到已配置的云同步目标（需已设默认口令）
    LaunchedEffect(Unit) { runCatching { BackupAuto.runIfDue(context) } }
}

/** 启动自动检查更新 + 更新包下载完成后重新弹出更新弹窗（从 BrowserScreen 拆出） */
@Composable
internal fun BrowserUiState.AppUpdateEffects(
    settings: AppSettings,
    context: android.content.Context,
) {
    // 启动自动检查更新：每个进程只查一次；有新版本且未被忽略时弹底部提示
    LaunchedEffect(Unit) {
        if (UpdateChecker.checkedThisProcess) return@LaunchedEffect
        UpdateChecker.checkedThisProcess = true
        // 稍等首屏稳定再请求，避免和启动渲染抢资源
        kotlinx.coroutines.delay(1500)
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            UpdateChecker.findPending(context, settings.ignoredUpdate, settings.updateChannel)
        }
        if (found != null) {
            // 引导页还在时先不弹更新提示，免得盖在引导上
            androidx.compose.runtime.snapshotFlow { settings.onboardingPending }.first { !it }
            lastUpdateRelease = found
            updateRelease = found
        }
    }

    // 弹窗收起后下载完成：把更新弹窗重新弹出，方便直接点「安装」（只处理从启动提示发起的下载）
    LaunchedEffect(UpdateDownload.phase) {
        val pending = lastUpdateRelease
        if (UpdateDownload.phase == UpdatePhase.DONE && UpdateDownload.origin == "launch" &&
            pending != null && pending.version == UpdateDownload.version && updateRelease == null
        ) {
            updateRelease = pending
        }
    }
}
