package dev.raccoon2api.glm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 对话消息（内部统一表示）。 */
data class ChatMessage(val role: String, val content: String)

/** 流式回调。 */
interface StreamSink {
    /** 正文增量。 */
    fun onDelta(text: String)
    /** 思考过程增量（推理模型）。 */
    fun onThink(text: String) {}
    /** 工具调用（finish 等）。 */
    fun onToolCall(name: String, arguments: String) {}
}

/**
 * 智谱清言 chatglm.cn 客户端。
 *
 * 走的是网页版真实接口：
 *   POST /chatglm/user-api/user/refresh          （refresh_token → access_token）
 *   POST /chatglm/backend-api/assistant/stream   （对话，SSE 流式）
 */
class GlmClient {

    companion object {
        private const val TAG = "GlmClient"
        const val BASE = "https://chatglm.cn"
        const val PATH_REFRESH = "/chatglm/user-api/user/refresh"
        const val PATH_STREAM = "/chatglm/backend-api/assistant/stream"

        /** 默认智能体 ID（对应网页版「GLM」通用助手）。 */
        const val DEFAULT_ASSISTANT_ID = "65940acff94777010aa6b796"

        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 300_000

        private const val EXP_GROUPS =
            "na_android_config:exp:NA,na_4o_config:exp:4o_A,tts_config:exp:tts_config_a," +
            "na_glm4plus_config:exp:open,mainchat_server_app:exp:A,mobile_history_daycheck:exp:a," +
            "desktop_toolbar:exp:A,chat_drawing_server:exp:A,drawing_server_cogview:exp:cogview4," +
            "app_welcome_v2:exp:A,chat_drawing_streamv2:exp:A,mainchat_rm_fc:exp:add,mainchat_dr:exp:open," +
            "chat_auto_entrance:exp:A,drawing_server_hi_dream:control:A,homepage_square:exp:close," +
            "assistant_recommend_prompt:exp:3,app_home_regular_user:exp:A,memory_common:exp:enable," +
            "mainchat_moe:exp:300,assistant_greet_user:exp:greet_user,app_welcome_personalize:exp:A," +
            "assistant_model_exp_group:exp:glm4.5,ai_wallet:exp:ai_wallet_enable"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

        /** 通用伪装请求头（对齐网页版）。 */
        private fun baseHeaders(json: Boolean): MutableMap<String, String> {
            val h = mutableMapOf(
                "App-Name" to "chatglm",
                "Origin" to BASE,
                "X-App-Fr" to "browser_extension",
                "X-App-Platform" to "pc",
                "X-App-Version" to "0.0.1",
                "X-Device-Brand" to "",
                "X-Device-Model" to "",
                "X-Exp-Groups" to EXP_GROUPS,
                "X-Lang" to "zh",
                "User-Agent" to USER_AGENT,
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            if (json) h["Content-Type"] = "application/json"
            return h
        }

        /** 带签名的请求头。 */
        private fun signedHeaders(token: String, stream: Boolean): Map<String, String> {
            val sig = GlmSign.generate()
            val h = baseHeaders(json = true)
            if (stream) {
                h["Accept"] = "text/event-stream"
                h["Cache-Control"] = "no-cache"
                h["Pragma"] = "no-cache"
            } else {
                h["Accept"] = "application/json, text/plain, */*"
            }
            h["Accept-Encoding"] = "identity"
            h["Authorization"] = "Bearer $token"
            h["X-Device-Id"] = GlmSign.uuid()
            h["X-Request-Id"] = GlmSign.uuid()
            h["X-Sign"] = sig.sign
            h["X-Timestamp"] = sig.timestamp
            h["X-Nonce"] = sig.nonce
            return h
        }
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    data class TokenPair(val accessToken: String, val refreshToken: String)

    /**
     * 用 refresh_token 换 access_token。
     * @throws GlmException 失败时抛出，message 为上游返回的可读原因。
     */
    fun refreshAccessToken(refreshToken: String): TokenPair {
        val conn = (URL(BASE + PATH_REFRESH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            signedHeaders(refreshToken, stream = false).forEach { (k, v) ->
                conn.setRequestProperty(k, v)
            }
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            Log.d(TAG, "refresh http=$code body=${text.take(200)}")
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw GlmException("刷新令牌失败：返回不是 JSON（HTTP $code）")
            }
            if (json.optInt("status", -1) != 0) {
                throw GlmException("刷新令牌被拒绝：" + json.optString("message", "未知错误"))
            }
            val result = json.optJSONObject("result")
                ?: throw GlmException("刷新令牌响应缺少 result")
            val at = result.optString("access_token")
            val rt = result.optString("refresh_token").ifEmpty { refreshToken }
            if (at.isEmpty()) throw GlmException("刷新令牌响应缺少 access_token")
            return TokenPair(at, rt)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 对话
    // ------------------------------------------------------------------

    /**
     * 流式对话。
     *
     * @param accessToken 有效的 access_token
     * @param messages OpenAI 风格的消息列表
     * @param assistantId 智能体 ID
     * @param conversationId 继续已有会话时传入；空串表示新会话
     * @param sink 增量回调
     * @return 完整回答文本
     */
    fun chatStream(
        accessToken: String,
        messages: List<ChatMessage>,
        assistantId: String = DEFAULT_ASSISTANT_ID,
        conversationId: String = "",
        sink: StreamSink
    ): String {
        val body = buildRequestBody(messages, assistantId, conversationId)
        val conn = (URL(BASE + PATH_STREAM).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        try {
            signedHeaders(accessToken, stream = true).forEach { (k, v) ->
                conn.setRequestProperty(k, v)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            if (code !in 200..299 || !contentType.contains("event-stream")) {
                val err = readAll(conn.errorStream ?: conn.inputStream)
                throw GlmException("对话请求失败（HTTP $code）：${err.take(300)}")
            }
            return parseSse(conn.inputStream, sink)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 组装上游请求体。
     *
     * 对齐网页版结构：messages 为「单条 user 消息 + 嵌套 content 数组」，
     * 多轮上下文用 <|user|>/<|assistant|> 标记拼进 text。
     */
    private fun buildRequestBody(
        messages: List<ChatMessage>,
        assistantId: String,
        conversationId: String
    ): JSONObject {
        val text = if (messages.size < 2) {
            // 单轮：直接拼接纯文本
            buildString {
                for (m in messages) {
                    append(m.content)
                    append('\n')
                }
            }
        } else {
            buildString {
                for (m in messages) {
                    val role = when (m.role) {
                        "system" -> "<|sytstem|>"
                        "assistant" -> "<|assistant|>"
                        "user" -> "<|user|>"
                        else -> "<|user|>"
                    }
                    append(role).append('\n').append(m.content).append('\n')
                }
                append("<|assistant|>\n")
            }
        }

        val contentArray = JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )
        val msgArray = JSONArray().put(
            JSONObject().put("role", "user").put("content", contentArray)
        )

        val meta = JSONObject()
            .put("channel", "")
            .put("draft_id", "")
            .put("if_plus_model", true)
            .put("input_question_type", "xxxx")
            .put("is_networking", false)
            .put("is_test", false)
            .put("platform", "pc")
            .put("quote_log_id", "")
            .put("cogview", JSONObject().put("rm_label_watermark", false))

        return JSONObject()
            .put("assistant_id", assistantId)
            .put("conversation_id", conversationId)
            .put("project_id", "")
            .put("chat_type", "user_chat")
            .put("messages", msgArray)
            .put("meta_data", meta)
    }

    /**
     * 解析 SSE 流。
     *
     * 上游对 text/think 的推送有两种形态：
     *   1) 增量片段（如 "杭州"、"是中国"）
     *   2) 完整快照（如整段 85 字）
     * 因此统一用「以已累积串开头 → 视为快照替换；否则视为增量追加」判断。
     */
    private fun parseSse(input: InputStream, sink: StreamSink): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var answer = StringBuilder()
        var thinking = StringBuilder()
        var line: String?
        var events = 0
        while (true) {
            line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
            val raw = trimmed.substring(5).trim()
            if (raw.isEmpty() || raw == "[DONE]") continue
            val ev = try {
                JSONObject(raw)
            } catch (e: Exception) {
                continue
            }
            events++
            val parts = ev.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val contents = part.optJSONArray("content") ?: continue
                for (j in 0 until contents.length()) {
                    val c = contents.optJSONObject(j) ?: continue
                    when (c.optString("type")) {
                        "text" -> {
                            val t = c.optString("text")
                            if (t.isEmpty()) continue
                            val cur = answer.toString()
                            if (t.startsWith(cur)) {
                                val delta = t.substring(cur.length)
                                answer = StringBuilder(t)
                                if (delta.isNotEmpty()) sink.onDelta(delta)
                            } else {
                                answer.append(t)
                                sink.onDelta(t)
                            }
                        }
                        "think" -> {
                            val t = c.optString("think")
                            if (t.isEmpty()) continue
                            val cur = thinking.toString()
                            if (t.startsWith(cur)) {
                                val delta = t.substring(cur.length)
                                thinking = StringBuilder(t)
                                if (delta.isNotEmpty()) sink.onThink(delta)
                            } else {
                                thinking.append(t)
                                sink.onThink(t)
                            }
                        }
                        "tool_calls" -> {
                            val tc = c.optJSONObject("tool_calls") ?: continue
                            sink.onToolCall(
                                tc.optString("name"),
                                tc.optString("arguments")
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
        Log.d(TAG, "SSE done, events=$events, answerLen=${answer.length}")
        return answer.toString()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun readAll(input: InputStream?): String {
        if (input == null) return ""
        return try {
            input.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            ""
        }
    }
}

class GlmException(message: String) : Exception(message)
