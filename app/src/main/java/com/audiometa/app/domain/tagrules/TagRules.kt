package com.audiometa.app.domain.tagrules

import android.content.Context
import com.audiometa.app.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条标签的识别结果。
 *
 * @param key 标签键名（原样，不做大小写归一）
 * @param value 标签值
 * @param platform 命中的平台名，未命中为 null
 * @param tool 命中的工具链签名，未命中为 null
 * @param hints 启发式提示（可能含站点 URL、Base64、UUID…）
 * @param isStandard 是否属于标准标签（标准标签不会出现在"未知标签"列表里）
 */
data class TagEntry(
    val key: String,
    val value: String,
    val platform: String? = null,
    val tool: String? = null,
    val hints: List<String> = emptyList(),
    val isStandard: Boolean = false
) {
    /** 有来源或提示，值得在界面上强调。 */
    val notable: Boolean get() = platform != null || tool != null || hints.isNotEmpty()
}

/** 规则元信息，界面上显示"规则版本 / 来源"。 */
data class TagRuleInfo(
    val version: Int = 0,
    val updatedAt: String = "",
    val source: String = "",
    val platformCount: Int = 0,
    val toolCount: Int = 0,
    val standardKeyCount: Int = 0
)

/**
 * 一个标签键（或一类值）的"是什么"。
 *
 * 用户要求"值和键名都有中文别名"，所以 [alias] 是中文短名，[type] 是类型，
 * [description] 才是展开说明。界面上：别名贴在名字旁边，说明放在最下面。
 */
data class TagKeyInfo(
    val type: String,
    val description: String,
    /** 中文别名，例如 keyInfo 的 `major_brand` → 「容器主品牌」。 */
    val alias: String = ""
)

/**
 * 分组规则在人看来的样子（「设置 - 插件 - 标签规则」那一页用它把规则翻成中文）。
 *
 * @param rightKey 列表右侧固定显示的那个键（[RuleKeyInfo.key] 的小写形式）；没写就是空串。
 *   文件里有多个键名时，列表右侧就认它，见 [MetaTrackValue] 那一层的用法。
 * @param keys 这一组认领的键名，含别名/类型/说明；[RuleKeyInfo.pattern] 为 true 的是正则。
 * @param valueLabels 值的中文译名（原值 → 译名）。
 */
data class RuleGroupInfo(
    val name: String,
    val kind: String,
    val rightKey: String = "",
    val keys: List<RuleKeyInfo> = emptyList(),
    val valueLabels: List<Pair<String, String>> = emptyList(),
    val keyPatternCount: Int = 0,
    val valuePatternCount: Int = 0
)

/** 规则里的一条键名（或一条正则）在人看来的样子。 */
data class RuleKeyInfo(
    val key: String,
    val alias: String = "",
    val type: String = "",
    val description: String = "",
    /** [key] 是正则（keyPatterns / keyInfoPatterns）而不是字面键名。 */
    val pattern: Boolean = false
)

/** 整份规则的人话版：分组 + 全局的值别名/说明/译名/提示。 */
data class RuleBook(
    val groups: List<RuleGroupInfo> = emptyList(),
    val valueAliases: List<RuleKeyInfo> = emptyList(),
    val valueInfos: List<RuleKeyInfo> = emptyList(),
    val valueLabels: List<Pair<String, String>> = emptyList(),
    val hints: List<RuleKeyInfo> = emptyList(),
    val standardKeyCount: Int = 0,
    val keyInfoCount: Int = 0,
    /** 键名 → 认可的值正则：这些键只有值也对得上才算标准标签。 */
    val standardValues: List<Pair<String, String>> = emptyList(),
    /** 规则里的源优先级（meta / artistImage / lyrics）。 */
    val sourcePriority: Map<String, List<String>> = emptyMap(),
    /** 规则里声明默认关闭的插件 id。 */
    val disabledSources: List<String> = emptyList()
)

/**
 * 标签识别规则。
 *
 * 规则与代码分离：APK 里**不带**规则，启动时读外部那一份（[externalFile]）；
 * 那一份由「更新订阅」从插件仓库的 index.json 里下载、校验 sha256 后装进来
 *（[installRules]，调用方是 [com.audiometa.app.plugin.install.PluginRepoUpdater]）。
 * 这样补充新平台标签不需要重新发版，用户也不用自己找规则文件。
 *
 * 判定流程严格按文档里的"第九节 软件实现建议"：
 *   1. 排除标准标签白名单；
 *   2. 剩下的依次匹配平台特征表与工具链表；
 *   3. 多来源冲突时按优先级（视频容器 > 加密残留 > 平台 > 工具）；
 *   4. 都没命中的归入"未知自定义标记"，按启发式规则给提示。
 *
 * **没有规则不等于什么都不显示**（见 [classify]）：文件里读到的标签照样逐条列出来，
 * 一份规则都没有时按**键名**分组（[TagSummary.group]），规则只负责给它们加别名、
 * 说明和平台分组。
 */
object TagRules {

    private const val TAG = "TagRules"
    private const val ASSET_NAME = "tag_rules.json"
    private const val EXTERNAL_NAME = "tag_rules.json"

    /**
     * "还没导入规则"的来源标记（[setEmpty] 和 [clearRules] 都会写到它）。
     *
     * 界面拿它判断"现在到底有没有规则" —— 写死字符串容易和 [setEmpty] 走散，
     * 所以统一从这里取。
     */
    const val SOURCE_NOT_IMPORTED = "未导入"

    /**
     * 现在是不是有一份可用的规则。
     *
     * 只看来源，**不看版本号**：规则文件里没写 `rulesVersion` 时版本是 0，
     * 但那份规则是真实生效的，不能因此被当成"没导入"。
     */
    val isImported: Boolean get() = info.source != SOURCE_NOT_IMPORTED

    /**
     * 规则变化信号：规则内容被替换（load 成功、installRules 生效、清空）就自增。
     *
     * [isImported] / [ruleInfo] 是普通属性，界面读它们不会建立订阅 ——
     * 订阅更新把规则装好之后，插件列表里"标签规则：未安装"那一行不会自己刷新，
     * 要重进页面才能看到。让界面 collect 这个 stamp：值一变就重组，
     * 再读 [isImported] 拿到的就是新状态。
     */
    private val _rulesStamp = kotlinx.coroutines.flow.MutableStateFlow(0)
    val rulesStamp: kotlinx.coroutines.flow.StateFlow<Int> = _rulesStamp

    private fun notifyRulesChanged() {
        _rulesStamp.value = _rulesStamp.value + 1
    }

    private val standardKeys = LinkedHashSet<String>()
    private val platforms = mutableListOf<PlatformRule>()
    private val tools = mutableListOf<ToolRule>()
    private val hints = mutableListOf<HintRule>()
    /*
     * 组级的**显示方式**（全部由规则文件决定，宿主不写死任何组名）：
     * - listBy        ：这一组按什么拆下级列表（album = 按专辑）
     * - mergeListBy   ：多个下级列表能不能合并（artist = 同一位艺术家的多张专辑合成一个）
     * - familyPatterns：值的"家族"正则（Lavf57.25.100 与 Lavf58.76.100 算一家）
     */
    private val groupListBy = mutableMapOf<String, String>()
    private val groupMergeBy = mutableMapOf<String, String>()
    private val groupFamilies = mutableMapOf<String, List<Regex>>()
    private val groupFamilyMin = mutableMapOf<String, Int>()
    /** 归一化键名 → （小写值 → 中文译名）。组内 valueLabels + 全局 byValue 合并后的结果。 */
    private val valueLabelsByKey = mutableMapOf<String, Map<String, String>>()
    /** 小写值 → 中文译名，全局兜底。 */
    private val valueLabelsByValue = mutableMapOf<String, String>()
    /** 带捕获组的译名规则（例如 EVENT 的 C75 → Comic Market 75）。 */
    private val valueLabelPatterns = mutableListOf<ValueLabelPattern>()
    /** 按**类型**给值取别名，顺序敏感：前面命中的先生效。 */
    private val valueAliases = mutableListOf<ValueAlias>()
    /** 归一化键名 → 类型与说明。 */
    private val keyInfos = mutableMapOf<String, TagKeyInfo>()
    /** 用正则匹配键名的说明：一类键共用一份说明（mora 的 7 个 UUID 就是这种）。 */
    private val keyInfoPatterns = mutableListOf<KeyInfoPattern>()
    /**
     * 按**值的类型**给说明，跨键复用。
     *
     * 用户要求："每个 UUID 字符串底下的介绍是针对其值做出的介绍，对值进行复用级匹配，
     * 不需要你为每一项都列出三种值类型"—— 所以同一类的值共用一条说明，
     * 不为每个键各写一份。
     */
    private val valueInfoRules = mutableListOf<ValueInfoRule>()
    /** 图片类值归到哪一组。来自规则里 `kind = "image"` 的那一组。 */
    private var imageGroupName: String = "未知图片"
    private var info = TagRuleInfo()

    /**
     * 每个分组"列表右侧固定显示哪个键"（分组名 → 键名小写）。
     *
     * 用户要求：文件里同一组有多个键名时，右侧要**稳定地**显示最关键的那个
     *（酷狗是 QUALITY、mora 是 ORGANIZATION），而不是碰到哪个算哪个。
     */
    private val rightKeys = mutableMapOf<String, String>()

    /**
     * 只有**值**也"标准"时才算标准标签的键（键名小写 → 认可的值正则）。
     *
     * 用户要求：编码函数库（`encoder` / `encodedby` / `©too` / `TENC`）这一项要看值 ——
     * 写 `Lavf58.29.100`、`LAME3.100` 是正常的，写 `baimusic` 就是第三方平台
     * 往文件里塞的私货，必须列到元信息页里。
     */
    private val standardValueRules = mutableMapOf<String, List<Regex>>()

    /** 整份规则的人话版，「设置 - 插件 - 标签规则」页直接画它。 */
    private var book = RuleBook()

    val ruleInfo: TagRuleInfo get() = info

    /** 规则内容的人话版；没有规则时是空的。 */
    val ruleBook: RuleBook get() = book

    /**
     * 这一组在列表右侧固定显示哪个键（小写）；规则里没写就返回 null。
     *
     * @param group 分组名（平台名 / 工具名 / 「未知图片」）。
     */
    fun rightKey(group: String): String? = rightKeys[group.trim()]?.takeIf { it.isNotBlank() }

    /** 一条按类型取别名的规则，例如"32 位十六进制 → 哈希值"。 */
    private class ValueAlias(val label: String, val pattern: Regex)

    /** 键名正则 → 说明。 */
    private class KeyInfoPattern(val pattern: Regex, val info: TagKeyInfo)

    /** 值正则 → 说明（可按键名限定）。 */
    private class ValueInfoRule(
        val keyPattern: Regex?,
        val valuePattern: Regex,
        val info: TagKeyInfo
    )

    /** 译名规则：键匹配 + 值匹配，label 里的 $1 会换成捕获组。 */
    private class ValueLabelPattern(
        val keyPattern: Regex?,
        val valuePattern: Regex,
        val label: String
    )

    private class PlatformRule(
        val name: String,
        val keys: List<String>,
        val keyPatterns: List<Regex>,
        val valuePatterns: List<Regex>
    )

    /**
     * 工具链签名。
     *
     * 除了按**值**匹配，也支持按**键名**匹配：专业音频工作站的字段
     * （UMID、ORIGINATOR_REFERENCE、TIME_REFERENCE、EVENT）本身就是标记，
     * 值里往往没有工具名，只按值匹配会把它们全丢进"未知标签"。
     */
    private class ToolRule(
        val name: String,
        val keys: List<String>,
        val keyPatterns: List<Regex>,
        val valuePatterns: List<Regex>
    )

    private class HintRule(
        val label: String,
        val keyPattern: Regex?,
        val valuePattern: Regex?
    )

    /**
     * 外部规则文件：`Android/data/<包名>/files/tag_rules.json`。
     *
     * 这是**唯一刻意保留成文件**的非数据库数据 ——
     * 它是"装进来的那份规则"本身，用户出问题时能直接看到里面是什么。
     * 写入只发生在订阅更新时（[installRules]）：App 里既没有手填规则地址的入口，
     * 也不能从本地文件导入。
     */
    fun externalFile(context: Context): File =
        com.audiometa.app.util.StoragePaths.tagRulesFile(context)

    /**
     * 加载规则。优先用外部文件（用户更新过的），没有就是"没有规则"。
     * 外部文件解析失败时不落在半坏的状态上，一样退回"没有规则"。
     */
    fun load(context: Context) {
        val external = externalFile(context)
        if (external.isFile && external.length() > 0L) {
            val text = runCatching { external.readText() }.getOrNull()
            if (text != null && parse(context, text, "外部文件")) return
            AppLog.w(TAG, "外部标签规则解析失败，按「没有规则」处理")
        }
        // 规则**不再内置**（用户要求）：APK 里已经没有 tag_rules.json 了。
        //
        // 这里刻意不再退回任何内置版本 —— 没装就是没有规则，
        // 元信息页会提示到「插件」页点一次「更新订阅」，规则会跟着订阅一起装进来，
        // 这样"看到的效果"和"用的是哪份规则"永远一致。
        //
        // **没有规则不等于元信息页是空的**：标准键的兜底在代码里
        // （[BUILT_IN_STANDARD_KEYS]），文件里读到的自定义标签照样逐条列出来，
        // 只是暂时都归到「未知标签」、没有别名和说明而已。
        //
        // 兼容：老版本把规则打在 assets 里，万一还有残留就仍然读它，
        // 免得升级上来的用户突然什么都看不见。
        val legacy = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (legacy != null && parse(context, legacy, "内置")) return
        AppLog.w(TAG, "还没有装上标签规则：自定义标签会归到「未知标签」，没有平台与说明")
        setEmpty()
    }

    /**
     * 安装一份新规则（调用方是订阅更新：下载自插件仓库 index.json 里 `rules` 指向的地址）。
     *
     * 只有解析成功才落盘，避免把坏文件写进去导致下次启动用不了。
     *
     * @return 成功返回 true，并立即生效。
     */
    fun installRules(context: Context, text: String, sourceLabel: String = "导入"): Boolean {
        val okay = runCatching { parse(context, text, sourceLabel) }.getOrDefault(false)
        if (!okay) return false
        val file = externalFile(context)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        }.getOrElse {
            AppLog.w(TAG, "标签规则写入失败：" + it.message)
            false
        }
    }

    /**
     * 清空到"没有规则"的状态。
     *
     * 规则不再内置，所以没有"回到内置版本"这回事了 —— 没装就是空的。
     * 界面靠 [ruleInfo] 的 source 字段判断要不要提示用户去订阅一次。
     *
     * **清空不等于不能识别**：标准键的兜底在 [BUILT_IN_STANDARD_KEYS]，
     * 文件里的自定义标签照样逐条列出来（归到「未知标签」），
     * 只是没有别名、说明和平台分组。
     */
    internal fun clearRules() {
        // 和 [parse] 抢同一把锁：两边都在改同一批可变字段。
        synchronized(this) { setEmpty() }
        notifyRulesChanged()
        // 日志放在锁外面：AppLog 会推一次 Flow，别占着锁做这件事。
        AppLog.i(TAG, "标签规则已清空：自定义标签会归到「未知标签」")
    }

    private fun setEmpty() {
        // 清掉解析出来的中间态，并把"来源"标成未安装 ——
        // 界面据此提示用户去「插件」页更新一次订阅。
        standardKeys.clear()
        platforms.clear()
        tools.clear()
        hints.clear()
        valueLabelsByKey.clear()
        groupListBy.clear(); groupMergeBy.clear(); groupFamilies.clear(); groupFamilyMin.clear()
        valueLabelsByValue.clear()
        valueLabelPatterns.clear()
        valueAliases.clear()
        keyInfos.clear()
        keyInfoPatterns.clear()
        valueInfoRules.clear()
        rightKeys.clear()
        // 一份规则都没有时，编码函数库仍按内置名单判标准与否。
        standardValueRules.clear()
        standardValueRules += BUILT_IN_STANDARD_VALUES
        book = RuleBook()
        imageGroupName = TagSummary.IMAGE_GROUP
        info = TagRuleInfo(version = 0, updatedAt = "", source = SOURCE_NOT_IMPORTED)
    }

    /**
     * 直接吃一段规则文本。
     *
     * 给单元测试用：判断逻辑不需要 Context，只有读文件那一步需要。
     * 测试里把 `MetaAudio-Repo/rules/tag_rules.json` 当普通文件读进来喂给这里，
     * 就能在电脑上验证规则表和识别结果，不必装到手机上试。
     */
    internal fun loadFromText(text: String, sourceLabel: String = "测试"): Boolean =
        parse(null, text, sourceLabel)

    /** 从规则 JSON 的一个对象里读出"别名 + 类型 + 说明"；都空就当没写。 */
    private fun readKeyInfo(obj: JSONObject): TagKeyInfo? {
        val description = obj.optString("description").trim()
        val type = obj.optString("type").trim()
        val alias = obj.optString("alias").trim()
        if (description.isBlank() && type.isBlank() && alias.isBlank()) return null
        return TagKeyInfo(type = type, description = description, alias = alias)
    }

    /** 一条"按值取说明"规则。 */
    private fun readValueInfoRule(obj: JSONObject): ValueInfoRule? {
        val pattern = obj.optString("valuePattern").toRegexSafe() ?: return null
        val info = readKeyInfo(obj) ?: return null
        return ValueInfoRule(
            keyPattern = obj.optString("keyPattern").takeIf { it.isNotBlank() }?.toRegexSafe(),
            valuePattern = pattern,
            info = info
        )
    }

    private fun parse(context: Context?, text: String, sourceLabel: String): Boolean = runCatching {
        val root = JSONObject(text)
        val nextStandard = LinkedHashSet<String>()
        // 新格式用 `standard`，老格式用 `standardTags`，两个都认。
        val standardArray = root.optJSONObject("standard")?.optJSONArray("keys")
            ?: root.optJSONObject("standardTags")?.optJSONArray("keys")
        if (standardArray != null) {
            for (i in 0 until standardArray.length()) {
                standardArray.optString(i).trim().lowercase()
                    .takeIf { it.isNotBlank() }?.let { nextStandard += it }
            }
        }

        val nextPlatforms = mutableListOf<PlatformRule>()
        val nextTools = mutableListOf<ToolRule>()
        val nextByKey = mutableMapOf<String, Map<String, String>>()
        val nextKeyInfos = mutableMapOf<String, TagKeyInfo>()
        val nextKeyInfoPatterns = mutableListOf<KeyInfoPattern>()
        val nextValueInfoRules = mutableListOf<ValueInfoRule>()
        var nextImageGroup = "未知图片"
        // 「标签规则」页要画的东西：每个分组各自一份。
        val nextGroupInfos = mutableListOf<RuleGroupInfo>()
        val nextRightKeys = mutableMapOf<String, String>()
        // 组里声明的值译名：先按组记着，最后并进对应键 / 全局兜底。
        val valueLabelsByValueTemp = mutableMapOf<String, String>()

        /** 一组规则的公共部分：名字、匹配方式、说明、值译名、右侧固定键。 */
        fun readGroup(obj: JSONObject) {
            val name = obj.optString("name").ifBlank { obj.optString("id") }
            val kind = obj.optString("kind").ifBlank { "platform" }.lowercase()
            if (kind == "image") {
                // 图片组不参与"按平台/工具认来源"，它只提供一个分组名。
                if (name.isNotBlank()) nextImageGroup = name
            }
            val groupKeys = obj.optJSONArray("keys").toStringListLower()
            // 组级显示方式（规则文件里的 listBy / mergeListBy / familyPatterns）。
            val groupName = obj.optString("name").trim()
            if (groupName.isNotBlank()) {
                obj.optString("listBy").takeIf { it.isNotBlank() }?.let { groupListBy[groupName] = it }
                obj.optString("mergeListBy").takeIf { it.isNotBlank() }?.let { groupMergeBy[groupName] = it }
                val families = obj.optJSONArray("familyPatterns").toRegexList()
                if (families.isNotEmpty()) groupFamilies[groupName] = families
                if (obj.has("familyMergeMin")) groupFamilyMin[groupName] = obj.optInt("familyMergeMin", 10)
            }
            val groupKeyPatterns = obj.optJSONArray("keyPatterns").toRegexList()
            val groupValuePatterns = obj.optJSONArray("valuePatterns").toRegexList()
            // 列表右侧固定显示哪个键（用户要求）：只认键名，不认正则。
            val rightKey = obj.optString("rightKey").ifBlank { obj.optString("displayKey") }
                .trim().lowercase()
            if (name.isNotBlank() && rightKey.isNotBlank()) nextRightKeys[name] = rightKey
            if (kind != "image") {
                if (kind == "tool") {
                    nextTools += ToolRule(name, groupKeys, groupKeyPatterns, groupValuePatterns)
                } else {
                    nextPlatforms += PlatformRule(name, groupKeys, groupKeyPatterns, groupValuePatterns)
                }
            }

            // 组内的键名说明。
            val groupKeyInfo = mutableMapOf<String, TagKeyInfo>()
            obj.optJSONObject("keyInfo")?.let { infoObj ->
                infoObj.keys().forEach { rawKey ->
                    readKeyInfo(infoObj.optJSONObject(rawKey) ?: return@forEach)
                        ?.let {
                            nextKeyInfos[normalizeKey(rawKey)] = it
                            groupKeyInfo[normalizeKey(rawKey)] = it
                        }
                }
            }
            // 组内的正则键名说明：一类键共用一份说明（mora 的 7 个 UUID）。
            val groupKeyInfoPatterns = mutableListOf<RuleKeyInfo>()
            obj.optJSONArray("keyInfoPatterns")?.forEachObject { item ->
                val pattern = item.optString("pattern").toRegexSafe() ?: return@forEachObject
                val keyInfo = readKeyInfo(item) ?: return@forEachObject
                nextKeyInfoPatterns += KeyInfoPattern(pattern, keyInfo)
                groupKeyInfoPatterns += RuleKeyInfo(
                    key = item.optString("pattern").trim(),
                    alias = keyInfo.alias,
                    type = keyInfo.type,
                    description = keyInfo.description,
                    pattern = true
                )
            }
            // 组内的值译名。
            val groupLabels = mutableMapOf<String, String>()
            obj.optJSONObject("valueLabels")?.let { labels ->
                val values = mutableMapOf<String, String>()
                labels.keys().forEach { rawValue ->
                    val label = labels.optString(rawValue).trim()
                    if (label.isNotBlank()) values[rawValue.trim().lowercase()] = label
                }
                if (values.isNotEmpty()) {
                    // 一组里的值译名对"这一组认领的键"生效；没有键时当全局兜底。
                    groupKeys.forEach { key -> nextByKey[normalizeKey(key)] = values }
                    if (groupKeys.isEmpty()) {
                        values.forEach { (k, v) -> valueLabelsByValueTemp[k] = v }
                    }
                    groupLabels += values
                }
            }
            // 组内的"按值取说明"规则。
            obj.optJSONArray("valueInfoPatterns")?.forEachObject { item ->
                readValueInfoRule(item)?.let { nextValueInfoRules += it }
            }

            // ---- 人话版的这一组：认领了哪些键、右侧固定哪个键、值有哪些译名 ----
            val claimed = LinkedHashMap<String, RuleKeyInfo>()
            groupKeys.forEach { key ->
                val info = groupKeyInfo[key]
                claimed[key] = RuleKeyInfo(
                    key = key,
                    alias = info?.alias.orEmpty(),
                    type = info?.type.orEmpty(),
                    description = info?.description.orEmpty()
                )
            }
            groupKeyInfo.forEach { (key, info) ->
                if (!claimed.containsKey(key)) {
                    claimed[key] = RuleKeyInfo(
                        key = key,
                        alias = info.alias,
                        type = info.type,
                        description = info.description
                    )
                }
            }
            nextGroupInfos += RuleGroupInfo(
                name = name,
                kind = kind,
                rightKey = rightKey,
                keys = claimed.values.toList() + groupKeyInfoPatterns,
                valueLabels = groupLabels.entries.map { it.key to it.value },
                keyPatternCount = groupKeyPatterns.size,
                valuePatternCount = groupValuePatterns.size
            )
        }

        // 全局的"按值取说明"规则。
        root.optJSONObject("valueInfoPatterns")?.optJSONArray("patterns")?.forEachObject { item ->
            readValueInfoRule(item)?.let { nextValueInfoRules += it }
        }
        // 全局的"按值取说明"也允许直接写成数组。
        root.optJSONArray("valueInfoPatterns")?.forEachObject { item ->
            readValueInfoRule(item)?.let { nextValueInfoRules += it }
        }

        root.optJSONArray("groups")?.forEachObject { readGroup(it) }
        // 兼容老格式：platforms / tools 平铺两张表。
        root.optJSONArray("platforms")?.forEachObject { readGroup(it) }
        root.optJSONArray("tools")?.forEachObject { readGroup(it) }

        val nextHints = mutableListOf<HintRule>()
        root.optJSONArray("hints")?.forEachObject { obj ->
            nextHints += HintRule(
                label = obj.optString("label").ifBlank { obj.optString("id") },
                keyPattern = obj.optString("keyPattern").takeIf { it.isNotBlank() }?.toRegexSafe(),
                valuePattern = obj.optString("valuePattern").takeIf { it.isNotBlank() }?.toRegexSafe()
            )
        }

        // 顶层 standardValues：键名 → 认可的值正则。这些键只有值也对得上才算标准。
        val nextStandardValues = mutableMapOf<String, List<Regex>>()
        root.optJSONObject("standardValues")?.let { obj ->
            obj.keys().forEach { rawKey ->
                val patterns = obj.optJSONArray(rawKey).toRegexList()
                if (patterns.isNotEmpty()) nextStandardValues[normalizeKey(rawKey)] = patterns
            }
        }

        // 全局值译名。
        val nextByValue = mutableMapOf<String, String>()
        valueLabelsByValueTemp.forEach { (k, v) -> nextByValue[k] = v }
        root.optJSONObject("valueLabels")?.let { labels ->
            labels.optJSONObject("byKey")?.let { keysObj ->
                keysObj.keys().forEach { rawKey ->
                    val map = keysObj.optJSONObject(rawKey) ?: return@forEach
                    val values = mutableMapOf<String, String>()
                    map.keys().forEach { rawValue ->
                        val label = map.optString(rawValue).trim()
                        if (label.isNotBlank()) values[rawValue.trim().lowercase()] = label
                    }
                    if (values.isNotEmpty()) nextByKey[normalizeKey(rawKey)] = values
                }
            }
            labels.optJSONObject("byValue")?.let { valuesObj ->
                valuesObj.keys().forEach { rawValue ->
                    val label = valuesObj.optString(rawValue).trim()
                    if (label.isNotBlank()) nextByValue[rawValue.trim().lowercase()] = label
                }
            }
        }
        /*
         * 组里的值译名**只对它认领的键生效**（上面 groupKeys 那一步已经登记好了）。
         *
         * 这里以前还有一段"没登记过就并进全局兜底"：于是 Apple Music 那一组的
         * `{"0":"无分级","1":"家长建议…","2":"…","4":"仅限成人"}` 变成了对**所有**键
         * 生效的值译名 —— `TRACKTOTAL: 4`、`DISCTOTAL: 1`（专辑分轨/碟数）在元信息页
         * 被判成了 Apple Music 的年龄分级，而且这些文件根本不是 Apple 下载的。
         * 只有**没有认领任何键**的组才允许当全局兜底（上面 if (groupKeys.isEmpty())）。
         */

        // 带捕获组的译名规则。
        val nextLabelPatterns = mutableListOf<ValueLabelPattern>()
        root.optJSONObject("valueLabelPatterns")?.optJSONArray("patterns")?.forEachObject { obj ->
            val valuePattern = obj.optString("valuePattern").toRegexSafe() ?: return@forEachObject
            val label = obj.optString("label").trim()
            if (label.isBlank()) return@forEachObject
            nextLabelPatterns += ValueLabelPattern(
                keyPattern = obj.optString("keyPattern").takeIf { it.isNotBlank() }?.toRegexSafe(),
                valuePattern = valuePattern,
                label = label
            )
        }

        val nextAliases = mutableListOf<ValueAlias>()
        root.optJSONObject("valueAliases")?.optJSONArray("aliases")?.forEachObject { obj ->
            val label = obj.optString("label").trim()
            val pattern = obj.optString("valuePattern").toRegexSafe()
            if (label.isNotBlank() && pattern != null) nextAliases += ValueAlias(label, pattern)
        }

        // 不属于任何组的通用键名说明。
        root.optJSONObject("keyInfo")?.let { obj ->
            obj.keys().forEach { rawKey ->
                readKeyInfo(obj.optJSONObject(rawKey) ?: return@forEach)
                    ?.let { nextKeyInfos[normalizeKey(rawKey)] = it }
            }
        }

        /*
         * 有效性判据：**至少要认领一个分组或列出一批标准键**。
         *
         * 这里不再额外要求"必须列出标准键"：标准键的兜底在代码里
         * （[BUILT_IN_STANDARD_KEYS]），一份只描述平台私有标签的规则文件
         * 是完全合理的（甚至是推荐的 —— 不必把标准字段再抄一遍）。
         *
         * 真正该拒的是"什么都没写"的空文件/坏文件：装上去等于什么都没改变，
         * 却会让界面显示"已导入"。要**清空**规则请走 [clearRules]。
         */
        if (nextPlatforms.isEmpty() && nextTools.isEmpty() && nextStandard.isEmpty()) {
            return@runCatching false
        }

        synchronized(this) {
            standardKeys.clear(); standardKeys += nextStandard
            platforms.clear(); platforms += nextPlatforms
            tools.clear(); tools += nextTools
            hints.clear(); hints += nextHints
            valueLabelsByKey.clear(); valueLabelsByKey += nextByKey
            valueLabelsByValue.clear(); valueLabelsByValue += nextByValue
            valueLabelPatterns.clear(); valueLabelPatterns += nextLabelPatterns
            valueAliases.clear(); valueAliases += nextAliases
            keyInfos.clear(); keyInfos += nextKeyInfos
            keyInfoPatterns.clear(); keyInfoPatterns += nextKeyInfoPatterns
            valueInfoRules.clear(); valueInfoRules += nextValueInfoRules
            imageGroupName = nextImageGroup
            rightKeys.clear(); rightKeys += nextRightKeys
            // 内置的编码函数库标准值打底，规则里的同名键覆盖它。
            standardValueRules.clear()
            standardValueRules += BUILT_IN_STANDARD_VALUES
            standardValueRules += nextStandardValues
            info = TagRuleInfo(
                version = root.optInt("rulesVersion", 0),
                updatedAt = root.optString("rulesUpdatedAt"),
                source = sourceLabel + "（" + root.optString("source").orEmpty() + "）",
                platformCount = nextPlatforms.size,
                toolCount = nextTools.size,
                standardKeyCount = nextStandard.size
            )
            // 「标签规则」页要画的人话版：分组 + 全局的值别名/说明/译名/提示。
            val globalLabels = mutableListOf<Pair<String, String>>()
            root.optJSONObject("valueLabels")?.optJSONObject("byValue")?.let { valuesObj ->
                valuesObj.keys().forEach { rawValue ->
                    val label = valuesObj.optString(rawValue).trim()
                    if (label.isNotBlank()) globalLabels += rawValue.trim() to label
                }
            }
            // 值译名里按**键**分的那一份也列出来（byKey：键 → 值 → 译名）。
            root.optJSONObject("valueLabels")?.optJSONObject("byKey")?.let { keysObj ->
                keysObj.keys().forEach { rawKey ->
                    val map = keysObj.optJSONObject(rawKey) ?: return@forEach
                    map.keys().forEach { rawValue ->
                        val label = map.optString(rawValue).trim()
                        if (label.isNotBlank()) globalLabels += rawKey.trim() + "=" + rawValue.trim() to label
                    }
                }
            }
            // 源优先级：meta / artistImage / lyrics；disabled 是默认关闭的插件。
            val nextPriority = linkedMapOf<String, List<String>>()
            val nextDisabled = mutableListOf<String>()
            root.optJSONObject("sourcePriority")?.let { priority ->
                priority.keys().forEach { key ->
                    if (key == "disabled") {
                        val arr = priority.optJSONArray(key)
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { nextDisabled += it }
                            }
                        }
                    } else {
                        val arr = priority.optJSONArray(key) ?: return@forEach
                        val ids = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { ids += it }
                        }
                        // 规则文件用 "meta" 作为人读的名字；App 内部的键是 "trackMeta"。
                        val targetKey = if (key == "meta") "trackMeta" else key
                        if (ids.isNotEmpty()) nextPriority[targetKey] = ids
                    }
                }
            }
            book = RuleBook(
                groups = nextGroupInfos.toList(),                valueAliases = nextAliases.map {
                    RuleKeyInfo(key = it.pattern.pattern, alias = it.label, pattern = true)
                },
                valueInfos = nextValueInfoRules.map {
                    RuleKeyInfo(
                        key = it.valuePattern.pattern,
                        alias = it.info.alias,
                        type = it.info.type,
                        description = it.info.description,
                        pattern = true
                    )
                },
                valueLabels = globalLabels,
                hints = nextHints.map {
                    RuleKeyInfo(
                        key = it.keyPattern?.pattern ?: it.valuePattern?.pattern.orEmpty(),
                        alias = it.label,
                        pattern = true
                    )
                },
                standardKeyCount = nextStandard.size,
                keyInfoCount = nextKeyInfos.size + nextKeyInfoPatterns.size,
                standardValues = nextStandardValues.entries.flatMap { (key, patterns) ->
                    patterns.map { key to it.pattern }
                },
                sourcePriority = nextPriority,
                disabledSources = nextDisabled
            )
        }
        // 规则内容已替换成功，通知界面（插件列表的"标签规则"行据此立即刷新）。
        notifyRulesChanged()
        AppLog.i(
            TAG,
            "标签规则已加载：$sourceLabel，平台 ${nextPlatforms.size} 个，工具 ${nextTools.size} 个，" +
                "值译名 ${nextByValue.size + nextByKey.size} 组，类型别名 ${nextAliases.size} 条，" +
                "标签说明 ${nextKeyInfos.size} 条 + 正则 ${nextKeyInfoPatterns.size} 条"
        )
        true
    }.getOrElse {
        AppLog.w(TAG, "标签规则解析异常：" + it.message)
        false
    }

    /**
     * 这个键是不是标准标签（标准标签不算"未知"）。
     *
     * **两份表的并集**：用户导入的规则里写了 `standard.keys` 就用它的，
     * 没写（或者干脆没导入规则）时用代码里的 [BUILT_IN_STANDARD_KEYS]。
     *
     * 为什么兜底必须是**代码**而不是再塞一份规则 JSON：用户明确要求
     * "内置白名单就是代码，不额外带规则文件"。规则文件是用户可替换的
     * （`Android/data/<包名>/files/tag_rules.json`），一旦白名单也跟着它走，
     * 用户换一份规则就会让标题、艺术家这些普通字段重新变成"未知标签"。
     * 标准字段是**协议层的事实**，和"哪份规则表"无关，所以它属于代码。
     */
    fun isStandardKey(key: String): Boolean {
        val norm = normalizeKey(key)
        if (norm.isEmpty()) return false
        if (BUILT_IN_STANDARD_KEYS.contains(norm)) return true
        return standardKeys.contains(norm)
    }

    /**
     * 这个标签算不算"不用进元信息页"的标准标签 —— **连值一起看**。
     *
     * 大多数标准键只看键名；编码函数库那类键（`encoder` / `encodedby` / `©too` / `TENC`）
     * 还要求值像标准编码器：`Lavf58.29.100`、`LAME3.100`、`libFLAC 1.3.3` 不列，
     * `baimusic` 这种第三方平台塞进来的名字要列出来（用户要求：
     * "如值不符合标准，就列出"）。
     *
     * 值是空的时候按标准处理：那是"文件里只有键"，界面上另有"（无值）"的处理，
     * 不该因为没写值就冒到元信息页里。
     */
    fun isStandardTag(key: String, value: String): Boolean {
        if (!isStandardKey(key)) return false
        val patterns = standardValueRules[normalizeKey(key)] ?: return true
        if (patterns.isEmpty()) return true
        val v = value.trim()
        if (v.isEmpty()) return true
        return patterns.any { it.containsMatchIn(v) }
    }

    /**
     * 这个标签键大概是什么。
     *
     * 顺序：精确键名 → 正则键名（一类键共用一份说明，mora 的 7 个 UUID 就是这种）。
     * 规则表里没写就返回 null —— 界面**不会**编一句"未知的自定义标记"糊弄过去，
     * 而是明确告诉用户"规则里还没有这个键的说明"。
     */
    fun describe(key: String): TagKeyInfo? {
        keyInfos[normalizeKey(key)]?.let { return it }
        val raw = key.trim()
        return keyInfoPatterns.firstOrNull {
            it.pattern.containsMatchIn(raw) || it.pattern.containsMatchIn(normalizeKey(raw))
        }?.info ?: builtInKeyInfo(raw)
    }

    /**
     * 内置兜底：规则文件里还没写、但归类已经确定的那几个键。
     *
     * 用户明确点名过两组：
     * - `VENDOR`（Vorbis 注释里的写入工具字符串，例如
     *   `reference libFLAC 1.3.1 20141125`）**归到「编码函数库」**，并且要带说明；
     * - `REPLAYGAIN_*`（回放增益）**单独一组「回放增益」**，组里按键盘出列表。
     *
     * 放在代码里而不是只写规则文件：订阅还没更新时行为就已经是对的，
     * 规则文件里再写一遍也不会冲突（规则优先）。
     */
    private fun builtInKeyInfo(rawKey: String): TagKeyInfo? {
        val key = rawKey.trim().lowercase()
        BUILT_IN_KEY_INFO[key]?.let { return it }
        if (key.startsWith("replaygain")) {
            return TagKeyInfo(
                alias = replayGainAlias(key),
                type = "回放增益",
                description = "播放器用来统一音量的回放增益（ReplayGain）参数。" + replayGainDetail(key)
            )
        }
        return null
    }

    private fun replayGainAlias(key: String): String = when {
        key.endsWith("reference_loudness") -> "参考响度"
        key.endsWith("track_gain") -> "单曲增益"
        key.endsWith("track_peak") -> "单曲峰值"
        key.endsWith("album_gain") -> "专辑增益"
        key.endsWith("album_peak") -> "专辑峰值"
        else -> "回放增益"
    }

    private fun replayGainDetail(key: String): String = when {
        key.endsWith("reference_loudness") ->
            "参考响度，通常是 89.0 dB；增益值就是相对它算出来的。"
        key.endsWith("track_gain") ->
            "整首歌需要调整的音量（dB，带正负号），播放器加上它就得到统一响度。"
        key.endsWith("track_peak") ->
            "整首歌的采样峰值（0~1 的小数），用来避免增益后削波。"
        key.endsWith("album_gain") -> "整张专辑的增益，用来保持专辑内各曲的相对音量。"
        key.endsWith("album_peak") -> "整张专辑的峰值。"
        else -> ""
    }

    /**
     * 这一组要不要**无条件**按键（变量）拆出下级列表。
     *
     * 回放增益的三个键：每首歌的增益值都不一样，按"同一个值 ≥10 首"永远凑不出来，
     * 但用户就是要看到"参考响度 / 单曲增益 / 单曲峰值"三条下级列表。
     */
    /** 这一组按什么拆下级列表（目前只定义 album = 按专辑）。 */
    fun listBy(groupName: String): String = groupListBy[groupName.trim()].orEmpty()

    /** 下级列表能不能合并（artist = 同一位艺术家的多张专辑合成一个）。 */
    fun mergeListBy(groupName: String): String = groupMergeBy[groupName.trim()].orEmpty()

    /** 值的"家族"正则：Lavf57.25.100 与 Lavf58.76.100 算一家。 */
    fun familyPatterns(groupName: String): List<Regex> = groupFamilies[groupName.trim()].orEmpty()

    /** 家族合并的阈值（规则没写时按 10 首）。 */
    fun familyMin(groupName: String): Int = groupFamilyMin[groupName.trim()] ?: 10
    fun wantsKeyLists(groupName: String): Boolean {
        val name = groupName.trim()
        if (name == REPLAY_GAIN_GROUP) return true
        return groupKeyListNames.any { it.equals(name, ignoreCase = true) }
    }

    /** 规则文件里 `keyLists = true` 的组名（在解析时收集）。 */
    private val groupKeyListNames = mutableListOf<String>()

    /** 图片类标签归到哪一组（规则里 `kind = "image"` 那一组的名字）。 */
    fun imageGroup(): String = imageGroupName

    /**
     * 内置的分组兜底（规则文件里还没写这些键时用）。
     *
     * 用户明确要求过：**未知标签里那些写入工具/函数库相关的，归到「编码函数库」**，
     * 例如 `VENDOR: reference libFLAC 1.3.1 20141125`；
     * `REPLAYGAIN_*` 单独一组「回放增益」。
     */
    fun builtInGroup(key: String): String? {
        val name = key.trim().lowercase()
        if (name.startsWith("replaygain")) return REPLAY_GAIN_GROUP
        if (name in BUILT_IN_ENCODER_KEYS) return ENCODER_GROUP
        return null
    }

    /**
     * 按**值**取说明。
     *
     * 用户要求"对值进行复用级匹配"：同一个键下面，值是哪一类就说哪一类的话
     * （mora 的数字是内部编号、TFCC 开头的是录音代码），不为每个键各写三行。
     */
    fun valueInfo(key: String, value: String): TagKeyInfo? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val rawKey = key.trim()
        return valueInfoRules.firstOrNull { rule ->
            val keyOk = rule.keyPattern?.let {
                it.containsMatchIn(rawKey) || it.containsMatchIn(normalizeKey(rawKey))
            } ?: true
            keyOk && rule.valuePattern.containsMatchIn(v)
        }?.info
    }

    /**
     * 值是不是"图片类"。
     *
     * 前缀常量放在这里而不是图片存储那边：识别规则属于领域层，
     * 存储层反过来依赖它，方向才是对的。
     */
    fun isImageValue(value: String): Boolean = value.startsWith(IMAGE_VALUE_PREFIX)

    /**
     * 界面上显示的键名。
     *
     * 容器原始写法（`TXXX:major_brand`、`----:com.apple.iTunes:voice-memo-uuid`）
     * 前缀对用户没有意义，MediaInfo 这类工具显示的也是最后一段。原始写法在
     * 标签详情页里作为补充信息保留。
     */
    fun displayKey(key: String): String {
        val k = key.trim()
        val lastColon = k.lastIndexOf(':')
        val short = if (lastColon >= 0 && lastColon < k.length - 1) k.substring(lastColon + 1).trim() else k
        return short.ifBlank { k }
    }

    /**
     * 识别一组标签。
     *
     * @param raw 键 → 值。键已做过容器前缀剥离（例如 ID3 的 `TXXX:FOO` → `FOO`）。
     * @param includeStandard 是否把标准标签也放进结果（详情页要显示，未知标签页不要）。
     */
    fun classify(
        raw: Map<String, String>,
        includeStandard: Boolean = true,
        /**
         * 值空的标签要不要留下。
         *
         * 元信息页要（用户要求："有的只有键，没有值，也显示出来"），
         * 别的地方不用 —— 空值对搜索、对合并都没有意义。
         */
        keepBlankValues: Boolean = false
    ): List<TagEntry> {
        /*
         * **没有规则也要逐条返回。**
         *
         * 这里曾经是 `if (!isImported) return emptyList()`，起因是没有规则时
         * 标准标签白名单是空的，标题、艺术家、专辑会被当成"未知标签"刷满一屏。
         * 但那个修法把因果关系弄反了：规则的作用是**描述已经读到的标签**
         * （别名、说明、平台分组、值译名），不是"决定哪些标签算存在"。
         * 没有规则时用户看到的应该是"这些标签我认出来了，只是还没有说明"，
         * 而不是"什么都没有"。
         *
         * 真正的洪水来源是"标准标签被误判成未知"，所以现在改成
         * [isStandardKey] 用**代码里硬编码的标准键表**兜底（[BUILT_IN_STANDARD_KEYS]）：
         * 规则里写了就按规则的，没写就按内置的。这样既不会刷屏，
         * 也不会因为"用户还没导入规则"把文件里真实存在的标签藏起来。
         */
        val out = mutableListOf<TagEntry>()
        raw.forEach { (key, value) ->
            if (value.isBlank() && !keepBlankValues) return@forEach
            // 标准与否**连值一起判**：编码函数库写了第三方平台的名字时，
            // 它是"基础标签之外的信息"，要照常列出来。
            val standard = isStandardTag(key, value)
            if (standard && !includeStandard) return@forEach
            out += classifyOne(key, value, standard)
        }
        // 有来源的排前面，其次按键名。
        return out.sortedWith(
            compareByDescending<TagEntry> { it.notable }
                .thenByDescending { it.platform != null }
                .thenBy { it.key.lowercase() }
        )
    }

    private fun classifyOne(key: String, value: String, standard: Boolean): TagEntry {
        val normKey = normalizeKey(key)
        var platform: String? = null
        var tool: String? = null

        // 图片类标签不看键名，只看"这个值本身是不是一张图"，
        // 统一归到「未知图片」那一组（用户要求：封面和艺术家图之外的图集中成一组）。
        // 同一首歌的其它标签照旧归各自来源，所以一首歌可以同时出现在多组里。
        if (isImageValue(value)) {
            return TagEntry(
                key = key,
                value = value,
                platform = imageGroupName,
                tool = null,
                hints = hints.filter { rule ->
                    (rule.keyPattern?.containsMatchIn(key) ?: true) &&
                        (rule.valuePattern?.containsMatchIn(value) ?: true)
                }.map { it.label },
                isStandard = standard
            )
        }

        for (rule in platforms) {
            if (rule.keys.any { it == normKey || it == key.lowercase() }) { platform = rule.name; break }
            if (rule.keyPatterns.any { it.containsMatchIn(key) }) { platform = rule.name; break }
            if (rule.valuePatterns.any { it.containsMatchIn(value) }) { platform = rule.name; break }
        }
        for (rule in tools) {
            if (rule.keys.any { it == normKey || it == key.lowercase() } ||
                rule.keyPatterns.any { it.containsMatchIn(key) } ||
                rule.valuePatterns.any { it.containsMatchIn(value) }
            ) {
                tool = rule.name
                break
            }
        }

        val hints = hints.filter { rule ->
            val keyOk = rule.keyPattern?.containsMatchIn(key) ?: true
            val valueOk = rule.valuePattern?.containsMatchIn(value) ?: true
            keyOk && valueOk
        }.map { it.label }

        return TagEntry(
            key = key,
            value = value,
            platform = platform,
            tool = tool,
            hints = hints,
            isStandard = standard
        )
    }

    /**
     * 只在"未知标签"里做聚合：键名 → 出现次数。
     * 「元信息」页签用它列出"这个库里有哪几种自定义标签"。
     *
     * 排序按**出现次数从多到少**（用户要求）：出现得多的标签更值得关注，
     * 一眼就能看到自己库里最普遍的那几种平台标记。
     * 次数相同时按有没有识别出来源排（有来源的更有信息量），最后按键名。
     */
    fun summarize(entries: List<TagEntry>): List<TagSummary> = entries
        .filter { !it.isStandard }
        // 按**显示键名**归并：`TXXX:major_brand` 和裸的 `major_brand` 是同一个东西，
        // 分成两行没有意义。原始写法在 TagSummary.rawKeys 里留着。
        .groupBy { displayKey(it.key).lowercase() }
        .map { (_, items) ->
            val distinct = items.map { it.value }.distinct()
            TagSummary(
                key = displayKey(items.first().key),
                rawKeys = items.map { it.key }.distinct(),
                count = items.size,
                platform = items.firstNotNullOfOrNull { it.platform },
                tool = items.firstNotNullOfOrNull { it.tool },
                hints = items.flatMap { it.hints }.distinct(),
                sampleValue = items.first().value.take(120),
                // 详情列表右侧要显示"这个标签在这个库里出现过哪些值"，
                // 所以保留去重后的取值。截断是为了防某个键有上百种值时撑爆界面。
                values = distinct.take(MAX_VALUES).map { displayValue(items.first().key, it) },
                valueCount = distinct.size,
                // 说明"这个标签大概是什么"。用户明确要求不能只给一个值。
                keyInfo = describe(items.first().key),
                isImage = items.any { isImageValue(it.value) }
            )
        }
        .sortedWith(
            compareByDescending<TagSummary> { it.count }
                .thenByDescending { it.platform != null || it.tool != null }
                .thenBy { it.key.lowercase() }
        )

    /**
     * 值的中文译名。规则里有就返回译名，没有返回 null。
     *
     * 三级：**精确值译名**（组内 / 全局）→ **带捕获组的译名规则**
     * （例如 EVENT 的 C75 → Comic Market 75）。
     * 用户要求"没有准确名字的就不必翻译"，所以这里只认规则表里写了的，绝不猜。
     */
    fun valueLabel(key: String, value: String): String? {
        val trimmed = value.trim()
        val needle = trimmed.lowercase()
        if (needle.isEmpty()) return null
        valueLabelsByKey[normalizeKey(key)]?.get(needle)?.let { return it }
        valueLabelsByValue[needle]?.let { return it }
        // 带捕获组的规则：label 里的 $1/$2… 换成捕获组。
        val rawKey = key.trim()
        valueLabelPatterns.forEach { rule ->
            val keyOk = rule.keyPattern?.let {
                it.containsMatchIn(rawKey) || it.containsMatchIn(normalizeKey(rawKey))
            } ?: true
            if (!keyOk) return@forEach
            val match = rule.valuePattern.find(trimmed) ?: return@forEach
            return expandLabel(rule.label, match)
        }
        return null
    }

    /** 把 `$1` 换成第一个捕获组。没有对应组就原样保留。 */
    private fun expandLabel(template: String, match: MatchResult): String {
        var out = template
        for (i in match.groupValues.indices.reversed()) {
            if (i == 0) continue
            out = out.replace("$" + i, match.groupValues[i])
        }
        return out
    }

    /**
     * 按**类型**给值取别名：32 位十六进制 → "哈希值"，UUID → "UUID"，
     * 一长串 JSON → "JSON 数据"，超长 Base64 → "加密元数据残留"…
     *
     * 用户要求："如原值为长的字符串，自动根据类型取个别名，
     * 如符合哈希值的就显示哈希值，实在没有的才显示原始字符串"。
     * 别名表在 `tag_rules.json` 的 `valueAliases` 里，顺序敏感。
     */
    fun valueAlias(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        // containsMatchIn 而不是 matches：像 "0x060A2B34" 这种是**前缀**特征，
        // 整串匹配永远不中。带 ^…$ 锚点的规则行为不变。
        return valueAliases.firstOrNull { it.pattern.containsMatchIn(v) }?.label
    }

    /**
     * 界面显示用的值。
     *
     * 优先级（用户指定）：
     *   1. 规则里有**中文译名** → 用译名（臻品母带）
     *   2. 能按**类型**认出来 → 用类型别名（哈希值 / UUID / JSON 数据…）
     *   3. 都没有 → 原样显示，**过长就截断**（界面还会再按宽度省略一次）
     */
    fun displayValue(key: String, value: String): String {
        valueLabel(key, value)?.let { return it }
        valueAlias(value)?.let { return it }
        val raw = value.trim()
        return if (raw.length > MAX_RAW_VALUE) raw.take(MAX_RAW_VALUE) + "…" else raw
    }

    private fun normalizeKey(key: String): String {
        // 容器的键名写法很杂：`TXXX:FOO`、`----:com.apple.iTunes:FOO`、`FOO`。
        // 统一取最后一段，再小写，便于和规则表比对。
        var k = key.trim()
        val lastColon = k.lastIndexOf(':')
        if (lastColon >= 0 && lastColon < k.length - 1) k = k.substring(lastColon + 1)
        return k.trim().lowercase()
    }

    /** 一个标签键最多保留多少种取值显示在详情列表里。 */
    private const val MAX_VALUES = 12

    /**
     * 内置的标准标签键（**代码里的白名单，不是规则文件**）。
     *
     * **范围就是用户点名的那份"基础标签"清单**，一个字都不多：
     *
     *   标题 / 专辑 / 艺术家 / 专辑艺术家 / 作曲 / 作词 / 音轨 / 碟号 / 日期 /
     *   注释 / 版权 / 语言 / 流派 / BPM / ISRC / 歌词 / 封面 / 艺术家图，
     *   外加**指挥**（[com.audiometa.app.domain.usecase.MetaFields] 会刮削并写进文件的
     *   一等字段，不收进来会让软件自己写的标签反过来出现在元信息页）。
     *
     * 其余一切 —— `totaltracks`、`discid`、`replaygain_*`、排序类、`publisher`、
     * `©prd`、`com.android.*`、`upc`…—— 都属于"基础标签之外的信息"，
     * 由「元信息」页照常列出来（用户要求：规则是规则，一般人文件里没那么多私货）。
     *
     * 表里全部是 `normalizeKey` 之后的形式（小写、已去掉 `TXXX:` /
     * `----:com.apple.iTunes:` 这类容器前缀）。同一个字段在不同容器里键名完全不同，
     * 所以要覆盖各种写法：
     *
     * - Vorbis Comment / MP4：`TITLE`、`ALBUMARTIST`、`TRACKNUMBER`…
     * - ID3v2：帧号 `TIT2`、`TPE1`、`TALB`… （jaudiotagger 的 `getFirst(FieldKey)`
     *   在只有帧号的帧上会把帧号当键名返回）
     * - MP4 / Apple 原子：`©nam`、`©gen`、`©lyr`…
     * - 图片帧：`APIC` / `PIC`（ID3）、`METADATA_BLOCK_PICTURE` / `COVERART`
     *   （Vorbis）、`covr`（MP4）
     *
     * 最后那几个**编码函数库**键是特殊的：它们只有**值也对得上**才算标准
     *（见 [isStandardTag] 与 [BUILT_IN_STANDARD_VALUES]）—— 值写 `baimusic`
     * 这种第三方平台名字时要列出来。
     */
    private val BUILT_IN_STANDARD_KEYS: Set<String> = hashSetOf(
        // 标题
        "title", "tit2", "©nam",
        // 专辑
        "album", "talb", "©alb",
        // 艺术家（含多艺术家写法）
        "artist", "artists", "tpe1", "©art",
        // 专辑艺术家
        "albumartist", "album artist", "album_artist", "tpe2", "aart",
        // 作曲
        "composer", "tcom", "©wrt",
        // 作词
        "lyricist", "text",
        // 音轨
        "track", "tracknumber", "trck", "trkn", "trk", "trackno",
        // 碟号
        "disc", "discnumber", "disk", "tpos", "discno",
        // 日期
        "date", "year", "tdrc", "tyer", "tdat", "tyerdat", "©day",
        // 注释
        "comment", "comm", "description", "©cmt",
        // 版权
        "copyright", "tcop", "cprt",
        // 语言
        "language", "tlan", "lang", "©lan",
        // 流派
        "genre", "tcon", "©gen", "gnre",
        // BPM
        "bpm", "tbpm", "tempo", "tmpo",
        // ISRC
        "isrc", "tsrc",
        // 歌词（含本软件自己写进去的那几个翻译字段）
        "lyrics", "lyric", "lyricseng", "unsyncedlyrics", "unsynced lyrics",
        "syncedlyrics", "uslt", "sylt", "©lyr",
        "lyrics_translation", "lyrics_trans", "lyrics_zh", "translatedlyrics",
        // 封面 / 艺术家图（图片帧）
        "cover", "coverart", "apic", "pic", "covr", "metadata_block_picture",
        "artistimage",
        // 指挥（软件自己刮削并写入的字段）
        "conductor", "tpe3",

        // 编码函数库：**连值一起判**（见 BUILT_IN_STANDARD_VALUES）
        "encoder", "encodedby", "encoded_by", "©too", "©enc", "tenc"
    )

    /** 编码函数库那几个键名（各种容器的写法）。 */
    private val ENCODER_LIBRARY_KEYS = listOf(
        "encoder", "encodedby", "encoded_by", "©too", "©enc", "tenc"
    )

    /** 认可的编码器 / 函数库签名（大小写不敏感，见 [isStandardTag]）。 */
    private val STANDARD_ENCODER_VALUES: List<Regex> = listOf(
        "(?i)^\\s*(lavf|lavc|libav|ffmpeg|ffprobe)",
        "(?i)^\\s*(lame|libmp3lame|libflac|reference libflac|flake|libvorbis|libopus|opusenc|libopusenc)",
        "(?i)^\\s*(qaac|fdkaac|nero|neroaacenc|helix|fraunhofer|fhg)",
        "(?i)^\\s*(itunes|apple|x lossless|xld|dbpoweramp|foobar2000|exact audio copy|eac)",
        "(?i)^\\s*(mediainfo|gpac|mp4box|bento4|atomicparsley|taglib|mkvmerge|libebur128|libsoxr|sox|gstreamer)",
        "(?i)^\\s*(sound forge|wavelab|audacity|adobe audition|pro tools|logic pro|cubase|steinberg|reaper|goldwave|samplitude|izotope)"
    ).map { Regex(it) }

    /**
     * 内置的"只有值也标准才算标准标签"的表。
     *
     * 用户要求：编码函数库（MediaInfo 里的 Encoded_Library）这一项要看值 ——
     * `Lavf58.29.100`、`LAME3.100`、`libFLAC 1.3.3` 是标准值，不列；
     * `baimusic` 这种第三方平台塞进文件的名字必须列出来。
     *
     * 规则文件里的 `standardValues` 会**覆盖**这里的同名键，方便以后只改仓库就生效。
     */
    private val BUILT_IN_STANDARD_VALUES: Map<String, List<Regex>> =
        ENCODER_LIBRARY_KEYS.associateWith { STANDARD_ENCODER_VALUES }

    /** 没有译名也没有类型别名时，原始字符串最长显示多少字符，超出截断。 */
    private const val MAX_RAW_VALUE = 48

    /**
     * "值是图片"的标记前缀。
     *
     * 图片不能当字符串存库，所以扫描时把图片落成文件、库里只留
     * `IMG:<sha1>.<ext>`。识别规则属于领域层，这个常量就放在这里，
     * 存储层（MetaImageStore）反过来引用它。
     */
    const val IMAGE_VALUE_PREFIX = "IMG:"

    // ---- 内置兜底：规则文件还没写、但归类已经确定的键 ----

    /** 「编码函数库」这一组的名字（内置兜底和规则文件里的写法保持一致）。 */
    const val ENCODER_GROUP = "编码函数库"

    /** 「回放增益」这一组的名字。 */
    const val REPLAY_GAIN_GROUP = "回放增益"

    /**
     * 内置归到「编码函数库」的键。
     *
     * `vendor` 是 Vorbis/FLAC 注释里的写入工具字符串（`reference libFLAC 1.3.1`），
     * 用户明确要求把它从"未知标签"挪到编码函数库并带上说明。
     */
    private val BUILT_IN_ENCODER_KEYS = setOf(
        "vendor", "encoder", "encodedby", "encoded_by", "encodersettings", "encodingtime",
        "©too", "©enc", "tenc", "tsse", "tool", "writinglibrary", "writing application"
    )

    /** 内置的键说明（规则文件优先，这里只是兜底）。 */
    private val BUILT_IN_KEY_INFO: Map<String, TagKeyInfo> = mapOf(
        "vendor" to TagKeyInfo(
            alias = "编码函数库",
            type = "写入工具签名",
            description = "写入这个文件的编码器/函数库厂商字符串，例如 " +
                "`reference libFLAC 1.3.1 20141125`。同一个文件里出现多个不同的 " +
                "VENDOR，说明它被别的工具重新封装过。"
        ),
        "tool" to TagKeyInfo(
            alias = "编码函数库",
            type = "写入工具签名",
            description = "写入文件用的工具名（MP4 里的 ©too 原子是同类信息）。"
        ),
        "writinglibrary" to TagKeyInfo(
            alias = "编码函数库",
            type = "写入工具签名",
            description = "写入文件用的函数库及版本。"
        )
    )
}

/** 一种未知标签在曲库里的汇总。 */
data class TagSummary(
    /** 界面上显示的键名（已去掉容器前缀）。 */
    val key: String,
    val count: Int,
    val platform: String? = null,
    val tool: String? = null,
    val hints: List<String> = emptyList(),
    val sampleValue: String = "",
    /** 这个标签在曲库里出现过的不同取值（已按中文译名显示，最多 12 个）。 */
    val values: List<String> = emptyList(),
    /** 去重后的取值总数，可能大于 [values] 的长度。 */
    val valueCount: Int = 0,
    /** 这个标签键大概是什么。规则里没写时为 null。 */
    val keyInfo: TagKeyInfo? = null,
    /** 容器里的原始键名（`TXXX:major_brand`、`----:com.apple.iTunes:voice-memo-uuid`）。 */
    val rawKeys: List<String> = emptyList(),
    /** 值是不是图片。 */
    val isImage: Boolean = false
) {
    /**
     * 归属的"来源"分组名。
     *
     * 图片类标签**一律归到「未知图片」那一组**（用户要求：除封面和艺术家图之外的图
     * 集中成一组，同一首歌的其它标签照旧归各自来源）。
     * 其余：平台优先，其次转码工具，都没有才算"不在规则里"的未知标签。
     *
     * **一份规则都没有时按键名分组**（用户要求）：这时没有任何平台/工具信息，
     * 把几十种自定义键全塞进一张「未知标签」卡里看不出东西来；
     * 按键名分开，每种键各自成一张卡，规则只负责给它们加别名和说明。
     */
    val group: String
        get() = when {
            isImage -> TagRules.imageGroup()
            platform != null -> platform
            tool != null -> tool
            // 规则文件还没覆盖到的键：内置兜底（VENDOR → 编码函数库、REPLAYGAIN_* → 回放增益）。
            else -> TagRules.builtInGroup(key) ?: if (!TagRules.isImported) {
                key.ifBlank { UNKNOWN_GROUP }
            } else {
                UNKNOWN_GROUP
            }
        }

    companion object {
        /** 「未知标签」那一组的名字。 */
        const val UNKNOWN_GROUP = "未知标签"

        /** 图片组的默认名字；实际以规则文件里 `kind = "image"` 那一组为准。 */
        const val IMAGE_GROUP = "未知图片"
    }
}

// ---- JSON 小工具 ----

private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        optJSONObject(i)?.let(action)
    }
}

private fun JSONArray?.toStringListLower(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        optString(i).trim().lowercase().takeIf { it.isNotBlank() }?.let { out += it }
    }
    return out
}

private fun JSONArray?.toRegexList(): List<Regex> {
    if (this == null) return emptyList()
    val out = mutableListOf<Regex>()
    for (i in 0 until length()) {
        optString(i).toRegexSafe()?.let { out += it }
    }
    return out
}

private fun String.toRegexSafe(): Regex? =
    takeIf { it.isNotBlank() }?.let { pattern -> runCatching { Regex(pattern) }.getOrNull() }
