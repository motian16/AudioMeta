package com.audiometa.app.ui.component

import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

/**
 * 可拖动的底部窗口。
 *
 * 为什么不用 Material 的 ModalBottomSheet：
 *
 * 1. 它的遮罩画不到状态栏——所在窗口默认让出系统栏，状态栏那一条依然透出底下的封面，
 *    看起来"没盖住"，很割裂。这里把 Dialog 窗口设成 decorFitsSystemWindows=false，
 *    遮罩就铺满整块屏幕，包含状态栏和导航栏。
 * 2. 需要"自由拖动 + 多吸附点"，Material 的 sheet 只有固定几个 state，拖到中间会弹回去。
 *
 * 拖动为什么不卡：
 * 窗口高度**全程固定为满屏**，拖动只改内容层的 [graphicsLayer] 位移和高度，
 * 而且位移直接用 `translationY = L - top` 这个恒等式化简掉了（见 [SheetDragHandle.onDelta]）。
 * 早期版本是每帧改窗口高度，那会触发整窗 relayout，手指跟不跟得上全看运气，手感极差。
 *
 * 交互约定：
 * - 打开后停在 [initialFraction]；
 * - 抓拖拽条或标题栏可以自由上下移动；
 * - 上提越过 [hintFraction] 轻微震动一下（提示"再往上就是全屏"），过 86% 吸附全屏；
 * - 松手在中间回 [initialFraction]；往下拖过 42% 按原来的逻辑关闭；
 * - 点窗口外面关闭；
 * - 窗口以外的整个屏幕（含状态栏）被暗色遮罩盖住。
 */
@Composable
fun BottomSheetScaffold(
    onDismiss: () -> Unit,
    /** 初始高度占屏幕的比例，0..1。 */
    initialFraction: Float = 0.5f,
    /** 上提到这个比例时给一次触觉反馈。 */
    hintFraction: Float = 0.7f,
    /**
     * 往下拖到这个比例以下就关闭。
     *
     * 独立成一个参数是为了让"内容本来就不多"的窗口能开得更矮（例如刮削窗口），
     * 否则会被 0.42 这条公共下沿顶住，窗口再矮就直接变成"一拖就关"。
     */
    dismissFraction: Float = 0.42f,
    /** 遮罩颜色，默认是半透明黑。 */
    scrimColor: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
    /** 附加在内容层上的修饰符（歌词窗口在这里挂"滚到顶再上滑就关闭"）。 */
    contentModifier: Modifier = Modifier,
    content: @Composable BoxScope.(dragHandle: SheetDragHandle) -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // 窗口高度在像素上的值。拖动过程中要拿它做换算。
    var screenHeightPx by remember { mutableStateOf(0) }

    /**
     * 悬浮状态：当前高度占屏幕的比例。
     * 初始给一个极小的值，再用动画推到 [initialFraction]，这样打开时有"升起"的过程
     * （Dialog 自带动画被关掉了，入场退场都用这一套数值动画，拖动和吸附是同一套物理，
     * 不会出现"先跳一下再动画"的割裂感）。
     */
    val fraction = remember { Animatable(0.001f) }
    val scrimAlpha = remember { Animatable(0f) }

    val target = initialFraction.coerceIn(0.2f, 1f)

    LaunchedEffect(Unit) {
        scrimAlpha.animateTo(1f)
        fraction.animateTo(target, SNAP_SPEC)
    }

    val hinted = remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    val handle = remember {
        SheetDragHandle(
            onStart = { dragging = true },
            onDelta = { deltaY ->
                if (screenHeightPx <= 0) return@SheetDragHandle
                // 手指往下 deltaY 为正 → 窗口变矮。
                val deltaFraction = deltaY / screenHeightPx
                val next = (fraction.value - deltaFraction).coerceIn(0.08f, 1f)
                scope.launch { fraction.snapTo(next) }
                if (!hinted.value && next >= hintFraction) {
                    hinted.value = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } else if (hinted.value && next < hintFraction - 0.05f) {
                    hinted.value = false
                }
            },
            onEnd = {
                dragging = false
                when {
                    // 往下拖过下沿：按原来的逻辑关闭。
                    fraction.value < dismissFraction -> {
                        scope.launch {
                            scrimAlpha.animateTo(0f, tweenFast())
                            fraction.animateTo(0.001f, tweenFast())
                            onDismiss()
                        }
                    }
                    // 拖到很靠上：吸附全屏。
                    fraction.value > 0.86f -> scope.launch { fraction.animateTo(1f, SNAP_SPEC) }
                    // 中间：回到初始高度。
                    else -> scope.launch { fraction.animateTo(target, SNAP_SPEC) }
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 窗口必须边到边，否则遮罩到不了状态栏。放在内容之前设置，
        // 让它在这一帧的测量之前生效。
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) { dialogWindow?.let { configureEdgeToEdge(it) } }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { screenHeightPx = it.height }
        ) {
            // 遮罩：铺满整屏，包含状态栏。点它关闭窗口。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        scrimColor.copy(alpha = scrimColor.alpha * scrimAlpha.value.coerceIn(0f, 1f))
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )

            // 悬浮窗口。高度用动画值算，拖动期间每帧只重组这一个节点；
            // translationY 把"底部对齐"换算成位移：窗口顶边在 0，要让底边贴屏底，
            // 就得整体下移 (屏高 - 窗口高)。用位移而不是改顶部 padding，
            // 是为了避免每帧触发额外测量。
            val sheetHeightPx = with(density) { (screenHeight * fraction.value).toPx() }
            val offsetPx = screenHeightPx - sheetHeightPx
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height((screenHeight * fraction.value).coerceAtLeast(1.dp))
                    .graphicsLayer { translationY = offsetPx },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 顶部的小横杠：专门用来抓。
                    SheetDragBar(handle)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(contentModifier)
                    ) { content(handle) }
                }
            }
        }
    }
}

/**
 * 拖拽回调。
 *
 * 打包成一个对象，让"跟着手指动的区域"（拖拽条、标题栏）一次性拿到三个回调，
 * 不用逐层传 lambda。需要自己滚动的列表**不要**挂它，否则列表就没法滚了。
 */
class SheetDragHandle(
    internal val onStart: () -> Unit = {},
    internal val onDelta: (Float) -> Unit,
    internal val onEnd: () -> Unit = {}
) {
    /**
     * 给任意可组合项挂上垂直拖动能力。
     *
     * 用 [detectDragGestures] 的"过滑移阈值才接管"特性：轻点不会被当成拖动，
     * 竖直方向移动一点点才开始跟手，横划不抢（交给 pager / 手势返回）。
     */
    fun Modifier.draggable(): Modifier = this.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
            onDrag = { change, delta ->
                change.consume()
                onDelta(delta.y)
            }
        )
    }
}

/** 顶部那条小横杠。 */
@Composable
private fun SheetDragBar(handle: SheetDragHandle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .then(handle.run { Modifier.draggable() }),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        )
    }
}

/** 吸附用的弹性：不回弹、偏软的刚度，收尾看起来是"滑过去"而不是"弹过去"。 */
private val SNAP_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

private fun tweenFast() = androidx.compose.animation.core.tween<Float>(durationMillis = 160)

/**
 * 让 Dialog 窗口也边到边。
 *
 * 不设这个的话，系统会给 Dialog 加一层系统栏内边距，遮罩就盖不住状态栏。
 * 同时把系统自带的暗化关掉——遮罩是自己画的，两层叠起来会太黑。
 */
private fun configureEdgeToEdge(window: Window) {
    runCatching {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setDimAmount(0f)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }
}
