package com.audiometa.app.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.data.prefs.SettingsStore
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.DurationFilter
import com.audiometa.app.util.StoragePermissions

// ---------------- 音频格式 ----------------

@Composable
internal fun FormatsSection(settings: AppSettings, vm: SettingsViewModel) {
    SettingsGroup("扫描哪些格式", "关掉不用的格式能明显加快扫描") {
        SettingsSegmented(
            options = listOf("COMMON" to "只留常见", "ALL" to "全选"),
            selected = "",
            onSelect = { if (it == "COMMON") vm.useCommonFormats() else vm.useAllFormats() }
        )
    }

    val all = AudioFormats.ALL.sorted()
    SettingsGroup("格式列表") {
        all.chunked(3).forEachIndexed { index, row ->
            if (index > 0) SettingsDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                row.forEach { ext ->
                    val n = AudioFormats.normalize(ext)
                    val checked = n in settings.formats
                    Box(Modifier.weight(1f)) {
                        com.audiometa.app.ui.component.CheckRow(
                            label = n.uppercase(),
                            checked = checked,
                            onChecked = { vm.toggleFormat(n, it) }
                        )
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }

    SettingsGroup("各格式支持的内嵌能力", "扫描和写入按这张表决定走内嵌还是外置文件") {
        all.forEachIndexed { index, ext ->
            if (index > 0) SettingsDivider()
            val n = AudioFormats.normalize(ext)
            SettingsRow(n.uppercase(), com.audiometa.app.domain.model.formatSupportOf(n).summary)
        }
    }
}

// ---------------- 音乐库 ----------------

@Composable
internal fun LibrarySection(
    settings: AppSettings,
    vm: SettingsViewModel,
    folders: List<FolderEntity>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scan by com.audiometa.app.di.ServiceLocator.scanner.progress.collectAsState()
    var pendingRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var customDialog by rememberSaveable { mutableStateOf(false) }
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

    val useMediaStore = settings.librarySource == SettingsStore.SOURCE_MEDIA_STORE
    /*
     * 媒体库来源需要音频读取权限，而**服务里申请不了权限** ——
     * 不在这里先申请，扫描启动后查询会被系统直接拒绝，用户看到的就是
     * "点了重新扫描什么都没发生"。所以：点扫描时发现没权限就先申请，
     * 用户同意之后再开始扫描。
     */
    val mediaPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var rescanAfterGrant by rememberSaveable { mutableStateOf(false) }
    var mediaAsked by rememberSaveable { mutableStateOf(false) }

    /*
     * 「所有文件访问」是**去系统设置页**授权的，不是弹窗。
     *
     * 所以用户点「去授权」→ 切到系统设置 → 授权 → 切回来，这个页面是**重新可见**
     * 而不是重新创建 —— Compose 不会因此重组，`isExternalStorageManager()`
     * 也不会被重新调用。结果就是：授权成功了，界面还显示"没权限"，
     * 再点一次「重新扫描」又提示去授权，用户会以为授权没用。
     *
     * 这里跟着生命周期重新读一次，回前台就刷新。
     */
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var allFilesGranted by remember { mutableStateOf(StoragePermissions.hasAllFilesAccess()) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                allFilesGranted = StoragePermissions.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 「所有文件访问」授权被拦下来时置位。
     *
     * 用 rememberSaveable：用户点「重新扫描」→ 弹 Toast → 去系统设置授权 →
     * 切回来时这个页面可能已经重建，标记得活下来才能给出正确反馈。
     */
    var allFilesAsked by rememberSaveable { mutableStateOf(false) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        mediaAsked = true
        if (!granted) {
            android.widget.Toast.makeText(
                context,
                "没有媒体读取权限，无法扫描系统媒体库",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        if (granted && rescanAfterGrant) vm.rescanEverything()
        rescanAfterGrant = false
    }

    /** 没有读媒体库的权限就先申请（申请过被拒也再给一次机会，用户可能刚在系统里放开）。 */
    fun ensureMediaPermission(thenRescan: Boolean) {
        rescanAfterGrant = thenRescan
        mediaPermissionLauncher.launch(mediaPermission)
    }

    // 顶部一行：重新扫描 + 管理音频格式
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                /*
                 * 开扫之前先确认真的读得到。
                 *
                 * 这里以前**只检查媒体库来源**，固定文件夹那条路什么都不看就开扫。
                 * 而安卓 11 起读 `/storage/emulated/0` 下的目录需要
                 * 「所有文件访问」权限 —— 没有它 `File.listFiles()` 返回 null，
                 * 扫描器静默跳过，用户看到的是"我选了文件夹却一首都没有"。
                 *
                 * 用 SAF 选文件夹拿到的是 Uri 授权，**它不能让 java.io.File 读到文件**，
                 * 所以这里必须单独查一次系统权限。
                 */
                if (useMediaStore) {
                    if (!com.audiometa.app.media.scan.MediaStoreSource.canRead(context)) {
                        ensureMediaPermission(thenRescan = true)
                    } else {
                        vm.rescanEverything()
                    }
                } else {
                    // 用随生命周期刷新的那份状态，而不是每次都重新查系统：
                    // 查出来一样，但这份在"授权后切回来"时已经是新的了。
                    if (!allFilesGranted) {
                        allFilesAsked = true
                    } else {
                        vm.rescanEverything()
                    }
                }
            },
            enabled = !scan.running && (
                settings.librarySource == SettingsStore.SOURCE_MEDIA_STORE || settings.roots.isNotEmpty()
                ),
            modifier = Modifier.weight(1f)
        ) { Text(if (scan.running) "扫描中" else "重新扫描") }
        OutlinedButton(
            onClick = { vm.openSection(SettingsSection.FORMATS) },
            modifier = Modifier.weight(1f)
        ) { Text("管理音频格式") }
    }
    ScanProgressBlock(scan)

    /*
     * 加目录失败时的提示。
     *
     * 选了网盘/云盘、或者解析不出本机路径时会走到这里。必须**直接说出来** ——
     * 以前这种情况会静默存一个 `content://…` 当路径，之后扫描永远
     * "已扫描 0 个文件"，用户根本不知道问题出在选位置这一步。
     */
    if (vm.libraryNotice.isNotBlank()) {
        SettingsNote(vm.libraryNotice)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { vm.clearLibraryNotice() }) { Text("知道了") }
        }
    }
    /*
     * 固定文件夹来源缺「所有文件访问」权限时的提示。
     *
     * 用 SAF 选文件夹拿到的 Uri 授权**不能**让 java.io.File 读到文件内容，
     * 所以这条路必须单独要系统权限。以前这里只看媒体库来源，用户切到固定文件夹
     * 后会一直"扫不到任何文件"却不知道原因。
     */
    if (!useMediaStore && !allFilesGranted && settings.roots.isNotEmpty()) {
        SettingsNote(
            "没有「所有文件访问」，扫描读不到文件。授权后再扫。"
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { StoragePermissions.openAllFilesAccessSettings(context) },
                modifier = Modifier.weight(1f)
            ) { Text("去授权") }
        }
    }
    if (!useMediaStore && allFilesAsked && !allFilesGranted) {
        android.widget.Toast.makeText(
            context,
            "没有「所有文件访问」权限，扫描读不到文件",
            android.widget.Toast.LENGTH_LONG
        ).show()
        allFilesAsked = false
    }
    // 缺权限时说清楚为什么扫不了 —— 只在还没申请过的时候提示，免得反复唠叨。
    if (useMediaStore && !mediaAsked &&
        !com.audiometa.app.media.scan.MediaStoreSource.canRead(context)
    ) {
        SettingsNote("系统媒体库需要「媒体文件权限」")
    }

    SettingsGroup("曲库来源") {
        SettingsSwitchRow(
            title = "使用安卓媒体库",
            checked = useMediaStore,
            summary = if (useMediaStore) "当前用系统媒体库，下面的文件夹不参与扫描"
            else "打开后改用系统媒体库，与固定文件夹二选一",
            onChecked = { on ->
                vm.setLibrarySource(
                    if (on) SettingsStore.SOURCE_MEDIA_STORE else SettingsStore.SOURCE_FOLDERS
                )
                // 打开这个来源时顺手把权限申请了：没权限的话下一次扫描必然读不到东西。
                if (on && !com.audiometa.app.media.scan.MediaStoreSource.canRead(context)) {
                    ensureMediaPermission(thenRescan = false)
                }
            }
        )
    }

    SettingsGroup(
        "自定义文件夹",
        if (useMediaStore) "已关闭固定文件夹扫描，列表暂不生效" else "点击目录进入排除页"
    ) {
        if (settings.roots.isEmpty()) {
            SettingsRow("还没有目录", "添加一个音乐文件夹")
        } else {
            settings.roots.forEachIndexed { index, path ->
                if (index > 0) SettingsDivider()
                val excludedHere = settings.excluded.count { it.startsWith(path) }
                SettingsRow(
                    title = path,
                    summary = if (excludedHere == 0) "未排除子目录" else "已排除 " + excludedHere + " 个子目录",
                    icon = Icons.Outlined.Folder,
                    enabled = !useMediaStore,
                    onClick = { vm.openSubfolders(path) }
                ) {
                    IconButton(onClick = { pendingRemove = path }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "移除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        SettingsDivider()
        SettingsRow(
            title = "添加目录",
            summary = "从系统文件选择器里挑一个文件夹",
            icon = Icons.Outlined.Add,
            enabled = !useMediaStore,
            onClick = { picker.launch(null) }
        )
    }

    pendingRemove?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除这个音乐目录？") },
            text = { Text(path + "\n\n只是不再扫描它，磁盘上的文件不会动。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeRoot(path)
                    pendingRemove = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } }
        )
    }

    SettingsGroup("扫描范围") {
        SettingsSwitchRow(
            title = "跳过子目录",
            checked = settings.excludeSubdirs,
            summary = "只扫每个音乐目录的第一层",
            onChecked = vm::setExcludeSubdirs
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "播放时跳过开头静音",
            checked = settings.skipSilentIntro,
            summary = "需要先在「关于 - 缓存」里算好静音档案",
            onChecked = vm::setSkipSilentIntro
        )
    }

    SettingsGroup("时长过滤", "太短的通常是铃声或语音碎片") {
        SettingsSegmented(
            options = DurationFilter.entries.map {
                it.name to durationLabel(it, settings.customMinSeconds)
            },
            selected = settings.durationFilter.name,
            onSelect = { name ->
                val filter = DurationFilter.valueOf(name)
                vm.setDuration(filter)
                if (filter == DurationFilter.CUSTOM) customDialog = true
            }
        )
    }

    if (customDialog) {
        var text by rememberSaveable { mutableStateOf(settings.customMinSeconds.toString()) }
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text("跳过多少秒以内的音乐") },
            text = {
                SettingsTextField(
                    label = "秒",
                    value = text,
                    supporting = "例如 45 表示少于 45 秒的不进曲库",
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { vm.setCustomMinSeconds(it.coerceIn(1, 3600)) }
                    customDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text("取消") } }
        )
    }

    SettingsGroup("权限", "软件只申请这两项") {
        PermissionRow(
            title = "所有文件访问",
            summary = "读写任意位置音频文件的标签与封面",
            granted = StoragePermissions.hasAllFilesAccess()
        ) {
            StoragePermissions.openAllFilesAccessSettings(context)
        }
    }
        SettingsDivider()
        PermissionRow(
            title = "媒体文件权限",
            summary = "读取系统媒体库里的音频",
            granted = StoragePermissions.hasMediaAccess(context)
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.packageName)
                    )
                )
            }
        }
    }

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    SettingsRow(title = title, summary = summary) {
        Text(
            if (granted) "已获取" else "未获取",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (!granted) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onRequest) { Text("去授权") }
        }
    }
}

private fun durationLabel(filter: DurationFilter, customSeconds: Int): String = when (filter) {
    DurationFilter.NONE -> "不过滤"
    DurationFilter.SKIP_UNDER_30 -> "30 秒"
    DurationFilter.SKIP_UNDER_60 -> "60 秒"
    DurationFilter.CUSTOM -> customSeconds.toString() + " 秒"
}

/** 扫描进度：状态与计数各占一行；消息行最多三行 —— 失败原因（服务没注册、读不到目录）必须让用户读完，截成一行等于没说。 */
@Composable
private fun ScanProgressBlock(progress: com.audiometa.app.domain.model.ScanProgress) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (progress.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        Text(
            if (progress.running) "正在扫描…" else if (progress.finished) "上次扫描已完成" else "还没有扫描过",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "已扫描 " + progress.scannedFiles + " 个文件 · 找到音频 " + progress.foundAudio + " 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            progress.message.ifBlank { progress.currentPath },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
