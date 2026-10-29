package io.github.dshmobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dshmobile.Downloads
import io.github.dshmobile.RelayAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.dshmobile.R

/**
 * 电脑文件管理器（原生）。
 *
 * 之前只有一个给「选工作区」用的 FileBrowserActivity：手写 View、只列目录、
 * 藏在 WebView 壳里。这个页面是给**日常拿文件**用的，所以从首页顶栏就能进，
 * 而且文件和目录都列。
 *
 * 后端是中转自己实现的 `directoryPicker/list`（因为 DSH 自带的目录选择器是
 * native 实现，在手机上会卡死），文件下载走 `/relay/file`，上传走 `/relay/upload`。
 *
 * 视觉上刻意跟会话列表用同一套语言：卡片底色 `Palette.Surface` + 16dp 圆角 +
 * 14/12 内边距 + 6dp 行距。之前这里是裸行加 emoji，跟 App 其它页面完全是两种气质。
 */
@Composable
fun FileManagerScreen(
    baseUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 目录浏览是独立的小请求，跟 ApiClient 的会话 RPC 不是一路，所以这里自己起线程池。
    val loader = remember(baseUrl) { FileLoader(baseUrl) }

    var path by remember { mutableStateOf("") }
    var crumbs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dirs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 正在下载的文件路径：用来在这一行上显示「下载中」，防止连点。
    var downloading by remember { mutableStateOf<String?>(null) }
    // 上传中显示的是文件名，列表那边不用管（传完刷新一下就看到了）。
    var uploading by remember { mutableStateOf<String?>(null) }
    // 盘符列表与切换弹层。根目录默认是中转的 C:\，D 盘上的项目进不去。
    var drives by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDrives by remember { mutableStateOf(false) }

    fun load(target: String) {
        loading = true
        error = null
        loader.list(target) { res ->
            res.onSuccess { page ->
                path = page.path
                crumbs = page.crumbs
                dirs = page.dirs
                files = page.files
                loading = false
            }.onFailure {
                error = it.message ?: "读取失败"
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("") }

    // 系统返回键：跟顶栏 ← 用同一套语义——先退一级目录，只有在根目录才退出。
    // 之前这里没有处理，返回键直接穿到 Activity 的 BackHandler，
    // 一步就退回会话列表，看起来像「返回跳到了根」。
    BackHandler {
        if (crumbs.size >= 2) load(crumbs[crumbs.size - 2].second) else onBack()
    }

    // 系统文件选择器：拿到 content:// 就流式 POST 到中转，不落临时文件。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayNameOf(context, uri) ?: "upload.bin"
        uploading = name
        loader.upload(context, path, uri, name) { res ->
            uploading = null
            res.onSuccess { saved ->
                Toast.makeText(context, "已传到电脑：$saved", Toast.LENGTH_SHORT).show()
                load(path)
            }.onFailure {
                Toast.makeText(context, "上传失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶栏：← 返回上一级（在根目录时退出页面），右侧是上传。
        // 原来是「返回」+「上级」两个按钮，语义重复，合成一个更接近系统习惯。
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
                    .clickable {
                        if (crumbs.size >= 2) load(crumbs[crumbs.size - 2].second) else onBack()
                    }
                    .padding(end = 12.dp, top = 2.dp, bottom = 2.dp)
                    .size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "电脑文件", color = Palette.TextPrimary, fontSize = Dims.titleSize)
                Text(
                    text = if (path.isBlank()) "正在读取…" else path,
                    // 这行是「我现在在电脑的哪个目录」，属于必读信息，
                    // 原来用 TextTertiary(#5A5A63)+12sp，在深色背景上基本看不见。
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "盘符",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        loader.drives { r ->
                            r.onSuccess {
                                drives = it
                                showDrives = true
                            }
                        }
                    }
                    .padding(end = 10.dp),
            )
            Text(
                text = uploading?.let { "上传中" } ?: "上传",
                color = if (uploading != null) Palette.TextTertiary else Palette.Accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(enabled = uploading == null) { picker.launch(arrayOf("*/*")) }
                    .background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 面包屑：可点任意一级直接跳。超过一行就横滚，不折行——折行会把列表顶下去。
        if (crumbs.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dims.screenPad),
            ) {
                items(crumbs.size) { i ->
                    val (name, p) = crumbs[i]
                    Text(
                        text = if (i == crumbs.lastIndex) name else "$name /",
                        color = if (i == crumbs.lastIndex) Palette.TextSecondary else Palette.Accent,
                        fontSize = Dims.metaSize,
                        modifier = Modifier
                            .clickable(enabled = i != crumbs.lastIndex) { load(p) }
                            .padding(end = 6.dp, top = 2.dp, bottom = 8.dp),
                    )
                }
            }
        }

        when {
            loading -> CenterNote("正在读取…")
            error != null -> CenterNote(error ?: "")
            dirs.isEmpty() && files.isEmpty() -> CenterNote("空目录")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dims.screenPad, end = Dims.screenPad, top = 4.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(dirs, key = { "d:" + it.path }) { d ->
                    EntryRow(
                        iconRes = R.drawable.ic_folder,
                        iconTint = Palette.Accent,
                        title = d.name,
                        meta = null,
                        action = "›",
                        actionColor = Palette.TextTertiary,
                        onClick = { load(d.path) },
                    )
                }
                items(files, key = { "f:" + it.path }) { f ->
                    val busy = downloading == f.path
                    EntryRow(
                        iconRes = R.drawable.ic_file,
                        iconTint = if (busy) Palette.TextTertiary else Palette.TextSecondary,
                        title = f.name,
                        meta = formatMeta(f.size, f.mtime),
                        action = if (busy) "下载中" else "保存",
                        actionColor = if (busy) Palette.TextTertiary else Palette.Accent,
                        enabled = !busy,
                        onClick = {
                            downloading = f.path
                            val url = "$baseUrl/relay/file?path=" +
                                URLEncoder.encode(f.path, "UTF-8") +
                                "&name=" + URLEncoder.encode(f.name, "UTF-8")
                            Downloads.save(context, url, f.name) { res ->
                                downloading = null
                                res.onSuccess {
                                    Toast.makeText(context, "已存到手机下载：${f.name}", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDrives) {
        DriveSheet(
            drives = drives,
            onPick = { d ->
                showDrives = false
                load(d)
            },
            onDismiss = { showDrives = false },
        )
    }
}

/** 盘符选择弹层 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DriveSheet(
    drives: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "选择盘符",
                color = Palette.TextPrimary,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            if (drives.isEmpty()) {
                Text(
                    text = "没读到盘符，检查电脑上的中转是不是最新版",
                    color = Palette.TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
            drives.forEach { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(d) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = Palette.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = d, color = Palette.TextPrimary, fontSize = Dims.bodySize)
                }
            }
        }
    }
}

/** 一行：圆角图标底板 + 名字/副标题 + 右侧动作。跟会话行保持同一个骨架。 */
@Composable
private fun EntryRow(
    iconRes: Int,
    iconTint: Color,
    title: String,
    meta: String?,
    action: String?,
    actionColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Dims.cardRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 矢量图标 + 浅色底板：底板让图标跟文字归到同一个层级里。
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Palette.TextPrimary,
                fontSize = Dims.bodySize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Spacer(modifier = Modifier.size(3.dp))
                Text(
                    text = meta,
                    color = Palette.TextTertiary,
                    fontSize = Dims.metaSize,
                    maxLines = 1,
                )
            }
        }
        if (action != null) {
            Text(
                text = action,
                color = actionColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Palette.TextSecondary, fontSize = Dims.bodySize)
    }
}

/** 系统选择器给的是 content://，文件名要从 OpenableColumns 查，不能拿 uri 最后一段。 */
private fun displayNameOf(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
    } catch (_: Exception) {
        null
    }
}

/** 目录或文件的一行。目录只用 name/path；文件还带 size/mtime。 */
data class FileEntry(
    val name: String,
    val path: String,
    val size: Long = 0L,
    val mtime: Long = 0L,
)

private data class DirPage(
    val path: String,
    val crumbs: List<Pair<String, String>>,
    val dirs: List<FileEntry>,
    val files: List<FileEntry>,
)

private fun formatMeta(size: Long, mtime: Long): String {
    val s = when {
        size >= 1024L * 1024 * 1024 -> "%.1f GB".format(size / 1024.0 / 1024 / 1024)
        size >= 1024L * 1024 -> "%.1f MB".format(size / 1024.0 / 1024)
        size >= 1024 -> "%.0f KB".format(size / 1024.0)
        else -> "$size B"
    }
    if (mtime <= 0) return s
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(mtime * 1000))
    return "$s · $day"
}

/**
 * 目录读取与上传。请求形状跟 ApiClient 的 RPC 不一样：
 * 中转的 `handlePicker` 直接读 `payload.args.path`，没有 `_request` 那层包装。
 */
private class FileLoader(private val baseUrl: String) {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun list(path: String, cb: (Result<DirPage>) -> Unit) {
        pool.execute {
            val r = try {
                val body = JSONObject().apply {
                    put("type", "client-request")
                    put("rpcId", "fm")
                    put("payload", JSONObject().apply {
                        put("args", JSONObject().apply { put("path", path) })
                    })
                }.toString().toByteArray(Charsets.UTF_8)
                val conn = (URL("$baseUrl/api/directoryPicker/list").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 20000
                    setRequestProperty("content-type", "application/json")
                    RelayAuth.headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.outputStream.use { it.write(body) }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Result.success(parse(JSONObject(text)))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post { cb(r) }
        }
    }

    /**
     * 把手机上的一个 content:// 流式传进电脑当前目录。
     *
     * 用 chunked（不先算 content-length）：视频动辄几百 MB，先读一遍算长度等于读两次；
     * 中转用 req.pipe(createWriteStream) 接，chunked 一样能落盘。
     */
    fun upload(
        context: Context,
        dir: String,
        uri: Uri,
        name: String,
        cb: (Result<String>) -> Unit,
    ) {
        pool.execute {
            val r = try {
                val query = "path=" + URLEncoder.encode(dir, "UTF-8") +
                    "&name=" + URLEncoder.encode(name, "UTF-8")
                val conn = (URL("$baseUrl/relay/upload?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 120000
                    setRequestProperty("content-type", "application/octet-stream")
                    setChunkedStreamingMode(64 * 1024)
                    RelayAuth.headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("读不到这个文件")
                input.use { src -> conn.outputStream.use { src.copyTo(it) } }
                val code = conn.responseCode
                val text = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText().orEmpty()
                }
                conn.disconnect()
                if (code !in 200..299) throw Exception("HTTP $code $text")
                val value = JSONObject(text).optJSONObject("result")?.optJSONObject("value")
                Result.success(value?.optString("name")?.ifBlank { name } ?: name)
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post { cb(r) }
        }
    }

    /** 列盘符。中转的 /relay/drives 是个裸 JSON，不走 RPC 信封。 */
    fun drives(cb: (Result<List<String>>) -> Unit) {
        pool.execute {
            val r = try {
                val conn = (URL("$baseUrl/relay/drives").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    RelayAuth.headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = JSONObject(text)
                    .optJSONObject("result")
                    ?.optJSONObject("value")
                    ?.optJSONArray("drives")
                val out = mutableListOf<String>()
                for (i in 0 until (arr?.length() ?: 0)) out.add(arr!!.optString(i))
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post { cb(r) }
        }
    }

    private fun parse(root: JSONObject): DirPage {
        val result = root.optJSONObject("result") ?: throw Exception("中转返回异常")
        if (!result.optBoolean("ok")) {
            throw Exception(result.optJSONObject("error")?.optString("message") ?: "读取失败")
        }
        val v = result.getJSONObject("value")
        val crumbs = mutableListOf<Pair<String, String>>()
        v.optJSONArray("crumbs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                crumbs.add(c.optString("name") to c.optString("path"))
            }
        }
        fun entries(key: String, withMeta: Boolean): List<FileEntry> {
            val out = mutableListOf<FileEntry>()
            val arr = v.optJSONArray(key) ?: return out
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                out.add(FileEntry(
                    name = e.optString("name"),
                    path = e.optString("path"),
                    size = if (withMeta) e.optLong("size") else 0L,
                    mtime = if (withMeta) e.optLong("mtime") else 0L,
                ))
            }
            return out
        }
        return DirPage(
            path = v.optString("path"),
            crumbs = crumbs,
            dirs = entries("entries", false),
            files = entries("files", true),
        )
    }
}
