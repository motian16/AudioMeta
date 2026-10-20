package com.audiometa.app.plugin.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 插件平台的 `Platform.xml.*`。
 *
 * Lyrico 的 Apple 插件用这组函数处理 TTML 歌词（本地化替换、删掉已合并的 translation）。
 * 宿主以前没有这组 API，插件会自己检测 `runtime.getInfo().supportedHostApis` 然后跳过 ——
 * 结果只是"Apple 官方歌词不做本地化"，但既然要兼容 Lyrico 源，这里补上最小的
 * 四个操作：读根属性、按标签+属性查找、按属性删除、按 `itunes:key` 替换子节点。
 *
 * 全部在宿主侧用标准 DOM 完成，返回值仍是 JSON 字符串，符合 HostBridge 的约束。
 * 任何解析/序列化失败都返回空串或原 XML，绝不让插件因为一段坏 XML 把刮削带崩。
 */
object PluginXml {

    fun handle(requestJson: String): String = runCatching {
        val options = JSONObject(requestJson)
        when (options.optString("op")) {
            "rootAttributes" -> rootAttributes(options.optString("xml"))
            "find" -> find(
                options.optString("xml"),
                options.optString("tag"),
                options.optJSONObject("attrs")
            )
            "remove" -> remove(
                options.optString("xml"),
                options.optString("tag"),
                options.optJSONObject("attrs")
            )
            "replaceChildren" -> replaceChildren(options)
            else -> ""
        }
    }.getOrDefault("")

    private fun rootAttributes(xml: String): String {
        val doc = parse(xml) ?: return "{}"
        val root = doc.documentElement ?: return "{}"
        return attributes(root).toString()
    }

    private fun find(xml: String, tag: String, attrs: JSONObject?): String {
        if (tag.isBlank()) return "[]"
        val doc = parse(xml) ?: return "[]"
        val out = JSONArray()
        val nodes = doc.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (!matches(element, attrs)) continue
            out.put(elementJson(element))
        }
        return out.toString()
    }

    private fun remove(xml: String, tag: String, attrs: JSONObject?): String {
        if (tag.isBlank()) return xml
        val doc = parse(xml) ?: return xml
        val nodes = doc.getElementsByTagName(tag)
        val targets = (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { matches(it, attrs) }
        targets.forEach { element ->
            element.parentNode?.removeChild(element)
        }
        return serialize(doc) ?: xml
    }

    private fun replaceChildren(options: JSONObject): String {
        val xml = options.optString("xml")
        val targetTag = options.optString("targetTag")
        val keyAttr = options.optString("keyAttr")
        val replacements = options.optJSONObject("replacements") ?: JSONObject()
        if (xml.isBlank() || targetTag.isBlank() || keyAttr.isBlank()) return xml

        val doc = parse(xml) ?: return xml
        val nodes = doc.getElementsByTagName(targetTag)
        val targets = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        for (element in targets) {
            // keyAttr 在目标元素自己身上（Apple 的 <p itunes:key="1">），
            // 不是子节点上；replacements 的键来自 <text for="1">。
            val replacement = replacements.optJSONObject(attrValue(element, keyAttr)) ?: continue
            while (element.firstChild != null) element.removeChild(element.firstChild)
            val value = replacement.optString("value")
            if (replacement.optString("mode") == "xml") {
                val fragment = parse("<root>" + value + "</root>")
                val fragmentRoot = fragment?.documentElement
                if (fragmentRoot != null) {
                    var item = fragmentRoot.firstChild
                    while (item != null) {
                        val imported = doc.importNode(item, true)
                        element.appendChild(imported)
                        item = item.nextSibling
                    }
                }
            } else if (value.isNotEmpty()) {
                element.appendChild(doc.createTextNode(value))
            }
        }

        val rootAttrs = options.optJSONObject("rootAttributes")
        val root = doc.documentElement
        if (rootAttrs != null && root != null) {
            rootAttrs.keys().forEach { key ->
                val value = rootAttrs.optString(key)
                if (value.isNotEmpty()) root.setAttribute(key, value)
            }
        }
        return serialize(doc) ?: xml
    }

    private fun parse(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // TTML 是外部内容：禁掉 DTD/外部实体，避免 XXE。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.parse(org.xml.sax.InputSource(StringReader(xml)))
    }.getOrNull()

    private fun serialize(doc: Document): String? = runCatching {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        writer.toString()
    }.getOrNull()

    private fun innerXml(element: Element): String = runCatching {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        var child = element.firstChild
        while (child != null) {
            transformer.transform(DOMSource(child), StreamResult(writer))
            child = child.nextSibling
        }
        writer.toString()
    }.getOrDefault("")

    private fun elementJson(element: Element): JSONObject {
        val children = JSONArray()
        var child = element.firstChild
        while (child != null) {
            if (child is Element) children.put(elementJson(child))
            child = child.nextSibling
        }
        return JSONObject()
            .put("tag", element.tagName)
            .put("attrs", attributes(element))
            .put("text", element.textContent.orEmpty())
            .put("innerXml", innerXml(element))
            .put("children", children)
    }

    private fun attributes(element: Element): JSONObject {
        val out = JSONObject()
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val node = attributes.item(i) ?: continue
            out.put(node.nodeName, node.nodeValue.orEmpty())
        }
        return out
    }

    private fun matches(element: Element, attrs: JSONObject?): Boolean {
        if (attrs == null || attrs.length() == 0) return true
        attrs.keys().forEach { key ->
            if (attrValue(element, key) != attrs.optString(key)) return false
        }
        return true
    }

    /**
     * 取属性值。
     *
     * DOM 对带前缀的属性（`itunes:key`、`xml:lang`）在部分实现里
     * `getAttribute(前缀:名)` 会返回空串，必须再按 nodeName 扫一遍。
     */
    private fun attrValue(element: Element, name: String): String {
        element.getAttributeNode(name)?.let { return it.value.orEmpty() }
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val node = attributes.item(i) ?: continue
            if (node.nodeName == name) return node.nodeValue.orEmpty()
        }
        return ""
    }
}
