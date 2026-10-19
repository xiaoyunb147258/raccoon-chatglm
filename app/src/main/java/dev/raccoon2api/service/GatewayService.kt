package dev.raccoon2api.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.raccoon2api.R
import dev.raccoon2api.core.Prefs
import dev.raccoon2api.gateway.GatewayServer
import dev.raccoon2api.gateway.OpenAiApi
import dev.raccoon2api.glm.GlmClient
import dev.raccoon2api.ui.MainActivity

/**
 * 前台服务：承载本地 OpenAI 兼容网关。
 * 只要用户没有手动停止，进程被系统回收后应重新拉起。
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "raccoon_gateway"
        private const val CHANNEL_NAME = "网关服务"
        private const val NOTIFICATION_ID = 3065

        const val ACTION_START = "dev.raccoon2api.START"
        const val ACTION_STOP = "dev.raccoon2api.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var server: GatewayServer? = null
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.desiredRunning = false
                stopGateway()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                prefs.desiredRunning = true
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
                startGateway()
            }
        }
        return START_STICKY
    }

    private fun startGateway() {
        if (server?.isRunning == true) {
            updateNotification("运行中 · 端口 ${prefs.port}")
            return
        }
        try {
            val client = GlmClient()
            val api = OpenAiApi(prefs, client)
            val srv = GatewayServer(prefs.port) { req ->
                prefs.requestCount = prefs.requestCount + 1
                api.handle(req)
            }
            srv.start()
            server = srv
            isRunning = true
            lastError = null
            prefs.lastError = null
            updateNotification("运行中 · 端口 ${prefs.port}")
            Log.i(TAG, "gateway started on ${prefs.port}")
        } catch (e: Exception) {
            isRunning = false
            lastError = e.message
            prefs.lastError = e.message
            Log.e(TAG, "gateway start failed", e)
            updateNotification("启动失败：${e.message}")
        }
    }

    private fun stopGateway() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop error", e)
        }
        server = null
        isRunning = false
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本地 AI 网关运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小浣熊 · 本地网关")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_raccoon)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
