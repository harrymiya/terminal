package dev.remote.terminal.jarvis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remote.terminal.jarvis.api.JarvisState
import dev.remote.terminal.jarvis.coordinator.JarvisCoordinator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 钢铁侠贾维斯式霓虹悬浮声纹 HUD (Jarvis Voiceprint HUD Overlay)。
 * 位于屏幕下方中部悬浮，展示多层霓虹色彩动态正弦波浪声纹曲线，
 * 说话时联动马达微震，并在波形下方实时展示管家字幕与状态输出。
 */
@Composable
fun JarvisVoiceprintOverlay(
    modifier: Modifier = Modifier
) {
    val state by JarvisCoordinator.state.collectAsState()
    val subtitle by JarvisCoordinator.subtitle.collectAsState()

    val isWorking = state != JarvisState.DISABLED && (state != JarvisState.STANDBY || subtitle.isNotBlank())

    AnimatedVisibility(
        visible = isWorking,
        enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 2 },
        exit = fadeOut(tween(350)) + slideOutVertically(tween(350)) { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            JarvisHudCard(
                state = state,
                subtitle = subtitle
            )
        }
    }
}

@Composable
private fun JarvisHudCard(
    state: JarvisState,
    subtitle: String
) {
    val neonBorderBrush = Brush.horizontalGradient(
        listOf(
            Color(0xFF00F5FF), // 霓虹青
            Color(0xFFFF007F), // 霓虹品红
            Color(0xFF7000FF), // 霓虹电光紫
            Color(0xFF00F5FF)
        )
    )

    val cardBackground = Brush.verticalGradient(
        listOf(
            Color(0xEE08101E),
            Color(0xFA030712)
        )
    )

    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 360.dp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(22.dp), spotColor = Color(0xFF00E5FF), ambientColor = Color(0xFFFF007F))
            .clip(RoundedCornerShape(22.dp))
            .background(cardBackground)
            .border(BorderStroke(1.2.dp, neonBorderBrush), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 顶部状态指示徽章 (Status Badge)
        HudStatusBadge(state)

        // 2. 霓虹声纹动态曲线 (Neon Voiceprint Curves Canvas)
        NeonWaveformCanvas(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
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

        // 2. 第一层：霓虹电光青波 (主基频波)
        val pathCyan = Path()
        val baseAmp1 = (height * 0.38f) * ampMultiplier
        val freq1 = 2.4f

        for (x in 0..width.toInt() step 2) {
            val progress = x / width
            // 两端施加正弦包络，使波浪平滑归零于左右边缘
            val envelope = sin(progress * PI).toFloat()
            val angle = (progress * freq1 * 2 * PI + phase).toFloat()
            val y = centerY + sin(angle) * baseAmp1 * envelope

            if (x == 0) pathCyan.moveTo(x.toFloat(), y) else pathCyan.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathCyan,
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF00E5FF), Color(0xFF00FFC2), Color(0xFF00B0FF))
            ),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 3. 第二层：霓虹品红波 (谐波，反向相位偏移)
        val pathMagenta = Path()
        val baseAmp2 = (height * 0.32f) * ampMultiplier
        val freq2 = 3.6f
        val phaseOffset2 = (PI * 0.65).toFloat()

        for (x in 0..width.toInt() step 2) {
            val progress = x / width
            val envelope = sin(progress * PI).toFloat()
            val angle = (progress * freq2 * 2 * PI - phase + phaseOffset2).toFloat()
            val y = centerY + sin(angle) * baseAmp2 * envelope

            if (x == 0) pathMagenta.moveTo(x.toFloat(), y) else pathMagenta.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathMagenta,
            brush = Brush.horizontalGradient(
                listOf(Color(0xFFFF2A85), Color(0xFFFF007F), Color(0xFFFF77A9))
            ),
            style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 4. 第三层：电光紫高频微波 (增加科技细腻质感)
        val pathPurple = Path()
        val baseAmp3 = (height * 0.22f) * ampMultiplier
        val freq3 = 5.2f
        val phaseOffset3 = (PI * 1.3).toFloat()

        for (x in 0..width.toInt() step 3) {
            val progress = x / width
            val envelope = sin(progress * PI).toFloat()
            val angle = (progress * freq3 * 2 * PI + phase * 1.4f + phaseOffset3).toFloat()
            val y = centerY + sin(angle) * baseAmp3 * envelope

            if (x == 0) pathPurple.moveTo(x.toFloat(), y) else pathPurple.lineTo(x.toFloat(), y)
        }

        drawPath(
            path = pathPurple,
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF7C4DFF), Color(0xFF651FFF), Color(0xFFB388FF))
            ),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
