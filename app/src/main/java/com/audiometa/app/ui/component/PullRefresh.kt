package com.audiometa.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 下拉刷新（「手指从上往下滑动 → 重新扫描」）。
 *
 * 用户要求：音频库的歌曲列表往下拉就重新扫描一遍，**上方出现一个刷新图标转圈**。
 *
 * ## 手感这一版改了什么（用户要求"优化刷新动画和手感"）
 *
 * 1. **不再每个滚动事件都 `launch` 一个协程**。以前 `onPostScroll` 里写
 *    `scope.launch { offset.snapTo(next) }`，一帧能排好几个协程，快速拖动时
 *    主线程被调度压力拖住，表现就是"下拉发涩、图标一顿一顿"。现在位移就是一个
 *    `mutableFloatState`，滚动回调里直接赋值（O(1)，无协程）；只有**松手**才启一个
 *    协程做回弹动画。
 * 2. **阻尼随距离递增**（越往下拉越拉不动），比固定 0.55 的线性阻尼更像原生。
 * 3. **过阈值时给一次触觉反馈**，并使用弹簧回弹（`spring`）而不是线性 `tween`，
 *    松手时"弹回去"的手感更自然。
 * 4. 指示器**随下拉距离淡入 / 放大**，过阈值后换成主色；刷新中持续旋转并停在顶部。
 *
 * 只接管"列表已经到顶还继续往下拉"的那部分位移 —— 正常滚动全部原样交给内容，
 * 所以不会出现列表滑不动或者和下拉打架的情况。
 *
 * @param refreshing 外部状态（这里是"曲库扫描是否在跑"）。它变 false 时指示器收起。
 * @param onRefresh 拉过阈值松手时调用一次。
 */
@Composable
fun PullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { THRESHOLD.toPx() }
    val maxPull = with(density) { MAX_PULL.toPx() }
    val indicatorGap = with(density) { 8.dp.toPx() }
    val indicatorSize = with(density) { 32.dp.toPx() }

    // 下拉位移（像素）。拖动时直接写这个状态，松手后才用动画收回。
    var pull by remember { mutableFloatStateOf(0f) }
    // 松手中的标志：松手动画期间忽略新的滚动增量，避免动画和手指打架。
    var settling by remember { mutableStateOf(false) }
    // 这一轮下拉是否已经因为"过阈值"震过（一次手势只震一次）。
    var armedHaptic by remember { mutableStateOf(false) }
    val refreshingNow by rememberUpdatedState(refreshing)
    val refreshAction by rememberUpdatedState(onRefresh)

    /** 把位移弹回 0。spring 只在这里跑，一次手势一次。 */
    fun settleBack(initialVelocity: Float = 0f) {
        if (settling) return
        settling = true
        scope.launch {
            try {
                animate(
                    initialValue = pull,
                    targetValue = 0f,
                    initialVelocity = initialVelocity,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { value, _ -> pull = value }
            } finally {
                pull = 0f
                settling = false
            }
        }
    }

    // 刷新结束：指示器收回去（刷新过程中 pull 已经是 0，内容不再跟着动）。
    LaunchedEffect(refreshing) {
        if (!refreshing && pull > 0f) settleBack()
    }

    val connection = remember(enabled) {
        object : NestedScrollConnection {
            /** 往下拉出来的位移要能原样收回去，否则会一直撑着一条空白。 */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || settling || available.y >= 0f || pull <= 0f) return Offset.Zero
                val next = (pull + available.y).coerceAtLeast(0f)
                val used = pull - next
                pull = next
                return Offset(0f, -used)
            }

            /**
             * 内容已经到顶、消费不掉的那部分往下位移，才拿来当"下拉"。
             *
             * 这里是**直接写状态**，不 launch 协程 —— 每个滚动事件排一个协程
             * 正是上一版手感发涩的原因（见类注释第 1 条）。
             */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!enabled || settling || refreshingNow || available.y <= 0f) return Offset.Zero
                // 只认手指拖动（不含惯性/侧效），否则列表一甩到底也会触发刷新。
                if (source != NestedScrollSource.Drag) return Offset.Zero
                val progress = (pull / maxPull).coerceIn(0f, 1f)
                // 阻尼随距离递减：前半程跟手，后半程越来越"重"。
                val damping = DRAG_DAMPING * (1f - RESISTANCE * progress)
                val next = (pull + available.y * damping).coerceAtMost(maxPull)
                pull = next
                // 刚跨过阈值：震一下，告诉用户"松手就刷新"。
                val armed = next >= threshold
                if (armed && !armedHaptic) {
                    armedHaptic = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else if (!armed && armedHaptic) {
                    armedHaptic = false
                }
                return Offset(0f, available.y)
            }

            /** 松手：过阈值就触发刷新，两种情况都把位移弹回 0。 */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!enabled || settling) return Velocity.Zero
                val past = pull >= threshold
                armedHaptic = false
                if (!refreshingNow) {
                    if (past) refreshAction()
                    // 松手速度作为初速度：快速下拉松手时回弹更"跟手"。
                    settleBack(initialVelocity = pull)
                }
                return Velocity.Zero
            }
        }
    }

    // 指示器的旋转：刷新时一直转；拉动时按比例先转半圈，让人知道"再拉一点就刷新"。
    val infinite = rememberInfiniteTransition(label = "pull-refresh")
    val spinning by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing)),
        label = "pull-refresh-spin"
    )
    val progress = (pull / threshold).coerceIn(0f, 1f)
    val angle = if (refreshingNow) spinning else progress * 180f
    // 指示器随下拉淡入 + 放大；过阈值（progress=1）时用主色。
    val visible = pull > 0f || refreshingNow
    val indicatorScale = if (progress >= 1f) 1f else 0.72f + 0.28f * progress

    Box(modifier.nestedScroll(connection)) {
        Box(Modifier.offset { IntOffset(0, pull.roundToInt()) }) {
            content()
        }
        if (visible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        // 平时跟着手指在内容上方；刷新时停在顶部不动。
                        val y = if (refreshingNow) indicatorGap
                        else (pull - indicatorSize - indicatorGap).coerceAtLeast(0f)
                        IntOffset(0, y.roundToInt())
                    }
                    .scale(indicatorScale)
                    .size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                shadowElevation = if (progress >= 1f) 6.dp else 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = if (refreshingNow) "正在重新扫描" else "下拉重新扫描",
                        tint = if (refreshingNow || progress >= 1f) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(19.dp).rotate(angle)
                    )
                }
            }
        }
    }
}

/** 拉多远算"要刷新"。 */
private val THRESHOLD = 64.dp

/** 最多能拉出多长（再多也不给拉了）。 */
private val MAX_PULL = 120.dp

/** 下拉阻尼：手指移动 1px，内容下移 0.55px（随距离递减，见 [RESISTANCE]）。 */
private const val DRAG_DAMPING = 0.55f

/** 越拉越沉的比例：拉到上限时阻尼只剩 `0.55 × (1 - 0.4)`。 */
private const val RESISTANCE = 0.4f
