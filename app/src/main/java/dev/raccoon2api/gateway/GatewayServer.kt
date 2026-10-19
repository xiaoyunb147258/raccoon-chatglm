package dev.raccoon2api.gateway

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 解析后的 HTTP 请求。 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: String
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * 极简 HTTP/1.1 服务器，只监听回环地址，用于对外暴露 OpenAI 兼容接口。
 * 不依赖任何第三方框架，纯 ServerSocket 实现。
 */
class GatewayServer(
    private val port: Int,
    private val router: (HttpRequest) -> GatewayResponse
) {

    companion object {
        private const val TAG = "GatewayServer"
        private const val MAX_BODY = 32 * 1024 * 1024

        fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "gateway-worker").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        val socket = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)
        pool.execute {
            Log.i(TAG, "gateway listening on 127.0.0.1:$port")
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        Log.i(TAG, "gateway stopped")
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 300_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 8192)
            val out = client.getOutputStream()
            val req = parseRequest(input)
            if (req == null) {
                writeSimple(out, 400, "Bad Request", "{\"error\":\"bad request\"}")
                return
            }
            Log.d(TAG, "${req.method} ${req.path}")
            val resp = try {
                router(req)
            } catch (e: Exception) {
                Log.e(TAG, "route error", e)
                GatewayResponse.Json.json(
                    500,
                    "{\"error\":{\"message\":\"" + escape(e.message ?: "internal error") + "\",\"type\":\"internal_error\"}}"
                )
            }
            writeResponse(out, resp)
        } catch (e: SocketException) {
            Log.d(TAG, "client disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------
    // 请求解析
    // ------------------------------------------------------------------

    private fun parseRequest(input: InputStream): HttpRequest? {
        val headerBytes = readUntilDoubleCrlf(input) ?: return null
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0]
        val target = requestLine[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = when {
            length > 0 && length <= MAX_BODY -> String(readExactly(input, length), Charsets.UTF_8)
            headers["transfer-encoding"]?.contains("chunked") == true -> String(readChunked(input), Charsets.UTF_8)
            else -> ""
        }
        return HttpRequest(method, path, query, headers, body)
    }

    private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buf.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
            if (buf.size() > 64 * 1024) return null
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n == -1) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            out.write(readExactly(input, size))
            readLine(input)
            if (out.size() > MAX_BODY) break
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            buf.write(b)
        }
    }

    // ------------------------------------------------------------------
    // 响应写出
    // ------------------------------------------------------------------

    private fun writeResponse(out: OutputStream, resp: GatewayResponse) {
        when (resp) {
            is GatewayResponse.Json -> writeSimple(out, resp.status, resp.statusText, resp.body)
            is GatewayResponse.Stream -> writeStream(out, resp)
        }
    }

    private fun writeSimple(out: OutputStream, status: Int, statusText: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun writeStream(out: OutputStream, resp: GatewayResponse.Stream) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val writer = ChunkedWriter(out)
        try {
            resp.producer { chunk ->
                writer.writeChunk(chunk)
            }
        } catch (e: Exception) {
            Log.d(TAG, "stream aborted: ${e.message}")
        } finally {
            try {
                writer.finish()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** chunked 传输编码写出器。 */
    private class ChunkedWriter(private val out: OutputStream) {
        fun writeChunk(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(Integer.toHexString(bytes.size).toByteArray(Charsets.ISO_8859_1))
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        fun finish() {
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

}

/** 网关响应。 */
sealed class GatewayResponse {
    data class Json(val status: Int, val statusText: String, val body: String) : GatewayResponse() {
        companion object {
            fun ok(body: String) = Json(200, "OK", body)
            fun json(status: Int, body: String) = Json(status, statusText(status), body)
            private fun statusText(status: Int) = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                else -> "OK"
            }
        }
    }

    class Stream(val producer: ((String) -> Unit) -> Unit) : GatewayResponse()
}
