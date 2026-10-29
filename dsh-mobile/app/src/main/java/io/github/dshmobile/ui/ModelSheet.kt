package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dshmobile.ApiClient

/**
 * 模型选择底部弹层。
 *
 * 用 ModalBottomSheet 而不是 Dialog：模型列表可能有几十个（这台机上有 12 个 provider
 * 几十个模型），底部弹层能滑、能滚动、单手够得着，Dialog 在手机上太局促。
 *
 * 按 provider 分组显示，因为同一个模型 id 在 workbuddy 和 deepseek-account 下是
 * 两条不同的路由，只显示模型名用户分不清点的是哪条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ApiClient.ModelOption>,
    currentProvider: String,
    currentModel: String,
    loading: Boolean,
    onPick: (ApiClient.ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.Bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = "选择模型",
                color = Palette.TextPrimary,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = Dims.screenPad, end = Dims.screenPad, bottom = 4.dp),
            )
            Text(
                text = if (loading) "加载中…" else "${models.size} 个可选",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
                modifier = Modifier.padding(start = Dims.screenPad, end = Dims.screenPad, bottom = 8.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Dims.screenPad, end = Dims.screenPad, bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(models) { m ->
                    val selected = m.provider == currentProvider && m.model == currentModel
                    ModelRow(m, selected) { onPick(m) }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(m: ApiClient.ModelOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) Palette.SurfaceHigh else Palette.Surface,
                RoundedCornerShape(Dims.cardRadius)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = m.name,
                color = if (selected) Palette.Accent else Palette.TextPrimary,
                fontSize = Dims.bodySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${m.providerName} · ${m.model}",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(Palette.Accent, CircleShape)
            )
        }
    }
}
