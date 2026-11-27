package dev.remote.terminal.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remote.terminal.jarvis.api.JarvisState
import dev.remote.terminal.jarvis.coordinator.JarvisCoordinator
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 钢铁侠贾维斯式霓虹悬浮声纹 HUD (Jarvis Voiceprint HUD Overlay)。
 * 仅在检测到用户说话、意图处理、终端执行或语音播报时悬浮展示（后台静默倾听时不打扰用户）。
 * 边框采用由内向外渐变至完全透明的宽霓虹光晕，声纹曲线两端渐隐，中部高振幅脉冲，并支持左右划走关闭。
 */
@Composable
fun JarvisVoiceprintOverlay(
    modifier: Modifier = Modifier
) {
    val state by JarvisCoordinator.state.collectAsState()
    val subtitle by JarvisCoordinator.subtitle.collectAsState()

    val isWorking = when (state) {
        JarvisState.DISABLED -> false
        JarvisState.STANDBY, JarvisState.LISTENING -> subtitle.isNotBlank()
        JarvisState.PROCESSING, JarvisState.EXECUTING, JarvisState.SPEAKING -> true
    }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 100.dp.toPx() }
    val flingTargetPx = with(density) { 420.dp.toPx() }

    LaunchedEffect(isWorking) {
        if (!isWorking) {
            offsetX.snapTo(0f)
        }
    }

    AnimatedVisibility(
        visible = isWorking,
        enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 2 },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 2 },
        modifier = modifier
    ) {
        val dragAlpha = (1f - (abs(offsetX.value) / (dismissThresholdPx * 2.2f))).coerceIn(0.15f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            JarvisHudCard(
                state = state,
                subtitle = subtitle,
                modifier = Modifier
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .alpha(dragAlpha)
                    .pointerInput(isWorking) {
                        var totalDragDelta = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDragDelta = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDragDelta += dragAmount
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount)
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    if (abs(offsetX.value) > dismissThresholdPx) {
                                        val target = if (offsetX.value >= 0f) flingTargetPx else -flingTargetPx
                                        offsetX.animateTo(target, tween(180))
                                        JarvisCoordinator.dismissOverlay()
                                    } else {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            }
                        )
                    }
            )
        }
    }
}

@Composable
private fun JarvisHudCard(
    state: JarvisState,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val cardBackground = Brush.verticalGradient(
        listOf(
            Color(0xEB08101E),
            Color(0xF6030712)
        )
    )

    val glowWidthDp = 16.dp
    val cornerRadiusDp = 22.dp

    Box(
        modifier = modifier
            .widthIn(min = 250.dp, max = 370.dp)
            .padding(glowWidthDp)
            .drawBehind {
                val maxSpread = glowWidthDp.toPx()
                val baseCorner = cornerRadiusDp.toPx()
                val layers = 14
                for (i in 0 until layers) {
                    val t = i.toFloat() / (layers - 1)
                    val spread = t * maxSpread
                    // 由内向外平方衰减至 0（外缘完全透明）
                    val falloff = (1f - t) * (1f - t)
                    val alphaScale = falloff * 0.42f
                    val ringStrokeWidth = (maxSpread / layers) * 1.45f
                    val brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFF00F5FF).copy(alpha = alphaScale),
                            Color(0xFFFF007F).copy(alpha = alphaScale),
                            Color(0xFF7000FF).copy(alpha = alphaScale),
                            Color(0xFF00F5FF).copy(alpha = alphaScale)
                        )
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2f, size.height + spread * 2f),
                        cornerRadius = CornerRadius(baseCorner + spread, baseCorner + spread),
                        style = Stroke(width = ringStrokeWidth)
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadiusDp))
                .background(cardBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 顶部状态指示徽章 (Status Badge)
            HudStatusBadge(state)

            // 2. 霓虹声纹动态曲线 (两端渐变透明，中部大振幅)
            NeonWaveformCanvas(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )

            // 3. 底部文字输出与字幕 (Subtitle Text Output)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color(0xFFF1F5F9),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HudStatusBadge(state: JarvisState) {
    val (statusText, dotColor) = when (state) {
        JarvisState.SPEAKING -> "JARVIS • 正在耳语播报" to Color(0xFFFF007F)
        JarvisState.LISTENING -> "JARVIS • 倾听中" to Color(0xFF00F5FF)
        JarvisState.PROCESSING -> "JARVIS • 意图解析中" to Color(0xFFFFD600)
        JarvisState.EXECUTING -> "JARVIS • 调度终端任务" to Color(0xFF00E676)
        JarvisState.STANDBY -> "JARVIS • 待命" to Color(0xFF00F5FF)
        JarvisState.DISABLED -> "JARVIS • 已休眠" to Color.Gray
    }

    val transition = rememberInfiniteTransition(label = "badge_pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dotAlpha))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusText,
            color = dotColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * 霓虹色彩声纹曲线绘制器。
 * 叠加多层不同波长与相位的正弦波，呈现类似钢铁侠贾维斯 HUD 交互界面的声纹能量效果。
 */
@Composable
private fun NeonWaveformCanvas(
    state: JarvisState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")

    // 动画相位推进
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    JarvisState.SPEAKING -> 1000
                    JarvisState.LISTENING -> 1400
                    JarvisState.PROCESSING -> 800
                    else -> 2200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // 动态振幅因子 (根据工作状态动态放大)
    val ampMultiplier = when (state) {
        JarvisState.SPEAKING -> 1.0f
        JarvisState.LISTENING -> 0.75f
        JarvisState.PROCESSING -> 0.45f
        JarvisState.EXECUTING -> 0.6f
        else -> 0.25f
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // 1. 底层参考基线微弱霓虹辉光
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(
                    Color.Transparent,
                    Color(0x3300F5FF),
                    Color(0x66FF007F),
                    Color(0x3300F5FF),
                    Color.Transparent
                )
            ),
            start = androidx.compose.ui.geometry.Offset(0f, centerY),
            end = androidx.compose.ui.geometry.Offset(width, centerY),
            strokeWidth = 1.dp.toPx()
        )

        // 2. 第一层：霓虹电光青波 (主基频波，中部大振幅，两端透明)
        val pathCyan = Path()
        val baseAmp1 = (height * 0.48f) * ampMultiplier
        val freq1 = 2.4f

        for (x in 0..width.toInt() step 2) {
            val progress = x / width
            // 中部聚焦包络：两端平滑归零，中部区域振幅显著增强
            val envelope = sin(progress * PI).pow(1.75).toFloat()
            val angle = (progress * freq1 * 2 * PI + phase).toFloat()
            val y = centerY + sin(angle) * baseAmp1 * envelope

            if (x == 0) pathCyan.moveTo(x.toFloat(), y) else pathCyan.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathCyan,
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.18f to Color(0xFF00E5FF),
                0.50f to Color(0xFF00FFC2),
                0.82f to Color(0xFF00B0FF),
                1.0f to Color.Transparent
            ),
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 3. 第二层：霓虹品红波 (谐波，两端透明，中部高亮)
        val pathMagenta = Path()
        val baseAmp2 = (height * 0.41f) * ampMultiplier
        val freq2 = 3.6f
        val phaseOffset2 = (PI * 0.65).toFloat()

        for (x in 0..width.toInt() step 2) {
            val progress = x / width
            val envelope = sin(progress * PI).pow(1.75).toFloat()
            val angle = (progress * freq2 * 2 * PI - phase + phaseOffset2).toFloat()
            val y = centerY + sin(angle) * baseAmp2 * envelope

            if (x == 0) pathMagenta.moveTo(x.toFloat(), y) else pathMagenta.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathMagenta,
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.18f to Color(0xFFFF2A85),
                0.50f to Color(0xFFFF007F),
                0.82f to Color(0xFFFF77A9),
                1.0f to Color.Transparent
            ),
            style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 4. 第三层：电光紫高频微波 (两端透明，增加中间脉冲层次)
        val pathPurple = Path()
        val baseAmp3 = (height * 0.30f) * ampMultiplier
        val freq3 = 5.2f
        val phaseOffset3 = (PI * 1.3).toFloat()

        for (x in 0..width.toInt() step 3) {
            val progress = x / width
            val envelope = sin(progress * PI).pow(1.75).toFloat()
            val angle = (progress * freq3 * 2 * PI + phase * 1.4f + phaseOffset3).toFloat()
            val y = centerY + sin(angle) * baseAmp3 * envelope

            if (x == 0) pathPurple.moveTo(x.toFloat(), y) else pathPurple.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathPurple,
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.20f to Color(0xFF7C4DFF),
                0.50f to Color(0xFF651FFF),
                0.80f to Color(0xFFB388FF),
                1.0f to Color.Transparent
            ),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
