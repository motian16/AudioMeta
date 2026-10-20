package com.audiometa.app.ui.screen.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.DurationFilter
import com.audiometa.app.domain.model.ScanProgress
import com.audiometa.app.ui.component.ChoiceChips
import com.audiometa.app.ui.component.SectionTitle
import com.audiometa.app.util.StoragePermissions

/**
 * 首次启动的引导页。
 *
 * ## 这一版改了什么（用户要求：引导页 UI / 交互优化）
 *
 * 1. **结构从"一长串控件"变成三步**：顶部一行步骤指示（权限 → 目录 → 扫描），
 *    每块内容前有 `1 ·` / `2 ·` 的序号，用户一眼知道还差几步。
 * 2. **权限不再只在上一页管**：这一页顶部会实时显示授权状态；没授权时给一条
 *    醒目但不挡路的提示（点一下直接去系统设置，回来自动刷新）。
 * 3. **常见目录改成芯片**：点一下加入、再点一下移除（以前只能加不能删，
 *    加错一个目录只能进主界面之后再去设置里删）。
 * 4. **扫描状态是一块独立区域**：扫描中显示已查看/已发现数量和当前文件（截断显示），
 *    并给"停止"按钮；扫完显示结果，主按钮顺势变成「进入主界面」。
 * 5. **所有失败都有话说**：选到网盘目录、加入目录失败、扫描起不来，都会在页面
 *    顶部显示一行原因 —— 以前这些异常会直接把应用带走（见 [OnboardingViewModel.addRoot]）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val progress by vm.scanProgress.collectAsState()
    val candidates by vm.candidates.collectAsState()
    val notice by vm.notice.collectAsState()

    var allFiles by remember { mutableStateOf(StoragePermissions.hasAllFilesAccess()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // 从系统设置页回来时刷新授权状态和"已加入"标记。
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allFiles = StoragePermissions.hasAllFilesAccess()
            vm.refreshCandidates()
        }
    }

    /*
     * 系统文件夹选择器。
     *
     * `takePersistableUriPermission` 在部分提供方（网盘、下载器）上会抛
     * SecurityException —— 以前这行是裸的，选到这类目录就闪退。现在兜住：
     * 拿不到持久权限也继续尝试加入，加不进去由 ViewModel 给出原因。
     */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.addRoot(uri)
        }
    }

    // 每行候选都要 stat 一次磁盘，放到 IO 线程去做（见 OnboardingViewModel）。
    LaunchedEffect(Unit) { vm.checkCandidates() }

    val roots = settings.roots
    val scanning = progress.running
    val finished = progress.finished && !progress.running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 顶部 ----------------
        Text(
            "把手机里的音乐整理成一个曲库",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "授予权限 → 选择目录 → 扫描。除「所有文件访问」外均可跳过。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StepStrip(
            steps = listOf("权限", "目录", "扫描"),
            done = listOf(allFiles, roots.isNotEmpty(), finished)
        )

        if (notice.isNotBlank()) {
            NoticeBar(notice) { vm.clearNotice() }
        }

        // ---------------- 1. 权限 ----------------
        if (!allFiles) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "还没有「所有文件访问」",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "不授权也能用，但只能逐个选择目录，部分文件夹扫不到。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                    TextButton(onClick = {
                        // 统一入口：ROM 没有这个页面时会自动退回总入口，不会抛。
                        StoragePermissions.openAllFilesAccessSettings(context)
                    }) { Text("去授权") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "已获得所有文件访问权限，可以扫描任意目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ---------------- 2. 目录 ----------------
        SectionTitle("1 · 选择音乐文件夹")
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("从系统选择器选择文件夹")
        }

        if (candidates.isNotEmpty()) {
            Text(
                "或点击添加常用目录，再次点击移除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                candidates.forEach { candidate ->
                    FilterChip(
                        selected = candidate.added,
                        onClick = {
                            if (candidate.added) vm.removeRoot(candidate.path)
                            else vm.addRootPath(candidate.path)
                        },
                        label = { Text(candidate.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (candidate.added) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null
                    )
                }
            }
        }

        if (roots.isEmpty()) {
            Text(
                "还没有选择目录，可稍后在「设置 - 音乐库」添加。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "已加入 " + roots.size + " 个目录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                roots.forEach { path ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeRoot(path) },
                        label = {
                            Text(
                                path.substringAfterLast('/').ifBlank { path },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "移除",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        // ---------------- 3. 扫描 ----------------
        SectionTitle("2 · 扫描曲库")
        Text(
            "格式已选 " + settings.formats.size + "/" + AudioFormats.ALL.size +
                " 种（可在设置里细调）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChoiceChips(
            options = listOf(
                DurationFilter.NONE.name to "不跳过",
                DurationFilter.SKIP_UNDER_30.name to "跳过 <30 秒",
                DurationFilter.SKIP_UNDER_60.name to "跳过 <60 秒",
                DurationFilter.CUSTOM.name to "自定义"
            ),
            selected = settings.durationFilter.name,
            onSelect = { vm.setDuration(DurationFilter.valueOf(it)) }
        )

        ScanStatus(
            progress = progress,
            onCancel = { vm.cancelScan() }
        )

        // ---------------- 主按钮 ----------------
        when {
            scanning -> OutlinedButton(
                onClick = { vm.cancelScan() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止扫描") }

            finished -> Button(
                onClick = { vm.finish(onFinished) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("进入主界面") }

            else -> Button(
                onClick = { vm.startScan() },
                modifier = Modifier.fillMaxWidth(),
                enabled = roots.isNotEmpty()
            ) { Text("开始首次扫描") }
        }

        // 任何时候都能进主界面：这一步是"可选"的，不能把人困在引导页。
        if (!finished) {
            TextButton(
                onClick = { vm.finish(onFinished) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (scanning) "后台继续扫描，先进主界面" else "跳过，先进主界面") }
        }
        Text(
            if (roots.isEmpty()) "不选目录也可以进入，曲库为空。"
            else "进入后可随时下拉刷新重新扫描。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** 顶部三步指示：已完成的步骤打勾，当前步骤高亮。 */
@Composable
private fun StepStrip(steps: List<String>, done: List<Boolean>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, label ->
            val complete = done.getOrElse(index) { false }
            val color = if (complete) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (complete) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (complete) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            if (index != steps.lastIndex) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/**
 * 扫描状态块。
 *
 * 三种形态：没扫过 / 扫描中 / 已完成。扫描中把当前文件截短显示 ——
 * 长路径会把这一块撑高，用户真正关心的是"还在动"。
 */
@Composable
private fun ScanStatus(progress: ScanProgress, onCancel: () -> Unit) {
    when {
        progress.running -> Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在扫描：已发现 " + progress.foundAudio + " 首 / 已查看 " +
                        progress.scannedFiles + " 个文件",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progress.currentPath.isNotBlank()) {
                Text(
                    progress.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        progress.finished -> Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (progress.foundAudio > 0) {
                    "扫描完成：发现 " + progress.foundAudio + " 首"
                } else {
                    "扫描完成，但没有找到音频"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (progress.foundAudio > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            if (progress.message.isNotBlank()) {
                Text(
                    progress.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress.foundAudio == 0) {
                Text(
                    "检查目录是否正确、是否已授予文件访问权限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        else -> Text(
            "尚未扫描。扫描会读取标签和封面，文件较多时需要几分钟。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 顶部的一次性提示条：带一个关闭按钮，不挡任何操作。 */
@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = "关闭提示",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClose)
                    .padding(7.dp)
            )
        }
    }
}

/**
 * 把 SAF 选中的目录 Uri 换算成**本机文件路径**。
 *
 * 形如 `content://com.android.externalstorage.documents/tree/primary%3AMusic`
 * 的 Uri，它的 docId 是 `primary:Music`，换算结果就是
 * `/storage/emulated/0/Music`。
 *
 * ## 什么情况下会失败（以前是静默给出错误路径）
 *
 * SAF 的提供方**不只有本地存储**。网盘、云盘、下载器给的 docId 不带 `:`，
 * 于是 `substringBefore(':')` 会把整串当卷名，拼出
 * `/storage/content://com.google.android.apps.docs.storage/...` 这种**根本不存在的路径**。
 * 存进去之后扫描器遍历不到任何东西，界面上只显示"已扫描 0 个文件"，
 * 却不报错 —— 用户完全不知道是"选的位置不对"。
 *
 * 所以这里**主动抛异常**，由调用方拒绝加入并说明原因。
 *
 * @throws IllegalArgumentException 不是本地存储的提供方，或 docId 结构不认识
 */
fun treePath(uri: Uri): String {
    val docId = DocumentsContract.getTreeDocumentId(uri)

    /*
     * docId 的合法形态是 `<卷>:<相对路径>`，例如 `primary:Music/Albums`。
     * 没有 `:` 就说明这个提供方不是"卷 + 路径"模型（网盘最常见），
     * 无法换算成本机路径 —— 直接拒绝，别拼一个假的出来。
     */
    require(docId.contains(':')) {
        "这个位置不是本机存储（提供方 " + uri.authority + " 给的标识是「" + docId + "」）"
    }

    val volume = docId.substringBefore(':')
    val relative = docId.substringAfter(':')

    /*
     * SAF 允许路径里带 `/`，但有些提供方会做 URL 编码。
     * 不还原的话会拼出 `/storage/emulated/0/Music%2FAlbums` 这种假目录。
     */
    val decoded = runCatching {
        java.net.URLDecoder.decode(relative, "UTF-8")
    }.getOrDefault(relative)

    val base = when (volume) {
        "primary" -> "/storage/emulated/0"
        // 常见别名：有些 ROM 用 `emulated/0` 这种写法
        "emulated" -> "/storage/emulated/0"
        else -> "/storage/$volume"
    }
    return if (decoded.isBlank()) base else "$base/$decoded"
}
