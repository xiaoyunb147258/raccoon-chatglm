package dev.raccoon2api.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.raccoon2api.core.Prefs
import dev.raccoon2api.databinding.ActivityMainBinding
import dev.raccoon2api.glm.GlmClient
import dev.raccoon2api.service.GatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论是否授权，网关本身仍可运行 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etToken.setText(prefs.refreshToken.orEmpty())
        binding.tvUsage.text = USAGE_TEXT

        binding.btnToggle.setOnClickListener { toggleGateway() }
        binding.btnSaveToken.setOnClickListener { saveToken() }
        binding.btnVerify.setOnClickListener { verifyToken() }
        binding.btnCopy.setOnClickListener { copyBaseUrl() }

        askNotificationPermission()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // 1 秒后刷新一次，等网关把状态置起来
        binding.root.postDelayed({ refreshUi() }, 1200)
    }

    // ---------------- 交互 ----------------

    private fun toggleGateway() {
        if (GatewayService.isRunning) {
            prefs.desiredRunning = false
            GatewayService.stop(this)
            binding.root.postDelayed({ refreshUi() }, 600)
            toast("网关已停止")
            return
        }
        if (!prefs.isConfigured) {
            toast("请先粘贴并保存 refresh_token")
            return
        }
        prefs.desiredRunning = true
        try {
            GatewayService.start(this)
            binding.root.postDelayed({ refreshUi() }, 800)
            toast("网关启动中…")
        } catch (e: Exception) {
            toast("启动失败：" + e.message)
        }
    }

    private fun saveToken() {
        val raw = binding.etToken.text.toString().trim()
        if (raw.isEmpty()) {
            toast("请输入 refresh_token")
            return
        }
        prefs.refreshToken = raw
        toast("已保存")
        refreshUi()
    }

    private fun verifyToken() {
        val raw = binding.etToken.text.toString().trim()
        if (raw.isEmpty()) {
            toast("请输入 refresh_token")
            return
        }
        binding.tvAccountInfo.visibility = View.VISIBLE
        binding.tvAccountInfo.text = "正在验证…"
        binding.btnVerify.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = GlmClient()
                    val pair = client.refreshAccessToken(raw)
                    Result.success(pair)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.btnVerify.isEnabled = true
            result.onSuccess { pair ->
                prefs.refreshToken = pair.refreshToken
                binding.etToken.setText(pair.refreshToken)
                binding.tvAccountInfo.text =
                    "验证通过 · access_token 已获取（长度 ${pair.accessToken.length}）\n刷新令牌已自动更新并保存"
                toast("令牌有效")
                refreshUi()
            }.onFailure { e ->
                binding.tvAccountInfo.text = "验证失败：" + (e.message ?: "未知错误")
                toast("验证失败")
            }
        }
    }

    private fun copyBaseUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("base_url", baseUrl()))
        toast("已复制接入地址")
    }

    // ---------------- 状态刷新 ----------------

    private fun refreshUi() {
        val running = GatewayService.isRunning
        val configured = prefs.isConfigured

        binding.tvStatus.text = if (running) "运行中" else "已停止"
        binding.dotStatus.alpha = if (running) 1f else 0.45f
        binding.btnToggle.text = if (running) "停止网关" else "启动网关"
        binding.tvRequests.text = prefs.requestCount.toString()
        binding.tvConfigured.text = if (configured) "已配置" else "未配置"
        binding.tvBaseUrl.text = baseUrl()
        binding.tvEndpointBrief.text = baseUrl()

        val err = GatewayService.lastError ?: prefs.lastError
        if (!err.isNullOrBlank()) {
            binding.cardError.visibility = View.VISIBLE
            binding.tvError.text = err
        } else {
            binding.cardError.visibility = View.GONE
        }
    }

    private fun baseUrl(): String = "http://127.0.0.1:${prefs.port}/v1"

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val USAGE_TEXT = """
            1. 在下方粘贴 refresh_token 并点「保存令牌」。
            2. 点上方「启动网关」，状态变为「运行中」。
            3. 在任意客户端（Cherry Studio、ChatBox、NextChat 等）中新增
               OpenAI 兼容服务，Base URL 填上面的接入地址。
            4. API Key 随意填写（例如 raccoon）；模型选择 GLM-4 即可。
            5. 支持流式输出与多轮上下文；请求经本机转发到智谱清言。
        """.trimIndent()
    }
}
