package com.audiometa.app.ui.screen.library

/**
 * 元信息页组内的**分层**规则（纯函数，单测直接喂数据）。
 *
 * 用户规则（三轮下来定型）：
 * 1. 组内列表分两级：**同一个变量（键）命中 ≥ 10 首**、或这个组声明的"按键盘出列表"
 *    （回放增益就是这种）→ 合并成一项；否则**同一个键 + 同一个值命中 ≥ 10 首**
 *    也算一项。点进去就是下一级列表。
 * 2. 一首歌内含多个标签时，可以**同时出现在下级列表和本级列表**。
 * 3. 一首歌可以出现在**多个组**里（组之间不互斥）。
 * 4. 下级列表里再按同样的规则分一次：变量项点进去看到的是它的各个取值，
 *    取值项点进去看到的是曲目 —— 也就是"一个变量 ≥10 首就多出一层三级列表"。
 */
object MetaBucketing {

    /** 一个标签取值：原始键（小写比较）+ 原始值。 */
    data class Key(val rawKey: String, val rawValue: String)

    /** 这一项合并的是"变量"（键）/"取值"（键 + 值）/"家族"（值前缀，如 Lavf）/ "专辑列表"。 */
    enum class Kind { VARIABLE, VALUE, FAMILY, ALBUM }

    data class Node(
        val kind: Kind,
        val rawKey: String,
        val rawValue: String,
        /** 命中这一项的曲目下标（对应输入顺序）。 */
        val tracks: List<Int>
    )

    /**
     * @param entries 本层的合并项（变量项和取值项不会同时覆盖同一个键）。
     * @param standalone 本层单独占一行的曲目下标。
     */
    data class Plan(
        val entries: List<Node>,
        val standalone: List<Int>
    )

    /**
     * @param valuesPerTrack 每首歌在本层范围内的取值。
     * @param keyLists 这些键**无条件**按变量拆出下级列表（回放增益的三个键）。
     * @param insideKey true 表示**已经在某个键的页面里**：这一层只按取值分，
     *   **绝不能再生成变量项** —— 否则同一个键会一层层重复出现，
     *   用户点「编码软件」进去还是「编码软件」，无限套娃。
     * @param min 合并阈值，默认 [BUCKET_MIN_TRACKS]（10 首）。
     */
    fun plan(
        valuesPerTrack: List<List<Key>>,
        keyLists: Set<String> = emptySet(),
        insideKey: Boolean = false,
        /** 值的"家族"正则（规则里 encoded-library 的 familyPatterns）。 */
        families: List<Regex> = emptyList(),
        min: Int = BUCKET_MIN_TRACKS
    ): Plan {
        // 同一首歌同一个取值只算一次（重复行不能把阈值顶穿）。
        val perKey = linkedMapOf<String, MutableList<Int>>()
        val perValue = linkedMapOf<Pair<String, String>, MutableList<Int>>()
        valuesPerTrack.forEachIndexed { index, keys ->
            keys.distinct().forEach { key ->
                perKey.getOrPut(key.rawKey) { mutableListOf() }.let { if (index !in it) it += index }
                perValue.getOrPut(key.rawKey to key.rawValue) { mutableListOf() }
                    .let { if (index !in it) it += index }
            }
        }

        val entries = mutableListOf<Node>()

        /*
         * 家族合并（编码函数库专用规则）：值后面的版本号是变量 ——
         * `Lavf57.25.100`、`Lavf58.76.100`、`reference libFLAC 1.3.1` …
         * 单个值往往凑不到 10 首，但"同一家族"合计够 10 首就合并成一项，
         * 点进去直接是曲目（用户明确：不必再开三级列表）。
         */
        val familyLabels = mutableMapOf<Pair<String, String>, String>()
        if (families.isNotEmpty()) {
            perValue.keys.forEach { key ->
                families.firstOrNull { it.containsMatchIn(key.second) }?.let { regex ->
                    familyLabels[key] = regex.pattern.trimStart('^').trim()
                }
            }
        }
        val perFamily = linkedMapOf<String, MutableList<Int>>()
        perValue.forEach { (key, tracks) ->
            val label = familyLabels[key] ?: return@forEach
            val bucket = perFamily.getOrPut(label) { mutableListOf() }
            tracks.forEach { if (it !in bucket) bucket += it }
        }
        perFamily.filterValues { it.size >= min }.forEach { (label, tracks) ->
            entries += Node(kind = Kind.FAMILY, rawKey = "", rawValue = label, tracks = tracks.toList())
        }
        /*
         * **先看取值，再看变量** —— 顺序很关键。
         *
         * 一个取值命中 ≥10 首时，它的键**必然**也命中 ≥10 首，所以"变量优先"
         * 会把取值级永远吃掉：同人音乐那种"例大祭 SP 有 30 首"就再也看不到值了。
         * 反过来先取值：值够 10 首就按值合并（用户第一轮的要求）；
         * 值都不够、但变量整体够 10 首（VENDOR 有 12 首、每首的值都不一样）才按变量合并
         * （用户这一轮的要求）。同一个键不会同时出现两种项，本级不会重复列同一批歌。
         */
        val coveredFamilies = perFamily.filterValues { it.size >= min }.keys
        val valueEntries = perValue.filter { (key, tracks) ->
            // 家族项已经收走的取值不再单独出项。
            key.first !in keyLists && familyLabels[key] !in coveredFamilies && tracks.size >= min
        }
        val variableKeys = if (insideKey) {
            // 已经在某个键的页面里：这一层只有取值项和曲目，
            // 再生成变量项就会"点进去还是它自己"。
            emptySet()
        } else {
            perKey.filter { (key, tracks) ->
                // 按键盘出列表的组（回放增益）无条件；其余只在"值都不够"时才按变量合并。
                // 已经被家族项收走的键也不再单独出变量项。
                key in keyLists || (
                    tracks.size >= min &&
                        valueEntries.keys.none { it.first == key } &&
                        perValue.keys.none { it.first == key && it in familyLabels }
                    )
            }.keys
        }

        variableKeys.forEach { key ->
            entries += Node(
                kind = Kind.VARIABLE,
                rawKey = key,
                rawValue = "",
                tracks = perKey.getValue(key).toList()
            )
        }
        valueEntries.forEach { (key, tracks) ->
            entries += Node(
                kind = Kind.VALUE,
                rawKey = key.first,
                rawValue = key.second,
                tracks = tracks.toList()
            )
        }

        val coveredByValue = valueEntries.keys
        val standalone = valuesPerTrack.indices.filter { index ->
            val keys = valuesPerTrack[index]
            // 没有任何取值的曲目（只有键、没有值，或者只有图片）也必须留在一级列表，
            // 否则它在任何合并项里都找不到，等于从页面上消失。
            keys.isEmpty() || keys.any { key ->
                // perValue / familyLabels 的键是 (原始键, 原始值) 这个 Pair，
                // 逐曲目的取值是 Key —— 这里显式拼成 Pair 再查，别混用。
                val pair = key.rawKey to key.rawValue
                val familyCovered = familyLabels[pair]?.let { label -> label in coveredFamilies } == true
                key.rawKey !in variableKeys && pair !in coveredByValue && !familyCovered
            }
        }
        return Plan(entries = entries, standalone = standalone)
    }
}
