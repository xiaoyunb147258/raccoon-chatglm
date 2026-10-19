package dev.raccoon2api

import android.app.Application
import dev.raccoon2api.core.Prefs
import dev.raccoon2api.service.GatewayService

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        // 若用户上次处于「期望运行」状态，冷启动后自动恢复网关
        if (prefs.autoStart && prefs.isConfigured && prefs.desiredRunning) {
            try {
                GatewayService.start(this)
            } catch (e: Exception) {
                // 忽略：前台服务启动限制导致的失败，用户手动打开界面时会再试
            }
        }
    }
}
