package dev.raccoon2api.gateway

import android.util.Log
import dev.raccoon2api.core.Prefs
import dev.raccoon2api.glm.ChatMessage
import dev.raccoon2api.glm.GlmClient
import dev.raccoon2api.glm.GlmException
import dev.raccoon2api.glm.StreamSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * OpenAI 兼容层。
 *
 * 对外暴露：
 *   GET  /v1/models
 *   POST /v1/chat/completions   （支持 stream=true / false）
 *   GET  /health
 *
 * 内部把请求翻译成智谱清言网页版协议，并把 SSE 流回写成 OpenAI 分块格式。
 */
class OpenAiApi(
    private val prefs: Prefs,
    private val client: GlmClient
) {

    companion object {
        private const val TAG = "OpenAiApi"
    }

    // ---------------- token 缓存 ----------------

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpireAt: Long = 0L

    private val tokenLock = Any()

    private fun acquireToken(): String {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()
            val cached = cachedToken
            if (cached != null && now < tokenExpireAt - 60_000L) return cached
            val refresh = prefs.refreshToken
            if (refresh.isNullOrBlank()) {
                throw GlmException("尚未配置 refresh_token，请先在「小浣熊」中登录或粘贴令牌")
            }
            val pair = client.refreshAccessToken(refresh)
            cachedToken = pair.accessToken
            tokenExpireAt = now + 55 * 60 * 1000L
            if (pair.refreshToken != refresh) prefs.refreshToken = pair.refreshToken
            Log.i(TAG, "access_token refreshed")
            return pair.accessToken
        }
    }

    fun invalidateToken() {
        synchronized(tokenLock) {
            cachedToken = null
            tokenExpireAt = 0L
        }
    }

    // ---------------- 路由 ----------------

    fun handle(req: HttpRequest): GatewayResponse {
        if (req.method == "OPTIONS") {
            return GatewayResponse.Json.ok("{}")
        }
        return when {
            req.path == "/v1/models" || req.path == "/models" -> models()
            req.path == "/v1/chat/completions" || req.path == "/chat/completions" -> chat(req)
            req.path == "/health" || req.path == "/" -> GatewayResponse.Json.ok(
                JSONObject()
                    .put("status", "ok")
                    .put("service", "raccoon2api")
                    .put("backend", GlmClient.BASE)
                    .put("configured", !prefs.refreshToken.isNullOrBlank())
                    .toString()
            )
            else -> GatewayResponse.Json.json(
                404,
                JSONObject().put(
                    "error",
                    JSONObject().put("message", "unknown path: ${req.path}").put("type", "invalid_request_error")
                ).toString()
            )
        }
    }

    private fun models(): GatewayResponse {
        val arr = JSONArray()
        for (m in dev.raccoon2api.glm.ModelCatalog.list()) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("object", "model")
                    .put("created", 1700000000)
                    .put("owned_by", m.ownedBy)
            )
        }
        return GatewayResponse.Json.ok(
            JSONObject().put("object", "list").put("data", arr).toString()
        )
    }

    private fun chat(req: HttpRequest): GatewayResponse {
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return badRequest("请求体不是合法 JSON")
        }

        val rawMessages = body.optJSONArray("messages")
            ?: return badRequest("缺少 messages")
        if (rawMessages.length() == 0) return badRequest("messages 为空")

        val messages = ArrayList<ChatMessage>(rawMessages.length())
        for (i in 0 until rawMessages.length()) {
            val m = rawMessages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = extractContent(m.opt("content"))
            if (content.isEmpty() && role != "assistant") continue
            messages.add(ChatMessage(role, content))
        }
        if (messages.isEmpty()) return badRequest("messages 内容为空")

        // tools 支持：把工具定义注入为 system 提示，让模型可以发起调用
        val tools = body.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val toolPrompt = buildToolPrompt(tools)
            if (toolPrompt.isNotEmpty()) {
                messages.add(0, ChatMessage("system", toolPrompt))
            }
        }

        val requestModel = body.optString("model", "GLM-4")
        val stream = body.optBoolean("stream", false)
        val assistantId = resolveAssistantId(requestModel)
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
        val created = System.currentTimeMillis() / 1000

        return if (stream) {
            GatewayResponse.Stream { emit ->
                try {
                    val token = acquireToken()
                    var sentRole = false
                    val sink = object : StreamSink {
                        override fun onDelta(text: String) {
                            if (text.isEmpty()) return
                            emit(chunk(id, created, requestModel, role = if (!sentRole) "assistant" else null, content = text))
                            sentRole = true
                        }

                        override fun onThink(text: String) {
                            // 思考过程以 reasoning_content 字段回传（部分客户端支持）
                            if (text.isEmpty()) return
                            emit(thinkChunk(id, created, requestModel, text))
                        }

                        override fun onToolCall(name: String, arguments: String) {
                            if (name.isEmpty() || name == "finish") return
                            emit(toolCallChunk(id, created, requestModel, name, arguments))
                        }
                    }
                    client.chatStream(token, messages, assistantId, "", sink)
                    emit(finishChunk(id, created, requestModel, "stop"))
                    emit("data: [DONE]\n\n")
                } catch (e: Exception) {
                    Log.e(TAG, "stream failed", e)
                    emit(errorChunk(e.message ?: "internal error"))
                    emit("data: [DONE]\n\n")
                }
            }
        } else {
            try {
                val token = acquireToken()
                val sb = StringBuilder()
                val thinks = StringBuilder()
                val sink = object : StreamSink {
                    override fun onDelta(text: String) {
                        sb.append(text)
                    }

                    override fun onThink(text: String) {
                        thinks.append(text)
                    }
                }
                client.chatStream(token, messages, assistantId, "", sink)
                val text = sb.toString()
                val msg = JSONObject()
                    .put("role", "assistant")
                    .put("content", text)
                if (thinks.isNotEmpty()) msg.put("reasoning_content", thinks.toString())
                val choice = JSONObject()
                    .put("index", 0)
                    .put("message", msg)
                    .put("finish_reason", "stop")
                val usage = JSONObject()
                    .put("prompt_tokens", estimateTokens(messages.joinToString("\n") { it.content }))
                    .put("completion_tokens", estimateTokens(text))
                    .put("total_tokens", estimateTokens(messages.joinToString("\n") { it.content }) + estimateTokens(text))
                val resp = JSONObject()
                    .put("id", id)
                    .put("object", "chat.completion")
                    .put("created", created)
                    .put("model", requestModel)
                    .put("choices", JSONArray().put(choice))
                    .put("usage", usage)
                GatewayResponse.Json.ok(resp.toString())
            } catch (e: Exception) {
                Log.e(TAG, "chat failed", e)
                serverError(e.message ?: "internal error")
            }
        }
    }

    // ---------------- 辅助 ----------------

    /** OpenAI content 可能是字符串，也可能是 [{type:"text",text:...}] 数组。 */
    private fun extractContent(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text" -> sb.append(part.optString("text"))
                        "image_url" -> sb.append("[图片]")
                        else -> sb.append(part.optString("text"))
                    }
                }
                sb.toString()
            }
            is JSONObject -> content.optString("text")
            else -> content.toString()
        }
    }

    /** model 字段若是 24 位以上 hex 则当作智能体 ID，否则用默认智能体。 */
    private fun resolveAssistantId(model: String): String {
        if (model.matches(Regex("^[a-z0-9]{24,}$"))) return model
        return GlmClient.DEFAULT_ASSISTANT_ID
    }

    /** 把 OpenAI tools 定义转成提示词，注入到对话开头。 */
    private fun buildToolPrompt(tools: JSONArray): String {
        val sb = StringBuilder()
        sb.append("你可以调用以下工具来完成任务。当需要调用工具时，")
        sb.append("只输出一个 JSON 对象，格式为：")
        sb.append("{\"tool_calls\":[{\"name\":\"工具名\",\"arguments\":{参数对象}}]}，不要输出其它内容。\n")
        sb.append("可用工具：\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            sb.append("- ").append(fn.optString("name")).append(": ")
            sb.append(fn.optString("description")).append("\n")
            val params = fn.opt("parameters")
            if (params != null) sb.append("  参数: ").append(params.toString()).append("\n")
        }
        return sb.toString()
    }

    private fun estimateTokens(text: String): Int = (text.length / 2).coerceAtLeast(1)

    private fun chunk(
        id: String,
        created: Long,
        model: String,
        role: String?,
        content: String?,
        finishReason: String? = null
    ): String {
        val delta = JSONObject()
        if (role != null) delta.put("role", role)
        if (content != null) delta.put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $obj\n\n"
    }

    private fun thinkChunk(id: String, created: Long, model: String, text: String): String {
        val delta = JSONObject().put("reasoning_content", text)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", JSONObject.NULL)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $obj\n\n"
    }

    private fun toolCallChunk(
        id: String,
        created: Long,
        model: String,
        name: String,
        arguments: String
    ): String {
        val fn = JSONObject().put("name", name).put("arguments", arguments.ifEmpty { "{}" })
        val call = JSONObject().put("index", 0).put("id", "call_" + GlmSignId.next()).put("type", "function").put("function", fn)
        val delta = JSONObject().put("tool_calls", JSONArray().put(call))
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", JSONObject.NULL)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $obj\n\n"
    }

    private fun finishChunk(id: String, created: Long, model: String, reason: String): String {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", JSONObject())
            .put("finish_reason", reason)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $obj\n\n"
    }

    private fun errorChunk(message: String): String {
        val obj = JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", "upstream_error")
        )
        return "data: $obj\n\n"
    }

    private fun badRequest(message: String): GatewayResponse = GatewayResponse.Json.json(
        400,
        JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", "invalid_request_error")
        ).toString()
    )

    private fun serverError(message: String): GatewayResponse = GatewayResponse.Json.json(
        500,
        JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", "upstream_error")
        ).toString()
    )
}

/** 短 ID 生成。 */
object GlmSignId {
    fun next(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 20)
}
