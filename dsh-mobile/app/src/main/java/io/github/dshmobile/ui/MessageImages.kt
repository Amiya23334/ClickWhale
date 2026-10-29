package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 消息里的图片附件。
 *
 * 事件里只有 `attachmentId: "sha256:<hex>"`，没有字节，所以图片要从电脑上的
 * 中转取：`<relayBase>/relay/attachment/sha256:<hex>`。中转读的是
 * `<DSH_HOME>/attachments/v1/objects/...`，那台机器本地已经有这份文件。
 *
 * 为什么不在 App 侧再要一份 base64：一条历史里可能有十几张图，全塞进事件
 * 会让快照体积翻几倍，而且每次重连都要重传。让 Coil 走 HTTP 拿，磁盘缓存
 * 命中后重进会话是零流量。
 *
 * 注意 [relayBase] 必须是手机能访问的地址（`http://192.168.x.x:8090`），
 * 不能是 `http://127.0.0.1:8090`，那在手机上指向手机自己。
 */
@Composable
fun MessageImages(
    attachmentIds: List<String>,
    relayBase: String,
    modifier: Modifier = Modifier,
    onOpen: ((String) -> Unit)? = null,
) {
    if (attachmentIds.isEmpty()) return
    val base = relayBase.trimEnd('/')

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 单图给到气泡的 100%，多图按两列铺。都是相对宽度，平板上不会失控。
        val cols = if (attachmentIds.size == 1) 1 else 2
        val gap = 6.dp
        val cellW = (maxWidth - gap * (cols - 1)) / cols
        // 图片高度上限：单图不超过 260dp（竖图不至于占满一屏），多图 140dp。
        val cellH = if (cols == 1) 260.dp else 140.dp

        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            attachmentIds.chunked(cols).forEach { rowIds ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowIds.forEach { id ->
                        val url = "$base/relay/attachment/$id"
                        AsyncImage(
                            model = url,
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(cellW, cellH)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.SurfaceHigh)
                                .then(
                                    if (onOpen != null) Modifier.clickable { onOpen(url) }
                                    else Modifier
                                ),
                        )
                    }
                    // 最后一行不满时补空位，避免图片被拉宽
                    repeat(cols - rowIds.size) {
                        Box(Modifier.size(cellW, cellH))
                    }
                }
            }
        }
    }
}

/** 拿不到中转地址时的占位。宁可显示一个「图片」块，也不要静默什么都不画。 */
@Composable
fun ImagePlaceholder(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("🖼", fontSize = 13.sp)
        Text(
            text = if (count == 1) "1 张图片" else "$count 张图片",
            color = Palette.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/** 全屏看大图。命中图片条时用它，省得自己做手势缩放。 */
@Composable
fun ImageViewer(url: String, onDismiss: () -> Unit) {
    // usePlatformDefaultWidth = false 是必须的：默认 Dialog 窗口按平台默认宽度
    // 收缩到内容宽度，图片会被挤成中间一条、四周还是半透明背景（实测踩过）。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.96f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "图片附件",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            )
        }
    }
}
