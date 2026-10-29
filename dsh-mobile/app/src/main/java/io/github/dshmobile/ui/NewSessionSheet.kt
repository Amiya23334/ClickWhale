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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 新建对话：先选工作区。
 *
 * 之前点「＋」是直接 `listWorkspaces()` 拿第一个工作区就建了，用户完全没得选——
 * 而 DSH 里工作区决定会话的 cwd，等于 agent 在哪个目录里干活，
 * 这个不该由 App 替用户随手挑一个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    workspaces: List<Pair<String, String>>,
    currentWorkspaceId: String,
    onCreate: (String) -> Unit,
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
                text = "新建对话",
                color = Palette.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "选一个工作区——它决定这个会话在电脑的哪个目录里干活。",
                color = Palette.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )

            if (workspaces.isEmpty()) {
                Text(
                    text = "还没读到工作区，先回列表下拉刷新一下",
                    color = Palette.TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            workspaces.forEach { (id, title) ->
                WorkspaceRow(
                    title = title.ifBlank { "未命名工作区" },
                    // 首页当前筛中的工作区做个标记，但不是"默认选中要建的那个"——
                    // 建会话还是要用户自己点一下，避免误建到不想去的目录。
                    highlighted = id == currentWorkspaceId,
                    onClick = { onCreate(id) },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceRow(title: String, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (highlighted) Palette.Accent else Palette.SurfaceHigh,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (highlighted) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Palette.Surface, CircleShape)
                )
            }
        }
        Text(
            text = title,
            color = Palette.TextPrimary,
            fontSize = Dims.bodySize,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
