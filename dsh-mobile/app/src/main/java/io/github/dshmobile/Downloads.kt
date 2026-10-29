package io.github.dshmobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 把电脑上的文件拉到手机里。
 *
 * 两个调用方共用这一个：
 *   - 文件管理器点某个文件
 *   - 对话里的 `📄 xxx` 附件块
 *
 * **存储策略**：Android 10（API 29）起用 MediaStore 写进系统「下载」集合，
 * 不需要任何存储权限；更低版本退回 app 专属的外部下载目录，同样不需要权限。
 * 不申请 MANAGE_EXTERNAL_STORAGE——那是个要审核的特殊权限，为下几个文件不值。
 */
object Downloads {

    private val pool = Executors.newSingleThreadExecutor()
    private const val TAG = "DshDownload"

    /**
     * @param url 完整的下载地址（中转的 /relay/file 或 /relay/attachment）
     * @param fileName 落盘文件名；重名时系统自动加 (1)
     * @param cb 在主线程回调；成功时给最终 uri，失败给异常
     */
    fun save(context: Context, url: String, fileName: String, cb: (Result<Uri>) -> Unit) {
        val app = context.applicationContext
        pool.execute {
            val r = try {
                Result.success(download(app, url, fileName))
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                Result.failure(e)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(r) }
        }
    }

    private fun download(context: Context, url: String, fileName: String): Uri {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 60000
            RelayAuth.headers().forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeViaMediaStore(context, input, fileName)
                } else {
                    writeToAppDir(context, input, fileName)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, input: InputStream, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            // IS_PENDING=1 时文件对其它 App 不可见，写完再置 0。
            // 否则下到一半用户就能在「文件」里点到一个不完整的文件。
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out)
            } ?: throw Exception("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            // 失败的半成品要删掉，不然下载目录里会留一堆 0 字节文件
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun writeToAppDir(context: Context, input: InputStream, fileName: String): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw Exception("外部存储不可用")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, fileName)
        FileOutputStream(out).use { input.copyTo(it) }
        return Uri.fromFile(out)
    }
}
