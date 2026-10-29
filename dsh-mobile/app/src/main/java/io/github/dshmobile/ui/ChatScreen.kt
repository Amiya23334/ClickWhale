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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.dshmobile.R
import androidx.compose.ui.draw.rotate

/**
 * 会话页：消息流 + 输入框。
 *
 * 状态管理故意做得很朴素——没有 ViewModel、没有 Flow、没有 DI。
 * 理由：这个 App 只有一个页面要管状态，引入架构组件换不来什么，
 * 反而让「一帧事件怎么变成一屏像素」这条链路变长，出问题不好查。
 *
 * 列表锚定策略：用户手动往上翻时不要强行拉回底部（这是聊天 App 最容易做错的地方）。
 * 判断依据是先看 firstVisibleItem 是不是最后一条 + 屏幕高度容差。
 */
@Composable
fun ChatScreen(
    sessionTitle: String,
    connected: Boolean,
    items: List<EventMapper.Item>,
    streaming: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    pendingText: String = "",
    modelName: String = "",
    onPickModel: (() -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    onPickAttachment: (() -> Unit)? = null,
    onRemoveAttachment: ((PendingAttachment) -> Unit)? = null,
    /** 中转地址（手机可访问的 http://ip:port），消息里的图片从这里取 */
    relayBase: String = "",
    onOpenImage: ((String) -> Unit)? = null,
    /** 还有更老的历史可翻 */
    hasMoreHistory: Boolean = false,
    /** 正在翻页（按钮显示转圈） */
    loadingMore: Boolean = false,
    /** 触发翻页；null 表示不支持翻页 */
    onLoadMore: (() -> Unit)? = null,
    /**
     * 顶部提示条内容：发送失败、连接断开重连中之类。
     *
     * 为什么需要它：这些错误以前只写进 Activity 的 `error` 字段，
     * 而聊天页压根没读那个字段——用户看到的现象是「点了发送，什么都没发生」。
     * 空串表示不显示。
     */
    notice: String = "",
    /** 提示条右侧的关闭按钮；null 表示不可关闭 */
    onDismissNotice: (() -> Unit)? = null,
    /**
     * 正在等用户授权的工具调用；null 表示没有待审批。
     *
     * 参数里的 `toolName` / `reason` 来自 waterfall 帧的 `request`，
     * `reason` 常常带完整命令行（qq-bridge 的 L3 审批闸门就是这么写的），可能就是用户
     * 判断该不该放行的唯一依据，所以必须原样显示，不能截断成「执行了一个工具」。
     */
    pendingApproval: ApprovalRequest? = null,
    /** 已经点了允许/拒绝、正在等回传结果 */
    approvalSubmitting: Boolean = false,
    /** 回传决定；参数是 `allowed-once` 或 `rejected` */
    onApprovalAnswer: ((String) -> Unit)? = null,
    /** 点输入框左侧权限标签；null 表示不显示这个入口 */
    onPickPermission: (() -> Unit)? = null,
    /** 当前权限预设名，空串表示还没读到 */
    permissionName: String = "",
    /** 非空时顶栏左侧显示 ←，用来退回会话列表（系统手势会直接退掉整个 Activity） */
    onBack: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var pinnedToBottom by remember { mutableStateOf(true) }
    var initialScrolled by remember { mutableStateOf(false) }

    // 连续的工具调用合成一条折叠块，否则一轮排查能刷出几十行 `_ops_bash ...`
    val rows = buildRows(items)
    val showPending = streaming && pendingText.isBlank()
    val showPendingText = pendingText.isNotBlank()
    val totalRows = rows.size + (if (showPending || showPendingText) 1 else 0)

    // 用户往上翻就解除锚定，翻回底部附近恢复
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        pinnedToBottom = last == null ||
            (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 120)
    }

    // 首次拿到历史必须强制跳到底。
    // 光靠下面的锚定逻辑不够：首帧布局时最后一条还没量出来，
    // pinnedToBottom 会被算成 false，于是永远不滚（实测踩过）。
    LaunchedEffect(items.isNotEmpty()) {
        if (!initialScrolled && items.isNotEmpty()) {
            listState.scrollToItem(totalRows - 1)
            initialScrolled = true
        }
    }

    // 新内容到达且处于锚定状态 → 滚到底。
    // 用 scrollToItem 而不是 animateScrollToItem：流式输出时动画会被下一帧取消，
    // 表现为「滚了一半停住」。
    LaunchedEffect(rows.size, pendingText.length, pinnedToBottom) {
        if (pinnedToBottom && totalRows > 0) {
            listState.scrollToItem(totalRows - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopBar(
            connected = connected,
            subtitle = sessionTitle,
            onBack = onBack,
            modelName = modelName,
            onPickModel = onPickModel,
        )

        if (notice.isNotBlank()) {
            NoticeBar(text = notice, onDismiss = onDismissNotice)
        }

        Box(modifier = Modifier.weight(1f)) {
            if (items.isEmpty() && !streaming) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    text = "还没有消息\n在下面说点什么",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Dims.rowGap),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    // 翻页入口放列表最顶部（LazyColumn 的 index 0 就是视觉最上方，
                    // 没有 reverseLayout）。
                    //
                    // 只做手动点击、不在这里自动触发：item 的 LaunchedEffect(Unit) 在
                    // LazyColumn 回收/重建时会重跑，用户想停在某段历史时会被一路往前拽。
                    if (hasMoreHistory) {
                        item(key = "load-more") {
                            LoadMoreRow(
                                loading = loadingMore,
                                onClick = { if (!loadingMore) onLoadMore?.invoke() },
                            )
                        }
                    }
                    items(count = rows.size, key = { rows[it].key }) { idx ->
                        when (val r = rows[idx]) {
                            is Row.Tools -> ToolGroupRow(r.calls)
                            is Row.Single -> when (val it = r.item) {
                                is EventMapper.UserMsg -> MessageBubble(
                                    text = it.text,
                                    isUser = true,
                                    attachmentIds = it.attachmentIds,
                                    relayBase = relayBase,
                                    onOpenImage = onOpenImage,
                                    files = it.files,
                                )
                                is EventMapper.AssistantMsg -> MessageBubble(
                                    text = it.text,
                                    isUser = false,
                                    streaming = it.streaming,
                                    attachmentIds = it.attachmentIds,
                                    relayBase = relayBase,
                                    onOpenImage = onOpenImage,
                                    files = it.files,
                                )
                                is EventMapper.Reasoning -> ReasoningRow(it.text)
                                is EventMapper.ToolCall -> ToolCallRow(
                                    name = it.name,
                                    detail = it.summary,
                                )
                                is EventMapper.Notice -> NoticeRow(it.text, it.isError)
                            }
                        }
                    }
                    // 正在生成但还没有 assistant/message 时，显示一个等待态
                    if (showPending) {
                        item { ThinkingRow() }
                    } else if (showPendingText) {
                        item {
                            MessageBubble(text = pendingText, isUser = false, streaming = true)
                        }
                    }
                }
            }
        }

        // 审批卡片。放在输入框上方、附件条之下：它会挡住发送动作，
        // 必须比「我要说什么」更显眼，但又不能盖住聊天内容。
        if (pendingApproval != null) {
            ApprovalCard(
                toolName = pendingApproval.toolName,
                reason = pendingApproval.reason,
                submitting = approvalSubmitting,
                onAllow = { onApprovalAnswer?.invoke("allowed-once") },
                onReject = { onApprovalAnswer?.invoke("rejected") },
                modifier = Modifier.padding(
                    horizontal = Dims.screenPad,
                    vertical = 6.dp,
                ),
            )
        }

        // 待发附件预览条。放在输入框**上面**而不是里面：输入框是单行自动增高，
        // 塞进去会把光标位置顶来顶去；单独一条横向滚动也更适合多图。
        if (attachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = attachments,
                onRemove = onRemoveAttachment,
            )
        }

        Composer(
            value = draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            streaming = streaming,
            onStop = onStop,
            onPickAttachment = onPickAttachment,
            attachmentCount = attachments.size,
            onPickPermission = onPickPermission,
            permissionName = permissionName,
        )
    }
}

/** 一屏上的一行：要么是单条消息，要么是一组连续的工具调用 */
private sealed interface Row {
    val key: String

    data class Single(val item: EventMapper.Item) : Row {
        override val key get() = "s${item.seq}-${item::class.simpleName}"
    }

    data class Tools(val calls: List<EventMapper.ToolCall>) : Row {
        override val key get() = "t${calls.first().seq}"
    }
}

private fun buildRows(items: List<EventMapper.Item>): List<Row> {
    val out = mutableListOf<Row>()
    var buf = mutableListOf<EventMapper.ToolCall>()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(Row.Tools(buf))
            buf = mutableListOf()
        }
    }
    for (it in items) {
        if (it is EventMapper.ToolCall) buf.add(it)
        else {
            flush()
            out.add(Row.Single(it))
        }
    }
    flush()
    return out
}

/** 折叠的一行容器，思考过程和工具组共用样式 */
@Composable
private fun CollapsibleCard(
    title: String,
    badge: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            // 收起时用页面底色 = 视觉上「没有卡片」。原来这里跟助手气泡一样是
            // Palette.Surface，两者同色，一屏堆十几行折叠条时根本分不出哪行是正文。
            // 展开才浮起来——那时用户主动点开了它，它才是主角。
            .background(if (expanded) Palette.Surface else Palette.Bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 一个右箭头旋转 90° 就是展开态，不用备两个字符（▸/▾ 各 ROM 字重不一）。
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = Palette.TextTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = title, color = Palette.TextSecondary, fontSize = Dims.metaSize)
            if (!expanded && badge.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(text = badge, color = Palette.TextTertiary, fontSize = 11.sp)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ReasoningRow(text: String) {
    var open by remember { mutableStateOf(false) }
    CollapsibleCard(
        title = "思考过程",
        badge = "${text.length} 字",
        expanded = open,
        onToggle = { open = !open },
    ) {
        Text(
            text = text,
            color = Palette.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun ToolGroupRow(calls: List<EventMapper.ToolCall>) {
    var open by remember { mutableStateOf(false) }
    CollapsibleCard(
        title = "${calls.size} 个工具调用",
        badge = calls.joinToString(" · ") { it.name }.take(40),
        expanded = open,
        onToggle = { open = !open },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            calls.forEach { c ->
                ToolCallRow(name = c.name, detail = c.summary)
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Error.copy(alpha = 0.14f))
            .padding(horizontal = Dims.screenPad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Palette.Error,
            fontSize = Dims.metaSize,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            Text(
                text = "关闭",
                color = Palette.Error,
                fontSize = Dims.metaSize,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ThinkingRow() {    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "···",
            color = Palette.TextTertiary,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Text(
                text = "正在加载更早的消息…",
                color = Palette.TextTertiary,
                fontSize = Dims.metaSize,
            )
        } else {
            Text(
                text = "↑ 点击加载更早的消息",
                color = Palette.Accent,
                fontSize = Dims.metaSize,
                modifier = Modifier.clickable(onClick = onClick),
            )
        }
    }
}

@Composable
private fun NoticeRow(text: String, isError: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPad, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) Palette.Error else Palette.TextTertiary,
            fontSize = Dims.metaSize,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, text: String) {
    // fillMaxWidth 是必须的：Column 的宽度默认只有最宽那个子元素那么宽，
    // 外面给的 align(Alignment.Center) 就居不了中——之前那只画出来的鲸鱼
    // 一直贴在左边，就是因为它太小、这个偏移没被看出来。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Feiyu(height = 170.dp)
        Box(modifier = Modifier.size(16.dp))
        Text(
            text = text,
            color = Palette.TextTertiary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}
