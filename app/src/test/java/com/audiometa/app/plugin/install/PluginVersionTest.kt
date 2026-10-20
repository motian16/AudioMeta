package com.audiometa.app.plugin.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件唯一性的版本比较。
 *
 * 同版本 → 覆盖安装（比 0）；更高版本 → 升级（比 < 0）；更低版本 → 跳过（比 > 0）。
 */
class PluginVersionTest {

    @Test
    fun `同版本判定为覆盖安装`() {
        assertEquals(0, compareVersions("0.4.2", "0.4.2"))
        // 末尾的零不影响判定，"1.0" 与 "1.0.0" 是同一版本。
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(0, compareVersions("1.0.0", "1.0"))
    }

    @Test
    fun `更高版本判定为升级`() {
        assertTrue(compareVersions("0.4.1", "0.4.2") < 0)
        assertTrue(compareVersions("0.4.2", "0.5.0") < 0)
        assertTrue(compareVersions("1.9.9", "1.10.0") < 0)
        assertTrue(compareVersions("1.0", "1.0.1") < 0)
    }

    @Test
    fun `更低版本判定为跳过`() {
        assertTrue(compareVersions("0.4.2", "0.4.1") > 0)
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun `带后缀的版本号按主版本比较`() {
        assertEquals(0, compareVersions("1.2.3", "1.2.3-beta"))
        assertTrue(compareVersions("1.2.3-beta", "1.2.4") < 0)
    }

    @Test
    fun `不是数字串时退回字符串比较`() {
        // 解析不出来时不能崩，只能退化成字符串比较。
        val result = compareVersions("abc", "abd")
        assertTrue(result < 0)
        assertEquals(0, compareVersions("abc", "abc"))
    }
}
