package io.github.dshmobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dshmobile.R
import io.github.dshmobile.ApiClient

/**
 * 会话列表页。
 *
 * 排序用 updatedAt 倒序（最近的在最上面），这是聊天 App 的默认预期。
 * 每行给三样信息就够：标题、状态点、相对时间——再多就成了信息噪音。
 *
 * 顶部两行：
 *   - 标题行：连接点 + 「肥鱼」+ 刷新 + 归档入口
 *   - 工作区行：横向可滚的 chip。只有真的存在多个工作区时才显示（单工作区时
 *     多一行等于噪音，而且默认工作区是自动创建的，用户根本没概念）
 */
@Composable
fun SessionListScreen(
    sessions: List<ApiClient.SessionSummary>,
    connected: Boolean,
    loading: Boolean,
    error: String?,
    onOpen: (ApiClient.SessionSummary) -> Unit,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (ApiClient.SessionSummary) -> Unit,
    onArchive: (ApiClient.SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    /** 工作区列表：workspaceId to title。为空表示还没读到 */
    workspaces: List<Pair<String, String>> = emptyList(),
    /** 当前选中的 workspaceId；空串表示「全部」 */
    currentWorkspaceId: String = "",
    onSelectWorkspace: ((String) -> Unit)? = null,
    /** 已归档会话条数（显示在归档入口上） */
    archivedCount: Int = 0,
    onOpenArchive: (() -> Unit)? = null,
    /** 插件页入口。放在首页而不是聊天页——它是「电脑上装了什么」这种全局信息 */
    onOpenPlugins: (() -> Unit)? = null,
    /** 文件管理器入口。同样是全局功能，跟会话无关，所以放首页顶栏。 */
    onOpenFiles: (() -> Unit)? = null,
    /** 刚归档的会话标题：非空时底部出现「已归档 · 撤回」条 */
    undoTitle: String? = null,
    onUndo: (() -> Unit)? = null,
    /** 设置入口：主题、中转地址、关于都收在那一页 */
    onOpenSettings: (() -> Unit)? = null,
    /** 自定义显示名（设置里可改，限长）。空串表示用默认文案 */
    displayName: String = "",
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
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (connected) Palette.Online else Palette.Offline, CircleShape)
            )
            Box(modifier = Modifier.size(8.dp))
            Text(
                // 显示名可在设置里改（限长），没设就显示中性的「DSH」——
                // 原来这里写死「肥鱼」，别人装了这个 App 顶上顶着自己不认识的名字。
                text = if (!connected) "未连接" else displayName.ifBlank { "DSH" },
                color = Palette.TextPrimary,
                fontSize = Dims.titleSize,
            )
            // 设置入口。原来是个 16sp 的灰齿轮，躺在标题旁边像个装饰符号；
            // 给它一个胶囊底 + 提亮，跟右边那排纯文字按钮区分开，才看得出能点。
            if (onOpenSettings != null) {
                // 矢量齿轮，不用「⚙」——那个字符在不同 ROM 上会变成彩色 emoji、
                // 单色符号、甚至豆腐块，这软件不止一个人用。
                Box(
                    modifier = Modifier
                        .clickable { onOpenSettings() }
                        .background(Palette.SurfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "设置",
                        tint = Palette.TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f))
            if (onOpenFiles != null) {
                Text(
                    text = "文件",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onOpenFiles() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (onOpenPlugins != null) {
                Text(
                    text = "插件",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onOpenPlugins() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (onOpenArchive != null && archivedCount > 0) {
                Text(
                    text = "归档 $archivedCount",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onOpenArchive() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
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

        // 工作区切换：只有一个工作区时不显示这一行
        if (workspaces.size > 1 && onSelectWorkspace != null) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.screenPad),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
            ) {
                item {
                    WorkspaceChip(
                        label = "全部",
                        selected = currentWorkspaceId.isEmpty(),
                        onClick = { onSelectWorkspace("") },
                    )
                }
                items(workspaces) { (id, title) ->
                    WorkspaceChip(
                        label = title.ifBlank { "未命名" },
                        selected = currentWorkspaceId == id,
                        onClick = { onSelectWorkspace(id) },
                    )
                }
            }
        }

        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dims.screenPad)
                    .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                    .padding(16.dp)
            ) {
                Text(error, color = Palette.Error, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (sessions.isEmpty() && !loading && error == null) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    // 筛了某个工作区却空着时，别再说「还没有会话」——
                    // 那听起来像数据丢了，实际是筛选条件把别的会话挡住了。
                    text = if (currentWorkspaceId.isNotEmpty()) {
                        "这个工作区下还没有会话\n点上方「全部」看其它"
                    } else {
                        "还没有会话\n点右下角新建一个"
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Dims.screenPad, end = Dims.screenPad, top = 4.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sessions) { s ->
                        SessionRow(s, onOpen = { onOpen(s) }, onRename = { onRename(s) }, onArchive = { onArchive(s) })
                    }
                }
            }
        }

        // 撤回条：占底部一条，不叠在列表上——叠上去会挡住最后一条会话。
        // 5 秒后由 ChatActivity 把 undoArchived 清空，条自己消失。
        // 右侧必须给新建按钮让位（FAB 浮在右下角，56dp + 20dp 边距），
        // 不让位会正好盖住「撤回」两个字——实测就是这样，用户只看到"已归档"。
        if (undoTitle != null && onUndo != null) {
            UndoBar(
                title = undoTitle,
                onUndo = onUndo,
                modifier = Modifier.padding(
                    start = Dims.screenPad,
                    end = 88.dp,
                    bottom = Dims.screenPad,
                ),
            )
        }
    }
}

/**
 * 「已归档 · 撤回」条。
 *
 * 归档以前是直接消失，没有后悔药——误滑一下就找不回来了
 * （得进归档页翻）。这里给 5 秒窗口。
 */
@Composable
private fun UndoBar(title: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已归档「$title」",
            color = Palette.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "撤回",
            color = Palette.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { onUndo() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 会话行。点击打开，长按弹出菜单（重命名 / 归档）。
 *
 * 用 combinedClickable 而不是同时挂 clickable + pointerInput——
 * 后者长按后还会补一次点击事件，等于「长按菜单一出来就把会话打开了」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: ApiClient.SessionSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 左滑归档。阈值给到 40%，避免竖着划列表时手一抖就归档掉。
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onArchive()
                true
            } else {
                false
            }
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 左滑露出来的底：淡红 + 右侧「归档」，滑到一半就知道自己在干什么
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Palette.Error.copy(alpha = 0.16f),
                        RoundedCornerShape(Dims.cardRadius),
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "归档",
                    color = Palette.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
                .combinedClickable(
                    onClick = { onOpen() },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s.running) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Palette.Accent, CircleShape)
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 空会话本来就没标题，直接画会是一行空白，看起来像条目坏了
                    text = s.title.ifBlank { "未命名会话" },
                    color = if (s.title.isBlank()) Palette.TextTertiary else Palette.TextPrimary,
                    fontSize = Dims.bodySize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(modifier = Modifier.size(3.dp))
                Text(
                    text = buildString {
                        append(relativeTime(s.updatedAt))
                        if (s.running) append(" · 正在运行")
                    },
                    // 时间原本是 TextTertiary(#5A5A63)，在卡片上几乎看不见；
                    // 提到 Secondary 才读得出来「多久前」，又不至于抢标题。
                    color = Palette.TextSecondary,
                    fontSize = Dims.metaSize,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("重命名", fontSize = 15.sp) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("归档", fontSize = 15.sp) },
                onClick = { menuOpen = false; onArchive() },
            )
        }
    }
    }
}

/**
 * 工作区筛选 chip。选中态用鲸鱼蓝描边 + 淡填充，未选中只有一层浅底，
 * 这样一排 chip 里当前筛选一眼能看出来，不用读文字。
 */
@Composable
private fun WorkspaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Palette.Accent.copy(alpha = 0.16f) else Palette.Surface,
                RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** 相对时间：聊天列表里比绝对时间更有用 */
private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return ""
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> {
            val d = java.util.Date(ms)
            val f = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
            f.format(d)
        }
    }
}

/** 右下角新建按钮：单纯的悬浮圆钮，不做拖动（这页不需要） */
@Composable
fun NewSessionFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(Palette.Accent, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("＋", color = androidx.compose.ui.graphics.Color.White, fontSize = 24.sp)
    }
}
