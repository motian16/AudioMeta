package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.plugin.model.PluginCapability

/**
 * 设置页。
 *
 * 只有两层：主页（[SettingsSection.roots]）+ 点进去的详情页。
 * 顶部不再有大标题，也不放搜索栏（用户要求），内容整体上移。
 * 返回层级：缓存详情 / 子文件夹页 → 设置详情 → 设置主页 → 上一次看的 Tab → 桌面。
 *
 * 所有页面的排版都用 [SettingsKit] 里那几个积木搭，不再各写各的。
 */
@Composable
fun SettingsScreen(
    resetTicket: Int = 0,
    backSignal: Int = 0,
    onBackHandled: () -> Unit = {},
    onNeedParent: () -> Boolean = { false },
    /**
     * 进去时直接打开哪一页。null = 设置主页。
     *
     * **在首次组合期间就用它设好 section**，所以不存在"先渲染主页再切过去"
     * 的中间帧 —— 这正是之前切 Tab 时闪一下音乐库页的原因。
     * 消费一次就失效，之后由用户自己在页面里导航。
     */
    initialSection: SettingsSection? = null,
    /**
     * 共享的 ViewModel。
     *
     * **由 MainActivity 创建并传进来**，不再各页各自 `viewModel()`：
     * 「控制」页要跳进「设置-网络遥控」，靠的就是直接调这个 VM 的
     * `openRemoteSettings()`。两个实例的话跳转传不过去。
     */
    vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by vm.state.collectAsState()
    val settings = state.settings
    /*
     * section **直接订阅 VM 里的同步流**，不读 state.section。
     *
     * state 是 combine + stateIn 的结果：openSectionNow 同步改了
     * vm.section 之后，combine 要等一次协程调度才会把新值发出来。
     * 从「控制」页跳进来时，首帧渲染赶在这次调度前面 —— 读到的还是
     * 上次停留的页面（比如音乐库页），于是"一闪而逝"显示旧页再跳走。
     * vm.section 是 MutableStateFlow，写入即生效，首帧就是目标页。
     *
     * 用 collectAsState().value 存进**局部 val**（而不是 by 委托）：
     * 委托属性没有稳定引用，后面的 `section.parent` 无法智能转换成非空。
     */
    val section = vm.section.collectAsState().value
    val page = vm.cachePage

    /*
     * 首帧就渲染目标页。
     *
     * 必须是"组合期间同步执行"（remember 而不是 LaunchedEffect）——
     * LaunchedEffect 在组合完成**之后**才跑，那一帧界面已经用旧的
     * section 渲染过了，"闪一下再切过去"就是这么来的。
     */
    val hasInitialSection = initialSection != null
    remember(initialSection) {
        if (initialSection != null) vm.openSectionNow(initialSection)
        initialSection
    }

    /*
     * 「回设置主页」的复位**不在这里**。
     *
     * 原来这里挂了一个 `LaunchedEffect(resetTicket)`，它是"闪一下旧页再跳"
     * 的元凶：这个 effect 在**每次设置页重新组合时都会首次执行**，
     * 而 resetTicket 是个跨 Tab 保留的计数 —— 从控制页跳进来时它一旦与
     * 本地记录不一致，就会把刚设好的目标页 openSection(null) 打回主页。
     *
     * 现在这条路径全部由 `MainActivity.switchTab` **同步**处理（见那里的注释），
     * 不再有任何"组合之后再纠正"的异步步骤，所以不存在中间帧。
     */

    // 进了缓存页就顺手把统计算出来（原来挂在这个 effect 上的另一半职责）。
    LaunchedEffect(page) {
        if (page != null) vm.loadCacheStats()
    }

    var handled by rememberSaveable { mutableStateOf(backSignal) }
    LaunchedEffect(backSignal) {
        if (backSignal == handled) return@LaunchedEffect
        handled = backSignal
        when {
            page != null -> vm.closeCachePage()
            section == SettingsSection.SUBFOLDERS -> vm.backFromSubfolders()
            // 二级页返回**它的上级**：插件清单回插件页、音频格式回音乐库，
            // 而不是一步跳回设置主页（上级为 null 的那些仍然是回主页）。
            section != null -> vm.openSection(section.parent)
            else -> if (!onNeedParent()) return@LaunchedEffect
        }
        onBackHandled()
    }

    /*
     * 离开设置页时**只清缓存页/子文件夹页**，不动 section。
     *
     * section 的复位交给 `MainActivity.switchTab` 同步做 —— 它必须发生在
     * "切走"那一刻，而不是这里。原因：
     *   - 在 onDispose 里复位是无条件的，会把「控制」页刚设好的 pending
     *     目标页一起清掉，跳转就失效了（这正是上一版"点了停在主页"的原因）；
     *   - 在这里复位也来不及：切回来时本页重新组合，第一次读到的是旧值，
     *     于是先渲染旧页再跳 —— 也就是用户看到的那一闪。
     */
    DisposableEffect(Unit) {
        onDispose {
            vm.closeCachePage()
            vm.backFromSubfolders()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page != null -> CacheDetailPage(vm)
                section == null -> SectionList(vm) { vm.openSection(it) }
                else -> SectionContent(section, settings, state.plugins, state.folders, vm)
            }
        }
    }
}

/** 设置主页。每项一张卡片：标题 + 一句说明 + 箭头（左侧不放图标）。 */
@Composable
private fun SectionList(vm: SettingsViewModel, onOpen: (SettingsSection) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
    ) {
        items(SettingsSection.roots, key = { it.name }) { section ->
            SettingsGroupCard(
                title = section.title,
                summary = section.subtitle,
                onClick = { onOpen(section) }
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    /*
     * 左侧**不放图标**。
     *
     * 原来每一项前面有个图标占位，看着像"没加载出来的图片"，而且每项图标
     * 都不同、颜色又都是主题色，视觉上很吵。去掉之后文字靠左对齐，
     * 卡片本身也窄了一圈、更像一份清单。
     *
     * 保留右侧箭头：它是"这一行可以点进去"的唯一提示，去掉会让人不知道能点。
     */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SectionContent(
    section: SettingsSection,
    settings: AppSettings,
    plugins: List<PluginRecordEntity>,
    folders: List<FolderEntity>,
    vm: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 24.dp)
    ) {
        when (section) {
            SettingsSection.APPEARANCE -> AppearanceSection(settings, vm)
            SettingsSection.LIBRARY,
            SettingsSection.DIRECTORIES,
            SettingsSection.EXCLUDE -> LibrarySection(settings, vm, folders)
            SettingsSection.SUBFOLDERS -> SubfoldersPage(settings, folders, vm)
            SettingsSection.FORMATS -> FormatsSection(settings, vm)
            // 「性能」已并入刮削页（用户要求）：刮削配置之后直接接性能分组，
            // 不再在设置主页单列一项。PERFORMANCE 仍可被搜索命中，单独渲染性能内容。
            SettingsSection.SCRAPE -> {
                ScrapeConfigSection(settings, plugins, vm)
                PerformanceSection(settings, vm)
            }
            SettingsSection.PLUGINS -> PluginRepoSection(plugins, settings, vm)
            SettingsSection.PLUGIN_META ->
                PluginGroupPage("元信息源", PluginCapability.SEARCH_SONGS, plugins, vm)
            SettingsSection.PLUGIN_ARTIST_IMAGE ->
                PluginGroupPage("艺术家图源", PluginCapability.SEARCH_ARTIST_IMAGES, plugins, vm)
            SettingsSection.TAG_RULES -> TagRulePage()
            // 网络遥控：设置项独立一项，播控界面在底部「控制」标签里。
            SettingsSection.REMOTE -> RemoteSettingsSection(settings, vm)
            // 局域网设备与可用地址列表（从「网络遥控」点设备进来）。
            SettingsSection.DEVICES -> RemoteDevicesSection(settings, vm)
            SettingsSection.WRITE -> WriteSection(settings, vm)
            SettingsSection.PERFORMANCE -> PerformanceSection(settings, vm)
            SettingsSection.DEBUG -> DebugSection(settings, vm)
            SettingsSection.ABOUT -> AboutSection(vm)
        }
    }
}
