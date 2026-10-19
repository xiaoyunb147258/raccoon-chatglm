package dev.raccoon2api.core

import android.content.Context

/**
 * 轻量配置存储。
 * 只保存运行网关所必需的信息：refresh_token、端口、是否自动启动。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("raccoon_prefs", Context.MODE_PRIVATE)

    /** 智谱清言 refresh_token（核心凭据，access_token 由它自动换取）。 */
    var refreshToken: String?
        get() = sp.getString(KEY_REFRESH_TOKEN, null)
        set(v) = sp.edit().putString(KEY_REFRESH_TOKEN, v).apply()

    /** 最近一次成功登录的用户标识（仅展示用）。 */
    var userId: String?
        get() = sp.getString(KEY_USER_ID, null)
        set(v) = sp.edit().putString(KEY_USER_ID, v).apply()

    /** 监听端口。 */
    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    /** 是否在应用启动时自动拉起网关。 */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /** 网关是否处于用户期望的运行状态。 */
    var desiredRunning: Boolean
        get() = sp.getBoolean(KEY_DESIRED_RUNNING, false)
        set(v) = sp.edit().putBoolean(KEY_DESIRED_RUNNING, v).apply()

    /** 累计请求数（用于首页统计展示）。 */
    var requestCount: Long
        get() = sp.getLong(KEY_REQUEST_COUNT, 0L)
        set(v) = sp.edit().putLong(KEY_REQUEST_COUNT, v).apply()

    /** 最近一次错误信息。 */
    var lastError: String?
        get() = sp.getString(KEY_LAST_ERROR, null)
        set(v) = sp.edit().putString(KEY_LAST_ERROR, v).apply()

    val isConfigured: Boolean get() = !refreshToken.isNullOrBlank()

    companion object {
        const val DEFAULT_PORT = 3065
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PORT = "port"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DESIRED_RUNNING = "desired_running"
        private const val KEY_REQUEST_COUNT = "request_count"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
