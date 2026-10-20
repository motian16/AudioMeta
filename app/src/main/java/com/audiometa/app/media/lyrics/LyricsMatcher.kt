package com.audiometa.app.media.lyrics

import com.audiometa.app.domain.model.LyricsItem
import com.audiometa.app.domain.model.lyricsTypeLabel

/**
 * 按优先级挑一条原文歌词，翻译作为附加项。
 *
 * 优先级里只有逐字和逐行（翻译不再是候选项，而是独立开关）。
 */
class LyricsMatcher {

    fun select(
        items: List<LyricsItem>,
        priority: List<String>,
        wantTranslation: Boolean
    ): List<LyricsItem> {
        if (items.isEmpty()) return emptyList()
        val originals = items.filter { !it.translated }
        val translations = items.filter { it.translated }

        if (originals.isEmpty()) {
            // 只有翻译、没有原文时也写进去，总比什么都没有强。
            return if (wantTranslation) listOfNotNull(translations.firstOrNull()) else emptyList()
        }

        for (key in priority) {
            val primary = originals.firstOrNull { it.type == key } ?: continue
            val translation = if (wantTranslation) pickTranslation(translations, key) else null
            return listOfNotNull(primary, translation)
        }

        val fallback = originals.first()
        val translation = if (wantTranslation) pickTranslation(translations, fallback.type) else null
        return listOfNotNull(fallback, translation)
    }

    /** 兜底歌词：取指定类型的那一条，优先原文。 */
    fun pickByType(items: List<LyricsItem>, type: String): LyricsItem? =
        items.firstOrNull { it.type == type && !it.translated }
            ?: items.firstOrNull { it.type == type }

    private fun pickTranslation(translations: List<LyricsItem>, type: String): LyricsItem? =
        translations.firstOrNull { it.type == type } ?: translations.firstOrNull()

    fun label(key: String): String = lyricsTypeLabel(key)
}
