package io.github.dshmobile

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 手机上的电脑文件管理器。
 *
 * 列目录、点进去、选一个当工作区。选中后调中转的 /relay/pick，
 * 这样再回 DSH 界面点「选择工作区」时，拿到就是这里选的目录。
 */
class FileBrowserActivity : Activity() {

    private lateinit var baseUrl: String
    private lateinit var listContainer: LinearLayout
    private lateinit var pathLabel: TextView
    private lateinit var progress: ProgressBar

    private var currentPath: String? = null
    private var parentPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = intent.getStringExtra("baseUrl") ?: ""

        // 浏览文件时别息屏，否则翻两下目录屏幕就黑了
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151517"))
            // 顶层内容别钻到状态栏底下
            fitsSystemWindows = true
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 根布局已 fitsSystemWindows，这里只留普通内边距
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val up = Button(this).apply {
            text = "↑"
            textSize = 18f
            setOnClickListener { currentPath?.let { loadDir(parentPath) } }
        }
        bar.addView(up, LinearLayout.LayoutParams(dp(56), dp(48)))

        pathLabel = TextView(this).apply {
            text = "载入中…"
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(dp(12), 0, 0, 0)
            setSingleLine(true)
        }
        bar.addView(pathLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pick = Button(this).apply {
            text = "选这个"
            setOnClickListener { chooseCurrent() }
        }
        bar.addView(pick, LinearLayout.LayoutParams(dp(88), dp(48)))

        root.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        loadDir(null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 状态栏高度（像素）；取不到就退回 24dp。 */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun loadDir(dir: String?) {
        progress.visibility = View.VISIBLE
        RelayApi.list(baseUrl, dir) { listing ->
            runOnUiThread {
                progress.visibility = View.INVISIBLE

                if (listing.error != null) {
                    pathLabel.text = "出错：" + listing.error
                    Toast.makeText(this, listing.error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                currentPath = listing.path
                parentPath = listing.parent
                pathLabel.text = listing.path.ifBlank { "选择磁盘" }

                listContainer.removeAllViews()
                for (e in listing.entries) {
                    listContainer.addView(rowView(e))
                }
                if (listing.entries.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = "（这里没有子文件夹）"
                        textSize = 14f
                        setTextColor(Color.parseColor("#777777"))
                        setPadding(dp(20), dp(20), dp(20), dp(20))
                    }
                    listContainer.addView(empty)
                }
            }
        }
    }

    private fun rowView(entry: RelayApi.Entry): View {
        val tv = TextView(this).apply {
            text = "📁  " + entry.name
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { loadDir(entry.path) }
        }
        val divider = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#2A2A2E"))
        }
        tv.background = divider
        return tv
    }

    private fun chooseCurrent() {
        val dir = currentPath
        if (dir.isNullOrBlank()) {
            Toast.makeText(this, "还没进到目录里", Toast.LENGTH_SHORT).show()
            return
        }
        // 盘根不能选：DSH 建会话时会 mkdir 目标目录，对 "D:\" 直接
        // `EPERM: operation not permitted, mkdir 'D:'`。
        if (Regex("^[A-Za-z]:\\\\?$").matches(dir)) {
            Toast.makeText(this, "不能直接选盘根，点进一个文件夹里再选", Toast.LENGTH_LONG).show()
            return
        }
        RelayApi.setPickedDir(baseUrl, dir) { ok ->
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "已选：$dir\n回 DSH 点「选择工作区」就会用这个", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "设置失败，检查中转还在不在", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        val p = parentPath
        if (p != null) {
            loadDir(p)
        } else {
            super.onBackPressed()
        }
    }
}
