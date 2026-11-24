package com.netcontrol

import android.app.Application
import com.netcontrol.di.AppContainer
import kotlinx.coroutines.launch

class NetControlApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.ruleEngine.start()
        container.networkStateMonitor.start()
        // 启动后异步执行一次幂等种子初始化与全量应用增量同步，随后刷新一次规则引擎
        container.appScope.launch {
            container.repository.syncAllInstalledApps()
            container.gkdSubscriptionRepository.ensureBuiltinSeedAndReload()
            container.networkStateMonitor.refreshNow("APP_STARTUP_AFTER_SYNC")
            container.exactAlarmScheduler.rescheduleNext("APP_STARTUP")
        }
    }
}
