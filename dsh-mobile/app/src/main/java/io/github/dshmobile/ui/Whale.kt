package io.github.dshmobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

/**
 * 肥鱼：Q 版简笔鲸鱼。
 *
 * 为什么用 Canvas 画而不是放图片：
 *   - 任意尺寸都清晰（24dp 的小头像到 200dp 的空状态大图共用一份代码）
 *   - 换配色只改一个参数
 *   - 体积为零，不用塞 png
 *
 * 形状继承官方蓝鲸 Logo 的三个特征：跃起的弧线身体、圆钝有重量感、水花。
 * 但换成拟人的正面站姿：圆头 + 单眼 + 两片鳍当手 + 尾巴。
 */
@Composable
fun Whale(
    modifier: Modifier = Modifier,
    color: Color = Palette.Accent,
    accent: Color = Palette.AccentWarm,
    /** 呼吸动画：加载态用 true，静态展示用 false */
    animated: Boolean = false,
    /** 眨眼：偶尔闭眼，让它显得活着 */
    angleDeg: Float = 0f,
) {
    val float = if (animated) {
        val transition = rememberInfiniteTransition(label = "whaleFloat")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "float"
        )
        v
    } else 0f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bob = sin(float * Math.PI).toFloat() * h * 0.02f
        drawWhale(w, h, bob, color, accent)
    }
}

private fun DrawScope.drawWhale(w: Float, h: Float, bob: Float, color: Color, accent: Color) {
    val cx = w / 2f
    val cy = h / 2f + bob
    val unit = minOf(w, h)

    // ── 身体：一个略扁的圆，下端收窄成锥形接尾巴 ──
    val bodyW = unit * 0.62f
    val bodyH = unit * 0.56f
    val bodyTop = cy - bodyH * 0.55f

    val body = Path().apply {
        // 从左中开始，顺时针画
        moveTo(cx - bodyW / 2f, cy - bodyH * 0.05f)
        // 头顶圆弧
        cubicTo(
            cx - bodyW / 2f, bodyTop,
            cx + bodyW / 2f, bodyTop,
            cx + bodyW / 2f, cy - bodyH * 0.05f
        )
        // 右侧下行，收成尾巴根部
        cubicTo(
            cx + bodyW / 2f, cy + bodyH * 0.30f,
            cx + bodyW * 0.16f, cy + bodyH * 0.46f,
            cx + bodyW * 0.10f, cy + bodyH * 0.50f
        )
        // 底部
        cubicTo(
            cx, cy + bodyH * 0.54f,
            cx - bodyW * 0.16f, cy + bodyH * 0.46f,
            cx - bodyW * 0.10f, cy + bodyH * 0.50f
        )
        // 左侧回升
        cubicTo(
            cx - bodyW / 2f, cy + bodyH * 0.30f,
            cx - bodyW / 2f, cy + bodyH * 0.10f,
            cx - bodyW / 2f, cy - bodyH * 0.05f
        )
        close()
    }
    drawPath(body, color)

    // ── 肚子：浅色椭圆，压在身体下半 ──
    drawOval(
        color = Color.White.copy(alpha = 0.16f),
        topLeft = Offset(cx - bodyW * 0.26f, cy + bodyH * 0.02f),
        size = Size(bodyW * 0.52f, bodyH * 0.40f)
    )

    // ── 尾巴：两片，往右上翘 ──
    val tailBaseX = cx + bodyW * 0.09f
    val tailBaseY = cy + bodyH * 0.48f
    val tail = Path().apply {
        moveTo(tailBaseX, tailBaseY)
        cubicTo(
            tailBaseX + unit * 0.10f, tailBaseY + unit * 0.02f,
            tailBaseX + unit * 0.16f, tailBaseY - unit * 0.06f,
            tailBaseX + unit * 0.20f, tailBaseY - unit * 0.10f
        )
        // 上叶
        cubicTo(
            tailBaseX + unit * 0.17f, tailBaseY - unit * 0.13f,
            tailBaseX + unit * 0.10f, tailBaseY - unit * 0.10f,
            tailBaseX + unit * 0.04f, tailBaseY - unit * 0.02f
        )
        close()
    }
    drawPath(tail, accent)

    val tail2 = Path().apply {
        moveTo(tailBaseX, tailBaseY)
        cubicTo(
            tailBaseX + unit * 0.10f, tailBaseY + unit * 0.02f,
            tailBaseX + unit * 0.15f, tailBaseY + unit * 0.08f,
            tailBaseX + unit * 0.18f, tailBaseY + unit * 0.12f
        )
        cubicTo(
            tailBaseX + unit * 0.14f, tailBaseY + unit * 0.13f,
            tailBaseX + unit * 0.08f, tailBaseY + unit * 0.08f,
            tailBaseX + unit * 0.03f, tailBaseY + unit * 0.02f
        )
        close()
    }
    drawPath(tail2, accent)

    // ── 两只鳍当手，从身体两侧伸出来 ──
    val fin = Path().apply {
        moveTo(cx - bodyW * 0.44f, cy + bodyH * 0.10f)
        cubicTo(
            cx - bodyW * 0.60f, cy + bodyH * 0.16f,
            cx - bodyW * 0.62f, cy + bodyH * 0.32f,
            cx - bodyW * 0.48f, cy + bodyH * 0.34f
        )
        cubicTo(
            cx - bodyW * 0.40f, cy + bodyH * 0.30f,
            cx - bodyW * 0.38f, cy + bodyH * 0.18f,
            cx - bodyW * 0.44f, cy + bodyH * 0.10f
        )
        close()
    }
    drawPath(fin, color.copy(alpha = 0.92f))

    val finR = Path().apply {
        moveTo(cx + bodyW * 0.40f, cy + bodyH * 0.10f)
        cubicTo(
            cx + bodyW * 0.56f, cy + bodyH * 0.16f,
            cx + bodyW * 0.58f, cy + bodyH * 0.32f,
            cx + bodyW * 0.44f, cy + bodyH * 0.34f
        )
        cubicTo(
            cx + bodyW * 0.36f, cy + bodyH * 0.30f,
            cx + bodyW * 0.34f, cy + bodyH * 0.18f,
            cx + bodyW * 0.40f, cy + bodyH * 0.10f
        )
        close()
    }
    drawPath(finR, color.copy(alpha = 0.92f))

    // ── 眼睛：左右各一只，白色底 + 深色瞳孔 ──
    val eyeY = cy - bodyH * 0.12f
    val eyeR = unit * 0.055f
    listOf(cx - bodyW * 0.17f, cx + bodyW * 0.17f).forEach { ex ->
        drawCircle(Color.White.copy(alpha = 0.95f), eyeR, Offset(ex, eyeY))
        drawCircle(Color(0xFF15151A), eyeR * 0.55f, Offset(ex, eyeY))
        // 高光
        drawCircle(Color.White, eyeR * 0.22f, Offset(ex - eyeR * 0.22f, eyeY - eyeR * 0.28f))
    }

    // ── 头顶喷水：三颗小水珠 ──
    val spoutX = cx
    val spoutY = cy - bodyH * 0.50f
    val dropR = unit * 0.030f
    drawCircle(accent.copy(alpha = 0.85f), dropR, Offset(spoutX, spoutY - unit * 0.02f))
    drawCircle(accent.copy(alpha = 0.65f), dropR * 0.8f, Offset(spoutX - unit * 0.045f, spoutY - unit * 0.06f))
    drawCircle(accent.copy(alpha = 0.65f), dropR * 0.8f, Offset(spoutX + unit * 0.045f, spoutY - unit * 0.06f))

    // ── 微笑：一小段弧 ──
    val smile = Path().apply {
        moveTo(cx - unit * 0.045f, cy + bodyH * 0.14f)
        quadraticTo(
            cx, cy + bodyH * 0.20f,
            cx + unit * 0.045f, cy + bodyH * 0.14f
        )
    }
    drawPath(smile, Color(0xFF15151A).copy(alpha = 0.55f), style = Stroke(width = unit * 0.012f))
}
