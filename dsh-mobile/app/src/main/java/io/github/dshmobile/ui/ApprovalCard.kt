package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 等待审批的工具调用卡片。
 *
 * 为什么必须做：`EventMapper` 里 `approval/asked` 原来只产出一行「等待审批：xxx」文案，
 * 没有任何可点的东西。agent 一到需要审批的工具就**永久卡住**——它不会超时，
 * 也不会自己退一步，用户只能杀进程。
 *
 * 两个按钮对应 DSH 的 `ApprovalOutcome`：允许 = `allowed-once`（只这一次，DSH 没有
 * 永久允许的概念），拒绝 = `rejected`。
 */
@Composable
fun ApprovalCard(
    toolName: String,
    reason: String,
    submitting: Boolean,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "等待授权",
                color = Palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "  " + toolName,
                color = Palette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // reason 里常常带完整命令行，可能是好几行；限高三行，剩下的靠省略号。
        if (reason.isNotBlank()) {
            Text(
                text = reason,
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onReject,
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.SurfaceHigh,
                    contentColor = Palette.TextSecondary,
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp
                ),
            ) { Text("拒绝", fontSize = 13.sp) }

            Button(
                onClick = onAllow,
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent,
                    // 蓝底上压深色字，比白字对比度更高；Palette 里没有专门的 OnAccent。
                    contentColor = Palette.Bg,
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp, vertical = 8.dp
                ),
            ) {
                Text(
                    text = if (submitting) "处理中…" else "允许一次",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(onClick = {}, enabled = false) {
                Text(
                    text = "DSH 不提供永久允许",
                    color = Palette.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
