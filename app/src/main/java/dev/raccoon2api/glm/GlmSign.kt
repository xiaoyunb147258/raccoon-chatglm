package dev.raccoon2api.glm

import java.security.MessageDigest
import java.util.UUID

/**
 * 智谱清言 chatglm.cn 的请求签名。
 *
 * 官网的时间戳算法：取 13 位毫秒时间戳，把「倒数第二位」替换成
 *   (各位数字之和 - 倒数第二位) % 10
 * 再用 md5(`${timestamp}-${nonce}-${SECRET}`) 得到 X-Sign。
 */
object GlmSign {

    /** 官网签名密钥，官网若变更需同步更新。 */
    const val SECRET = "8a1317a7468aa3ad86e997d08f3f31cb"

    data class Signature(val timestamp: String, val nonce: String, val sign: String)

    fun generate(): Signature {
        val raw = System.currentTimeMillis().toString()
        val len = raw.length
        val digits = IntArray(len) { raw[it] - '0' }
        var sum = 0
        for (d in digits) sum += d
        // 减去倒数第二位
        val weighted = sum - digits[len - 2]
        val mod = weighted % 10
        val timestamp = raw.substring(0, len - 2) + mod + raw.substring(len - 1)
        val nonce = UUID.randomUUID().toString()
        return Signature(timestamp, nonce, md5("$timestamp-$nonce-$SECRET"))
    }

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun uuid(): String = UUID.randomUUID().toString()
}
