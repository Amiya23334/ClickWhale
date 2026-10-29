package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.dshmobile.R

/**
 * 待发附件预览条（输入框上方，横向滚动）。
 *
 * 几个决定：
 *   - **不画缩略图**。画图要引 Coil（+3 MB 依赖）和一条异步加载链路，
 *     而这里要的只是「我选了哪几个文件」。用图标 + 文件名 + 大小足够，
 *     等真的要预览聊天里的图片时再统一引一个图片库。
 *   - 每个 chip 右上角一个 ✕，点击删掉这一项（不是长按菜单，少一步）。
 *   - 横向滚动而不是换行：选 8 张图时换行会把输入区顶到屏幕一半高。
 */
@Composable
fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: ((PendingAttachment) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dims.screenPad, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        for (a in attachments) {
            AttachmentChip(
                attachment = a,
                onRemove = onRemove?.let { { it(a) } },
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: (() -> Unit)?,
) {
    Box {
        Row(
            modifier = Modifier
                .widthIn(max = 190.dp)
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.SurfaceHigh)
                .padding(start = 9.dp, end = 22.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // 图片走内联、其它走上传，两条路在视觉上区分一下，用户能预期
            // 「这张图会不会占额度/慢」。用矢量图标，不用 emoji（各 ROM 渲染不一）。
            Icon(
                painter = painterResource(
                    if (attachment.isInlineImage) R.drawable.ic_image else R.drawable.ic_file
                ),
                contentDescription = null,
                tint = Palette.TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Column {
                Text(
                    text = attachment.name,
                    color = Palette.TextPrimary,
                    fontSize = Dims.metaSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (attachment.size > 0) {
                    Text(
                        text = PendingAttachment.formatSize(attachment.size),
                        color = Palette.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = Palette.TextTertiary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
