package com.audiometa.app.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频库和刮削页之间的选中项传递。
 *
 * 之前 SELECTED 范围取的是 scrapeState == 0，跟音频库里勾了什么毫无关系，
 * 用户勾半天没用。这里用一个进程内的状态让两边真正对齐。
 */
class SelectionStore {
    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    fun set(ids: Set<Long>) {
        _ids.value = ids
    }

    fun toggle(id: Long) {
        _ids.value = _ids.value.toMutableSet().apply { if (!add(id)) remove(id) }
    }

    fun addAll(ids: Collection<Long>) {
        _ids.value = _ids.value + ids
    }

    fun clear() {
        _ids.value = emptySet()
    }

    /** 曲库变化后清掉已经不存在的曲目，避免把过期 id 传给服务。 */
    fun retainExisting(existing: Set<Long>) {
        val kept = _ids.value.intersect(existing)
        if (kept.size != _ids.value.size) _ids.value = kept
    }
}
