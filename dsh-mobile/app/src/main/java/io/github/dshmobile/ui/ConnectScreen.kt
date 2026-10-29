package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 连接页：填电脑地址，或者从扫出来的列表里挑一个。
 *
 * 这一页以前是 MainActivity 里用原生 View 拼的。搬成 Compose 之后，原生 UI
 * 可以直接把它当首屏——没连上就显示它，连上了换成会话列表，不用再先落一个
 * WebView、再点小鲸鱼才进得来。
 */
@Composable
fun ConnectScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    key: String,
    onKeyChange: (String) -> Unit,
    scanning: Boolean,
    found: List<String>,
    error: String?,
    onConnect: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 整块垂直居中。以前是从顶部 56dp 开始往下排，手机屏幕一高就顶在上半屏，
    // 底下大片空着；连接页内容不多，居中更像一个「入口」。
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dims.screenPad)
            .padding(vertical = 24.dp),
    ) {
        Text("连接电脑上的 DSH", color = Palette.TextPrimary, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "先在电脑上跑 relay/start-relay.cmd，它会打印一个 http://192.168.x.x:8090 地址。",
            color = Palette.TextTertiary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(24.dp))

        // 输入框：占位符要自己叠一层，BasicTextField 没有 hint
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (url.isEmpty()) {
                Text(
                    text = "http://192.168.1.x:8090",
                    color = Palette.TextTertiary,
                    fontSize = Dims.bodySize,
                )
            }
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.TextPrimary, fontSize = Dims.bodySize),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))

        // 访问密钥：电脑端插件生成的那串。中转没启用校验时留空也能连。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (key.isEmpty()) {
                Text(
                    text = "访问密钥（电脑端生成的，没启用校验可留空）",
                    color = Palette.TextTertiary,
                    fontSize = Dims.bodySize,
                )
            }
            BasicTextField(
                value = key,
                onValueChange = onKeyChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.TextPrimary, fontSize = Dims.bodySize),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))

        ActionButton(
            text = "连接",
            primary = true,
            enabled = true,
            onClick = onConnect,
        )
        Spacer(Modifier.height(10.dp))
        ActionButton(
            text = if (scanning) "正在搜索局域网…" else "自动搜索电脑",
            primary = false,
            enabled = !scanning,
            onClick = onScan,
        )

        if (found.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("找到这些：", color = Palette.TextSecondary, fontSize = Dims.metaSize)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                found.forEach { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.Surface, RoundedCornerShape(10.dp))
                            .clickable {
                                onUrlChange(f)
                                onConnect()
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f, color = Palette.Accent, fontSize = 14.sp)
                    }
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(18.dp))
            Text(error, color = Palette.Error, fontSize = Dims.metaSize, lineHeight = 19.sp)
        }

    }
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = when {
            !enabled -> Palette.TextTertiary
            primary -> Palette.Bg
            else -> Palette.TextPrimary
        },
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (primary) Palette.Accent else Palette.SurfaceHigh,
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
    )
}
