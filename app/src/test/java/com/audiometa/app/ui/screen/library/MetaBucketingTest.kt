package com.audiometa.app.ui.screen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组内分层规则。
 *
 * 用户要求（三轮定型）：
 * - 同一个**变量（键）** ≥10 首 → 合并成一项，点进去看到它的取值（多一层，"三级列表"）；
 * - 否则同一个**键 + 值** ≥10 首 → 合并成一项；
 * - **不足 10 首不单独建下级列表**；
 * - 回放增益这种组**按键盘出列表**（三个二级列表），不受 10 首限制；
 * - 一首歌带多个标签时，可以同时出现在下级列表和本级列表。
 */
class MetaBucketingTest {

    private fun key(name: String, value: String) = MetaBucketing.Key(name, value)

    @Test
    fun `变量超过10首合并为一项并给出下一层`() {
        // VENDOR 有 12 首，取值各不相同 —— 按变量合并，点进去是它的取值/曲目。
        val tracks = (0 until 12).map { listOf(key("vendor", "reference libFLAC 1.$it")) }
        val plan = MetaBucketing.plan(tracks)
        assertEquals(1, plan.entries.size)
        assertEquals(MetaBucketing.Kind.VARIABLE, plan.entries.first().kind)
        assertEquals("vendor", plan.entries.first().rawKey)
        assertEquals(12, plan.entries.first().tracks.size)
        assertTrue("全被变量项收走了，本级不再单独列歌", plan.standalone.isEmpty())
    }

    @Test
    fun `值够10首就按值合并 变量不重复出现`() {
        // event 有两种取值，每种 10 首：应该出两个"取值项"，
        // 而不是一个把两种值混在一起的"变量项"（用户第一轮的要求）。
        val tracks = (0 until 10).map { listOf(key("event", "C75")) } +
            (0 until 10).map { listOf(key("event", "例大祭 SP")) }
        val plan = MetaBucketing.plan(tracks)
        assertEquals(2, plan.entries.size)
        assertTrue("全是取值项：" + plan.entries.map { it.kind }, plan.entries.all { it.kind == MetaBucketing.Kind.VALUE })
        assertEquals(setOf("C75", "例大祭 SP"), plan.entries.map { it.rawValue }.toSet())
        assertTrue(plan.standalone.isEmpty())
    }

    @Test
    fun `不足10首不建下级列表`() {
        // 9 首同值：既没有变量项，也没有取值项，全部单独列出。
        val plan = MetaBucketing.plan((0 until 9).map { listOf(key("event", "C75")) })
        assertTrue(plan.entries.isEmpty())
        assertEquals(9, plan.standalone.size)
    }

    @Test
    fun `回放增益按键盘出列表 不受10首限制`() {
        val gains = listOf(
            "replaygain_reference_loudness", "replaygain_track_gain", "replaygain_track_peak"
        )
        // 每首歌三个键各一个值，一共只有 3 首 —— 远不到 10 首。
        val tracks = (0 until 3).map { index ->
            gains.map { name -> key(name, "值$index") }
        }
        val plan = MetaBucketing.plan(tracks, keyLists = gains.toSet())
        assertEquals("三个二级列表", 3, plan.entries.size)
        assertTrue(plan.entries.all { it.kind == MetaBucketing.Kind.VARIABLE })
        assertTrue(plan.entries.all { it.tracks.size == 3 })
        assertTrue(plan.standalone.isEmpty())
    }

    @Test
    fun `一首歌带多个标签时既进下级列表又留在本级`() {
        // 10 首 event=例大祭 SP，其中第 10 首还带 mood=电子 → 它要同时出现在两处。
        val tracks: List<List<MetaBucketing.Key>> = Array(10) { index ->
            if (index == 9) listOf(key("event", "例大祭 SP"), key("mood", "电子"))
            else listOf(key("event", "例大祭 SP"))
        }.toList()
        val plan = MetaBucketing.plan(tracks)
        // event 命中 10 首 → 变量项；mood 只有 1 首 → 不合并。
        assertEquals(1, plan.entries.size)
        assertEquals(listOf(9), plan.standalone)
    }

    @Test
    fun `没有取值的曲目一定留在本级`() {
        val plan = MetaBucketing.plan(
            listOf(emptyList<MetaBucketing.Key>()) + (0 until 10).map { listOf(key("event", "C75")) }
        )
        assertTrue(0 in plan.standalone)
        assertFalse(plan.entries.any { 0 in it.tracks })
    }

    @Test
    fun `同一首歌同一个值重复出现只算一次`() {
        val plan = MetaBucketing.plan(listOf(listOf(key("event", "C75"), key("event", "C75"))))
        assertTrue("一首歌重复两次不该凑成阈值", plan.entries.isEmpty())
    }

    @Test
    fun `已经在键的页面里就绝不再生成变量项（不许无限套娃）`() {
        // 用户报的 bug：编码函数库 →「编码软件」点进去还是「编码软件」，一层层套下去。
        // 12 首都带 encoder，值各不相同。
        val tracks = (0 until 12).map { listOf(key("encoder", "软件$it")) }
        val outer = MetaBucketing.plan(tracks)
        assertEquals(MetaBucketing.Kind.VARIABLE, outer.entries.single().kind)

        // 进到 encoder 这一层：只按值分，不能再出现 encoder 这个变量项。
        val inner = MetaBucketing.plan(tracks, insideKey = true)
        assertTrue("这一层不许再有变量项", inner.entries.none { it.kind == MetaBucketing.Kind.VARIABLE })
        // 值都只命中 1 首 → 也不合并，直接列曲目。
        assertTrue(inner.entries.isEmpty())
        assertEquals("12 首全部单独列出", 12, inner.standalone.size)
    }

    @Test
    fun `家族合并：单个值不够10首但一家够10首`() {
        /*
         * 用户点名：VENDOR 的值后面是变量（Lavf57.25.100 / Lavf58.76.100）。
         * 每种都不到 10 首，但 Lavf 一家合计超过 10 首 → 合并成一项，
         * 点进去直接是曲目（不必再开三级列表）。
         */
        val families = listOf(Regex("^Lavf"), Regex("^reference libFLAC"))
        val tracks: List<List<MetaBucketing.Key>> = buildList {
            repeat(6) { add(listOf(key("vendor", "Lavf57.25.100"))) }
            repeat(6) { add(listOf(key("vendor", "Lavf58.76.100"))) }
            repeat(3) { add(listOf(key("encoder", "reference libFLAC 1.3.1 20141125"))) }
        }
        val plan = MetaBucketing.plan(tracks, families = families)
        val familiesOnly = plan.entries.filter { it.kind == MetaBucketing.Kind.FAMILY }
        assertEquals("Lavf 一家 12 首要合并", 1, familiesOnly.size)
        assertEquals("Lavf", familiesOnly.single().rawValue)
        assertEquals(12, familiesOnly.single().tracks.size)
        // reference libFLAC 只有 3 首 → 不合并。
        assertTrue(plan.entries.none { it.kind == MetaBucketing.Kind.FAMILY && it.rawValue.startsWith("reference") })
        // 家族项命中的曲目不再单独占一行（它们已经在下级列表里）。
        assertTrue("被 Lavf 收走的 12 首不该再单独列出", plan.standalone.none { it < 12 })
    }

    @Test
    fun `没有家族规则时行为不变`() {
        val tracks = (0 until 6).map { listOf(key("vendor", "Lavf57.25.100")) }
        val plan = MetaBucketing.plan(tracks)
        assertTrue("没给 familyPatterns 就不该凭空合并", plan.entries.isEmpty())
        assertEquals(6, plan.standalone.size)
    }

    @Test
    fun `键页面里值够10首仍然按值合并`() {
        // VENDOR 的同一个值命中 ≥10 首：在组页面就该合并成一项（用户要求"合并为二级列表"），
        // 进到键页面之后则是取值项。
        val tracks = (0 until 12).map { listOf(key("vendor", "reference libFLAC 1.3.1 20141125")) }
        val groupLevel = MetaBucketing.plan(tracks)
        assertEquals(1, groupLevel.entries.size)
        assertEquals(MetaBucketing.Kind.VALUE, groupLevel.entries.single().kind)
        assertEquals("reference libFLAC 1.3.1 20141125", groupLevel.entries.single().rawValue)

        // 键页面（insideKey）：同一个值够 10 首 → 出一个取值项，点进去就是曲目（四级到底）。
        val keyLevel = MetaBucketing.plan(tracks, insideKey = true)
        assertEquals(1, keyLevel.entries.size)
        assertEquals(MetaBucketing.Kind.VALUE, keyLevel.entries.single().kind)
        assertEquals(12, keyLevel.entries.single().tracks.size)
    }
}
