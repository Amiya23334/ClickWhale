package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一条消息的气泡。
 *
 * 布局决策：
 *   - 用户消息靠右、宽不超过 82%，气泡色 SurfaceHigh（更亮，像被点亮）
 *   - 助手消息靠左、宽不超过 92%（要放代码块，需要更多横向空间），气泡色 Surface
 *   - 宽度上限用屏宽比例（fillMaxWidth(fraction)），不要写死 dp：
 *     340dp 在手机上会顶到边、在平板上又太窄。代价是短消息不会自动收紧到内容宽，
 *     这是有意取舍——按内容收缩要 onTextLayout 量宽再回写约束，会多一帧抖动。
 *   - 助手消息不画头像（正文区每行都重复一个 24dp 圆太吵）；改成流式输出的头部
 *     显示一个小鲸鱼，只在第一条出现
 */
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    /** 流式输出中：末尾加光标 */
    streaming: Boolean = false,
    /** 折叠时的摘要行（工具调用这类） */
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    /** 图片附件的 `sha256:` id；非空时在正文下方铺图 */
    attachmentIds: List<String> = emptyList(),
    /** 中转地址（手机可访问的 http://ip:port），用来拼图片 URL */
    relayBase: String = "",
    onOpenImage: ((String) -> Unit)? = null,
    /** 可保存的文件附件；非空时在图片下面再画一排 chip */
    files: List<EventMapper.FileAttachment> = emptyList(),
) {
    val maxWidthFactor = if (isUser) 0.82f else 0.92f
    // 事件里的 text 经常带前导换行（模型输出、拼接残留都会留），
    // 不 trim 的话气泡顶部会空出一大块（实测截图里空了 100+ px）。
    val body = text.trim()
    // 用 BoxWithConstraints 拿到这一行的实际可用宽度，再按比例算出封顶宽度。
    // 直接 fillMaxWidth(fraction) 会把**短消息也拉满**（"好"变成一个大方块）；
    // 写死 340dp 又会在手机上顶边、平板上太窄。widthIn(max=实测宽度×比例) 两头都对。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val cap = maxWidth * maxWidthFactor
        Column(
            modifier = Modifier
                .widthIn(max = cap)
                .background(
                    color = if (isUser) Palette.SurfaceHigh else Palette.Surface,
                    shape = RoundedCornerShape(
                        topStart = Dims.bubbleRadius,
                        topEnd = Dims.bubbleRadius,
                        bottomStart = if (isUser) Dims.bubbleRadius else 4.dp,
                        bottomEnd = if (isUser) 4.dp else Dims.bubbleRadius,
                    )
                )
                .then(
                    if (onToggleCollapse != null) Modifier.clickable { onToggleCollapse() }
                    else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (collapsed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "▸",
                        color = Palette.TextTertiary,
                        fontSize = Dims.metaSize
                    )
                    Text(
                        text = text,
                        color = Palette.TextSecondary,
                        fontSize = Dims.metaSize
                    )
                }
            } else {
                SelectionContainer {
                    if (looksLikeCode(body)) {
                        CodeBlock(body)
                    } else if (body.isNotEmpty()) {
                        Text(
                            text = if (streaming) "$body▍" else body,
                            color = if (isUser) Palette.TextPrimary else Palette.TextPrimary,
                            fontSize = Dims.bodySize,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }
            // 图片放在正文下方：文字是「说了什么」，图是「附了什么」，
            // 先文后图符合阅读顺序；图片独占一行铺满气泡宽，不受文字窄宽限制。
            if (attachmentIds.isNotEmpty()) {
                if (relayBase.isNotBlank()) {
                    MessageImages(
                        attachmentIds = attachmentIds,
                        relayBase = relayBase,
                        onOpen = onOpenImage,
                        modifier = Modifier.padding(top = if (body.isNotEmpty()) 8.dp else 0.dp),
                    )
                } else {
                    // 拿不到中转地址（还没连上）时不要静默丢图，给一个可辨认的占位。
                    ImagePlaceholder(
                        count = attachmentIds.size,
                        modifier = Modifier.padding(top = if (body.isNotEmpty()) 8.dp else 0.dp),
                    )
                }
            }
            // 文件附件：点了存到手机「下载」。跟图片一样是「附了什么」，放正文后面。
            if (files.isNotEmpty()) {
                FileAttachmentRow(
                    files = files,
                    relayBase = relayBase,
                    modifier = Modifier.padding(top = if (body.isNotEmpty() || attachmentIds.isNotEmpty()) 8.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * 一排可点的文件 chip。点击经中转拉字节，写进系统「下载」集合。
 * 下载状态是**每个 chip 自己的**（用 remember(file.id)），不会互相连坐。
 */
@Composable
private fun FileAttachmentRow(
    files: List<EventMapper.FileAttachment>,
    relayBase: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = modifier) {
        files.forEach { f ->
            var busy by androidx.compose.runtime.remember(f.id) { androidx.compose.runtime.mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Palette.SurfaceHigh, RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy && relayBase.isNotBlank()) {
                        if (relayBase.isBlank()) return@clickable
                        busy = true
                        val url = relayBase.trimEnd('/') + "/relay/attachment/" +
                            java.net.URLEncoder.encode(f.id, "UTF-8")
                        io.github.dshmobile.Downloads.save(context, url, f.name) { res ->
                            busy = false
                            res.onSuccess {
                                android.widget.Toast.makeText(
                                    context, "已存到手机下载：${f.name}", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context, "下载失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = if (busy) "⏳" else "📄", fontSize = 15.sp)
                Box(modifier = Modifier.size(8.dp))
                Text(
                    text = f.name,
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (busy) "下载中" else "保存",
                    color = if (busy) Palette.TextTertiary else Palette.Accent,
                    fontSize = Dims.metaSize,
                )
            }
        }
    }
}

/**
 * 代码块：单色底 + 等宽字。
 * 不做语法高亮——手机上滚动时代码重着色会掉帧，而且高亮库是几百 KB 的依赖。
 */
@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.CodeBg, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = code,
            color = Palette.TextPrimary,
            fontSize = Dims.codeSize,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
        )
    }
}

/** 极简判断：三反引号开头，或者超过 60% 的行以缩进/符号开头 */
private fun looksLikeCode(text: String): Boolean {
    val t = text.trim()
    if (t.startsWith("```")) return true
    val lines = t.lines().filter { it.isNotBlank() }
    if (lines.size < 3) return false
    val codeish = lines.count { line ->
        val s = line.trimStart()
        s.startsWith("$") || s.startsWith("{") || s.startsWith("}") ||
            s.startsWith("//") || s.startsWith("#") || s.startsWith("import ") ||
            s.startsWith("fun ") || s.startsWith("val ") || s.startsWith("class ") ||
            s.startsWith("<") || line.startsWith("    ") || line.startsWith("\t")
    }
    return codeish.toFloat() / lines.size > 0.6f
}

/**
 * 工具调用行：比正文弱，折叠成一行。
 * 这是长对话能读下去的关键——一次任务里几十条工具调用全部展开会淹没正文。
 */
@Composable
fun ToolCallRow(
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad + 4.dp, vertical = 3.dp)
            .clickable { onToggle() },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            color = Palette.TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 1.dp, end = 6.dp)
        )
        Text(
            text = name,
            color = Palette.TextSecondary,
            fontSize = Dims.metaSize,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        if (detail.isNotBlank()) {
            Text(
                text = "  $detail",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) 50 else 1,
            )
        }
    }
}
