package com.audiometa.app.plugin.install

import com.audiometa.app.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * **[只给单元测试用]**：把仓库里按组分片的 tag_rules 清单合并成一份完整规则。
 *
 * ## 为什么它在测试源集里
 *
 * 它以前是产品代码：App 运行时按 `rules/rules.json` 的 `files[]` 逐个下载
 * 30 多个分片、下完再合并。现在**更新只下载 `MetaAudio-Subscription.zip`**
 * （包里直接就是合并好的 `rules/tag_rules.json`），分片下载这条路
 * 已经连入口一起删掉了 —— 主代码里对这个对象 0 引用。
 *
 * 但仓库里那份**分片结构还在**（仓库要按组维护规则），而单测需要从仓库真实文件
 * 重建出"合并后的规则"来验证解析/识别：所以合并算法留在这里，当测试夹具用。
 * 放在 main 里就是"永远不执行的死代码"，放这里则名副其实。
 *
 * 合并规则（和以前一致，测试夹具必须和仓库的合并结果一致）：
 * - 数组（groups / hints）**拼接**；
 * - 对象（standard / valueLabels / keyInfo…）**按键合并**（分片里的键覆盖清单里的）；
 * - 其它（schema / rulesVersion / rulesUpdatedAt / source）以清单为准。
 *
 * 注意：**没有** `resolve()`（分片地址拼接）了 —— 那是下载器才需要的东西，
 * 下载器已经不存在。
 */
object RuleBundle {

    /** 清单里的一个分片。 */
    data class Entry(val file: String, val sha256: String = "")

    /** 顶层字段里哪些是"数组要拼"、哪些是"对象要合"。 */
    private val ARRAY_KEYS = setOf("groups", "hints")
    private val OBJECT_KEYS = setOf(
        "standard", "standardValues", "valueLabels", "valueLabelPatterns",
        "valueInfoPatterns", "valueAliases", "keyInfo", "sourcePriority"
    )

    private const val TAG = "RuleBundle"

    /** 这份 JSON 是不是"清单"（带 files 数组）。老的单文件规则会返回 false。 */
    fun isManifest(text: String): Boolean = runCatching {
        val obj = JSONObject(stripBom(text))
        obj.has("files") && obj.optJSONArray("files")?.length() ?: 0 > 0
    }.getOrDefault(false)

    /** 清单里列出的分片（保持顺序）。 */
    fun entries(manifestText: String): List<Entry> = runCatching {
        val array = JSONObject(stripBom(manifestText)).optJSONArray("files") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val file = item.optString("file").trim()
            if (file.isBlank()) null else Entry(file, item.optString("sha256").trim())
        }
    }.getOrDefault(emptyList())

    /**
     * 合成一份完整规则 JSON。
     *
     * @param parts 文件路径 → 内容。**缺失的分片直接跳过**：一个组拿不到不该让
     *   整份规则作废（那会让元信息页少认一批标签）。
     */
    fun merge(manifestText: String, parts: List<Pair<String, String>>): String {
        val manifest = JSONObject(stripBom(manifestText))
        val out = JSONObject()
        // 清单自己的字段先铺上（除了 files）。
        manifest.keys().forEach { key ->
            if (key != "files") out.put(key, manifest.get(key))
        }
        parts.forEach { (file, text) ->
            val part = runCatching { JSONObject(stripBom(text)) }.getOrNull()
            if (part == null) {
                AppLog.w(TAG, "规则分片不是有效 JSON，已跳过：" + file)
                return@forEach
            }
            part.keys().forEach { key ->
                when {
                    key in ARRAY_KEYS -> out.put(key, concat(out.optJSONArray(key), part.optJSONArray(key)))
                    key in OBJECT_KEYS -> out.put(key, mergeObjects(out.optJSONObject(key), part.optJSONObject(key)))
                    // 清单没写的标量才用分片里的（版本号一律以清单为准）。
                    !out.has(key) -> out.put(key, part.get(key))
                }
            }
        }
        return out.toString()
    }

    private fun concat(base: JSONArray?, extra: JSONArray?): JSONArray {
        val out = JSONArray()
        base?.let { for (i in 0 until it.length()) out.put(it.get(i)) }
        extra?.let { for (i in 0 until it.length()) out.put(it.get(i)) }
        return out
    }

    private fun mergeObjects(base: JSONObject?, extra: JSONObject?): JSONObject {
        val out = JSONObject()
        base?.keys()?.forEach { key -> out.put(key, base.get(key)) }
        extra?.keys()?.forEach { key -> out.put(key, extra.get(key)) }
        return out
    }

    private fun stripBom(text: String): String = text.removePrefix("\uFEFF").trim()
}
