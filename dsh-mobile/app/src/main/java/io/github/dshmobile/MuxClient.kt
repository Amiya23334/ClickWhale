package io.github.dshmobile

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DSH 远程 mux 客户端（原生渲染用）。
 *
 * 协议（2026-10-29 实测，详见 docs/dsh-mobile.md）：
 *   - 连 ws://<relay>/api/remote.mux
 *   - 发 {type:"open", streamId, endpoint:"session/follow", payload:{args:{request:{address:{kind:"session",sessionId}, maxMessages:N}}}}
 *     收到 {type:"item", streamId, value:{type:"snapshot", ...}}  ← 开场快照（历史）
 *     收到 {type:"item", streamId, value:{type:"event", event:{...}}} ← 实时事件
 *   - 发 prompt 走 HTTP POST /api/sessions/prompt（不是 WS），
 *     参数必须是 {sessionId, mode:"queue", content:[{type:"text", text}]}
 *     写成 text 会静默无效。
 *
 * 关键：同一个 WebSocket 上**不能**对同一会话重复 open（服务端会忽略），
 * 但不同连接可以同时 follow 同一会话，事件广播、seq 一致 —— 这就是「多端同步」的基础。
 */
class MuxClient(
    private val baseUrl: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        /**
         * 开场快照：历史事件一次性下发。
         *
         * [cursor] 是开场帧的 `value.cursor`，也就是 `session/page` 的 `throughSeq`
         * 来源（**同一个值**，实测两者一致）。翻页时一直用它，不要每页重算。
         * [hasMore] 表示这条会话还有没有更老的历史没下发；为 false 就不用做「加载更多」。
         */
        fun onSnapshot(sessionId: String, events: List<JSONObject>, cursor: Long, hasMore: Boolean)
        /** 实时事件 */
        fun onEvent(sessionId: String, event: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 长连接不能超时
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var sessionId: String? = null
    private var streamId: String? = null

    /**
     * 重连状态。
     *
     * 为什么需要：手机切后台、锁屏、Wi-Fi 换基站，WS 都会被动断开。
     * 以前断了就只是把 connected 置 false，用户得手动退回列表再点进来
     * ——而正在跑的回合其实还在服务端继续，看起来就是「消息不往下走了」。
     *
     * 退避参数参考 DSHgo（2/4/8/16/30 秒），最后一档封顶不再翻倍，
     * 避免长时间断网时无限拉长到几小时。
     */
    private var closedByUser = false
    private var retryCount = 0
    private var retryRunnable: Runnable? = null

    private val backoffMs = longArrayOf(2000, 4000, 8000, 16000, 30000)

    fun connect(sessionId: String) {
        this.sessionId = sessionId
        this.streamId = UUID.randomUUID().toString()
        this.closedByUser = false
        this.retryCount = 0
        openSocket()
    }

    private fun openSocket() {
        val sid = sessionId ?: return
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/remote.mux"
        val req = Request.Builder().url(wsUrl).apply {
            RelayAuth.headers().forEach { (k, v) -> addHeader(k, v) }
        }.build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                retryCount = 0
                sendFollow(ws)
                main.post { listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect(sid, t.message ?: "连接失败")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect(sid, "连接关闭：$reason")
            }
        })
    }

    /** 断开后按退避重连；用户主动 close 时不重连。 */
    private fun scheduleReconnect(sid: String, reason: String) {
        if (closedByUser) return
        val delay = backoffMs[retryCount.coerceAtMost(backoffMs.size - 1)]
        retryCount++
        main.post {
            listener.onDisconnected(reason)
            retryRunnable?.let { main.removeCallbacks(it) }
            val r = Runnable {
                if (!closedByUser && sessionId == sid) {
                    streamId = UUID.randomUUID().toString()
                    openSocket()
                }
            }
            retryRunnable = r
            main.postDelayed(r, delay)
        }
    }

    private fun sendFollow(ws: WebSocket) {
        val sid = sessionId ?: return
        val frame = JSONObject().apply {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", "session/follow")
            put("payload", JSONObject().apply {
                put("args", JSONObject().apply {
                    put("request", JSONObject().apply {
                        put("address", JSONObject().apply {
                            put("kind", "session")
                            put("sessionId", sid)
                        })
                        // 历史深度：给多一点，手机上要能往回翻
                        put("maxMessages", 200)
                    })
                })
            })
        }
        ws.send(frame.toString())
    }

    private fun handleFrame(text: String) {
        val obj = try { JSONObject(text) } catch (e: Exception) { return }
        if (obj.optString("type") != "item") return
        if (obj.optString("streamId") != streamId) return

        val value = obj.optJSONObject("value") ?: return
        when (value.optString("type")) {
            "snapshot" -> {
                val sid = sessionId ?: return
                val records = value.optJSONArray("records") ?: JSONArray()
                val events = mutableListOf<JSONObject>()
                for (i in 0 until records.length()) {
                    val rec = records.optJSONObject(i) ?: continue
                    // records 里每一项是 `{type:"event", event:{...}}`，
                    // 不是裸事件。直接当事件用会让 EventMapper 永远匹配不上
                    // （record.type 恒为 "event"），表现为「历史消息一条都不显示」。
                    val ev = if (rec.optString("type") == "event") {
                        rec.optJSONObject("event")
                    } else {
                        rec   // 兼容没有包装层的情况
                    } ?: continue
                    events.add(ev)
                }
                val cursor = value.optLong("cursor", -1L)
                val hasMore = value.optBoolean("hasMore", false)
                main.post { listener.onSnapshot(sid, events, cursor, hasMore) }
            }
            "event" -> {
                val sid = sessionId ?: return
                val event = value.optJSONObject("event") ?: return
                // 打一行事件类型：后台收不到通知时要能一眼看出是「WS 没送到」
                // 还是「送到了但没触发通知」，否则只能靠猜。
                Log.d("DshMux", "event: ${event.optString("type")}")
                main.post { listener.onEvent(sid, event) }
            }
        }
    }

    fun close() {
        closedByUser = true
        retryRunnable?.let { main.removeCallbacks(it) }
        retryRunnable = null
        socket?.close(1000, "bye")
        socket = null
    }
}
