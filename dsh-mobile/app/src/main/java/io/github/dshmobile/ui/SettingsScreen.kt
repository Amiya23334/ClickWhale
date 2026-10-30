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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.dshmobile.R
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip

/**
 * 设置页。
 *
 * 之前主题开关是首页顶栏上一个孤零零的 ☀，中转地址只能靠首屏那次输入，
 * 改了就没地方改回去。这些零碎的东西收进这里，首页顶栏就能干净下来。
 *
 * 只放「真的会去改」的项，不做成 iOS 那种一长串开关阵容——
 * 每多一个开关就多一个会被误触的东西。
 */
@Composable
fun SettingsScreen(
    baseUrl: String,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accent: AccentPreset,
    onAccentChange: (AccentPreset) -> Unit,
    font: FontPreset,
    onFontChange: (FontPreset) -> Unit,
    appName: String,
    versionName: String,
    authorName: String,
    onOpenRepo: () -> Unit,
    onOpenAuthor: () -> Unit,
    backgroundUri: String,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    bgBlur: Int,
    onBgBlurChange: (Int) -> Unit,
    bgAlpha: Float,
    onBgAlphaChange: (Float) -> Unit,
    customFontName: String,
    onPickFont: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 断开会清掉中转地址，误触一次就得重新扫描；加一层确认。
    var confirmDisconnect by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dims.screenPad, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = Palette.Accent,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp, top = 2.dp, bottom = 2.dp)
                    .size(22.dp),
            )
            Text(text = "设置", color = Palette.TextPrimary, fontSize = Dims.titleSize)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dims.screenPad),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle("外观")

            // 主题：两个 chip，点一下就换。当前选中用强调色底。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "主题",
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    modifier = Modifier.weight(1f),
                )
                ThemeChip("系统", selected = themeMode == ThemeMode.SYSTEM) {
                    onThemeModeChange(ThemeMode.SYSTEM)
                }
                Box(modifier = Modifier.padding(start = 5.dp)) {
                    ThemeChip("夜间", selected = themeMode == ThemeMode.DARK) {
                        onThemeModeChange(ThemeMode.DARK)
                    }
                }
                Box(modifier = Modifier.padding(start = 5.dp)) {
                    ThemeChip("日间", selected = themeMode == ThemeMode.LIGHT) {
                        onThemeModeChange(ThemeMode.LIGHT)
                    }
                }
            }

            // 强调色：一排圆点。选中的套一圈描边——只靠颜色区分不够，
            // 深色主题下好几个红/橙圆点在深底上本来就难分辨。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "强调色",
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    modifier = Modifier.weight(1f),
                )
                AccentPreset.entries.forEach { preset ->
                    val color = if (Palette.dark) preset.dark else preset.light
                    val selected = preset == accent
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(color, CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, Palette.TextPrimary, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onAccentChange(preset) },
                    )
                }
            }

            // 字体：三档都来自系统字体族，不打包字体文件（APK 会大几 MB）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "字体",
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    modifier = Modifier.weight(1f),
                )
                FontPreset.entries.forEachIndexed { index, preset ->
                    Box(modifier = Modifier.padding(start = if (index == 0) 0.dp else 5.dp)) {
                        ThemeChip(preset.label, selected = preset == font) {
                            if (preset == FontPreset.CUSTOM && customFontName.isBlank()) {
                                // 从没选过字体文件：开选择器选一个
                                onPickFont()
                            } else {
                                // 有文件（或切到其他档位）：正常切档。
                                // 想换文件走下面的「更换」，两个入口各管各的。
                                onFontChange(preset)
                            }
                        }
                    }
                }
            }

            if (customFontName.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp)
                        .clickable { onPickFont() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "自定义字体：$customFontName",
                        color = Palette.TextTertiary,
                        fontSize = Dims.metaSize,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "更换",
                        color = Palette.Accent,
                        fontSize = Dims.metaSize,
                    )
                }
            }

            // 背景图：叠在底色上，所以带透明的 PNG 会透出底色。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "背景图",
                        color = Palette.TextPrimary,
                        fontSize = Dims.bodySize,
                    )
                    Text(
                        text = if (backgroundUri.isBlank()) "未设置" else "已设置（支持透明 PNG）",
                        color = Palette.TextTertiary,
                        fontSize = Dims.metaSize,
                    )
                }
                if (backgroundUri.isNotBlank()) {
                    Text(
                        text = "清除",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onClearBackground() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = "选择图片",
                    color = Palette.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onPickBackground() }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }

            if (backgroundUri.isNotBlank()) {
                // 模糊：0 / 8 / 16 / 28dp 四档。低版本 Android 上 Modifier.blur 无效，
                // 这里不做提示——静默退回不模糊，总比弹个「你的系统不支持」好。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "模糊",
                        color = Palette.TextPrimary,
                        fontSize = Dims.bodySize,
                        modifier = Modifier.weight(1f),
                    )
                    listOf("无" to 0, "轻" to 1, "中" to 2, "重" to 3).forEachIndexed { i, pair ->
                        Box(modifier = Modifier.padding(start = if (i == 0) 0.dp else 5.dp)) {
                            ThemeChip(pair.first, selected = bgBlur == pair.second) {
                                onBgBlurChange(pair.second)
                            }
                        }
                    }
                }

                // 浓度：越低越透，图片和（跟着强调色染过的）底色混在一起
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "浓度",
                        color = Palette.TextPrimary,
                        fontSize = Dims.bodySize,
                        modifier = Modifier.weight(1f),
                    )
                    listOf("100%" to 1.0f, "85%" to 0.85f, "70%" to 0.7f, "50%" to 0.5f)
                        .forEachIndexed { i, pair ->
                            Box(modifier = Modifier.padding(start = if (i == 0) 0.dp else 5.dp)) {
                                ThemeChip(pair.first, selected = kotlin.math.abs(bgAlpha - pair.second) < 0.01f) {
                                    onBgAlphaChange(pair.second)
                                }
                            }
                        }
                }
            }

            SectionTitle("称呼")
            // 首页左上角显示的名字。限长是为了别把顶栏顶爆——
            // 那一行还要排「文件/插件/归档/刷新」四个入口。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "显示名",
                    color = Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    modifier = Modifier.weight(1f),
                )
                NameField(
                    value = displayName,
                    onChange = onDisplayNameChange,
                    modifier = Modifier.weight(1.2f),
                )
            }
            Text(
                text = "最多 $MAX_NAME_LEN 个字，留空就显示「DSH」",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )

            SectionTitle("连接")

            InfoCard(
                label = "中转地址",
                value = baseUrl.ifBlank { "还没连过" },
            )

            // 取消连接：清掉当前地址、退回连接页。调试连接时不用杀进程重进。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .clickable { confirmDisconnect = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "取消连接",
                        color = Palette.Error,
                        fontSize = Dims.bodySize,
                    )
                    Text(
                        text = "断开后回到连接页，可重新扫描或手填地址",
                        color = Palette.TextTertiary,
                        fontSize = Dims.metaSize,
                    )
                }
            }

            SectionTitle("关于")

            AboutCard(
                appName = appName,
                versionName = versionName,
                authorName = authorName,
                onOpenRepo = onOpenRepo,
                onOpenAuthor = onOpenAuthor,
            )
        }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("断开连接？", color = Palette.TextPrimary) },
            text = {
                Text(
                    "会清掉当前的中转地址并回到连接页，需要重新扫描或手填。电脑端的会话不受影响。",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) {
                    Text("断开", color = Palette.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text("取消", color = Palette.TextSecondary)
                }
            },
            containerColor = Palette.Surface,
        )
    }
    }
}

/**
 * 关于卡片：版本、开源地址、许可证、吉祥物署名。
 *
 * 以前这里只有两行静态文字，连版本号都没有——用户报 bug 时说不清自己跑的是哪版。
 */
@Composable
private fun AboutCard(
    appName: String,
    versionName: String,
    authorName: String,
    onOpenRepo: () -> Unit,
    onOpenAuthor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 用 App 自己的图标，一眼对得上桌面上的那个。
            // 不能直接 painterResource(R.mipmap.ic_launcher)：API 26+ 它解析成
            // 自适应图标 XML，painterResource 不认，进来就崩（crash 日志里那条
            // Only VectorDrawables and rasterized asset types are supported）。
            // 所以用复制出来的普通位图。
            Image(
                painter = painterResource(R.drawable.about_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = appName, color = Palette.TextPrimary, fontSize = Dims.titleSize)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "版本 $versionName",
                    color = Palette.TextSecondary,
                    fontSize = Dims.metaSize,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "把电脑上的 DeepSeek Harness 接到手机上。不是投屏，是原生客户端——"
                + "和电脑镜像同一个会话。",
            color = Palette.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "开源 · MIT 协议",
            color = Palette.Accent,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onOpenRepo),
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "作者 ",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
            )
            Text(
                text = authorName,
                color = Palette.Accent,
                fontSize = Dims.metaSize,
                modifier = Modifier.clickable(onClick = onOpenAuthor),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "吉祥物「肥鱼」形象由作者绘制",
            color = Palette.TextTertiary,
            fontSize = Dims.metaSize,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Palette.TextTertiary,
        fontSize = Dims.metaSize,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun InfoCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = label, color = Palette.TextPrimary, fontSize = Dims.bodySize)
        Box(modifier = Modifier.padding(top = 3.dp)) {
            Text(
                text = value,
                color = Palette.TextSecondary,
                fontSize = Dims.metaSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThemeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Palette.Accent.copy(alpha = 0.16f) else Palette.SurfaceHigh,
                RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }

}

/** 显示名字数上限。按字符算，8 个字在顶栏已经相当宽了。 */
private const val MAX_NAME_LEN = 8

/**
 * 显示名输入框。
 *
 * 超长直接忽略（不回显、不弹提示）——顶栏那一行还要排四个入口，
 * 让用户输入一个必然被截断的名字没有意义。
 */
@Composable
private fun NameField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= MAX_NAME_LEN) onChange(it) },
        singleLine = true,
        textStyle = TextStyle(color = Palette.TextPrimary, fontSize = Dims.bodySize),
        cursorBrush = SolidColor(Palette.Accent),
        modifier = modifier
            .background(Palette.SurfaceHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text("DSH", color = Palette.TextTertiary, fontSize = Dims.bodySize)
            }
            inner()
        },
    )
}
