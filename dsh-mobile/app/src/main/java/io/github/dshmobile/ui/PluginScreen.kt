package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import io.github.dshmobile.ApiClient

/**
 * 插件页（原生）。
 *
 * 之前这里是套 WebView 打开整个网页版再点出插件面板——视觉跟原生完全割裂，
 * 还带着网页版的侧栏、顶栏一堆用不上的东西。现在直接用中转透传的
 * `/dsh-market/installed` 画一个列表：名字、版本、状态。
 *
 * 只读。插件开关要重启 DSH 才生效，那件事留着在电脑上做。
 */
@Composable
fun PluginScreen(
    plugins: List<ApiClient.PluginInfo>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Box(modifier = Modifier.size(4.dp))
            Text(
                text = if (plugins.isEmpty()) "插件" else "插件 ${plugins.size}",
                color = Palette.TextPrimary,
                fontSize = Dims.titleSize,
            )
            Box(modifier = Modifier.weight(1f))
            if (loading) {
                Text("加载中", color = Palette.TextTertiary, fontSize = Dims.metaSize)
            } else {
                Text(
                    text = "刷新",
                    color = Palette.Accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onRefresh() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.screenPad)
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(14.dp)
            ) {
                Text(error, color = Palette.Error, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (plugins.isEmpty() && !loading && error == null) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    text = "这台电脑上还没有装插件",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dims.screenPad, end = Dims.screenPad, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(plugins) { p -> PluginRow(p) }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(p: ApiClient.PluginInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        // 三种状态：正在跑 / 装了没跑 / 包不在了
                        when {
                            p.live -> Palette.Online
                            p.present -> Palette.Offline
                            else -> Palette.Error
                        },
                        CircleShape
                    )
            )
            Box(modifier = Modifier.size(8.dp))
            Text(
                text = p.name,
                color = Palette.TextPrimary,
                fontSize = Dims.bodySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shortVersion(p.version),
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
                maxLines = 1,
            )
        }
        val note = when {
            !p.present -> "包不在了，可能被 pnpm 清掉；在电脑上重装一次"
            p.live -> if (p.bundle) "运行中 · 随 bundle 挂载" else "运行中"
            else -> "已安装，未启用"
        }
        Text(
            text = note,
            color = if (!p.present) Palette.Error else Palette.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, start = 15.dp),
        )
        if (p.source.isNotBlank()) {
            Text(
                text = p.source,
                color = Palette.TextTertiary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 15.dp),
            )
        }
    }
}

/**
 * 版本号太长会把名字挤掉：`link:C:/Users/.../dsh-glass-skin` 这种直接截成 `link`，
 * `github:owner/repo#<40位 sha>` 截成 `#<前 7 位>`，普通 semver 原样显示。
 */
private fun shortVersion(v: String): String = when {
    v.startsWith("link:") -> "本地"
    v.startsWith("github:") -> {
        val sha = v.substringAfterLast('#', "")
        if (sha.length >= 7) "git ${sha.take(7)}" else "git"
    }
    else -> v
}
