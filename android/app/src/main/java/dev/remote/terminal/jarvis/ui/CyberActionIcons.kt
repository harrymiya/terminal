package dev.remote.terminal.jarvis.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.remote.terminal.jarvis.api.JarvisState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 赛博六边形代码棱镜 Agent 图标（替换老式机器人图标）：
 * 棱角切面六边形芯片框 + 命令行尖角 `>` + 光标 `_` + 右上角四芒星 AI 节点。
 */
val AgentCyberVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "AgentCyberPrism",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 外框：锐利切角六边形科技芯片轮廓
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2.3f)
            lineTo(20.1f, 6.9f)
            lineTo(20.1f, 17.1f)
            lineTo(12f, 21.7f)
            lineTo(3.9f, 17.1f)
            lineTo(3.9f, 6.9f)
            close()
        }
        // 左右赛博引脚微刻线
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.45f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(1.8f, 10.2f)
            lineTo(3.8f, 10.2f)
            moveTo(1.8f, 13.8f)
            lineTo(3.8f, 13.8f)
            moveTo(20.2f, 10.2f)
            lineTo(22.2f, 10.2f)
            moveTo(20.2f, 13.8f)
            lineTo(22.2f, 13.8f)
        }
        // 内部代码指令箭头 >
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.8f, 9.3f)
            lineTo(11.3f, 12.1f)
            lineTo(7.8f, 14.9f)
        }
        // 底部代码光标 _
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(12.6f, 15.1f)
            lineTo(16.2f, 15.1f)
        }
        // 右上方四芒星 AI 节点（实心棱星）
        path(fill = SolidColor(Color.White)) {
            moveTo(15.2f, 7.2f)
            lineTo(15.85f, 9.05f)
            lineTo(17.7f, 9.7f)
            lineTo(15.85f, 10.35f)
            lineTo(15.2f, 12.2f)
            lineTo(14.55f, 10.35f)
            lineTo(12.7f, 9.7f)
            lineTo(14.55f, 9.05f)
            close()
        }
    }.build()
}

/**
 * Jarvis 首页顶部快捷开关按钮（圆形方舟反应堆 / 轨道全息核心风格，与 Agent 棱角风格完全区分）。
 * - 开启时：青荷霓虹光环高亮显示，核心点亮；
 * - 关闭时：去高亮暗淡态，外环熄灭并带轻斜线休眠标识。
 */
@Composable
fun JarvisQuickToggleButton(
    enabled: Boolean,
    jarvisState: JarvisState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 21.dp,
) {
    val activeAccent = when {
        !enabled -> Color(0xFF637384)
        jarvisState == JarvisState.LISTENING -> Color(0xFFFF6E7A)
        jarvisState == JarvisState.PROCESSING -> Color(0xFF72B5FF)
        else -> Color(0xFF00F0FF)
    }
    val secondaryMint = Color(0xFF83E2BD)

    val ringColor by animateColorAsState(
        targetValue = if (enabled) activeAccent else Color(0xFF647486),
        animationSpec = tween(220),
        label = "jarvisRingColor"
    )
    val bgColor by animateColorAsState(
        targetValue = if (enabled) activeAccent.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(220),
        label = "jarvisBgColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) activeAccent.copy(alpha = 0.72f) else Color.Transparent,
        animationSpec = tween(220),
        label = "jarvisBorderColor"
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .then(
                if (enabled) Modifier.border(BorderStroke(1.dp, borderColor), CircleShape)
                else Modifier
            )
            .semantics {
                contentDescription = if (enabled) "关闭 Jarvis 语音助手" else "开启 Jarvis 语音助手"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val center = Offset(cx, cy)

            if (enabled) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(activeAccent.copy(alpha = 0.34f), Color.Transparent),
                        center = center,
                        radius = w * 0.56f
                    ),
                    radius = w * 0.56f,
                    center = center
                )
            }

            // 外圈三段式全息弧（Arc Reactor Segmented Ring）
            val outerR = w * 0.43f
            val arcTopLeft = Offset(cx - outerR, cy - outerR)
            val arcSize = Size(outerR * 2f, outerR * 2f)
            val arcStroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
            val startAngles = listOf(-68f, 52f, 172f)
            for (angle in startAngles) {
                drawArc(
                    color = ringColor,
                    startAngle = angle,
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = arcStroke
                )
            }

            // 外环径向微型刻度点（增强精密 HUD 感）
            val tickInner = outerR * 0.90f
            val tickOuter = outerR * 1.08f
            for (deg in listOf(-90f, 30f, 150f)) {
                val rad = Math.toRadians(deg.toDouble())
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                drawLine(
                    color = if (enabled) secondaryMint else ringColor.copy(alpha = 0.65f),
                    start = Offset(cx + tickInner * cosA, cy + tickInner * sinA),
                    end = Offset(cx + tickOuter * cosA, cy + tickOuter * sinA),
                    strokeWidth = w * 0.065f,
                    cap = StrokeCap.Round
                )
            }

            // 内圈能量环
            val innerR = w * 0.24f
            drawCircle(
                color = if (enabled) secondaryMint.copy(alpha = 0.9f) else ringColor.copy(alpha = 0.7f),
                radius = innerR,
                center = center,
                style = Stroke(width = w * 0.065f)
            )

            if (enabled) {
                // 点亮的反应堆菱晶核心
                val coreR = w * 0.135f
                val corePath = Path().apply {
                    moveTo(cx, cy - coreR)
                    lineTo(cx + coreR * 0.88f, cy)
                    lineTo(cx, cy + coreR)
                    lineTo(cx - coreR * 0.88f, cy)
                    close()
                }
                drawPath(
                    path = corePath,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFFFFF), activeAccent, secondaryMint)
                    )
                )
            } else {
                // 未开启：极简熄灭核心点 + 轻斜线休眠标识
                drawCircle(
                    color = ringColor.copy(alpha = 0.55f),
                    radius = w * 0.06f,
                    center = center
                )
                drawLine(
                    color = Color(0xFF0A0F15),
                    start = Offset(w * 0.22f, h * 0.22f),
                    end = Offset(w * 0.78f, h * 0.78f),
                    strokeWidth = w * 0.14f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = ringColor.copy(alpha = 0.85f),
                    start = Offset(w * 0.22f, h * 0.22f),
                    end = Offset(w * 0.78f, h * 0.78f),
                    strokeWidth = w * 0.075f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
