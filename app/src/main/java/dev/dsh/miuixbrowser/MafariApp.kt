package dev.dsh.miuixbrowser

import android.app.Application

/**
 * 进程创建后第一时间开始预热加密库（见 [StartupWarmup]）。
 * GeckoView 会拉起子进程（内容进程 / 隔离进程），它们也会走 Application.onCreate，
 * 但子进程不需要这份库，所以只在主进程里预热。
 */
class MafariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == packageName) {
            Diag.init(this) // 先开诊断日志，预热线程里的分段耗时才记得下来
            Diag.log("MafariApp.onCreate")
            StartupWarmup.start(this)
            FaviconLoader.init(this) // 只定磁盘目录，修剪在后台
        }
    }
}
