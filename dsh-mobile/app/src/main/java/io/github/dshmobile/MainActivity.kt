package io.github.dshmobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * DSH Mobile —— 把电脑上的 DeepSeek Harness 桌面版装进手机。
 *
 * 它就是一个全屏 WebView，指向电脑上跑的中转（dsh-relay）。
 * 界面、工具调用、文件操作全部复用电脑那份，App 只负责壳。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var setupPanel: LinearLayout
    private lateinit var urlInput: EditText
    /** 自动发现的结果列表容器。 */
    private lateinit var foundBox: LinearLayout
    private lateinit var progress: ProgressBar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 本次进入是不是要顺手点开插件面板（从原生 UI 跳过来时为 true）。 */
    private var pendingPluginPanel = false

    companion object {
        private const val TAG = "DshMain"
        private const val PREFS = "dsh_mobile"
        private const val KEY_URL = "server_url"
        private const val REQ_FILE = 1001
        private const val REQ_LOCAL_NET = 1002

        /** Android 16（API 36）起访问局域网要这个运行时权限。 */
        private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /**
         * 点开网页版侧栏里的插件面板。
         *
         * 网页版的侧栏面板按钮是纯图标，没有文字，只有 `aria-label="插件"`。
         * 匹配不到就什么都不做——宁可点了没反应，也别误点别的面板。
         */
        private const val JS_OPEN_PLUGINS = """
            (function () {
              var b = document.querySelector('[aria-label="插件"]');
              if (!b) return 'no-button';
              b.click();
              return 'ok';
            })()
        """
    }

    /**
     * 让悬浮按钮可以拖着走，同时保留点击。
     *
     * 关键是不能用 OnClickListener：它在 ACTION_DOWN 之后就不再跟踪坐标，
     * 手指移开还是算点击。这里自己记起点，位移超过系统触摸阈值才当拖动，
     * 否则抬手时按点击处理——两者只能选一个，不能同时触发。
     */
    private fun makeDraggable(v: View, lp: FrameLayout.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false

        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val parent = view.parent as? View ?: return@setOnTouchListener true
                        val maxLeft = (parent.width - view.width - dp(12)).coerceAtLeast(0)
                        val maxTop = (parent.height - view.height - dp(12)).coerceAtLeast(0)
                        lp.leftMargin = (startLeft + dx.toInt()).coerceIn(0, maxLeft)
                        lp.topMargin = (startTop + dy.toInt()).coerceIn(dp(40), maxTop)
                        view.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 拖动结束不触发点击，否则想挪个位置就顺手打开了文件浏览器
                    if (!dragging) view.performClick()
                    true
                }
                else -> true
            }
        }
        // 有自己 OnTouchListener 时 performClick 不会自动走 OnClickListener，
        // 这里手动接一个，保证无障碍服务和点击行为都正常。
        v.setOnClickListener { openFileBrowser() }
    }

    /** 缺局域网权限就申请；没有这个权限的旧系统直接返回 true。 */
    private fun ensureLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return true
        if (checkSelfPermission(PERM_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_LOCAL_NET)
        return false
    }

    private var pendingUrl: String? = null
    /** 权限申请期间如果用户点了「搜索电脑」，授完权立刻补扫。 */
    private var pendingScan = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCAL_NET) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingUrl?.let { webView.loadUrl(it) }
                if (pendingScan) {
                    pendingScan = false
                    startScan()
                }
            } else {
                Toast.makeText(
                    this,
                    "需要「附近的设备/本地网络」权限才能连电脑，请到设置里开启",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingUrl = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#151517"))
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 细进度条，贴在顶部
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        val pLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(3)
        )
        root.addView(progress, pLp)

        // 设置面板（首次启动 / 长按菜单触发）
        setupPanel = buildSetupPanel()
        setupPanel.visibility = View.GONE
        root.addView(setupPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 一进来就要局域网权限。Android 16+ 不给这个权限，自己的 Socket
        // 连局域网会被静默拒绝（连局域网地址都被拒，报错都不给一条），
        // 自动发现会「扫完了但一台都没找到」，极难查。
        if (Build.VERSION.SDK_INT >= 36 &&
            checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_LOCAL_NET)
        }

        // 悬浮的小文件夹按钮：随时进电脑文件浏览器。
        // DSH 自己的目录选择器是电脑原生的，在手机上点了只会把对话框弹到电脑上，
        // 所以浏览目录这件事得由 App 自己提供入口。
        val fab = TextView(this).apply {
            text = "📁"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC2A2A2E"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val fabLp = FrameLayout.LayoutParams(dp(48), dp(48))
        fabLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        fabLp.leftMargin = dp(12)
        fabLp.topMargin = dp(40)
        root.addView(fab, fabLp)
        makeDraggable(fab, fabLp)

        // 原生 UI 入口：小鲸鱼按钮，点进 ChatActivity。
        // 和上面的 📁 分开放，两者互不干扰（📁 是文件夹管理，这个是聊天）。
        val chatFab = TextView(this).apply {
            text = "🐋"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1B2A4A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val chatLp = FrameLayout.LayoutParams(dp(48), dp(48))
        chatLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        chatLp.leftMargin = dp(12)
        chatLp.topMargin = dp(100)
        root.addView(chatFab, chatLp)
        chatFab.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("baseUrl", currentBaseUrl())
            })
        }

        setContentView(root)
        configureWebView()

        // 从原生 UI 的「插件」按钮跳过来时，直接落到插件面板
        pendingPluginPanel = intent?.getBooleanExtra("openPlugins", false) == true

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        if (saved.isBlank()) {
            showSetup()
        } else if (ensureLocalNetworkPermission()) {
            webView.loadUrl(saved)
        } else {
            pendingUrl = saved
        }
    }

    /**
     * 已经在后台时被 `FLAG_ACTIVITY_REORDER_TO_FRONT` 拉回前台，走的是这里而不是
     * `onCreate`——APP 不会被重建，所以插件面板得在这里补点一次。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("openPlugins", false) == true) {
            if (webView.url.isNullOrBlank()) {
                pendingPluginPanel = true
            } else {
                openPluginPanel(webView)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 在网页版里点开插件面板。
     *
     * 侧栏那个按钮在窄屏下可能被折叠，所以先尝试直接点；找不到就退一步，
     * 先点开侧栏开关再点插件。两条路都不通就提示一句，不静默失败。
     */
    private fun openPluginPanel(view: WebView?) {
        if (view == null) return
        view.evaluateJavascript(JS_OPEN_PLUGINS) { r ->
            val ok = r?.contains("ok") == true
            if (!ok) {
                Toast.makeText(this, "没找到插件入口，可能页面还没加载完", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 当前连的中转地址（给原生 UI 复用），没连过就返回空 */
    private fun currentBaseUrl(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""

    private fun buildSetupPanel(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Color.parseColor("#151517"))
        box.setPadding(dp(24), dp(80), dp(24), dp(24))

        val title = TextView(this)
        title.text = "连接电脑上的 DSH"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        box.addView(title)

        val hint = TextView(this)
        hint.text = "先在电脑上跑 relay/dsh-relay.mjs，\n它会打印一个 http://192.168.x.x:8090 地址，填在下面。"
        hint.textSize = 14f
        hint.setTextColor(Color.parseColor("#999999"))
        hint.setPadding(0, dp(12), 0, dp(24))
        box.addView(hint)

        urlInput = EditText(this)
        urlInput.hint = "http://192.168.1.x:8090"
        urlInput.textSize = 15f
        urlInput.setTextColor(Color.WHITE)
        urlInput.setHintTextColor(Color.parseColor("#666666"))
        urlInput.setSingleLine(true)
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        if (saved.isNotBlank()) urlInput.setText(saved)
        box.addView(urlInput)

        val connect = Button(this)
        connect.text = "连接"
        connect.setOnClickListener {
            val raw = urlInput.text.toString().trim()
            if (raw.isBlank()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = if (raw.startsWith("http")) raw else "http://$raw"
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
            setupPanel.visibility = View.GONE
            if (ensureLocalNetworkPermission()) {
                webView.loadUrl(url)
            } else {
                pendingUrl = url
            }
        }
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.topMargin = dp(20)
        box.addView(connect, clp)

        val change = Button(this)
        change.text = "换个地址"
        change.setOnClickListener { showSetup() }
        val chLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        chLp.topMargin = dp(8)
        box.addView(change, chLp)

        // DSH 的目录选择器是电脑原生的，手机上点会把对话框弹在电脑上。
        // 这里给一个自己的文件管理器，选好的目录会写进中转，DSH 再要目录时就拿到它。
        val files = Button(this)
        files.text = "浏览电脑文件"
        files.setOnClickListener {
            val base = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
            if (base.isBlank()) {
                Toast.makeText(this, "先填地址并连上", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, FileBrowserActivity::class.java).apply {
                putExtra("baseUrl", base)
            })
        }
        val fLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fLp.topMargin = dp(8)
        box.addView(files, fLp)

        // 自动发现：不用手抄 IP。换 Wi-Fi、路由器重新分配地址都不影响。
        val scan = Button(this)
        scan.text = "搜索电脑（自动）"
        scan.setOnClickListener { runDiscovery() }
        val sLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sLp.topMargin = dp(16)
        box.addView(scan, sLp)

        // 发现结果放这里
        foundBox = LinearLayout(this)
        foundBox.orientation = LinearLayout.VERTICAL
        box.addView(foundBox)

        return box
    }

    /** 广播找电脑，找到几个就列几个。 */
    private fun runDiscovery() {
        // 扫描用的是自己的 Socket，必须有局域网权限；没有就先申请，授完再扫。
        if (!ensureLocalNetworkPermission()) {
            pendingScan = true
            return
        }
        startScan()
    }

    private fun startScan() {
        foundBox.removeAllViews()
        val tip = TextView(this)
        tip.text = "正在搜索局域网里的电脑…"
        tip.textSize = 14f
        tip.setTextColor(Color.parseColor("#999999"))
        tip.setPadding(0, dp(12), 0, 0)
        foundBox.addView(tip)

        Discovery.scan(
            cb = { found ->
                tip.text = "找到："
                val b = Button(this)
                b.text = found.url
                b.setOnClickListener { connectTo(found.url) }
                foundBox.addView(b)
            },
            onDone = {
                if (foundBox.childCount <= 1) {
                    tip.text = "没找到电脑。检查：① 电脑上跑了 relay/start-relay.cmd；" +
                        "② 手机和电脑在同一个 Wi-Fi；③ 上面手动填地址。"
                }
            }
        )
    }

    /** 统一走这里把 WebView 指到某个地址。 */
    private fun connectTo(url: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
        if (urlInput != null) urlInput.setText(url)
        setupPanel.visibility = View.GONE
        if (ensureLocalNetworkPermission()) {
            webView.loadUrl(url)
        } else {
            pendingUrl = url
        }
    }

    private fun showSetup() {
        setupPanel.visibility = View.VISIBLE
        setupPanel.bringToFront()
    }

    /** 打开电脑文件浏览器；没连上就让用户先填地址。 */
    private fun openFileBrowser() {
        val base = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        if (base.isBlank()) {
            Toast.makeText(this, "先填地址并连上", Toast.LENGTH_SHORT).show()
            showSetup()
            return
        }
        startActivity(Intent(this, FileBrowserActivity::class.java).apply {
            putExtra("baseUrl", base)
        })
    }

    /**
     * App 在前台期间别让系统息屏。
     *
     * 改系统 screen_off_timeout 会全局生效、退出 App 也亮着，太耗电；
     * 用窗口 flag 只影响当前 Activity，一退到后台系统就照常休眠。
     */
    private fun keepScreenOnWhileVisible() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        // 退到后台就撤掉，避免在后台白耗电
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        keepScreenOnWhileVisible()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // WebView 不读系统代理，直连即可（手机和电脑在同一局域网）
        s.javaScriptCanOpenWindowsAutomatically = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (pendingPluginPanel) {
                    pendingPluginPanel = false
                    // 页面 DOM 刚 ready，React 还没挂载完；延后一点再点。
                    view?.postDelayed({ openPluginPanel(view) }, 900)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "连不上电脑：${error?.description ?: "网络错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // 外链用系统浏览器
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                val host = u.host ?: ""
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
                val ownHost = runCatching { Uri.parse(saved).host ?: "" }.getOrDefault("")
                return if (host != ownHost) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    true
                } else false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    startActivityForResult(params!!.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return
            val result = if (resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (setupPanel.visibility == View.VISIBLE) {
                setupPanel.visibility = View.GONE
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
