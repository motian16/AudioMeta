package com.audiometa.app.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.plugin.model.PluginCapability
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- 插件仓库 ----------------

/**
 * 插件页。
 *
 * 用户要求的形态：
 * - 顶部是**插件仓库**一行：整行可点 = 填订阅地址，右边只留刷新；
 *   行里**不再有第二行小字**，标题显示 index.json 里写的仓库名（例如「渊の插件源」），
 *   还没拉到过就显示地址；
 * - 点刷新先检查版本号，**有新版本才下载**；同一个 index.json 里 `rules` 指向的
 *   标签识别规则也会在这一步一起更新（见 [com.audiometa.app.plugin.install.PluginRepoUpdater]）；
 * - 下面是两个入口：「元信息源」和「艺术家图源」，各自进去是一页插件清单；
 * - 清单每行是插件自己的图标 + 名字 + id + 版本 + 一个只更新这一个源的按钮
 *   （确认最新之后显示「最新」并置灰），行与行之间用看得清的分隔线；
 * - 「手动安装」下面跟着**网络与镜像**一组（用户要求从「刮削」页搬到这里）；
 * - 读仓库、下载失败这类反馈放在卡片下面的小字里（行里已经没有位置写了）。
 */
@Composable
internal fun PluginRepoSection(
    plugins: List<PluginRecordEntity>,
    settings: AppSettings,
    vm: SettingsViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    /*
     * 订阅规则变化信号：订阅更新把规则装好之后这一行**立即**刷新，
     * 不再是"依旧是未安装，重进时才变"。
     * TagRules.isImported 是普通属性，界面读它不建立订阅 ——
     * collect 这个 stamp 建立依赖，值一变就重组。
     */
    val rulesStamp by com.audiometa.app.domain.tagrules.TagRules.rulesStamp.collectAsState()
    val rulesInstalled = rulesStamp >= 0 && com.audiometa.app.domain.tagrules.TagRules.isImported

    // 顺序能力都要读安装目录里的 manifest.json，所以放到 IO 线程做一次。
    androidx.compose.runtime.LaunchedEffect(plugins.map { it.pluginId + "@" + it.pluginVersion }) {
        vm.loadPluginInfo(plugins)
    }

    SettingsGroup("插件仓库") {
        SettingsRow(
            title = repoTitle(settings, vm),
            icon = Icons.Outlined.Extension,
            // 整行都能点：原来只有右侧那支铅笔能点，用户得先找到它。
            onClick = { vm.editRepoDialog = true }
        ) {
            IconButton(
                onClick = { vm.checkRepoUpdates(context) },
                enabled = vm.repoUrl.isNotBlank() && !vm.repoBusy
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "更新订阅")
            }
        }
    }

    // 行里不显示任何小字之后，"正在检查更新 / 已经是最新版本 / 下载失败原因"
    // 只剩这一处能说，所以只要不是空的就显示。
    if (vm.repoStatus.isNotBlank()) SettingsNote(vm.repoStatus)

    // 删除单个源的确认框：删安装目录和记录不可撤销，必须先问一次。
    vm.pendingUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { vm.pendingUninstall = null },
            title = { Text("删除 " + plugin.pluginName.ifBlank { plugin.pluginId }) },
            text = {
                Text(
                    "会删除这个源的安装目录和插件记录（" + plugin.pluginId + "）。\n" +
                        "已经写进音频文件的标签不会回滚。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.uninstallPlugin(plugin.pluginId) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { vm.pendingUninstall = null }) { Text("取消") }
            }
        )
    }

    SettingsGroup("插件列表") {
        PluginGroupEntry("元信息源", SettingsSection.PLUGIN_META, PluginCapability.SEARCH_SONGS, plugins, vm)
        SettingsStrongDivider()
        PluginGroupEntry(
            "艺术家图源",
            SettingsSection.PLUGIN_ARTIST_IMAGE,
            PluginCapability.SEARCH_ARTIST_IMAGES,
            plugins,
            vm
        )
        SettingsStrongDivider()
        // 标签识别规则：点进去是这份规则的人话版（用户要求，插件列表里给它一个入口）。
        // 右侧显示规则版本，和上面两行的"x 个"是同一种写法。
        //
        // 注意：「网络遥控」**不在这里** —— 它是设置主页的独立一项
        // （见 SettingsSection.REMOTE），因为它连的是另一台电脑上的播放器，
        // 和这里的标签数据源插件不是一回事。
        SettingsRow(
            title = "标签规则",
            onClick = { vm.openSection(SettingsSection.TAG_RULES) }
        ) {
            Text(
                if (rulesInstalled) {
                    com.audiometa.app.domain.tagrules.TagRules.ruleInfo.version.toString() + " 版"
                } else {
                    "未安装"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (vm.editRepoDialog) {
        // 用当前地址当 key：改完保存再打开时，输入框里是新的地址而不是上一次那份。
        var input by rememberSaveable(vm.repoUrl) { mutableStateOf(vm.repoUrl) }
        AlertDialog(
            onDismissRequest = { vm.editRepoDialog = false },
            title = { Text("插件仓库地址") },
            text = {
                SettingsTextField(
                    label = "地址",
                    value = input,
                    onValueChange = { input = it }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.saveRepoUrl(input) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { vm.editRepoDialog = false }) { Text("取消") }
            }
        )
    }

    SettingsGroup("手动安装") {
        ManualInstallRow(vm)
    }

    // 「网络与镜像」按用户要求从「设置 - 刮削」搬到这一页，就在「手动安装」下面：
    // 它管的是"下载走哪条路"，和插件仓库是同一件事。
    MirrorListGroup(settings, vm)
}

/**
 * 「手动安装」一行：点下去**先调 MT 管理器（bin.mt.plus）的文件选取页**，
 * 调不起来（没装 MT，或系统不让解析到它）再退回系统文件选择器。
 *
 * MT 没有公开的第三方选取 API，所以这里用的是最通用的一招：
 * 把 `ACTION_GET_CONTENT` 限定到它的包名，`resolveActivity` 能解析到
 * 就说明这台机器上的 MT 能接这个 intent —— 接得住就由它来选，
 * 接不住（或没装）就走系统的 `OpenDocument`。两条路最后拿到的都是
 * 同一个 content:// Uri，交给 [SettingsViewModel.importLocalPlugin] 安装。
 */
@Composable
private fun ManualInstallRow(vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val zipMimes = remember {
        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
    }

    // 两个 launcher 拿到 Uri 之后走的是同一条安装路径；取消（Uri 为 null）就不动。
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocalPlugin(uri)
    }
    val mtPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
        if (uri != null) vm.importLocalPlugin(uri)
    }

    SettingsRow(
        title = "从本地导入插件包",
        summary = "优先用 MT 管理器选 zip，支持多插件整包",
        icon = Icons.Outlined.Add,
        onClick = {
            // 正版和 Canary 版都试一遍：谁装了用谁。
            val mtIntent = listOf("bin.mt.plus", "bin.mt.plus.canary")
                .firstNotNullOfOrNull { pkg ->
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, zipMimes)
                        `package` = pkg
                    }
                    intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
                }
            if (mtIntent != null) mtPicker.launch(mtIntent) else systemPicker.launch(zipMimes)
        }
    )
}

/**
 * 「网络与镜像」：这一组的内容就是镜像表本身，没有二级入口。
 *
 * **位置在「设置 - 插件」页，「手动安装」下面**（用户要求从「刮削」页搬过来）。
 *
 * 一行的东西：左边域名，右边耗时（或转圈），最快的那条挂一个徽标；
 * 当前在用的那条整行淡主题色 + 左边一条竖条。点一行 = 以后下载先试它，
 * 长按一行 = 删掉它（删要确认，所以不挂图标，免得每行都乱）。
 *
 * 测完重排之后，每一行会从**它上一次的位置**滑到新位置（见 [MirrorRow]）：
 * 外面这层是 verticalScroll 的 Column，套不了 LazyColumn，所以用逐行位移来做。
 */
@Composable
private fun MirrorListGroup(settings: AppSettings, vm: SettingsViewModel) {
    // 空行是旧版的"直连"占位，不显示（直连本来就第一个试）。
    val mirrors = settings.mirrorPrefixes.map { it.trim() }.filter { it.isNotBlank() }
    val results by vm.mirrorTestResults.collectAsState()
    val running by vm.mirrorTestRunning.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    // 这一轮实测最快的那个：没测过就不标。
    val fastest = mirrors
        .mapNotNull { mirror -> results[mirror]?.millis?.let { mirror to it } }
        .minByOrNull { it.second }
        ?.first
    val preferred = settings.preferredMirror.trim()

    /*
     * 重排动画的账本：每一行上一次排在第几号。
     *
     * 序号一变，这一行就先按"旧序号 - 新序号"的行数错开画，再用 320ms 滑回 0 ——
     * 看起来就是它从老位置挪到了新位置。这份账本不需要是 Compose 状态：
     * 它只在每次组合时写一遍，写它不该引起任何重组。
     */
    val previousIndex = remember { HashMap<String, Int>() }

    SettingsGroup(
        title = "网络与镜像",
        trailing = {
            TextButton(
                onClick = { vm.testMirrors() },
                enabled = !running && mirrors.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    if (running) "测试中" else "测试",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    ) {
        mirrors.forEachIndexed { index, mirror ->
            val shiftRows = (previousIndex[mirror] ?: index) - index
            // key 里带上序号：换位置时这一行按新位置重新组合，
            // 于是它的起始位移就是旧位置，第一帧不会闪到新位置去。
            key(mirror, index) {
                MirrorRow(
                    mirror = mirror,
                    result = results[mirror],
                    selected = preferred.isNotBlank() &&
                        preferred.trimEnd('/') == mirror.trimEnd('/'),
                    fastest = mirror == fastest,
                    first = index == 0,
                    shiftRows = shiftRows,
                    onClick = { vm.preferMirror(mirror) },
                    onLongClick = { removing = mirror }
                )
            }
            previousIndex[mirror] = index
        }

        SettingsRow(
            title = "添加",
            icon = Icons.Outlined.Add,
            onClick = { adding = true }
        ) {
            // 删光了、改乱了，用它回到内置的六个。做成一枚小图标：
            // 这一组不放第二个文字按钮。
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.resetMirrorPrefixes() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Restore,
                    contentDescription = "恢复默认",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (adding) {
        var input by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加镜像") },
            text = {
                SettingsTextField(label = "前缀", value = input, onValueChange = { input = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addMirror(input)
                    adding = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } }
        )
    }

    removing?.let { mirror ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除镜像") },
            text = { Text(mirror) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMirror(mirror)
                    removing = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        )
    }
}

/**
 * 镜像表里的一行，高度固定 [MIRROR_ROW_HEIGHT]（重排的位移要按整行算，不能随文字变）。
 *
 * [shiftRows] 是"这一行上一次在第几号减现在第几号"：非 0 就说明刚被重排过，
 * 先按这个行数错开画，再滑回 0。行高固定，所以位移量是准的。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MirrorRow(
    mirror: String,
    result: MirrorTestResult?,
    selected: Boolean,
    fastest: Boolean,
    first: Boolean,
    shiftRows: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    // 卡片本身是半透明的，滑动时行与行会重叠；这里按"卡片实际看起来的颜色"
    // 铺一层不透明的底，重叠时上面那行能把下面那行盖住，不会糊成一片。
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        .compositeOver(MaterialTheme.colorScheme.background)
    val density = LocalDensity.current
    val pitchPx = with(density) { MIRROR_ROW_HEIGHT.toPx() }

    val offset = remember { Animatable(shiftRows * pitchPx) }
    LaunchedEffect(Unit) {
        if (offset.value != 0f) {
            offset.animateTo(0f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(MIRROR_ROW_HEIGHT)
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .background(
                if (selected) primary.copy(alpha = 0.10f).compositeOver(cardColor) else cardColor
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左边那条 3dp 竖条：只有当前在用的那条是主题色。
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (selected) primary else Color.Transparent)
            )
            Spacer(Modifier.width(11.dp))
            Text(
                com.audiometa.app.util.RemoteText.mirrorLabel(mirror),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier.width(MIRROR_LATENCY_WIDTH),
                contentAlignment = Alignment.CenterEnd
            ) {
                MirrorLatency(result)
            }
            Box(
                modifier = Modifier.width(MIRROR_BADGE_WIDTH),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (fastest) SettingsBadge("最快", highlight = true)
            }
        }
        // 分隔线跟着行走（行本身是不透明的），滑动时不会有一条静止的线划过去。
        if (!first) SettingsStrongDivider()
    }
}

/** 右侧那一格：耗时 / 超时 / 没测过 / 正在测。 */
@Composable
private fun MirrorLatency(result: MirrorTestResult?) {
    val primary = MaterialTheme.colorScheme.primary
    when {
        result == null -> Text(
            "—",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
        result.pending -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = primary
        )
        result.millis == null -> Text(
            "超时",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.End
        )
        else -> Text(
            result.millis.toString() + " ms",
            style = MaterialTheme.typography.labelLarge,
            // 分级：很快的用主题色，一般般的老实退成次要颜色。
            color = if (result.millis < 150) primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

/** 镜像表一行的高度。固定值：重排动画按"几行"算位移，行高一变位移就不准了。 */
private val MIRROR_ROW_HEIGHT = 44.dp

/** 耗时那一格的宽度。固定住，数字出现/消失（"超时"、"—"、转圈）时整行不会左右跳。 */
private val MIRROR_LATENCY_WIDTH = 64.dp

/** 「最快」徽标那一格：一直占着位置，徽标冒出来时不会把左边的数字挤走。 */
private val MIRROR_BADGE_WIDTH = 44.dp

// ---------------- 插件清单（按能力分成两页） ----------------

/**
 * 仓库那一行的标题。
 *
 * 订阅过的仓库用 index.json 里写的名字（例如「渊の插件源」）；还没拉到过 index.json
 * （或者那个仓库没写名字）就显示订阅的地址；完全没订阅才显示提示语。
 */
private fun repoTitle(settings: AppSettings, vm: SettingsViewModel): String =
    settings.pluginRepoName.ifBlank { vm.repoUrl }.ifBlank { "还没有订阅仓库" }

/** 某一类插件的入口行：右边是数量 + 箭头，点进去是这一类插件的清单页。 */
@Composable
private fun PluginGroupEntry(
    title: String,
    section: SettingsSection,
    capability: PluginCapability,
    plugins: List<PluginRecordEntity>,
    vm: SettingsViewModel
) {
    val count = plugins.count { capability in vm.pluginInfo[it.pluginId]?.capabilities.orEmpty() }
    SettingsRow(title = title, onClick = { vm.openSection(section) }) {
        Text(
            count.toString() + " 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 某一类插件的清单页（「元信息源」/「艺术家图源」）。
 *
 * 只列声明了对应能力的插件；两个都声明的插件两页都会出现。
 *
 * 这里**用普通 Column 逐行铺开，不放 LazyColumn** —— 外面这层已经是
 * verticalScroll 的 Column，里面再套一个能滚的列表会冲突（以前这么写直接崩）。
 */
@Composable
internal fun PluginGroupPage(
    title: String,
    capability: PluginCapability,
    plugins: List<PluginRecordEntity>,
    vm: SettingsViewModel
) {
    // 元信息源 / 艺术家图源共用同一套插件页：列表、启停、设置项、右侧把手拖动排序。
    // 排序只影响显示，不影响刮削优先级（优先级来自标签规则）。
    val filter = when (capability) {
        PluginCapability.SEARCH_ARTIST_IMAGES ->
            com.audiometa.app.domain.model.ScrapeCapability.ARTIST_IMAGE
        else -> com.audiometa.app.domain.model.ScrapeCapability.TRACK_META
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        com.audiometa.app.ui.screen.plugin.PluginScreen(
            title = title,
            capabilityFilter = filter,
            onNeedParent = {
                vm.openSection(SettingsSection.PLUGINS)
                true
            }
        )
    }
}

/**
 * 清单里的一行插件。
 *
 * 用户要求（原插件页的内容移植到设置里之后）：
 * - **每行高度提高**：图标、名字、插件 id、版本、开关、删除都摆得下，不挤成一团；
 * - **支持启用 / 停用**：用横向滑动开关，一眼看出状态；
 * - **支持删除单个源**：右侧垃圾桶，点了先确认再删。
 */
@Composable
private fun PluginRow(
    plugin: PluginRecordEntity,
    context: android.content.Context,
    vm: SettingsViewModel
) {
    // 图标名取自 manifest（默认 icon.png）。
    val iconName = vm.pluginInfo[plugin.pluginId]?.icon.orEmpty().ifBlank { "icon.png" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PluginIcon(java.io.File(plugin.installPath), plugin.pluginVersion, iconName)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    plugin.pluginName.ifBlank { plugin.pluginId },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (plugin.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    plugin.pluginId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 启用 / 停用：横向滑动开关（用户明确要求用开关，不用文字标签）。
            androidx.compose.material3.Switch(
                checked = plugin.enabled,
                onCheckedChange = { checked -> vm.setPluginEnabled(plugin.pluginId, checked) }
            )
            IconButton(onClick = { vm.pendingUninstall = plugin }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除 " + plugin.pluginName,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 56.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsBadge("v" + plugin.pluginVersion)
            /*
             * 这里原来有一个「更新」按钮（只检查/更新这一个插件）。
             *
             * 它走的是"拉 index → 只下载这一个插件包"的单文件下载路径，
             * 而订阅更新现在**只下载 MetaAudio-Subscription.zip**（用户要求），
             * 单文件下载的代码已经整条删掉。为了更新一个源去下 1.7MB 的整包
             * 也不合理，所以按钮一并撤掉：更新只留「更新订阅」一个入口，
             * 点一次把插件、规则、图标一起刷新。
             */
        }
    }
}

/**
 * 插件自己的图标。
 *
 * 读的是插件目录里 manifest 指定的那张图（默认 icon.png），走的是
 * [PlatformIcons] 那份读取逻辑（和「元信息」页同一个实现）。文件读取与解码都在
 * 后台线程做，解出来的位图按「目录 + 版本」缓存起来 —— 列表每次重组都重新解码
 * 会明显卡顿。读不到就退回一个通用的插件图标，绝不让这一行空着或者报错。
 */
@Composable
private fun PluginIcon(dir: java.io.File, version: String, iconName: String) {
    val key = dir.absolutePath + "@" + version
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = PluginIconCache.get(key),
        key
    ) {
        PluginIconCache.get(key)?.let {
            value = it
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            val bytes = com.audiometa.app.media.image.PlatformIcons.pluginIconBytes(dir, iconName)
                ?: return@withContext null
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (decoded != null) PluginIconCache.put(key, decoded)
        value = decoded
    }
    // 取到局部变量再判空：`by produceState` 的属性没法智能转换。
    val image = bitmap
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        } else {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 插件图标的解码结果缓存：按「插件目录@版本」存，切进切出不用重新解码。 */
private object PluginIconCache {
    private val decoded =
        java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    fun get(key: String): android.graphics.Bitmap? = decoded[key]

    fun put(key: String, bitmap: android.graphics.Bitmap) {
        decoded[key] = bitmap
    }
}
