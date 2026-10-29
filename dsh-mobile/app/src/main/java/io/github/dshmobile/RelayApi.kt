package io.github.dshmobile

import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 跟中转的 relay 端点对话。
 *
 * 中转在电脑上跑（dsh-relay.mjs），它自己实现了 directoryPicker 的
 * list / pick / createDirectory，因为 DSH 原生的目录选择器是 native 实现、
 * 没有 browse 能力（原文：the composed picker serves "native"），
 * 在手机上点会把文件夹对话框弹在电脑上。
 */
object RelayApi {

    private val pool = Executors.newSingleThreadExecutor()

    data class Entry(val name: String, val path: String, val isDir: Boolean = true)

    data class Listing(
        val path: String,
        val parent: String?,
        val entries: List<Entry>,
        val error: String? = null
    )

    /** 列一个目录。baseUrl 形如 http://192.168.x.x:8090 */
    fun list(baseUrl: String, dir: String?, cb: (Listing) -> Unit) {
        pool.execute {
            val result = try {
                val body = JSONObject().apply {
                    put("type", "client-request")
                    put("rpcId", "app-" + System.currentTimeMillis())
                    put("method", "directoryPicker/list")
                    put("payload", JSONObject().apply {
                        put("args", JSONObject().apply {
                            if (dir != null) put("path", dir)
                        })
                    })
                }.toString()

                val text = post("$baseUrl/api/directoryPicker/list", body)
                parseListing(text)
            } catch (e: Exception) {
                Listing(dir ?: "", null, emptyList(), e.message ?: "网络错误")
            }
            cb(result)
        }
    }

    /** 告诉中转：下次 DSH 要目录时返回这个。 */
    fun setPickedDir(baseUrl: String, dir: String, cb: (Boolean) -> Unit) {
        pool.execute {
            val ok = try {
                val text = post("$baseUrl/relay/pick", JSONObject().apply { put("dir", dir) }.toString())
                text.contains("\"dir\"")
            } catch (e: Exception) {
                false
            }
            cb(ok)
        }
    }

    private fun post(urlStr: String, body: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            RelayAuth.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun parseListing(json: String): Listing {
        return try {
            val root = JSONObject(json)
            val result = root.optJSONObject("result")
            if (result == null || !result.optBoolean("ok", false)) {
                val msg = result?.optJSONObject("error")?.optString("message") ?: "未知错误"
                return Listing("", null, emptyList(), msg)
            }
            val value = result.optJSONObject("value") ?: return Listing("", null, emptyList(), "返回为空")
            val path = value.optString("path", "")

            // DSH 的 DirectoryListing 没有 parent 字段，只有 crumbs（面包屑）。
            // 上一级 = 面包屑倒数第二项。
            val crumbs = value.optJSONArray("crumbs")
            var parent: String? = null
            if (crumbs != null && crumbs.length() >= 2) {
                parent = crumbs.optJSONObject(crumbs.length() - 2)?.optString("path", "")
            }

            val entries = mutableListOf<Entry>()
            val arr = value.optJSONArray("entries")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    entries.add(Entry(o.optString("name"), o.optString("path")))
                }
            }
            Listing(path, parent, entries)
        } catch (e: Exception) {
            Listing("", null, emptyList(), e.message ?: "解析失败")
        }
    }
}
