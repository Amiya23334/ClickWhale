package io.github.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dshmobile.ui.ChatScreen
import io.github.dshmobile.ui.ApprovalRequest
import io.github.dshmobile.ui.ArchiveScreen
import io.github.dshmobile.ui.FileManagerScreen
import io.github.dshmobile.ui.PermissionSheet
import io.github.dshmobile.ui.PluginScreen
import io.github.dshmobile.ui.ConnectScreen
import io.github.dshmobile.ui.EventMapper
import io.github.dshmobile.ui.ImageViewer
import io.github.dshmobile.ui.ModelSheet
import io.github.dshmobile.ui.NewSessionFab
import io.github.dshmobile.ui.NewSessionSheet
import io.github.dshmobile.ui.AppBackground
import io.github.dshmobile.ui.AccentPreset
import io.github.dshmobile.ui.FontPreset
import io.github.dshmobile.ui.Palette
import io.github.dshmobile.ui.ThemeMode
import io.github.dshmobile.ui.PendingAttachment
import io.github.dshmobile.ui.SessionListScreen
import io.github.dshmobile.ui.SettingsScreen
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生 UI 版主界面（替代 WebView 投屏）。
 *
 * 分两页：会话列表 → 会话详情。
 * 页面切换用手写的 var 而不是 Navigation 库——只有两个页面，
 * 引 navigation-compose 要多 4MB 依赖和一套路由概念，不值。
 */
class ChatActivity : ComponentActivity() {

    private var baseUrl: String = ""
    private lateinit var api: ApiClient
    private var mux: MuxClient? = null

    /**
     * 审批通道。**必须**独立于 mux 常开：审批事件走的是全局 `$events` 流，
     * 而不是某个会话的 follow 流。只连 mux 的话，agent 一到审批就永久卡住。
     */
    private var approvals: ApprovalChannel? = null

    /**
     * 所有等待中的审批，key 是服务端的 eventId。
     *
     * **不能用单个变量**：多会话并发时，后到的审批会把前一条覆盖掉，而 agent
     * 是**永久等待**的（不超时、不放弃），那条被覆盖的审批等于把那个会话卡死。
     * 实测这个通道是全局的，所有会话的审批都从同一条 `$events` 流出来，
     * 所以必须按 eventId 存全，界面上再按当前会话过滤。
     */
    private val pendingApprovals = mutableStateMapOf<String, ApprovalRequest>()

    /** 当前会话里优先级最高的一条待审批请求，供 UI 显示；null 表示这一屏没有。 */
    private val pendingApprovalForCurrent: ApprovalRequest?
        get() {
            val sid = currentSessionId ?: return null
            return pendingApprovals.values.firstOrNull { it.sessionId == sid }
        }

    /** 已经点了允许/拒绝，正在等回传结果；期间按钮禁用，防止连点。 */
    private var approvalSubmitting by mutableStateOf(false)

    /** 当前会话的权限预设名（`/permission` 输出的 current）。空串表示还没读到。 */
    private var permissionName by mutableStateOf("")

    /** `/permission` 报的可选预设列表。 */
    private val permissionOptions = mutableStateListOf<String>()

    /** 权限弹层是否打开。 */
    private var showPermissionSheet by mutableStateOf(false)

    /** 连接页状态。hasServer 为 false 时整页显示 ConnectScreen。 */
    private var hasServer by mutableStateOf(false)
    private var urlInput by mutableStateOf("")
    private var scanning by mutableStateOf(false)
    private var connectError by mutableStateOf<String?>(null)
    private val foundUrls = mutableStateListOf<String>()

    /** 点开的图片 URL；非空时盖一层全屏大图。 */
    private var viewingImage by mutableStateOf<String?>(null)

    // 状态（Compose 直接读这些）
    private var connected by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    /** 刚归档的会话（id to 标题）：底部弹「已归档 · 撤回」，5 秒后自动消失 */
    private var undoArchived by mutableStateOf<Pair<String, String>?>(null)
    /** 首页左上角显示名；空串表示显示默认的「DSH」 */
    private var displayName by mutableStateOf("")
    /** 中转访问密钥；空串表示这台中转没启用校验 */
    private var relayKey by mutableStateOf("")

    /**
     * App 是否在前台。用来决定回合结束时要不要弹通知——
     * 用户正看着屏幕就没必要再响一下。
     *
     * 不用 mutableStateOf：它不在 Compose 里读，改了不该触发重组。
     */
    @Volatile
    private var isInForeground = false
    private val sessions = mutableStateListOf<ApiClient.SessionSummary>()

    private var currentSessionId by mutableStateOf<String?>(null)
    private var currentTitle by mutableStateOf("")
    private var draft by mutableStateOf("")
    private var streaming by mutableStateOf(false)
    private var pendingText by mutableStateOf("")
    private val items = mutableStateListOf<EventMapper.Item>()

    /** 非空时弹重命名对话框 */
    private var renaming by mutableStateOf<ApiClient.SessionSummary?>(null)
    private var renameDraft by mutableStateOf("")

    /** 模型选择弹层 */
    private var modelSheetOpen by mutableStateOf(false)
    private var modelLoading by mutableStateOf(false)
    private val models = mutableStateListOf<ApiClient.ModelOption>()
    private var currentProvider by mutableStateOf("")
    private var currentModel by mutableStateOf("")

    /** workspace 只探测一次，之后复用 */
    private var workspaceId: String? = null

    /**
     * 历史翻页状态。
     *
     * [throughSeq] 来自 follow 开场帧的 `value.cursor`（与 session/page 的 throughSeq 同值），
     * 整条会话期间**保持不变**，不要每页重算。
     * [oldestSeq] 是当前已加载的最老事件 seq，下一页的 `beforeSeq` 传它。
     * [hasMoreHistory] 跟开场帧的 `hasMore` 走；为 false 就不显示「加载更多」。
     */
    private var throughSeq by mutableStateOf(-1L)
    private var oldestSeq by mutableStateOf(-1L)
    private var hasMoreHistory by mutableStateOf(false)
    private var loadingMore by mutableStateOf(false)

    // ---- 工作区 / 归档 ----
    /** workspaceId to title；来自 workspace/follow 的 baseline 帧 */
    private val workspaces = mutableStateListOf<Pair<String, String>>()
    /** 空串 = 全部工作区 */
    private var currentWorkspaceId by mutableStateOf("")
    /** 每个工作区下的 sessionId（用于本地过滤，避免为切工作区再发一次 RPC） */
    private var wsSessionIds: Map<String, List<String>> = emptyMap()
    private val archivedIds = mutableStateListOf<String>()
    /** 归档会话的标题缓存（session/list 里没有它们） */
    private val archivedTitles = mutableStateMapOf<String, String>()
    /** 是否正显示归档页 */
    private var showArchive by mutableStateOf(false)
    private var archiveLoading by mutableStateOf(false)

    /** 是否正显示插件页 */
    private var showPlugins by mutableStateOf(false)
    private var showFiles by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    /** 新建对话的工作区选择弹层 */
    private var showNewSheet by mutableStateOf(false)
    private val plugins = mutableStateListOf<ApiClient.PluginInfo>()
    private var pluginsLoading by mutableStateOf(false)
    private var pluginsError by mutableStateOf<String?>(null)

    /** 待发送的附件（选完还没发出去） */
    private val attachments = mutableStateListOf<PendingAttachment>()

    /** 正在上传/组包（发送按钮转圈用） */
    private var sendingAttachments by mutableStateOf(false)

    /**
     * 文件选择器。
     *
     * 用 `OpenMultipleDocuments` 而不是 `PickVisualMedia`：
     *   - PickVisualMedia 只能选图/视频，但我们还要发文本、zip、apk 这些；
     *   - `OpenMultipleDocuments` 走 SAF，**不需要任何存储权限**
     *     （内容 URI 由系统授权给本次选择），也不用申请 READ_MEDIA_IMAGES。
     *
     * MIME 过滤器传通配（任意类型）看起来很粗，但 SAF 会把用户限制在自己
     * 能看到的文件里，而且我们后面会按 MIME 决定走内联还是上传。
     */
    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (uri in uris) {
            val meta = readUriMeta(uri) ?: continue
            // 同一个文件选两次就跳过，免得发出去两份一样的
            if (attachments.any { it.uri == meta.uri }) continue
            attachments.add(meta)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 作为启动入口时没人给它传 baseUrl，这时先落到连接页；
        // 连上之后再建 ApiClient。以前是 MainActivity 拿着 WebView 当首屏，
        // 这里改成原生界面直接当前台。
        val passed = intent.getStringExtra("baseUrl")
        val saved = savedBaseUrl()
        baseUrl = passed ?: saved

        // 主题要在第一次组合之前定好，否则会先闪一下默认夜间再跳成日间
        applySavedTheme()
        setContent {
            // 字体：LocalTextStyle 是所有 Text 的默认值，改这一处就全 App 生效，
            // 不用给一百多个 Text 逐个加 fontFamily。
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(
                    fontFamily = Palette.fontFamily
                )
            ) {
                // 背景层：底色 + 可选自定义图。各页面已经不再自己画底色。
                AppBackground { Root() }
            }
        }

        // Android 16+ 不给这个权限，App 自己的 Socket 连局域网会被静默拒绝
        // ——不是报错，是「请求发出去了但永远没响应」，极难查。
        // 以前这段在 MainActivity 里，改成 ChatActivity 当入口后必须搬过来，
        // 否则装完就卡在「未连接」。
        ensureLocalNetworkPermission()
        ensureNotificationPermission()

        if (baseUrl.isNotBlank()) {
            bindAndLoad(baseUrl)
        } else {
            urlInput = saved
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // 回到前台说明用户已经看到了，断线提示和回合结束的提醒都该撤
        Notifier.clearDisconnected(this)
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel("turnend", 0)
        }
        // 人在前台不需要保活条——前台进程本来就是最高优先级，
        // 常驻通知只会碍眼。切后台时 onPause 再拉起。
        KeepAliveService.stop(this)
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        // 切后台才需要保活：否则进程被降级成 cached，WebSocket 立刻被掐，
        // turn/end 事件根本到不了手机，通知逻辑再对也没用。
        // 只要连上了就保活——不要求当前打开了某个会话，会话列表停在后台
        // 也可能收到别的会话跑完的事件。
        if (baseUrl.isNotBlank()) {
            runCatching { KeepAliveService.start(this) }
                .onFailure { Log.w(TAG, "start keepalive failed", it) }
        }
    }

    /** 缺局域网权限就申请；没有这个权限的旧系统直接返回 true。 */
    private fun ensureLocalNetworkPermission(): Boolean {        if (Build.VERSION.SDK_INT < 36) return true
        if (checkSelfPermission(PERM_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_LOCAL_NET)
        return false
    }

    /**
     * 申请通知权限（Android 13+）。
     *
     * 被拒也不影响 App 使用，只是收不到「回合结束」提醒，所以不提示、不重试。
     * 注意要在局域网权限之后再申请：两个 requestPermissions 连着发，系统只弹一个，
     * 先申请更重要的那个。
     */
    private fun ensureNotificationPermission() {
        Notifier.ensureChannel(this)
        if (!Notifier.needsPermission(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), Notifier.REQ_NOTIFICATION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCAL_NET) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 授权是异步的，前面那些请求多半已经静默失败了，重拉一次。
                if (baseUrl.isNotBlank()) refreshSessions()
            } else {
                connectError = "需要「本地网络」权限才能连电脑，请到系统设置里开启"
            }
        }
    }

    private fun savedBaseUrl(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER_URL, "") ?: ""

    private fun persistBaseUrl(url: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url).apply()
    }

    /** 日间/夜间存在 prefs 里，下次启动接着用。默认夜间（用惯的那套）。 */
    private fun applySavedTheme() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        Palette.mode = when (prefs.getString(KEY_THEME_MODE, "system")) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        displayName = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        Palette.accent = runCatching {
            AccentPreset.valueOf(prefs.getString(KEY_ACCENT, "") ?: "")
        }.getOrDefault(AccentPreset.BLUE)
        Palette.font = runCatching {
            FontPreset.valueOf(prefs.getString(KEY_FONT, "") ?: "")
        }.getOrDefault(FontPreset.SYSTEM)
        Palette.backgroundUri = prefs.getString(KEY_BG_URI, "") ?: ""
        Palette.bgBlur = prefs.getInt(KEY_BG_BLUR, 0)
        Palette.bgAlpha = prefs.getFloat(KEY_BG_ALPHA, 1f)
        relayKey = prefs.getString(KEY_RELAY_KEY, "") ?: ""
        RelayAuth.key = relayKey
        Palette.customFontPath = prefs.getString(KEY_FONT_FILE, "") ?: ""
        Palette.customFontName = prefs.getString(KEY_FONT_NAME, "") ?: ""
        // 恢复完路径再算一次，否则刚启动就切到 CUSTOM 档时拿不到字体文件
        Palette.refreshFontFamily()
        applySystemBarAppearance()
    }

    /**
     * 设/清背景图。存的是 content:// URI——读权限必须在选图那一步
     * 用 takePersistableUriPermission 持久化，否则进程重启后这张图就读不出来了。
     */
    /**
     * 把用户选的字体文件复制到 App 私有目录。
     *
     * 不能直接存 content:// URI：Compose 的 Font(File) 要的是真实路径，
     * 而且相册/文件管理器给的 URI 权限随时可能失效。复制一份几百 KB 的副本最稳。
     */
    private fun installCustomFont(uri: android.net.Uri, displayName: String) {
        val dir = java.io.File(filesDir, "fonts").apply { mkdirs() }
        val dest = java.io.File(dir, "custom_font")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!ok) {
            error = "字体复制失败"
            return
        }
        Palette.customFontPath = dest.absolutePath
        Palette.customFontName = displayName
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_FONT_FILE, dest.absolutePath)
            .putString(KEY_FONT_NAME, displayName)
            .putString(KEY_FONT, FontPreset.CUSTOM.name)
            .apply()
        setFont(FontPreset.CUSTOM)
    }

    /**
     * 断开当前中转，退回连接页。
     *
     * 只清本地的连接状态（mux / 会话 / 地址），不碰电脑端任何东西——
     * 调试时最常做的就是「断开重连」，不该为此杀进程重进。
     */
    private fun disconnect() {
        showSettings = false
        mux?.close()
        mux = null
        currentSessionId = null
        currentTitle = ""
        items.clear()
        draft = ""
        attachments.clear()
        streaming = false
        connected = false
        error = ""
        hasServer = false
        baseUrl = ""
        persistBaseUrl("")
        foundUrls.clear()
    }

    private fun setBackgroundBlur(level: Int) {
        Palette.bgBlur = level
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_BG_BLUR, level).apply()
    }

    private fun setBackgroundAlpha(value: Float) {
        Palette.bgAlpha = value
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_BG_ALPHA, value).apply()
    }

    private fun setBackgroundUri(uri: String) {
        Palette.backgroundUri = uri
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_BG_URI, uri).apply()
    }

    private fun setAccent(preset: AccentPreset) {
        Palette.accent = preset
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, preset.name).apply()
    }

    /** 关于页要显示的名字：直接用系统读到的应用名，别写死（改名会不同步）。 */
    private fun appName(): String =
        runCatching {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault("点击肥鱼")

    /** 版本号：用户报问题时第一句会问「你装的哪版」。 */
    private fun versionName(): String =
        runCatching {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")

    /** 打开开源仓库地址。 */
    private fun openRepo() {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(REPO_URL)
                )
            )
        }
    }

    private fun setFont(preset: FontPreset) {
        Palette.font = preset
        // 必须重算：fontFamily 是 State，不改它界面不会重组，切字体看着没反应
        Palette.refreshFontFamily()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_FONT, preset.name).apply()
    }

    /** 存显示名。名字不能叫 setDisplayName——那是 displayName 这个 var 自动生成的 setter。 */
    private fun saveDisplayName(name: String) {
        displayName = name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DISPLAY_NAME, name)
            .apply()
    }

    private fun setThemeMode(mode: ThemeMode) {
        Palette.mode = mode
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(
                KEY_THEME_MODE,
                when (mode) {
                    ThemeMode.LIGHT -> "light"
                    ThemeMode.DARK -> "dark"
                    ThemeMode.SYSTEM -> "system"
                },
            )
            .apply()
        applySystemBarAppearance()
    }

    /**
     * 状态栏/导航栏图标颜色。
     *
     * 不跟着主题走的话，日间模式下状态栏那排时间是白字白图标，贴在浅底上根本看不见
     * （截图里 02:16 那片几乎全消失）。浅底要深色图标，反之亦然。
     */
    private fun applySystemBarAppearance() {
        val light = !Palette.dark
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** 拿到（或换到）一个中转地址之后，重建 ApiClient 并拉会话。 */
    private fun bindAndLoad(url: String) {
        baseUrl = url
        api = ApiClient(url)
        persistBaseUrl(url)
        Log.i(TAG, "baseUrl=$url")
        // 必须走 state 才能让 Compose 从连接页切到会话列表；
        // 之前只改普通字段，界面不会重组，表现就是「点了连接没反应」。
        hasServer = true
        startApprovalChannel(url)
        refreshSessions()
    }

    /**
     * 起审批通道。失败不阻断主流程——连不上审批流只是不能批工具，
     * 普通对话仍然能用，比整个 App 卡在连接页好。
     */
    private fun startApprovalChannel(url: String) {
        approvals?.close()
        val ch = ApprovalChannel(url, object : ApprovalChannel.Listener {
            override fun onApprovalAsked(
                id: String,
                agentId: String,
                toolName: String,
                reason: String,
            ) {
                runOnUiThread {
                    // 按 eventId 全存着，不覆盖：全局流上可能同时挂着多个会话的审批，
                    // 而 agent 不会超时，丢一条就把那个会话永久挂住。
                    pendingApprovals[id] = ApprovalRequest(id, agentId, toolName, reason)
                    approvalSubmitting = false
                    Notifier.show(
                        this@ChatActivity,
                        "approval",
                        "DSH 等你授权",
                        "$toolName 需要你确认",
                    )
                }
            }

            override fun onApprovalConnected() {
                Log.i(TAG, "approval channel connected")
            }

            override fun onApprovalDisconnected(reason: String) {
                Log.w(TAG, "approval channel disconnected: $reason")
            }
        })
        approvals = ch
        ch.connect()
    }

    /**
     * 回传审批决定。
     *
     * [answer] 只能是 `allowed-once` 或 `rejected`——DSH 的 `ApprovalOutcome`
     * 还有 `cancelled`/`unavailable`，但那是系统侧语义，界面不该给用户这两个按钮。
     */
    /** 读当前会话的权限预设与可选项。 */
    private fun refreshPermission() {
        val sid = currentSessionId ?: return
        api.getPermissionPreset(sid) { res ->
            runOnUiThread {
                res.onSuccess { st ->
                    permissionName = st.current
                    permissionOptions.clear()
                    permissionOptions.addAll(st.available)
                }.onFailure {
                    Log.w(TAG, "read permission failed", it)
                }
            }
        }
    }

    /**
     * 换权限。走 `/permission <名字>` 这条 slash 命令——**没有对应的 RPC**。
     * 成功后立刻重读一次，因为服务端会做归一化（比如切到当前已生效的预设时无变化）。
     */
    private fun setPermission(name: String) {
        val sid = currentSessionId ?: return
        showPermissionSheet = false
        api.executeCommand(sid, "/permission $name") { res ->
            runOnUiThread {
                res.onSuccess { cr ->
                    if (!cr.ok) {
                        error = "换权限失败：${cr.text}"
                        return@onSuccess
                    }
                    Log.i(TAG, "permission -> $name")
                    refreshPermission()
                }.onFailure {
                    error = "换权限失败：${it.message ?: "网络错误"}"
                }
            }
        }
    }

    private fun answerApproval(answer: String) {
        // 只回答**当前会话**的审批。别的会话的请求留在 map 里，
        // 等用户切过去再处理——不能在这里顺手替它答了。
        val req = pendingApprovalForCurrent ?: return
        if (approvalSubmitting) return
        approvalSubmitting = true
        approvals?.answer(req.id, answer) { ok ->
            runOnUiThread {
                approvalSubmitting = false
                if (ok) {
                    pendingApprovals.remove(req.id)
                } else {
                    // 回传失败不清卡片：清了用户就没法重试，agent 还在等。
                    error = "授权没送出去，请再点一次"
                }
            }
        }
    }

    /** 连接页点「连接」：补全协议头、落盘、再绑定。 */
    private fun connectFromInput() {
        val raw = urlInput.trim()
        if (raw.isEmpty()) {
            connectError = "地址不能为空"
            return
        }
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        connectError = null
        // 密钥要在建 ApiClient 之前生效——所有请求都会读 RelayAuth.key
        RelayAuth.key = relayKey.trim()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_RELAY_KEY, relayKey.trim()).apply()
        bindAndLoad(url)
    }

    /** 扫局域网找中转。 */
    private fun scanForRelay() {
        if (scanning) return
        scanning = true
        foundUrls.clear()
        connectError = null
        Discovery.scan(
            cb = { found ->
                val u = found.url
                if (!foundUrls.contains(u)) foundUrls.add(u)
            },
            onDone = {
                scanning = false
                if (foundUrls.isEmpty()) {
                    connectError = "没找到电脑。检查：① 电脑上跑了 relay/start-relay.cmd；" +
                        "② 手机和电脑在同一个 Wi-Fi；③ 或者上面手动填地址。"
                }
            }
        )
    }

    @Composable
    private fun Root() {
        // 跟随系统：只有 Compose 层拿得到系统的深浅色，同步给 Palette；
        // 系统切换深色（比如日落自动）时会重新进来，界面当场跟着变。
        val systemDark = isSystemInDarkTheme()
        LaunchedEffect(systemDark) {
            Palette.systemDark = systemDark
            applySystemBarAppearance()
        }

        // 背景图选择。选完必须把读权限持久化——否则进程重启后
        // 这个 content:// URI 就再也打不开了（图直接消失，还不报错）。
        // 选字体文件。复制到私有目录，见 installCustomFont 的注释。
        val pickFont = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "自定义字体"
                installCustomFont(uri, name)
            }
        }

        val pickBackground = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                setBackgroundUri(uri.toString())
            }
        }

        // 归档提示 5 秒后自己消失。key 用 undoArchived：连按两次归档会重置计时。
        LaunchedEffect(undoArchived) {
            if (undoArchived != null) {
                delay(5000)
                undoArchived = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // 返回键。整个 App 之前一个返回处理都没有，聊天页侧滑直接退回桌面，
            // 用户前面几层全靠重进——这里按「从哪来回哪去」逐层退：
            // 全屏图 → 模型弹层 → 重命名框 → 插件/归档页 → 会话 → 退出。
            BackHandler(enabled = true) {
                when {
                    viewingImage != null -> viewingImage = null
                    showPermissionSheet -> showPermissionSheet = false
                    modelSheetOpen -> modelSheetOpen = false
                    renaming != null -> renaming = null
                    showSettings -> showSettings = false
                    showFiles -> showFiles = false
                    showPlugins -> showPlugins = false
                    showArchive -> showArchive = false
                    currentSessionId != null -> closeSession()
                    else -> finish()
                }
            }

            // 没有地址就没得连，先给连接页。
            // 以前这一步是 MainActivity 的 WebView 首屏 + 一个鲸鱼悬浮按钮，
            // 现在直接从原生界面进来，少跳一层。
            if (!hasServer) {
                ConnectScreen(
                    url = urlInput,
                    onUrlChange = { urlInput = it },
                    key = relayKey,
                    onKeyChange = { relayKey = it },
                    scanning = scanning,
                    found = foundUrls,
                    error = connectError,
                    onConnect = { connectFromInput() },
                    onScan = { scanForRelay() },
                )
                return@Box
            }

            // 列表 ↔ 会话：这两层原来是真的硬切（同一个 Activity 里的状态切换，
            // 没有系统转场可用）。AnimatedContent 给一个方向感：进会话从右滑入，
            // 回来从左滑入，退出的一方反向小幅滑走，看起来才像"翻了一页"。
            AnimatedContent(
                targetState = currentSessionId,
                transitionSpec = {
                    val enter = if (targetState != null) {
                        slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(200))
                    } else {
                        slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(200))
                    }
                    val exit = if (targetState != null) {
                        slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(160))
                    } else {
                        slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(160))
                    }
                    enter togetherWith exit
                },
                label = "list-chat",
            ) { sid ->
            Box(modifier = Modifier.fillMaxSize()) {
            if (sid == null) {
                if (showSettings) {
                    SettingsScreen(
                        baseUrl = baseUrl,
                        displayName = displayName,
                        onDisplayNameChange = { saveDisplayName(it) },
                        themeMode = Palette.mode,
                        onThemeModeChange = { setThemeMode(it) },
                        accent = Palette.accent,
                        onAccentChange = { setAccent(it) },
                        font = Palette.font,
                        onFontChange = { setFont(it) },
                        appName = appName(),
                        versionName = versionName(),
                        onOpenRepo = { openRepo() },
                        backgroundUri = Palette.backgroundUri,
                        onPickBackground = { pickBackground.launch(arrayOf("image/*")) },
                        bgBlur = Palette.bgBlur,
                        onBgBlurChange = { setBackgroundBlur(it) },
                        bgAlpha = Palette.bgAlpha,
                        onBgAlphaChange = { setBackgroundAlpha(it) },
                        onClearBackground = { setBackgroundUri("") },
                        onDisconnect = { disconnect() },
                        customFontName = Palette.customFontName,
                        onPickFont = { pickFont.launch(arrayOf("*/*")) },
                        onBack = { showSettings = false },
                    )
                    return@Box
                }
                if (showPlugins) {
                    PluginScreen(
                        plugins = plugins,
                        loading = pluginsLoading,
                        error = pluginsError,
                        onBack = { showPlugins = false },
                        onRefresh = { refreshPlugins() },
                    )
                    return@Box
                }
                if (showFiles) {
                    FileManagerScreen(
                        baseUrl = baseUrl,
                        onBack = { showFiles = false },
                    )
                    return@Box
                }
                if (showArchive) {
                    ArchiveScreen(
                        ids = archivedIds,
                        titles = archivedTitles,
                        loading = archiveLoading,
                        onBack = { showArchive = false },
                        onUnarchive = { unarchive(it) },
                    )
                    return@Box
                }
                // 按工作区过滤：选中某个工作区时只显示归属它的会话。
                // 用 baseline 里的 sessionIds 做本地过滤，不为切换再打一次 RPC。
                // blank 会话（建了没发过消息的空壳）直接不显示：DSH 自己会复用它们，
                // 列出来就是一堆「未命名会话」，纯噪音。
                val notBlank = sessions.filterNot { it.blank }
                val visible = if (currentWorkspaceId.isEmpty()) {
                    notBlank
                } else {
                    val allowed = wsSessionIds[currentWorkspaceId].orEmpty().toSet()
                    notBlank.filter { it.sessionId in allowed }
                }
                SessionListScreen(
                    sessions = visible,
                    connected = connected,
                    loading = loading,
                    error = error,
                    onOpen = { openSession(it) },
                    onNewSession = { createSession() },
                    onRefresh = { refreshSessions() },
                    onRename = { target ->
                        renameDraft = target.title
                        renaming = target
                    },
                    onArchive = { archive(it) },
                    undoTitle = undoArchived?.second,
                    onUndo = {
                        undoArchived?.first?.let { id ->
                            undoArchived = null
                            unarchive(id)
                        }
                    },
                    workspaces = workspaces,
                    currentWorkspaceId = currentWorkspaceId,
                    onSelectWorkspace = { currentWorkspaceId = it },
                    archivedCount = archivedIds.size,
                    onOpenArchive = {
                        showArchive = true
                        archiveLoading = archivedTitles.size < archivedIds.size
                    },
                    onOpenPlugins = {
                        showPlugins = true
                        refreshPlugins()
                    },
                    onOpenFiles = { showFiles = true },
                    onOpenSettings = { showSettings = true },
                    displayName = displayName,
                )
                NewSessionFab(
                    onClick = { showNewSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(20.dp)
                )
            } else {
                ChatScreen(
                    sessionTitle = currentTitle,
                    connected = connected,
                    items = items,
                    streaming = streaming,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sendMessage() },
                    onStop = { stopStreaming() },
                    pendingText = pendingText,
                    modelName = models.firstOrNull {
                        it.provider == currentProvider && it.model == currentModel
                    }?.name ?: currentModel,
                    onPickModel = { openModelSheet() },
                    attachments = attachments,
                    onPickAttachment = { pickFiles.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { attachments.remove(it) },
                    relayBase = baseUrl,
                    hasMoreHistory = hasMoreHistory,
                    loadingMore = loadingMore,
                    onLoadMore = { loadMoreHistory() },
                    onOpenImage = { url -> viewingImage = url },
                    notice = if (sendingAttachments) "" else (error ?: ""),
                    onDismissNotice = { error = "" },
                    pendingApproval = pendingApprovalForCurrent,
                    approvalSubmitting = approvalSubmitting,
                    onApprovalAnswer = { answer -> answerApproval(answer) },
                    onPickPermission = { showPermissionSheet = true },
                    permissionName = permissionName,
                    onBack = { closeSession() },
                )
            }
            }
            }

            // 全屏看大图。放在最外层 Box 里，盖住顶栏和输入框。
            viewingImage?.let { url ->
                ImageViewer(url = url, onDismiss = { viewingImage = null })
            }

            if (showNewSheet) {
                NewSessionSheet(
                    workspaces = workspaces.toList(),
                    currentWorkspaceId = currentWorkspaceId,
                    onCreate = { createSessionIn(it) },
                    onDismiss = { showNewSheet = false },
                )
            }

            if (modelSheetOpen) {
                ModelSheet(
                    models = models,
                    currentProvider = currentProvider,
                    currentModel = currentModel,
                    loading = modelLoading,
                    onPick = { pickModel(it) },
                    onDismiss = { modelSheetOpen = false },
                )
            }

            if (showPermissionSheet) {
                PermissionSheet(
                    current = permissionName,
                    available = permissionOptions,
                    onSelect = { setPermission(it) },
                    onDismiss = { showPermissionSheet = false },
                )
            }

            renaming?.let { target ->
                RenameDialog(
                    initial = renameDraft,
                    onDismiss = { renaming = null },
                    onConfirm = { newTitle ->
                        renaming = null
                        doRename(target, newTitle)
                    },
                )
            }
        }
    }

    // ───────────── 数据 ─────────────

    private fun refreshSessions() {
        loading = true
        error = null
        refreshWorkspaceSnapshot()
        ensureWorkspace { wsId ->
            if (wsId == null) {
                loading = false
                error = "拿不到工作区，检查电脑端中转是否在跑"
                return@ensureWorkspace
            }
            connected = true
            api.listSessions(wsId) { r ->
                loading = false
                r.onSuccess { list ->
                    Log.i(TAG, "listSessions 成功，wsId=$wsId，条数=${list.size}")
                    sessions.clear()
                    sessions.addAll(list.sortedByDescending { it.updatedAt })
                    error = null
                }.onFailure { e ->
                    Log.w(TAG, "listSessions 失败：${e.message}")
                    connected = false
                    if (e is RelayUnauthorized) {
                        hasServer = false
                        connectError = "访问密钥不对，或电脑端改过密钥"
                    }
                    error = "拉会话失败：${e.message}"
                }
            }
        }
    }

    /**
     * 拉工作区列表 + 归档集合。
     *
     * 网关没有 unary 的「列工作区/列归档」端点，唯一来源是 `workspace/follow` 的
     * baseline 帧（stream 方法，只能走 mux）。归档集合也要它——`session/list`
     * 直接把已归档会话滤掉了，光看它根本不知道有东西被藏起来。
     */
    private fun refreshWorkspaceSnapshot() {
        WorkspaceProbe(baseUrl).fetch { r ->
            r.onSuccess { snap ->
                workspaces.clear()
                workspaces.addAll(snap.workspaces)
                wsSessionIds = snap.sessionIds
                archivedIds.clear()
                archivedIds.addAll(snap.archivedSessionIds)
                Log.i(TAG, "workspace 快照：${snap.workspaces.size} 个工作区，${snap.archivedSessionIds.size} 条归档")
                // 归档会话不在 session/list 里，标题得单独问
                if (snap.archivedSessionIds.isNotEmpty()) {
                    api.fetchTitles(snap.archivedSessionIds) { tr ->
                        tr.onSuccess { map ->
                            archivedTitles.clear()
                            archivedTitles.putAll(map)
                        }
                    }
                }
            }
            r.onFailure { e ->
                Log.w(TAG, "workspace 快照失败：${e.message}")
            }
        }
    }

    /** 撤销归档并刷新列表 */
    private fun unarchive(sessionId: String) {
        api.unarchiveSession(sessionId) { r ->
            r.onSuccess {
                archivedIds.remove(sessionId)
                refreshSessions()
            }
            r.onFailure { e ->
                error = "撤销归档失败：${e.message}"
            }
        }
    }

    /**
     * 拉插件列表。
     *
     * 数据来自中转透传的 `/dsh-market/installed`，不是网关那套 client-request。
     * 失败不清空已有列表——用户切出去再回来时看到旧数据比看到空白页好。
     */
    private fun refreshPlugins() {
        if (baseUrl.isBlank()) return
        pluginsLoading = true
        pluginsError = null
        api.listPlugins { r ->
            runOnUiThread {
                pluginsLoading = false
                r.onSuccess { list ->
                    plugins.clear()
                    plugins.addAll(list)
                }
                r.onFailure { e ->
                    pluginsError = "读取插件列表失败：${e.message}"
                }
            }
        }
    }

    private fun ensureWorkspace(cb: (String?) -> Unit) {        workspaceId?.let { cb(it); return }
        api.listWorkspaces { r ->
            r.onSuccess { list ->
                val id = list.firstOrNull()?.first
                workspaceId = id
                cb(id?.takeIf { it.isNotEmpty() })
            }.onFailure { e ->
                if (e is RelayUnauthorized) {
                    // 中转启用了密钥校验。这里必须退回连接页——不然用户看到的是
                    // 「拿不到工作区，检查中转是否在跑」，方向完全错了。
                    hasServer = false
                    connectError = "电脑端启用了密钥校验，把访问密钥填上再连"
                }
                cb(null)
            }
        }
    }

    private fun createSession() {
        ensureWorkspace { ws ->
            if (ws == null) { error = "没有工作区"; return@ensureWorkspace }
            api.createSession(ws) { r ->
                r.onSuccess { sid ->
                    openSession(ApiClient.SessionSummary(sid, "新会话", true, true, System.currentTimeMillis(), ""))
                }.onFailure { e -> error = "新建失败：${e.message}" }
            }
        }
    }

    /**
     * 在指定工作区里新建会话。
     *
     * 跟 [createSession] 的区别：这里不再去猜「用哪个工作区」——那个是用户点的。
     */
    private fun createSessionIn(workspaceId: String) {
        showNewSheet = false
        api.createSession(workspaceId) { r ->
            r.onSuccess { sid ->
                openSession(
                    ApiClient.SessionSummary(
                        sid, "新会话", true, true, System.currentTimeMillis(), ""
                    )
                )
            }.onFailure { e -> error = "新建失败：${e.message}" }
        }
    }

    private fun openSession(s: ApiClient.SessionSummary) {
        currentSessionId = s.sessionId
        currentTitle = s.title
        items.clear()
        pendingText = ""
        streaming = s.running
        error = ""
        // 权限是**会话级**的，换会话必须重新读，不能沿用上一个会话的值。
        refreshPermission()

        mux?.close()
        mux = MuxClient(baseUrl, object : MuxClient.Listener {
            override fun onConnected() {
                connected = true
                Notifier.clearDisconnected(this@ChatActivity)
                Log.i(TAG, "mux connected")
            }

            override fun onDisconnected(reason: String) {
                connected = false
                // 断线时不要把 streaming 置 false：回合可能还在服务端跑，
                // 置 false 会让用户以为已经结束了，重连回来又「凭空」蹦出新消息。
                // 保留 streaming，等重连后的 snapshot 用 turn/start|turn/end 重新判断。
                Log.w(TAG, "mux disconnected: $reason")
            }

            override fun onSnapshot(sessionId: String, events: List<JSONObject>, cursor: Long, hasMore: Boolean) {
                items.clear()
                for (ev in events) {
                    items.addAll(EventMapper.mapAll(ev))
                }
                // 翻页游标：cursor 就是 throughSeq，events 里最小的 seq 是下一页的 beforeSeq
                throughSeq = cursor
                hasMoreHistory = hasMore
                oldestSeq = events.mapNotNull { it.optLong("seq", -1L).takeIf { s -> s > 0 } }
                    .minOrNull() ?: -1L
                // 快照里可能带着进行中的回合
                streaming = events.any {
                    it.optString("type") == "turn/start"
                } && !events.any { it.optString("type") == "turn/end" }
                // 模型不在快照事件里（records 是事件流，没有 projections），
                // 单独请求一次投影表拿 modelSelection
                loadCurrentModel(sessionId)
            }

            override fun onEvent(sessionId: String, event: JSONObject) {
                val type = event.optString("type")
                when (type) {
                    "turn/start" -> { streaming = true; pendingText = "" }
                    "turn/end" -> {
                        streaming = false
                        pendingText = ""
                        notifyTurnEnd(event)
                    }
                    "tool/result" -> {
                        // 并进对应的工具行
                        EventMapper.toolResult(event)?.let { (callId, text, isErr) ->
                            val idx = items.indexOfLast { it is EventMapper.ToolCall && it.callId == callId }
                            if (idx >= 0) {
                                val old = items[idx] as EventMapper.ToolCall
                                items[idx] = old.copy(result = text, isError = isErr)
                            }
                        }
                    }
                    else -> {
                        items.addAll(EventMapper.mapAll(event))
                    }
                }
            }
        })
        mux?.connect(s.sessionId)
    }

    /**
     * 回合结束时弹通知。
     *
     * 只在 App 不在前台时弹：用户正盯着屏幕看它一个字一个字写出来，
     * 这时候再弹一条「回复完成」纯属打扰。
     *
     * 也不通知 connect/disconnect 这种噪音——只在真的产出了结果时说话。
     */
    private fun notifyTurnEnd(event: JSONObject) {
        if (isInForeground) return
        val reason = event.optJSONObject("data")?.optJSONObject("reason")
        val kind = reason?.optString("kind", "") ?: ""
        if (kind == "error") {
            val msg = reason?.optString("message", "执行出错") ?: "执行出错"
            Notifier.show(this, "turnend", "DSH 执行出错", msg)
        } else {
            Notifier.show(this, "turnend", "DSH 回复完成", "agent 这一回合跑完了")
        }
    }

    private fun sendMessage() {
        val sid = currentSessionId ?: return
        val text = draft.trim()
        val pending = attachments.toList()
        if (text.isEmpty() && pending.isEmpty()) return
        if (sendingAttachments) return

        // 先清输入区，让用户能立刻接着打字。失败时再把文字放回去。
        draft = ""
        attachments.clear()
        streaming = true
        pendingText = ""

        if (pending.isEmpty()) {
            api.sendPrompt(sid, text) { r ->
                r.onFailure { e ->
                    streaming = false
                    error = "发送失败：${e.message}"
                    draft = text
                    Log.e(TAG, "sendPrompt failed", e)
                }
            }
            return
        }

        // 有附件：组包要在后台线程做（读文件、base64、上传都是阻塞的）。
        // 上传是异步回调接口，这里用 CountDownLatch 把它拉平成循环，
        // 顺序就是用户选文件的顺序——附件顺序对模型理解有影响。
        sendingAttachments = true
        Thread {
            val parts = mutableListOf<ApiClient.OutgoingPart>()
            if (text.isNotBlank()) parts.add(ApiClient.OutgoingPart.Text(text))
            var failure: String? = null

            for (a in pending) {
                val bytes = readBytes(a.uri)
                if (bytes == null) {
                    failure = "读不到「${a.name}」"
                    break
                }
                if (bytes.size > MAX_ATTACHMENT_BYTES) {
                    failure = "「${a.name}」超过 ${PendingAttachment.formatSize(MAX_ATTACHMENT_BYTES)}"
                    break
                }
                if (a.isInlineImage) {
                    // 图片内联进 content，不上传（网关 schema 就是这样定义的）
                    parts.add(
                        ApiClient.OutgoingPart.Image(
                            mediaType = a.mime,
                            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            name = a.name,
                        )
                    )
                } else {
                    val latch = CountDownLatch(1)
                    var receipt: String? = null
                    var err: String? = null
                    api.uploadFile(sid, bytes, a.name) { r ->
                        r.onSuccess { receipt = it }
                        r.onFailure { err = it.message }
                        latch.countDown()
                    }
                    latch.await(60, TimeUnit.SECONDS)
                    val got = receipt
                    if (got.isNullOrBlank()) {
                        failure = err ?: "「${a.name}」上传超时"
                        break
                    }
                    parts.add(ApiClient.OutgoingPart.FileRef(got, a.name))
                }
            }

            runOnUiThread {
                sendingAttachments = false
                val f = failure
                if (f != null) {
                    streaming = false
                    error = "发送失败：$f"
                    draft = text
                    return@runOnUiThread
                }
                api.sendParts(sid, parts) { r ->
                    r.onFailure { e ->
                        streaming = false
                        error = "发送失败：${e.message}"
                        draft = text
                        Log.e(TAG, "sendParts failed", e)
                    }
                }
            }
        }.start()
    }

    /** 从 content:// 读出元信息。SAF 不需要存储权限，这里只查询名字和大小。 */
    private fun readUriMeta(uri: Uri): PendingAttachment? {
        var name = "未命名"
        var size = -1L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "query meta failed: $uri", e)
        }
        val mime = contentResolver.getType(uri) ?: guessMime(name)
        return PendingAttachment(uri.toString(), name, mime, size)
    }

    private fun readBytes(uriStr: String): ByteArray? = try {
        contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "read bytes failed: $uriStr", e)
        null
    }

    /**
     * 往回翻一页历史。
     *
     * 游标规则（实测 2026-10-29）：`throughSeq` 固定用开场帧的 cursor；`beforeSeq`
     * 传当前已加载的最老 seq。相邻两页 seq 刚好差 1、无重叠（翻 4 页验过）。
     * `maxMessages` 数的是**消息**不是事件，传 20 实际回来一百多条记录，
     * 所以这里用 15 就够一屏。
     *
     * 新事件要 **addAll(0, ...)** 插到最前面，不能 addAll 追加到尾部。
     */
    private fun loadMoreHistory() {
        val sid = currentSessionId ?: return
        if (loadingMore || !hasMoreHistory) return
        if (throughSeq <= 0 || oldestSeq <= 0) return
        loadingMore = true
        api.loadPage(sid, throughSeq, oldestSeq, 15) { r ->
            loadingMore = false
            r.onSuccess { page ->
                // 防重：游标必须往前推进，否则说明服务端没给更老的内容，
                // 再点只会把同一段历史重复插进列表（而且会无限循环）。
                if (page.minSeq <= 0 || page.minSeq >= oldestSeq) {
                    hasMoreHistory = false
                    return@onSuccess
                }
                val mapped = ArrayList<EventMapper.Item>()
                for (ev in page.events) mapped.addAll(EventMapper.mapAll(ev))
                if (mapped.isNotEmpty()) {
                    items.addAll(0, mapped)
                }
                oldestSeq = page.minSeq
                hasMoreHistory = page.hasMore
            }
            r.onFailure { e ->
                Log.w(TAG, "loadPage 失败：${e.message}")
                hasMoreHistory = false
            }
        }
    }

    /**
     * 停掉当前回合。以前这里只把本地 streaming 置 false，agent 那边照跑不误
     * （手机上看着停了、电脑上还在输出）。现在真调 session/cancel。
     */
    private fun stopStreaming() {
        val sid = currentSessionId ?: return
        streaming = false
        api.cancelPrompt(sid) { r ->
            r.onFailure { e -> Log.w(TAG, "cancelPrompt 失败：${e.message}") }
        }
    }

    private fun doRename(target: ApiClient.SessionSummary, newTitle: String) {
        val t = newTitle.trim()
        if (t.isEmpty() || t == target.title) return
        api.renameSession(target.sessionId, t) { r ->
            r.onSuccess {
                // 本地先改，免得等一次刷新
                val idx = sessions.indexOfFirst { it.sessionId == target.sessionId }
                if (idx >= 0) sessions[idx] = sessions[idx].copy(title = t)
                if (currentSessionId == target.sessionId) currentTitle = t
            }.onFailure { e ->
                error = "重命名失败：${e.message}"
                Log.w(TAG, "rename 失败：${e.message}")
            }
        }
    }

    /**
     * 退回会话列表：关掉 WS、清掉当前会话状态。
     *
     * 注意不清 `throughSeq` / `hasMoreHistory` 以外的历史——`items` 必须清，
     * 否则下次打开别的会话会先闪一屏上一个会话的消息。
     */
    private fun closeSession() {
        mux?.close()
        mux = null
        currentSessionId = null
        items.clear()
        draft = ""
        attachments.clear()
        error = ""
        streaming = false
        pendingText = ""
        hasMoreHistory = false
        loadingMore = false
        throughSeq = -1L
        oldestSeq = -1L
    }

    private fun archive(target: ApiClient.SessionSummary) {
        api.archiveSession(target.sessionId) { r ->
            r.onSuccess {
                sessions.removeAll { it.sessionId == target.sessionId }
                // 归档不该是「一去不回」：底部给 5 秒后悔期，点了撤回就调 unarchive
                undoArchived = target.sessionId to target.title.ifBlank { "未命名会话" }
                // 归档的正好是当前打开的会话就退回列表
                if (currentSessionId == target.sessionId) {
                    mux?.close()
                    mux = null
                    currentSessionId = null
                    items.clear()
                }
            }.onFailure { e ->
                error = "归档失败：${e.message}"
                Log.w(TAG, "archive 失败：${e.message}")
            }
        }
    }

    /**
     * 打开模型弹层。模型目录首次拉过后缓存住——它跟会话无关（全机路由表），
     * 每次打开都重新请求纯属浪费一次往返。
     */
    /**
     * 读当前会话的模型。快照 records 只有事件流没有 projections，
     * 所以单独打一次 `session/projections`（参数 `{sessionId}`），
     * 从 `values.modelSelection` 里取。
     */
    private fun loadCurrentModel(sid: String) {
        api.getModelSelection(sid) { r ->
            r.onSuccess { (p, m) ->
                if (p.isNotBlank()) currentProvider = p
                if (m.isNotBlank()) currentModel = m
            }.onFailure { e -> Log.d(TAG, "读 modelSelection 失败（不影响使用）：${e.message}") }
        }
    }

    private fun openModelSheet() {
        modelSheetOpen = true
        if (models.isNotEmpty()) return
        modelLoading = true
        api.listModels { r ->
            modelLoading = false
            r.onSuccess { list ->
                models.clear()
                models.addAll(list)
                // 没记录过当前模型就用目录里的默认值显示（不代表已经改过会话）
                if (currentProvider.isBlank() && list.isNotEmpty()) {
                    currentProvider = list.first().provider
                    currentModel = list.first().model
                }
            }.onFailure { e ->
                error = "拉模型列表失败：${e.message}"
                Log.w(TAG, "modelCatalog 失败：${e.message}")
            }
        }
    }

    private fun pickModel(m: ApiClient.ModelOption) {
        modelSheetOpen = false
        val sid = currentSessionId ?: return
        if (m.provider == currentProvider && m.model == currentModel) return
        api.selectModel(sid, m.provider, m.model) { r ->
            r.onSuccess {
                currentProvider = m.provider
                currentModel = m.model
                Log.i(TAG, "模型切到 ${m.provider}/${m.model}")
            }.onFailure { e ->
                error = "切模型失败：${e.message}"
                Log.w(TAG, "selectModel 失败：${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mux?.close()
        approvals?.close()
    }

    companion object {
        private const val TAG = "DshChat"

        /** 和 MainActivity 共用同一份偏好，两边读到的是同一个地址。 */
        private const val PREFS = "dsh_mobile"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_FONT = "font"
        /** 开源仓库地址，关于页里点「开源 · MIT 协议」会跳到这里 */
        private const val REPO_URL = "https://github.com/Amiya23334/ClickWhale"
        private const val KEY_BG_URI = "background_uri"
        private const val KEY_BG_BLUR = "background_blur"
        private const val KEY_BG_ALPHA = "background_alpha"
        private const val KEY_FONT_FILE = "custom_font_file"
        private const val KEY_FONT_NAME = "custom_font_name"
        private const val KEY_RELAY_KEY = "relay_key"
        private const val KEY_DISPLAY_NAME = "display_name"

        private const val REQ_LOCAL_NET = 1002
        private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /**
         * 单附件上限。
         *
         * base64 会把体积撑大约 4/3，而 `session/prompt` 整个请求体
         * 还要过一次网关校验，24 MB 是一条保守线（对应 base64 后 32 MB）。
         * 超了明确报错，让用户自己压一下，别静默失败或者把 App OOM 掉。
         */
        private const val MAX_ATTACHMENT_BYTES = 24L * 1024 * 1024
    }
}

/**
 * 按扩展名猜 MIME。
 *
 * `contentResolver.getType()` 对一些第三方文件管理器返回的内容是 null，
 * 这时只能自己猜——猜错到 `application/octet-stream` 也没关系（走上传路径），
 * 但**猜成图片类型却发不出**才要命，所以这里只对确定的那几个写死。
 */
private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "txt", "md", "log" -> "text/plain"
    "json" -> "application/json"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

/**
 * 重命名对话框。
 *
 * 用 AlertDialog + OutlinedTextField 而不是弹一个自定义 Dialog——原生控件自带
 * 输入法适配、返回键处理、深色主题，自己画这些都要重复一遍。
 */@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话", fontSize = 17.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("会话标题", fontSize = 15.sp) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
