package dev.raccoon2api.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.raccoon2api.core.Prefs

/**
 * 开机自启：仅在用户此前主动开启过网关（desiredRunning=true）时才恢复。
 * 用户若在界面上关闭，或从最近任务中划掉，则不会被自动拉起。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val prefs = Prefs(context)
        if (prefs.autoStart && prefs.isConfigured && prefs.desiredRunning) {
            try {
                GatewayService.start(context)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
