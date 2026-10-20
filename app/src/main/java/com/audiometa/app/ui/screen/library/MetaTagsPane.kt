package com.audiometa.app.ui.screen.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.domain.tagrules.TagSummary
import com.audiometa.app.ui.component.EmptyHint
import com.audiometa.app.ui.component.LocalImage
import com.audiometa.app.ui.component.TrackCover
import com.audiometa.app.ui.component.copyToClipboard
import com.audiometa.app.ui.component.listSubtitle
import com.audiometa.app.ui.component.listTitle
import com.audiometa.app.ui.theme.cornerShape

/**
 * 「元信息」页签。
 *
 * 只显示**自定义标签** —— 也就是除了歌曲名、专辑、艺术家、艺术家图、封面、
 * 专辑艺术家、音轨碟号、版权、注释、语言、日期、作曲、作词、BPM、ISRC
 * 这些标准字段之外的标签。标准键的白名单是**代码里的**
 * [com.audiometa.app.domain.tagrules.TagRules.isStandardKey]（导入的规则
 * 可以再加，但拿掉不了它）—— 以前这里写的是"白名单在 tag_rules.json 的
 * standardTags 里"，那个说法随规则文件的引入已经不对了。
 *
 * **没有规则也要有内容**：规则只加别名、说明、平台分组和值译名，
 * 不决定哪些标签算存在。文件里读到的自定义标签一律显示（没有规则时
 * 全部归到「未知标签」），"去订阅一次"只是列表上方一句可点的提示。
 *
 * 界面按用户要求：
 *
 * - **按平台名合并成一张卡**：方形占位图 + 平台名 + 标签种类数。
 *   命中不了任何平台/工具的归到「未知标签」（就是不在识别规则里的标签）。
 * - **点进去是曲目列表**：每行封面 + 标题 / 艺术家 · 专辑（不显示格式），
 *   右列是这个来源下这条曲目的取值。**点值即复制**。
 * - **头部显示键名**：大标题是来源名，底下的小字是**标签键名**，
 *   点它只复制键名；再下面一行说明**这个标签大概是什么**（来自规则表）。
 * - 值是图片时右列直接画缩略图，点开可以放大看。
 * - 标题走统一的 [listTitle]：文件里没有内嵌标签就显示完整文件名。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaTagsPane(
    debugMode: Boolean = false,
    onOpenTrack: (String, MetaTagFocus) -> Unit = { _, _ -> },
    cornerStyle: com.audiometa.app.domain.model.CornerStyle =
        com.audiometa.app.domain.model.CornerStyle.ROUNDED,
    /** 没有规则时那一行提示点一下就跳到设置页（规则在「插件」页随着订阅更新）。 */
    onOpenSettings: () -> Unit = {},
    vm: MetaTagsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    /*
     * 分组名 → 这个分组下的标签键（例如「酷狗音乐」→ [KGHASH, QUALITY…]）。
     *
     * 图标不只是按分组名认平台：分组名认不出来、或认出来的平台一张图都没有时，
     * 还要拿组里的键再猜一次（`KUGOU_*` 这种带平台前缀的写法）。
     * 在这里一次算好，别让每张卡各自过滤一遍整张标签表。
     */
    val keysByGroup = remember(state.tags) {
        state.tags.groupBy({ it.group }, { it.key })
    }

    // 一次性提示走系统横幅，不写进界面文字。
    com.audiometa.app.ui.component.ToastEffect(
        message = vm.notice.text,
        onConsumed = vm::noticeConsume
    )

    val selected = state.selected
    if (selected != null) {
        // 系统返回键：一层层往回退（组内可能有三层），退到底才回分组列表。
        androidx.activity.compose.BackHandler(enabled = true) {
            if (state.path.isNotEmpty()) vm.openBucket(null) else vm.select(null)
        }
        GroupTracks(
            group = selected,
            // 组里的标签键也带过去：分组名认不出平台时，图标靠它们再猜一次。
            iconKeys = keysByGroup[selected.name].orEmpty(),
            entries = state.entries,
            path = state.path,
            loading = state.loadingTracks,
            cornerStyle = cornerStyle,
            onOpenBucket = { bucket -> vm.openBucket(bucket) },
            onBackLevel = { vm.openBucket(null) },
            onOpenTrack = onOpenTrack,
            onCopy = { text, label ->
                copyToClipboard(context, label, text)
                android.widget.Toast.makeText(context, "已复制" + label, android.widget.Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            state.groups.isEmpty() && state.loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "正在读取标签 " + state.scannedTracks + "/" + state.totalTracks,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            state.groups.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    /*
                     * 「本地无规则」这句话**只在这份结果真的为空时**说。
                     *
                     * 规则只负责给标签加别名/说明/平台分组，不决定标签存不存在：
                     * 文件里有自定义标签时，就算没导入规则也该照常列出来
                     * （归到「未知标签」组）。以前这里只看 `!isImported` 就说
                     * "没有规则"，于是明明扫到了一堆标签，页面还是一片空白 ——
                     * 用户会以为扫描没跑成功。
                     */
                    if (!com.audiometa.app.domain.tagrules.TagRules.isImported) {
                        // 一行小字 + 点一下去设置：用户看到的就是"没有规则"这一个事实
                        // 和一条出路，不需要再读一段解释。
                        Text(
                            "还没有标签规则。到「设置 - 插件」更新订阅。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onOpenSettings)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    } else {
                        Text(
                            "这里显示曲库里「不在识别规则中」的标签：平台私有标记、转码工具签名等。\n" +
                                "曲库扫描时会一起识别；也可以点右上角的「重新扫描」重读一遍文件标签。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> Column(Modifier.fillMaxWidth()) {
                Text(
                    "标签来源 " + state.groups.size + " 组 · 标签 " + state.tags.size + " 种" +
                        if (state.loading) " · 正在重扫 " + state.scannedTracks + "/" + state.totalTracks else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LazyVerticalGrid(
                    // 默认双列（用户要求），元信息页不给布局调整入口。
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.groups, key = { it.name }, contentType = { "meta-group" }) { group ->
                        // 图标要"分组名 + 该分组下的标签键"两个线索才认得全。
                        GroupCard(group, keysByGroup[group.name].orEmpty()) { vm.select(group) }
                    }
                }
            }
        }

        /*
         * 有结果、但还没有规则时，把"去加规则"那句话**留在列表下面**。
         *
         * 列表能显示不代表识别是完整的：没有规则时所有自定义标签都只能落在
         * 「未知标签」一组里，没有平台名、没有别名、没有说明。这条提示是可点的
         * （跳到设置页的规则导入），所以它不是废话 —— 用户想看到"这个标签是什么"
         * 就得先有规则。
         */
        if (state.groups.isNotEmpty() &&
            !com.audiometa.app.domain.tagrules.TagRules.isImported
        ) {
            Text(
                "没有标签规则，暂只显示为「未知标签」。到「设置 - 插件」更新订阅后可显示平台与说明。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        if (debugMode) {
            Text(
                // 「发送报告」打开后，顶栏复制键左边会多一个发送键：
                // 发送只带未知标签，复制是全部来源分组的 Markdown。
                "发送报告已开启：右上角「发送」把未知标签发到邮箱，「复制」按组复制 Markdown 报告。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * 一张平台图标（分组网格里的方形图、来源列表页头部的小图共用这一份）。
 *
 * 取图在 IO 线程做（[com.audiometa.app.media.image.PlatformIcons.bytes] 要读盘），
 * 用 produceState 把它变成可组合状态；解码结果按字节数组 remember。
 *
 * **不要把这个 remember 挪进条件分支**：插槽表会和重组对不上。
 * 认不出平台、本地一张图都没有、解码失败，都退回 [fallback] —— 位置照旧占着，
 * 标题行不会因为有没有图而忽左忽右。
 */
@Composable
private fun PlatformIcon(
    label: String,
    keys: List<String>,
    boxSize: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    fallback: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val iconBytes by androidx.compose.runtime.produceState<ByteArray?>(null, label, keys) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.audiometa.app.media.image.PlatformIcons.bytes(context, label, keys)
        }
    }
    val bitmap = androidx.compose.runtime.remember(iconBytes) {
        iconBytes?.let { bytes ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(boxSize / 10)
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = null,
                modifier = Modifier.size(boxSize * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 没有平台图时用的通用图标（和以前那种统一占位图保持一致）。 */
private fun fallbackIconFor(group: TagGroup): androidx.compose.ui.graphics.vector.ImageVector =
    when {
        group.isImage -> Icons.Outlined.Image
        group.isUnknown -> Icons.Outlined.HelpOutline
        else -> Icons.Outlined.Sell
    }

/**
 * 一个来源分组（双列网格里的一格）。
 *
 * 左侧方形图标，右侧平台名 + 标签种类数。
 *
 * 用户明确要求**去掉后面的"命中 x 次"** —— 那个数字在这一层没有解释力，
 * 反而把这一格撑得又挤又乱。要看命中有多少，点进去就有曲目列表。
 */
@Composable
private fun GroupCard(group: TagGroup, keys: List<String>, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 方形图标：按**分组名**认平台（对不上再按组里的标签键猜），
            // 去订阅装进来的那批 png 里取图（qq.png ↔ 「QQ 音乐」），
            // 订阅里没有才翻已安装插件包的 icon.png。
            // 认不出来的平台（未知标签、未知图片）继续用原来的占位图标。
            PlatformIcon(
                label = group.name,
                keys = keys,
                boxSize = 44.dp,
                corner = 10.dp,
                fallback = fallbackIconFor(group)
            )
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    group.tagCount.toString() + " 种标签",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

/**
 * 某个来源下的曲目列表（可以有三层）。
 *
 * 用户规则：
 * - **一级**（组页面）：变量项 / 取值项 + 单独列出的曲目；同一首歌可以既在下级
 *   列表里、又在这里单独占一行（它还有别的没被合并的取值）；
 * - **二级**：变量项点进去 → 它的各个取值；
 * - **三级**：取值项点进去 → 这批曲目（这就是"一个变量 ≥10 首多出一层"）。
 *
 * 头部只有一行：来源名（左边带这个来源的图标）＋ 面包屑（变量 › 取值 + 数量），
 * 整行可点回上一级。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupTracks(
    group: TagGroup,
    // 这个分组下的标签键；分组名认不出平台时，图标靠它们再猜一次。
    iconKeys: List<String>,
    entries: List<MetaListEntry>,
    path: List<MetaBucket>,
    loading: Boolean,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onOpenBucket: (MetaBucket) -> Unit,
    onBackLevel: () -> Unit,
    onOpenTrack: (String, MetaTagFocus) -> Unit,
    onCopy: (String, String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // 最深那一项是"取值"时，本层已经是曲目列表了，直接列；否则列它的下级项。
        val deepest = path.lastOrNull()
        val showTracks = deepest != null && !deepest.isVariable
        val crumb = buildString {
            append(group.name)
            path.forEach { step -> append(" › ").append(step.title) }
            if (showTracks) append(" · ").append(deepest!!.count).append(" 首")
        }
        if (path.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBackLevel() }
                    .padding(start = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头部也带来源图标（用户要求）：进了某个来源之后一眼还能认出是哪家。
                PlatformIcon(
                    label = group.name,
                    keys = iconKeys,
                    boxSize = 18.dp,
                    corner = 5.dp,
                    fallback = fallbackIconFor(group)
                )
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "回到上一级",
                    // padding 在 size 前面：先让出 4dp 间距，再画 16dp 的箭头
                    // （反过来的话是把箭头挤在 16dp 里，会变小）。
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    crumb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        } else {
            val songCount = entries.filterIsInstance<MetaListEntry.Song>().size
            val itemCount = entries.size - songCount
            Row(
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlatformIcon(
                    label = group.name,
                    keys = iconKeys,
                    boxSize = 18.dp,
                    corner = 5.dp,
                    fallback = fallbackIconFor(group)
                )
                Text(
                    group.name + " · 共 " + songCount +
                        (if (itemCount > 0) " 首 + " + itemCount + " 个标签项" else " 首"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        if (showTracks) {
            // 第三层：这批曲目的列表。
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(deepest!!.rows, key = { it.track.id }, contentType = { "meta-track" }) { row ->
                    MetaTrackCard(row, group.name, cornerStyle, onOpenTrack, onCopy)
                }
            }
            return@Column
        }

        when {
            loading && entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            entries.isEmpty() -> EmptyHint("这一层没有内容。")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(
                    entries,
                    key = { entry ->
                        when (entry) {
                            is MetaListEntry.Bucket ->
                                "bucket|" + entry.item.kind + "|" + entry.item.rawKey + "|" + entry.item.rawValue
                            is MetaListEntry.Song -> "song|" + entry.row.track.id
                        }
                    },
                    contentType = { "meta-entry" }
                ) { entry ->
                    when (entry) {
                        is MetaListEntry.Bucket -> BucketCard(entry.item, cornerStyle) { onOpenBucket(entry.item) }
                        is MetaListEntry.Song -> MetaTrackCard(
                            entry.row, group.name, cornerStyle, onOpenTrack, onCopy
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一级列表里的"入口项"：变量项（同一个键 ≥10 首，或回放增益这种按键盘出的）、
 * 取值项（同一个键 + 同一个值 ≥10 首）、家族项（Lavf… 一族）或专辑项。
 *
 * 左边**显示这批歌里第一首的封面**（用户要求：入口显示封面图，不要数字占位），
 * 数量写在副标题里。
 */
@Composable
private fun BucketCard(
    bucket: MetaBucket,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cover = bucket.rows.firstOrNull()
            if (cover != null) {
                TrackCover(
                    audioPath = cover.track.path,
                    shape = cornerShape(cornerStyle, 10.dp),
                    stamp = cover.track.updatedAt,
                    modifier = Modifier.size(44.dp),
                    contentDescription = bucket.title
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        bucket.count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    bucket.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    bucket.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 元信息页里的一行曲目。
 *
 * 用户要求：**右侧恢复信息显示区域**，这一列**只显示别名**
 * （例如 QQ 音乐各级音质就是"臻品母带""无损品质（SQ）"），
 * **点一下复制这个值原来的内容**（`Premium-Master`）。
 * 想看完整值、键名和说明，点进歌曲详情页。
 */
@Composable
private fun MetaTrackCard(
    row: MetaTrackRow,
    groupName: String,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onOpenTrack: (String, MetaTagFocus) -> Unit,
    onCopy: (String, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTrack(row.track.path, row.focus(groupName)) }
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackCover(
                audioPath = row.track.path,
                shape = cornerShape(cornerStyle, 8.dp),
                stamp = row.track.updatedAt,
                modifier = Modifier.size(44.dp),
                contentDescription = row.track.listTitle
            )
            Column(Modifier.weight(0.55f).padding(start = 10.dp)) {
                Text(
                    row.track.listTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = row.track.listSubtitle
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 右列：只显示别名；点一下复制原始值。
            val image = row.firstImage
            Column(
                Modifier.weight(0.45f).padding(start = 6.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (image != null) {
                    LocalImage(
                        path = com.audiometa.app.media.tag.MetaImageStore
                            .fileOf(context, image.imageMarker)?.absolutePath,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onCopy(image.raw, "图片标记") },
                        contentDescription = "标签图片"
                    )
                }
                val alias = row.valueText
                if (alias.isNotBlank()) {
                    /*
                     * 右列：**显示的是别名，复制的是原值**（用户要求）。
                     *
                     * 点击区域只圈住这行文字本身 —— 不加 fillMaxWidth：
                     * 用户看到的是别名那一段，点到右边空白处却复制了值，
                     * 会让人以为点错了地方；整行的点击留给"进详情页"。
                     */
                    Text(
                        alias,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onCopy(row.copyText, "值") }
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}