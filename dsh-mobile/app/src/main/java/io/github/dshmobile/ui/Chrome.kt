package io.github.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dshmobile.R

/**
 * 顶部状态条：连接状态 + 当前工作区 + 余额。
 *
 * 为什么放顶部而不是底部：输入框要贴底（手指够得着），
 * 状态是低频信息，放顶部一眼扫到就行。
 *
 * 右侧 `modelName` 非空时显示成可点的模型胶囊（点了弹模型选择）。
 */
@Composable
fun TopBar(
    connected: Boolean,
    subtitle: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    /** 非空时左侧显示 ←。会话是从列表点进来的，没有它就只能靠系统手势——
     *  而系统手势会把整个 Activity 退掉（会话直接没了），不是退回列表。 */
    onBack: (() -> Unit)? = null,
    modelName: String = "",
    onPickModel: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = Dims.screenPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            // 矢量图标而不是「←」字符：不同字体的箭头长短、字重差很多，
            // 有的 ROM 还会把它排成全角，跟后面的标题挤在一起。
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = Palette.Accent,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = 2.dp)
                    .size(22.dp),
            )
        }
        // 连接状态点
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (connected) Palette.Online else Palette.Offline,
                    CircleShape
                )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (connected) "已连接" else "未连接",
                color = Palette.TextPrimary,
                fontSize = 14.sp,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Palette.TextTertiary,
                    fontSize = Dims.metaSize,
                    maxLines = 1,
                )
            }
        }
        // 模型胶囊不能因为「还没读出来」就整个消失——用户会以为没有模型选择这个功能。
        // 读不到名字时显示占位文案，点了照样能打开选择器。
        if (onPickModel != null) {
            Text(
                text = modelName.ifBlank { "选择模型" },
                color = if (modelName.isBlank()) Palette.TextSecondary else Palette.Accent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { onPickModel() }
                    .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 底部输入区。
 *
 * 交互：
 *   - 单行时高度自适应，最多 5 行后内部滚动（BasicTextField 自己处理）
 *   - 发送按钮只在有内容时点亮，空输入时是灰的
 *   - 流式输出中发送按钮变成「停止」，这是最基本的需求——不能等它跑完
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStop: () -> Unit = {},
    onPickAttachment: (() -> Unit)? = null,
    attachmentCount: Int = 0,
    /** 点输入框左侧的安全等级小标签，打开权限弹层 */
    onPickPermission: (() -> Unit)? = null,
    /** 当前权限预设名，空串表示还没读到 */
    permissionName: String = "",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .padding(horizontal = Dims.screenPad, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 权限入口：输入框**左边**一个小标签，写着当前权限名。
        //
        // 为什么不做成第二个圆形按钮：底部已经有一个 ＋ 了，再加一个圆按钮
        // 这两个图标很难一眼区分。用带文字的小胶囊，既省位置又能让用户随时
        // 看见「现在是什么权限」——这比藏在下拉里更安全，切错了能立刻发现。
        if (onPickPermission != null && permissionName.isNotBlank()) {
            Box(
                modifier = Modifier
                    .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClick = onPickPermission)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = shortPermission(permissionName),
                    color = Palette.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        // 附件入口。用「＋」而不是回形针：微信/QQ 都是 ＋，而且回形针在
        // 小尺寸下容易糊成一团。有附件时角标显示数量。
        if (onPickAttachment != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Palette.SurfaceHigh, CircleShape)
                    .clickable(enabled = enabled && !streaming, onClick = onPickAttachment),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋",
                    color = if (enabled && !streaming) Palette.TextSecondary else Palette.TextTertiary,
                    fontSize = 20.sp,
                )
                if (attachmentCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(15.dp)
                            .background(Palette.Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = attachmentCount.toString(),
                            color = Color.White,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .background(Palette.SurfaceHigh, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    text = if (streaming) "肥鱼正在干活…" else "说点什么",
                    color = Palette.TextTertiary,
                    fontSize = Dims.bodySize,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    lineHeight = 21.sp,
                ),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 21.dp),
            )
        }

        // 发送 / 停止。有附件时即使一个字没打也能发（网关要求「至少一个非空白
        // 文本部分**或**附件」，附件本身就满足条件）。
        val canSend = (value.isNotBlank() || attachmentCount > 0) && !streaming && enabled
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = when {
                        streaming -> Palette.SurfaceHigh
                        canSend -> Palette.Accent
                        else -> Palette.Surface
                    },
                    shape = CircleShape
                )
                .clickable(enabled = streaming || canSend) {
                    if (streaming) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                // 停止：小方块
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .background(Palette.TextPrimary, RoundedCornerShape(2.dp))
                )
            } else {
                // 发送：向上箭头
                Text(
                    text = "↑",
                    color = if (canSend) Color.White else Palette.TextTertiary,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

/** 权限名太长，底栏放不下，缩成一眼能认的短标签。 */
private fun shortPermission(name: String): String = when (name) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区"
    "danger-full-access" -> "全权限"
    "qq-full-ask" -> "全权限+审批"
    else -> name
}
