package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 归档会话页。
 *
 * 为什么需要它：`workspace/archiveSession` 是「从列表里藏起来」，不是删除；
 * 但网页端之外没有入口把它们找回来——归档完就等于永久消失。这一页就是补那个口子。
 *
 * 归档会话不在 `session/list` 里，标题要单独用 `session/projections` 问。
 * 有些归档项对应的会话已经删了（projections 返回 `value: null`），
 * 标题会是空串，这里显示成「已删除的会话」并允许直接清理。
 */
@Composable
fun ArchiveScreen(
    ids: List<String>,
    titles: Map<String, String>,
    loading: Boolean,
    onBack: () -> Unit,
    onUnarchive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dims.screenPad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                color = Palette.Accent,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
            Box(modifier = Modifier.padding(start = 12.dp))
            Text(
                text = "已归档 ${ids.size}",
                color = Palette.TextPrimary,
                fontSize = Dims.titleSize,
            )
        }

        if (ids.isEmpty()) {
            EmptyState(
                modifier = Modifier.fillMaxSize(),
                text = if (loading) "读取中…" else "没有归档的会话",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Dims.screenPad, end = Dims.screenPad, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ids, key = { it }) { id ->
                val title = titles[id].orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.ifBlank { "已删除的会话" },
                            color = if (title.isBlank()) Palette.TextTertiary else Palette.TextPrimary,
                            fontSize = Dims.bodySize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(modifier = Modifier.padding(top = 2.dp))
                        Text(
                            text = id,
                            color = Palette.TextTertiary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (title.isNotBlank()) {
                        Text(
                            text = "恢复",
                            color = Palette.Accent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onUnarchive(id) }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
