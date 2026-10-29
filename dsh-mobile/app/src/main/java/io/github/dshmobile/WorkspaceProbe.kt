package io.github.dshmobile

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 一次性读 `workspace/follow` 的 baseline 帧。
 *
 * 为什么要单独一个类：网关没有 unary 的「列工作区 / 列归档」端点——`workspace/follow`
 * 是唯一的来源，而且它是 **stream** 方法，只能走 `/api/remote.mux` 的 open，
 * 不能被 HTTP POST（会报 `stream Remote methods must be opened through the stream carrier`）。
 *
 * 用法是「连上 → 收第一帧 → 立刻关」，不当长连接用：归档/工作区变动不频繁，
 * 需要时重新开一次比维护一条常驻流简单得多。
 */
class WorkspaceProbe(private val baseUrl: String) {

    /** baseline 帧解析结果 */
    data class Snapshot(
        /** workspaceId -> title */
        val workspaces: List<Pair<String, String>>,
        /** workspaceId -> 该工作区下的 sessionId 列表（按手动顺序） */
        val sessionIds: Map<String, List<String>>,
        val archivedSessionIds: List<String>,
        val pinnedSessionIds: List<String>,
    )

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var finished = false

    fun fetch(cb: (Result<Snapshot>) -> Unit) {
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/remote.mux"
        val streamId = UUID.randomUUID().toString()
        val req = Request.Builder().url(wsUrl).build()

        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val open = JSONObject().apply {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", "workspace/follow")
                    put("payload", JSONObject().put("args", JSONObject()))
                }
                webSocket.send(open.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (finished) return
                val obj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                if (obj.optString("type") != "item") return
                if (obj.optString("streamId") != streamId) return
                val value = obj.optJSONObject("value") ?: return
                if (value.optString("type") != "baseline") return

                val snap = parseBaseline(value)
                finish(Result.success(snap), cb)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(Result.failure(IllegalStateException(t.message ?: "工作区读取失败")), cb)
            }
        })

        // 5 秒还没拿到 baseline 就当失败，别让调用方一直转圈
        main.postDelayed({
            if (!finished) finish(Result.failure(IllegalStateException("工作区读取超时")), cb)
        }, 5000)
    }

    private fun parseBaseline(value: JSONObject): Snapshot {
        val inner = value.optJSONObject("value") ?: JSONObject()
        val items = inner.optJSONArray("items")
        val wss = ArrayList<Pair<String, String>>()
        val map = HashMap<String, List<String>>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val w = items.optJSONObject(i) ?: continue
                val id = w.optString("workspaceId")
                if (id.isEmpty()) continue
                wss.add(id to (w.optString("title").ifEmpty { w.optString("path") }))
                val sids = w.optJSONArray("sessionIds") ?: continue
                val list = ArrayList<String>(sids.length())
                for (j in 0 until sids.length()) {
                    val s = sids.optString(j)
                    if (s.isNotEmpty()) list.add(s)
                }
                map[id] = list
            }
        }
        return Snapshot(
            workspaces = wss,
            sessionIds = map,
            archivedSessionIds = stringList(inner.optJSONArray("archivedSessionIds")),
            pinnedSessionIds = stringList(inner.optJSONArray("pinnedSessionIds")),
        )
    }

    private fun stringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun finish(r: Result<Snapshot>, cb: (Result<Snapshot>) -> Unit) {
        if (finished) return
        finished = true
        socket?.close(1000, "done")
        socket = null
        main.post { cb(r) }
    }
}
