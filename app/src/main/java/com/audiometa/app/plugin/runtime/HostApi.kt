package com.audiometa.app.plugin.runtime

import android.content.Context
import android.util.Base64
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.LogLevel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 暴露给插件 JS 的宿主能力。
 *
 * 两条硬约束：
 * 1. 必须是**没有父接口的纯接口**，否则 QuickJs.set 会抛 UnsupportedOperationException。
 * 2. 参数和返回值只用 String，原生桥只可靠地支持字符串与基本类型。
 *    复杂参数一律用 JSON 字符串传递，由宿主解析。
 */
interface HostBridge {
    fun http(requestJson: String): String
    fun base64(requestJson: String): String
    fun bytes(requestJson: String): String
    fun compression(requestJson: String): String
    fun crypto(requestJson: String): String
    fun cache(requestJson: String): String
    fun log(level: String, tag: String, message: String): String
    fun info(): String
    fun now(): String
    fun xml(requestJson: String): String
}

class HostApi(
    private val context: Context,
    private val client: OkHttpClient,
    private val appVersion: String,
    private val userAgent: String
) : HostBridge {

    override fun http(requestJson: String): String {
        val options = JSONObject(requestJson)
        val url = options.optString("url")
        if (url.isBlank()) throw IllegalArgumentException("http 缺少 url")
        val op = options.optString("op", "getText")
        val headers = options.optJSONObject("headers")
        val contentType = options.optString("contentType")
        // 默认值收紧：插件里写的是阻塞式 HTTP，withTimeout 打断不了它，
        // 真正能兜住「某个源连不上拖垮整次刮削」的就是这两个超时。
        // 插件仍可通过 connectTimeoutMs / readTimeoutMs 自行调整，但上限也收紧了。
        val connectMs = options.optLong("connectTimeoutMs", 8_000L).coerceIn(1_000L, 20_000L)
        val readMs = options.optLong("readTimeoutMs", 12_000L).coerceIn(1_000L, 30_000L)

        val builder = Request.Builder().url(url)
        if (headers != null) {
            headers.keys().forEach { key -> builder.header(key, headers.optString(key)) }
        }
        val bodyText = options.optString("body").takeIf { options.has("body") }
        val method = when (op) {
            "postText", "postBytes" -> "POST"
            else -> options.optString("method", "GET").uppercase().ifBlank { "GET" }
        }
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else {
            val media = (contentType.ifBlank { "application/x-www-form-urlencoded; charset=utf-8" })
                .toMediaTypeOrNull()
            builder.method(method, (bodyText ?: "").toRequestBody(media))
        }

        val callClient = client.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .callTimeout(readMs + connectMs, TimeUnit.MILLISECONDS)
            .build()

        callClient.newCall(builder.build()).execute().use { response ->
            val headerJson = JSONObject()
            response.headers.names().forEach { name -> headerJson.put(name, response.header(name).orEmpty()) }
            val body = response.body
            return when (op) {
                "getText", "postText" -> body?.string().orEmpty()
                "get" -> JSONObject()
                    .put("code", response.code)
                    .put("body", body?.string().orEmpty())
                    .put("headers", headerJson)
                    .toString()
                "postBytes" -> {
                    val bytes = body?.bytes() ?: ByteArray(0)
                    JSONObject()
                        .put("code", response.code)
                        .put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("headers", headerJson)
                        .toString()
                }
                else -> body?.string().orEmpty()
            }
        }
    }

    override fun base64(requestJson: String): String {
        val options = JSONObject(requestJson)
        val data = options.optString("data")
        return when (options.optString("op")) {
            "encode" -> Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "decode" -> String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)
            "decodeUrl" -> String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            "dropBytes" -> {
                val count = options.optInt("count", 0).coerceAtLeast(0)
                val raw = Base64.decode(data, Base64.DEFAULT)
                val rest = if (count >= raw.size) ByteArray(0) else raw.copyOfRange(count, raw.size)
                Base64.encodeToString(rest, Base64.NO_WRAP)
            }
            else -> ""
        }
    }

    override fun bytes(requestJson: String): String {
        val options = JSONObject(requestJson)
        val data = Base64.decode(options.optString("data"), Base64.DEFAULT)
        val keyArray = options.optJSONArray("key") ?: JSONArray()
        if (keyArray.length() == 0) return options.optString("data")
        val out = ByteArray(data.size)
        for (i in data.indices) {
            val k = keyArray.optInt(i % keyArray.length(), 0) and 0xFF
            out[i] = ((data[i].toInt() and 0xFF) xor k).toByte()
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun compression(requestJson: String): String {
        val options = JSONObject(requestJson)
        val raw: ByteArray = when (options.optString("op")) {
            "inflateBase64" -> Base64.decode(options.optString("data"), Base64.DEFAULT)
            "inflateBytes" -> {
                val array = options.optJSONArray("data") ?: JSONArray()
                ByteArray(array.length()) { index -> (array.optInt(index, 0) and 0xFF).toByte() }
            }
            else -> ByteArray(0)
        }
        if (raw.isEmpty()) return ""
        return String(inflate(raw), Charsets.UTF_8)
    }

    override fun crypto(requestJson: String): String {
        val options = JSONObject(requestJson)
        val data = options.optString("data")
        return when (options.optString("op")) {
            "md5" -> digest("MD5", data)
            "sha1" -> digest("SHA-1", data)
            "sha256" -> digest("SHA-256", data)
            "aesEcbEncryptHex" -> hex(aes(data, options.optString("key"), Cipher.ENCRYPT_MODE))
            "aesEcbEncryptBase64" ->
                Base64.encodeToString(aes(data, options.optString("key"), Cipher.ENCRYPT_MODE), Base64.NO_WRAP)
            "aesEcbDecryptBase64ToText" -> {
                val raw = Base64.decode(data, Base64.DEFAULT)
                String(aesBytes(raw, options.optString("key"), Cipher.DECRYPT_MODE), Charsets.UTF_8)
            }
            else -> ""
        }
    }

    override fun cache(requestJson: String): String {
        val options = JSONObject(requestJson)
        val key = options.optString("key")
        if (key.isBlank()) return ""
        // 缓存落在 Android/data/<包名>/files/插件缓存.json，
        // 不再用 SharedPreferences —— 后者固定在用户访问不到的私有目录。
        return when (options.optString("op")) {
            "get" -> PluginCacheStore.get(context, key)
            "set" -> {
                PluginCacheStore.set(
                    context = context,
                    key = key,
                    value = options.optString("value"),
                    ttlMs = options.optLong("ttlMs", 0L)
                )
                ""
            }
            "remove" -> {
                PluginCacheStore.remove(context, key)
                ""
            }
            else -> ""
        }
    }

    override fun log(level: String, tag: String, message: String): String {
        val resolved = when (level.lowercase()) {
            "debug" -> LogLevel.DEBUG
            "warn", "warning" -> LogLevel.WARN
            "error" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        AppLog.log(resolved, tag.ifBlank { "Plugin" }, message)
        return ""
    }

    override fun info(): String {
        val apis = JSONArray()
        listOf(
            "http.getText", "http.get", "http.postText", "http.postBytesResponse",
            "base64.encodeText", "base64.decodeText", "base64.decodeUrlText", "base64.dropBytes",
            "bytes.xorBase64",
            "compression.inflateBase64ToText", "compression.inflateBytesToText",
            "crypto.md5", "crypto.aesEcbPkcs5EncryptHex", "crypto.aesEcbPkcs5EncryptBase64",
            "crypto.aesEcbPkcs5DecryptBase64ToText",
            "cache.get", "cache.set", "cache.remove",
            "xml.getRootAttributes", "xml.findElements", "xml.removeElements",
            "xml.replaceChildrenByAttr"
        ).forEach { apis.put(it) }
        return JSONObject()
            .put("supportedHostApis", apis)
            .put("hostApiVersion", HOST_API_VERSION)
            .put("appVersion", appVersion)
            .put("userAgent", userAgent)
            .put("platform", "Android")
            .toString()
    }

    override fun now(): String = System.currentTimeMillis().toString()

    override fun xml(requestJson: String): String = PluginXml.handle(requestJson)

    private fun aes(data: String, key: String, mode: Int): ByteArray =
        aesBytes(data.toByteArray(Charsets.UTF_8), key, mode)

    private fun aesBytes(input: ByteArray, key: String, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"))
        return cipher.doFinal(input)
    }

    private fun inflate(raw: ByteArray): ByteArray {
        // 插件给的通常是 zlib 流；失败时退回裸 deflate。
        runCatching { return inflateWith(raw, true) }
        return inflateWith(raw, false)
    }

    private fun inflateWith(raw: ByteArray, zlib: Boolean): ByteArray {
        val inflater = Inflater(zlib)
        inflater.setInput(raw)
        val out = ByteArrayOutputStream(raw.size * 4)
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                out.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun digest(algorithm: String, text: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val HOST_API_VERSION = 5
        // 插件缓存已经改到 PluginCacheStore（落到外部目录的 JSON），
        // 那两个 SharedPreferences 常量不再需要。
    }
}
