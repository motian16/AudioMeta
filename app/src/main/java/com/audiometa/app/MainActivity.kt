package com.audiometa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.ui.navigation.MainTab
import com.audiometa.app.ui.screen.library.LibraryScreen
import com.audiometa.app.ui.screen.onboarding.OnboardingScreen
import com.audiometa.app.ui.screen.permission.PermissionGateScreen
import com.audiometa.app.ui.screen.plugin.PluginScreen
import com.audiometa.app.ui.screen.scrape.ScrapeSheet
import com.audiometa.app.ui.screen.settings.SettingsScreen
import com.audiometa.app.ui.theme.AudioMetaTheme
import com.audiometa.app.ui.theme.parseHexColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：让封面能画到状态栏底下，状态栏不再是突兀的黑条。
        // 各页面自己用 statusBarsPadding() 让出安全区。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AudioMetaRoot() }

        /*
         * 读设置、迁移旧目录、清临时文件、后台补全 —— 全部推迟到**第一帧画完之后**。
         *
         * 用 Choreographer 而不是 onCreate 直接跑：进程被系统顺手拉起来
         *（桌面上移除图标、包管理器重新解析 MAIN/LAUNCHER）时，
         * Application.onCreate / Activity.onCreate 里的活会直接拖慢
         * 系统那次同步请求，桌面表现成"移除图标时自己重启、特别卡"。
         * 放到第一帧之后，这段时间只属于界面自己。
         *
         * 真正的开关在 App.deferredStarted，旋转屏幕导致的重复调用会直接返回。
         */
        android.view.Choreographer.getInstance().postFrameCallback {
            runCatching { (application as? App)?.onFirstFrame() }
                .onFailure { com.audiometa.app.util.AppLog.w("MainActivity", "启动初始化失败：" + it.message) }
        }
    }
}

@Composable
private fun AudioMetaRoot() {
    val settings by ServiceLocator.settings.settings.collectAsState(initial = null)
    val loaded = settings
    val context = androidx.compose.ui.platform.LocalContext.current

    /*
     * 上次崩溃的提示。
     *
     * 用户装上包之后闪退，手上什么线索都没有；崩溃文件在
     * `Android/data/com.audiometa.app/files/crash.log`，但没人会去翻。
     *
     * 这里**不是弹窗**，而是先画一整屏"上次崩溃"。原因：如果崩溃发生在主界面
     * 组合期间，弹窗根本来不及画出来（同一帧里就先崩了）。先占住屏幕，
     * 用户才有机会把堆栈复制出来。同一次崩溃只提示一次（见 CrashLog.markSeen）。
     */
    var crashText by remember { mutableStateOf<String?>(null) }
    var crashChecked by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 读文件放 IO：崩溃日志最大 256KB，不该在首帧的主线程上读。
        crashText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.audiometa.app.util.CrashLog.pendingReport(context) }.getOrNull()
        }
        crashChecked = true
    }

    AudioMetaTheme(
        mode = loaded?.themeMode ?: com.audiometa.app.domain.model.ThemeMode.DARK,
        dynamicColor = loaded?.dynamicColor ?: false,
        preset = loaded?.themePreset ?: com.audiometa.app.domain.model.ThemePreset.DEFAULT,
        customPrimary = parseHexColor(loaded?.customPrimary.orEmpty()),
        customBackground = parseHexColor(loaded?.customBackground.orEmpty()),
        customSurface = parseHexColor(loaded?.customSurface.orEmpty())
    ) {
        // 关键：所有页面都画在这个 Surface 上，窗口背景永远不会露出来。
        // 之前权限页是裸 Column，深色窗口背景配上浅色主题的深色文字，直接看不见。
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                // 还没读完崩溃文件：先给一个空页面，避免闪一下主界面
                !crashChecked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                crashText != null -> CrashGate(
                    text = crashText.orEmpty(),
                    onContinue = {
                        com.audiometa.app.util.CrashLog.markSeen(context)
                        crashText = null
                    }
                )
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !loaded.onboardingDone -> SetupFlow()
                else -> MainScaffold()
            }
        }
    }
}

/**
 * 「上次运行崩溃了」整屏。
 *
 * 刻意做成**独占一屏**而不是 Dialog：崩溃可能发生在主界面组合期间，
 * 那样弹窗还没画出来进程就没了，用户永远看不到原因。
 */
@Composable
private fun CrashGate(text: String, onContinue: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
    ) {
        Text("上次运行未正常退出", style = MaterialTheme.typography.headlineSmall)
        Text(
            "把下面的信息发给开发者即可定位问题。只保存在本机，不会自动上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                .padding(10.dp)
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                )
            }
        }
        androidx.compose.material3.Button(
            onClick = {
                runCatching {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    android.widget.Toast
                        .makeText(context, "已复制崩溃信息", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                onContinue()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制并继续") }
        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("跳过") }
    }
}

@Composable
private fun SetupFlow() {
    var step by rememberSaveable { mutableStateOf(SetupStep.PERMISSION.name) }
    /*
     * 防御性解析：`rememberSaveable` 恢复出来的字符串如果对不上当前枚举
     * （旧版本改名、恢复数据损坏），`SetupStep.valueOf` 会直接抛
     * IllegalArgumentException —— 那是**启动阶段**的闪退。
     */
    val current = remember(step) {
        SetupStep.entries.firstOrNull { it.name == step } ?: SetupStep.PERMISSION
    }
    when (current) {
        SetupStep.PERMISSION -> PermissionGateScreen(onContinue = { step = SetupStep.ONBOARDING.name })
        SetupStep.ONBOARDING -> OnboardingScreen(onFinished = { })
    }
}

private enum class SetupStep { PERMISSION, ONBOARDING }

@Composable
private fun MainScaffold() {
    var current by rememberSaveable { mutableStateOf(MainTab.LIBRARY.name) }
    val haptic = LocalHapticFeedback.current

    /*
     * 设置 ViewModel **在这里创建、往下传**。
     *
     * 为什么不各页各自 `viewModel()`：那样会拿到不同实例。
     * 「控制」页要跳进「设置-网络遥控」，而设置页当前在看哪一页是
     * SettingsViewModel 里的状态 —— 两个实例的话，控制页设的那个状态
     * 设置页根本读不到，用户点了按钮就像没反应。
     *
     * 顺带也解决了切换 Tab 时设置页被重建、状态丢失导致的闪烁。
     */
    val sharedSettingsVm: com.audiometa.app.ui.screen.settings.SettingsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()

    /**
     * 「控制」页按「打开网络遥控设置」时，这里存放"设置页首帧该渲染哪一页"。
     *
     * 不用 rememberSaveable：它只在切 Tab 的那一次组合里有效，
     * 旋转屏幕后重放反而会莫名跳页。
     */
    var pendingSettingsTarget by remember {
        mutableStateOf<com.audiometa.app.ui.screen.settings.SettingsSection?>(null)
    }

    /*
     * 一次性跳转目标在**离开设置页之后立刻作废**。
     *
     * 不这样做的话：控制页点过一次「打开网络遥控设置」，这个值就永久残留
     * 成 REMOTE；之后从任意 Tab 点底栏「设置」，SettingsScreen 首帧的
     * remember(initialSection) 又会把它打开 —— 又是一种"进设置先闪一下子页"。
     * 设置页已经消费过它（首帧 openSectionNow），离开时清空没有副作用。
     */
    androidx.compose.runtime.LaunchedEffect(current) {
        if (current != MainTab.SETTINGS.name && pendingSettingsTarget != null) {
            pendingSettingsTarget = null
        }
    }

    /**
     * 重定信号：+1 表示"当前这个 Tab 回到自己最表层"。
     *
     * 两个来源：
     * 1. 再点一次已经选中的底部标签（点「设置」时如果正在设置里的某个二级页，
     *    应该回设置主页，而不是原样停在子页面）；
     * 2. [goUp] 走到"没有别的 Tab 可退"时，把当前页压回最外层。
     *
     * 各页面自己知道"最表层"是哪一层（设置页有分类页/缓存页/子文件夹页），
     * 所以这里只递信号，不替它决定 —— 和 backSignal 是同一套思路。
     */
    var resetTicket by rememberSaveable { mutableStateOf(0) }
    // 音频库的搜索页是全屏的：打开它的时候把底部标签栏收起来，
    // 不然搜索列表会被标签栏从中间截断，看起来像"半个页面"。
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    // 刮削窗口：从底部上拉的面板，不再是一个独立的底部 Tab。
    var scrapeOpen by rememberSaveable { mutableStateOf(false) }

    /**
     * 设置页的「回到最外层」信号。
     *
     * 只有在设置页自己的层级里后退用；跨 Tab 的跳转现在直接走
     * [sharedSettingsVm]，不再需要中转状态。
     */

    /**
     * 访问过的 Tab 历史，用来实现"返回 = 回上一次看的页"。
     *
     * 以前在「设置」里按系统返回会直接回桌面：MainActivity 自己没接返回键，
     * 各页面又只在"有子层级"时才拦，于是系统把整个 Activity 结束了。
     */
    val tabHistory = rememberSaveable(saver = TabHistorySaver) {
        mutableStateListOf(MainTab.LIBRARY.name)
    }

    /**
     * 返回信号。
     *
     * 为什么用"信号"而不是在这一层直接判断：各页面自己知道返回该做什么
     * （关搜索、收多选、退分类…），但**当前是哪个页面**只有这里知道。
     * 所以这里推一个计数下去，由可见的那个页面处理；没人处理才算"没有上一层级"。
     */
    var backSignal by rememberSaveable { mutableStateOf(0) }
    // 页面是否已经处理掉当前这次返回。初始为 true，表示"没有待处理的返回"。
    var backHandled by remember { mutableStateOf(true) }

    /**
     * 全局返回路由。
     *
     * 它自己不判断层级，只负责递信号；具体退到哪一层由各页面自己决定。
     * 每次按下只生效一次：立刻把 backHandled 置回 false，
     * 防止同一次返回被多个页面重复消费。
     */
    androidx.activity.compose.BackHandler(enabled = true) {
        backHandled = false
        backSignal++
    }

    fun switchTab(tab: MainTab) {
        if (tab.name == current) {
            /*
             * 再点一次已经选中的标签 = "回到这一页的最外层"。
             *
             * 例：在「设置 - 音乐库 - 某个目录的子文件夹页」里点底部的「设置」，
             * 应该回设置主页，而不是停在子页面。
             *
             * **在这里同步做**，不走 resetTicket 信号 —— 那个信号要等
             * SettingsScreen 组合之后的 effect 才能消费，中间会多出一帧
             * "还停在子页面"的画面。同步复位则下一帧就是主页。
             */
            if (tab == MainTab.SETTINGS) {
                sharedSettingsVm.exitSettings()
            }
            resetTicket++
        } else {
            /*
             * 离开设置页时把它的层级复位。
             *
             * 用户要求过：**设置页的页面状态不暂存** —— 从某个设置页切到
             * 控制页/音频库页，再切回设置应该落在**设置主页**。
             *
             * 同样必须在这里（同步）做：等 SettingsScreen 的 effect 的话，
             * "切回来"的第一次组合会先用旧的 section 渲染 ——
             * 表现就是先闪一下上次那一页再跳回主页。
             *
             * 反向（从别的 Tab 点进设置）同样**同步兜底落主页**：历史上
             * onDispose 等路径可能在 VM 里残留非空 section，首帧就会渲染
             * 旧子页。唯一例外是带了 pendingSettingsTarget 的跳转
             * （控制页「打开网络遥控设置」）—— 它在调本函数之前就写好了
             * 目标页，这里不能清。
             */
            if (current == MainTab.SETTINGS.name) {
                sharedSettingsVm.exitSettings()
            } else if (tab == MainTab.SETTINGS && pendingSettingsTarget == null) {
                sharedSettingsVm.exitSettings()
            }
            current = tab.name
        }
        // 记录访问历史：刚看的页排在最前。
        tabHistory.remove(tab.name)
        tabHistory.add(0, tab.name)
    }

    /**
     * 返回上一层。
     *
     * 层级顺序：页面内部层级 → 上一次看过的 Tab → 该 Tab 的最外层。
     *
     * **永远不退出应用**（用户反复强调的要求）：
     * 以前走到头会 `return false`，把返回键交还给系统，于是整个 Activity 被结束、
     * 直接回到桌面。用户要的是"返回动作永远是返回上一层级"，
     * 所以到最外层之后就停在那里（并让当前页回到自己的第一层），
     * 返回键被消费掉，不退桌面。
     *
     * 返回值现在恒为 true（已消费）。保留返回值是为了兼容调用点签名，
     * 将来若要恢复"退到桌面"只需在这里改回 false。
     */
    fun goUp(): Boolean {
        // 页面还没处理这次返回，交给它。
        if (!backHandled) return true
        // 页面没得退了：回上一次看的 Tab。
        val parent = tabHistory.firstOrNull { it != current }
        if (parent != null) {
            current = parent
            tabHistory.remove(parent)
            tabHistory.add(0, parent)
            // 换页之后这次返回就算用掉了，并让新页面回到最外层。
            backHandled = false
            backSignal++
            return true
        }
        // 只剩一个 Tab：停在它最外层，但仍然算"返回已处理"，不退桌面。
        if (current == MainTab.SETTINGS.name) sharedSettingsVm.exitSettings()
        resetTicket++
        backHandled = true
        return true
    }

    // 刮削窗口打开时，返回先关窗口。
    if (scrapeOpen) {
        ScrapeSheet(onDismiss = { scrapeOpen = false })
    }

    Scaffold(
        // 内边距交给每个页面自己处理：详情页的封面要画到状态栏底下，
        // 而 Scaffold 默认会把状态栏内边距加进 padding，导致普通页面顶部双重留白。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 刮削窗口打开时也让开底部标签栏：窗口是从底部上拉的，
            // 标签栏压在它下面会挡住「开始刮削」。
            if (!fullScreen && !scrapeOpen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    MainTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = current == entry.name,
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onClick = {
                                if (current != entry.name) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                switchTab(entry)
                            },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (MainTab.entries.firstOrNull { it.name == current } ?: MainTab.LIBRARY) {
                MainTab.LIBRARY -> LibraryScreen(
                    onFullScreenChanged = { fullScreen = it },
                    backSignal = backSignal,
                    onBackHandled = { backHandled = true },
                    onNeedParent = { goUp() },
                    scrapeOpen = scrapeOpen,
                    onOpenScrape = { scrapeOpen = true },
                    onCloseScrape = { scrapeOpen = false },
                    // 元信息页的"本地无规则"提示点一下就到设置页（规则在「插件」页随订阅更新）。
                    onOpenSettings = { switchTab(MainTab.SETTINGS) }
                )
                // 「控制」页：网络遥控（连电脑上的 Salt Player 遥控播放）。
                // 点「打开网络遥控设置」直接进设置-网络遥控页，不是设置主页 ——
                // 用户点了那个按钮就是想解决这一件事。
                //
                // 注意：这里和控制页共用**同一个** [sharedSettingsVm]（在
                // MainScaffold 顶层创建）。如果两处各自 viewModel()，会拿到两个
                // 不同实例，控制页里设的状态永远传不到设置页 —— 之前
                // 「点了没反应」就是这么来的。
                MainTab.CONTROL -> com.audiometa.app.ui.screen.control.ControlScreen(
                    vm = sharedSettingsVm,
                    onOpenRemoteSettings = {
                        // 顺序很重要：**先**让 VM 记下目标页并立刻取出来，
                        // **再**切 Tab。设置页用这个值在首帧就渲染目标页，
                        // 中间不存在"先渲染旧页再切过去"的帧。
                        sharedSettingsVm.openRemoteSettings()
                        pendingSettingsTarget = sharedSettingsVm.consumePendingSection()
                        switchTab(MainTab.SETTINGS)
                    }
                )
                MainTab.SETTINGS -> SettingsScreen(
                    resetTicket = resetTicket,
                    backSignal = backSignal,
                    onBackHandled = { backHandled = true },
                    onNeedParent = { goUp() },
                    initialSection = pendingSettingsTarget,
                    vm = sharedSettingsVm
                )
            }
        }
    }
}

/** 保存 Tab 历史。 */
private val TabHistorySaver: androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.snapshots.SnapshotStateList<String>, Any> =
    androidx.compose.runtime.saveable.listSaver(
        save = { it.toList() },
        restore = { mutableStateListOf<String>().apply { addAll(it) } }
    )
