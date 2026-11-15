package dev.dsh.miuixbrowser

import android.app.Application

/**
 * 持有进程级容器 [container]，并在主进程里启动 [StartupCoordinator]（加密库预热等，见该类）。
 * GeckoView 会拉起子进程（内容进程 / 隔离进程），它们也会走 Application.onCreate，
 * 但子进程不需要这份库，所以启动编排只在主进程里做。
 */
class MafariApp : Application() {

    /** 进程内共享的设置 / 书签 / 历史 / 拓展存储（懒创建，见 [AppContainer]）。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (Application.getProcessName() == packageName) {
            StartupCoordinator.onProcessCreate(this)
        }
    }
}
