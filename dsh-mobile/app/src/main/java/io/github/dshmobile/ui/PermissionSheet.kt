package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 权限预设选择弹层。
 *
 * 对应电脑端输入框旁边那个安全等级下拉。DSH 里权限是「沙箱模式 + 审批策略」的捆绑，
 * 由 `@deepseek-ai/dsh-permission-presets` 管理；**没有切换用的 RPC**，
 * 只能发 `/permission <名字>` 这条 slash 命令（见 `ApiClient.executeCommand`）。
 *
 * 所以这里的条目来自 `/permission` 输出的 available 列表，不写死——用户装了新预设
 * （比如 qq-full-ask）会自动出现在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    current: String,
    available: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "权限",
                color = Palette.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "决定 agent 能碰什么。每换一次都会写进会话记录。",
                color = Palette.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )

            available.forEach { name ->
                PermissionRow(
                    name = name,
                    selected = name == current,
                    onClick = { onSelect(name) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 选中用实心圆点，未选中用空心——比打勾更符合「单选项」的语义。
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (selected) Palette.Accent else Color.Transparent,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Palette.Bg, CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Palette.Offline, CircleShape)
                        .padding(1.5.dp)
                        .background(Palette.Surface, CircleShape),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = describe(name),
                color = Palette.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 预设名 -> 一句话解释。
 *
 * 名字是电脑端配置里的 key，用户看不懂 `workspace-write` 和 `danger-full-access`
 * 差在哪，所以硬编码一份对照。认不出的原样返回空串，界面就不会显示描述行。
 */
private fun describe(name: String): String = when (name) {
    "read-only" -> "只能看，不能改任何文件"
    "workspace-write" -> "能改工作区里的文件，工作区外面不行"
    "danger-full-access" -> "整个磁盘随便读写，不拦"
    "qq-full-ask" -> "完全访问，但每次调用工具都要你批"
    else -> ""
}
