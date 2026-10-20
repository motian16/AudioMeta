package com.audiometa.app.plugin.runtime

import app.cash.quickjs.QuickJs
import com.audiometa.app.plugin.model.InstalledPlugin
import com.audiometa.app.plugin.model.PluginCapability
import com.audiometa.app.plugin.model.PluginFunction
import com.audiometa.app.plugin.model.localeCandidates
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

class PluginRuntime(
    private val hostApiFactory: (OkHttpClient, String) -> HostApi,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    constructor(hostApiFactory: (OkHttpClient, String) -> HostApi) : this(hostApiFactory, OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build())

    /**
     * 调用插件的全局入口函数。
     *
     * 每次都新建一个 QuickJS 上下文：插件的 lib 里有 const 顶层声明，
     * 复用上下文会在第二次加载时因为重复声明而报错。
     *
     * @param locale 用于解析 Platform.i18n.t 的语言
     * @return 插件返回值的 JSON 文本；插件返回 undefined 时为 "null"
     */
    suspend fun invoke(
        plugin: InstalledPlugin,
        functionName: String,
        request: JSONObject,
        locale: String
    ): String = withContext(Dispatchers.IO) {
        withTimeout(INVOKE_TIMEOUT_MS) {
            val quickJs = QuickJs.create()
            try {
                val host = hostApiFactory(httpClient, plugin.manifest.name)
                quickJs.set(HOST_NAME, HostBridge::class.java, host)
                quickJs.evaluate(PLATFORM_JS, "platform-shim.js")
                quickJs.evaluate(i18nScript(plugin, locale), "i18n.js")
                plugin.sources.forEach { (name, source) ->
                    quickJs.evaluate(source, name)
                }
                val script = buildString {
                    append("(function(){\n")
                    append("  if (typeof ").append(functionName).append(" !== 'function') {\n")
                    append("    throw new Error('插件未实现 ").append(functionName).append("');\n")
                    append("  }\n")
                    append("  var result = ").append(functionName).append("(")
                    append(jsLiteral(request.toString())).append(");\n")
                    append("  if (result && typeof result.then === 'function') {\n")
                    append("    throw new Error('插件返回了 Promise，宿主只支持同步调用');\n")
                    append("  }\n")
                    append("  return JSON.stringify(result === undefined ? null : result);\n")
                    append("})()")
                }
                val raw = quickJs.evaluate(script, "invoke.js") as? String ?: "null"
                raw.ifBlank { "null" }
            } catch (e: PluginException) {
                throw e
            } catch (e: Exception) {
                throw PluginException(plugin.manifest.name + " 执行 " + functionName + " 失败：" + e.message, e)
            } finally {
                runCatching { quickJs.close() }
            }
        }
    }

    /** 把结果统一成 JSONArray，插件返回对象时包一层。 */
    fun asArray(raw: String): JSONArray {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return JSONArray()
        return when {
            text.startsWith("[") -> runCatching { JSONArray(text) }.getOrElse { JSONArray() }
            text.startsWith("{") -> JSONArray().put(JSONObject(text))
            else -> JSONArray()
        }
    }

    /**
     * 安装时做一次加载检查：模块能否解析、声明的能力是否真的实现了。
     * 返回警告列表，不抛异常——顶层代码可能因为网络问题失败，不应阻止安装。
     */
    fun validate(plugin: InstalledPlugin, locale: String): List<String> {
        val warnings = mutableListOf<String>()
        val quickJs = QuickJs.create()
        try {
            val host = hostApiFactory(httpClient, plugin.manifest.name)
            quickJs.set(HOST_NAME, HostBridge::class.java, host)
            quickJs.evaluate(PLATFORM_JS, "platform-shim.js")
            quickJs.evaluate(i18nScript(plugin, locale), "i18n.js")
            plugin.sources.forEach { (name, source) -> quickJs.evaluate(source, name) }
        } catch (e: Exception) {
            warnings += "脚本加载失败：" + (e.message ?: e.javaClass.simpleName)
            return warnings
        } finally {
            runCatching { quickJs.close() }
        }

        val check = QuickJs.create()
        try {
            val host = hostApiFactory(httpClient, plugin.manifest.name)
            check.set(HOST_NAME, HostBridge::class.java, host)
            check.evaluate(PLATFORM_JS, "platform-shim.js")
            check.evaluate(i18nScript(plugin, locale), "i18n.js")
            plugin.sources.forEach { (name, source) -> check.evaluate(source, name) }
            plugin.manifest.capabilities.forEach { capability ->
                val fn = when (capability) {
                    PluginCapability.SEARCH_SONGS -> PluginFunction.SEARCH_SONGS
                    PluginCapability.SEARCH_COVERS -> PluginFunction.SEARCH_COVERS
                    PluginCapability.GET_LYRICS -> PluginFunction.GET_LYRICS
                    PluginCapability.SEARCH_ARTIST_IMAGES -> PluginFunction.SEARCH_ARTIST_IMAGES
                }
                val script = "(function(){ return typeof " + fn + " === 'function'; })()"
                val ok = check.evaluate(script)
                if (ok != true) warnings += "声明了 " + fn + " 但没有实现该函数"
            }
        } catch (e: Exception) {
            warnings += "能力检查失败：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { check.close() }
        }
        return warnings
    }

    /** 下载封面等二进制资源。 */
    fun downloadBytes(url: String, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val callClient = httpClient.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        callClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PluginException("封面下载失败 HTTP " + response.code)
            val body = response.body ?: throw PluginException("封面下载失败：响应为空")
            if (body.contentLength() > maxBytes) throw PluginException("封面超过体积上限")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw PluginException("封面下载失败：内容为空")
            if (bytes.size > maxBytes) throw PluginException("封面超过体积上限")
            return bytes
        }
    }

    private fun i18nScript(plugin: InstalledPlugin, locale: String): String {
        val manifest = plugin.manifest
        val primary = localeCandidates(locale)
            .firstNotNullOfOrNull { manifest.localeTexts[it] }
            ?: emptyMap()
        val fallback = manifest.localeTexts[manifest.defaultLocale] ?: emptyMap()
        return "var __i18n = { primary: " + JSONObject(primary as Map<*, *>).toString() +
            ", fallback: " + JSONObject(fallback as Map<*, *>).toString() + " };"
    }

    private fun jsLiteral(text: String): String =
        text.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    companion object {
        private const val HOST_NAME = "__host"
        private const val INVOKE_TIMEOUT_MS = 60_000L
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val USER_AGENT = "AudioMeta/1.0 (Android)"

        /**
         * Platform 垫片。所有复杂参数都序列化成 JSON 字符串交给宿主，
         * 因为原生桥只可靠支持字符串。
         */
        private const val PLATFORM_JS = """
var Platform = (function () {
  function post(json) { return __host.http(json); }
  function normalizeHeaders(headers) {
    if (!headers) return {};
    var out = {};
    for (var key in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, key) && headers[key] != null) {
        out[key] = String(headers[key]);
      }
    }
    return out;
  }
  function httpOptions(kind, url, extra) {
    var options = Object.assign({ op: kind, url: String(url) }, extra || {});
    if (options.headers) options.headers = normalizeHeaders(options.headers);
    return options;
  }
  return {
    http: {
      getText: function (url, options) {
        options = options || {};
        var merged = httpOptions('getText', url, {
          headers: options.headers,
          connectTimeoutMs: options.connectTimeoutMs,
          readTimeoutMs: options.readTimeoutMs
        });
        return post(JSON.stringify(merged));
      },
      get: function (url, options) {
        options = options || {};
        return JSON.parse(post(JSON.stringify(httpOptions('get', url, {
          headers: options.headers,
          connectTimeoutMs: options.connectTimeoutMs,
          readTimeoutMs: options.readTimeoutMs
        }))));
      },
      postText: function (url, body, options) {
        options = options || {};
        var payload = body;
        if (payload != null && typeof payload !== 'string') payload = JSON.stringify(payload);
        return post(JSON.stringify(httpOptions('postText', url, {
          body: payload == null ? '' : payload,
          contentType: options.contentType,
          headers: options.headers,
          connectTimeoutMs: options.connectTimeoutMs,
          readTimeoutMs: options.readTimeoutMs
        })));
      },
      postBytesResponse: function (url, body, options) {
        options = options || {};
        var payload = body;
        if (payload != null && typeof payload !== 'string') payload = JSON.stringify(payload);
        return JSON.parse(post(JSON.stringify(httpOptions('postBytes', url, {
          body: payload == null ? '' : payload,
          contentType: options.contentType,
          headers: options.headers,
          connectTimeoutMs: options.connectTimeoutMs,
          readTimeoutMs: options.readTimeoutMs
        }))));
      }
    },
    base64: {
      encodeText: function (text) {
        return __host.base64(JSON.stringify({ op: 'encode', data: String(text) }));
      },
      decodeText: function (text) {
        return __host.base64(JSON.stringify({ op: 'decode', data: String(text) }));
      },
      decodeUrlText: function (text) {
        return __host.base64(JSON.stringify({ op: 'decodeUrl', data: String(text) }));
      },
      dropBytes: function (text, count) {
        return __host.base64(JSON.stringify({ op: 'dropBytes', data: String(text), count: Number(count || 0) }));
      }
    },
    bytes: {
      xorBase64: function (data, key) {
        return __host.bytes(JSON.stringify({ op: 'xorBase64', data: String(data), key: key || [] }));
      }
    },
    compression: {
      inflateBase64ToText: function (data) {
        return __host.compression(JSON.stringify({ op: 'inflateBase64', data: String(data) }));
      },
      inflateBytesToText: function (data) {
        return __host.compression(JSON.stringify({ op: 'inflateBytes', data: data || [] }));
      }
    },
    crypto: {
      md5: function (text) {
        return __host.crypto(JSON.stringify({ op: 'md5', data: String(text) }));
      },
      sha1: function (text) {
        return __host.crypto(JSON.stringify({ op: 'sha1', data: String(text) }));
      },
      sha256: function (text) {
        return __host.crypto(JSON.stringify({ op: 'sha256', data: String(text) }));
      },
      aesEcbPkcs5EncryptHex: function (data, key) {
        return __host.crypto(JSON.stringify({ op: 'aesEcbEncryptHex', data: String(data), key: String(key) }));
      },
      aesEcbPkcs5EncryptBase64: function (data, key) {
        return __host.crypto(JSON.stringify({ op: 'aesEcbEncryptBase64', data: String(data), key: String(key) }));
      },
      aesEcbPkcs5DecryptBase64ToText: function (data, key) {
        return __host.crypto(JSON.stringify({ op: 'aesEcbDecryptBase64ToText', data: String(data), key: String(key) }));
      }
    },
    cache: {
      get: function (key) {
        var value = __host.cache(JSON.stringify({ op: 'get', key: String(key) }));
        return value ? value : null;
      },
      set: function (key, value, ttlMs) {
        __host.cache(JSON.stringify({ op: 'set', key: String(key), value: String(value), ttlMs: Number(ttlMs || 0) }));
      },
      remove: function (key) {
        __host.cache(JSON.stringify({ op: 'remove', key: String(key) }));
      }
    },
    log: {
      debug: function (tag, message) { __host.log('debug', String(tag), String(message)); },
      info: function (tag, message) { __host.log('info', String(tag), String(message)); },
      warn: function (tag, message) { __host.log('warn', String(tag), String(message)); },
      error: function (tag, message) { __host.log('error', String(tag), String(message)); }
    },
    i18n: {
      t: function (key, arg1, arg2, arg3) {
        var name = String(key);
        var text = (__i18n.primary && __i18n.primary[name]) || (__i18n.fallback && __i18n.fallback[name]) || name;
        var args = [arg1, arg2, arg3];
        for (var i = 0; i < args.length; i++) {
          if (args[i] === undefined || args[i] === null) continue;
          var token = '%' + (i + 1) + String.fromCharCode(36) + 's';
          text = text.split(token).join(String(args[i]));
        }
        return text;
      }
    },
    xml: {
      getRootAttributes: function (xml) {
        var text = __host.xml(JSON.stringify({ op: 'rootAttributes', xml: String(xml || '') }));
        return text ? JSON.parse(text) : {};
      },
      findElements: function (xml, query) {
        query = query || {};
        var text = __host.xml(JSON.stringify({
          op: 'find',
          xml: String(xml || ''),
          tag: String(query.tag || ''),
          attrs: query.attrs || {}
        }));
        return text ? JSON.parse(text) : [];
      },
      removeElements: function (xml, query) {
        query = query || {};
        return __host.xml(JSON.stringify({
          op: 'remove',
          xml: String(xml || ''),
          tag: String(query.tag || ''),
          attrs: query.attrs || {}
        }));
      },
      replaceChildrenByAttr: function (xml, query) {
        query = query || {};
        return __host.xml(JSON.stringify({
          op: 'replaceChildren',
          xml: String(xml || ''),
          targetTag: String(query.targetTag || ''),
          keyAttr: String(query.keyAttr || ''),
          replacements: query.replacements || {},
          rootAttributes: query.rootAttributes || {}
        }));
      }
    },
    app: {
      getUserAgent: function () {
        var info = JSON.parse(__host.info());
        return String(info.userAgent || '');
      }
    },
    runtime: {
      getInfo: function () { return JSON.parse(__host.info()); }
    }
  };
})();
"""
    }
}
