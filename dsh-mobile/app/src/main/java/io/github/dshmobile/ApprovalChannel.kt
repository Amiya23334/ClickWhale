package io.github.dshmobile

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * 审批通道。
 *
 * DSH 的审批**不是普通 RPC**，而是 waterfall 事件：
 *   1. 客户端额外开一条 `/api/remote.mux` 流，endpoint = `$events`，payload = `{args:{}}`。
 *   2. 服务端先推一帧 `{type:"item", value:{type:"ready", clientId, host}}`。
 *   3. agent 要跑敏感工具时推 `{type:"item", value:{type:"waterfall", event:"approval/request",
 *      eventId, agentId, request:{toolName, callId, reason}}}`。
 *   4. 客户端把决定 POST 回 `/api/$events/result`，形状：
 *      `{type:"client-request", method:"$events/result",
 *        payload:{args:{clientId, eventId, outcome:{kind:"result", value:"allowed-once"}}}}`
 *
 * `ApprovalOutcome` 只有四个合法值：`allowed-once` / `rejected` / `cancelled` / `unavailable`。
 * 没有"永久允许"——DSH 设计如此，一次批一次。
 *
 * 这条流是**全局的**，与会话无关：所有会话的审批请求都从这里出来。
 * 所以它跟 ChatActivity 的生命周期绑定，而不是跟某个 MuxClient。
 */
class ApprovalChannel(
    private val baseUrl: String,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * 有工具在等审批。
         *
         * [id] 是 eventId，回传时要用；[agentId] 实测**就是发起审批的 sessionId**
         * （`dsh-qq` 里这个字段叫 agentId，但值是 `session-xxx`，不是子代理 id）。
         */
        fun onApprovalAsked(id: String, agentId: String, toolName: String, reason: String)

        fun onApprovalConnected()

        fun onApprovalDisconnected(reason: String)
    }

    private companion object {
        const val TAG = "DshApproval"

        /** 退避重连，和 MuxClient 用同一套参数。 */
        val BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: WebSocket? = null
    private var streamId: String? = null
    private var clientId: String? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var closedByUser = false

    fun connect() {
        if (closedByUser) return
        retryJob?.cancel()
        openSocket()
    }

    private fun openSocket() {
        socket?.cancel()
        // 每次重连都换新 streamId：服务端对同一个 streamId 重复 open 会直接忽略。
        streamId = UUID.randomUUID().toString()
        val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/remote.mux"
        Log.d(TAG, "connecting $url")
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0
                val open = JSONObject().apply {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", "\$events")
                    put("payload", JSONObject().put("args", JSONObject()))
                }
                webSocket.send(open.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleFrame(text)
                } catch (e: Exception) {
                    Log.w(TAG, "bad frame", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "mux failed: ${t.message}")
                scheduleReconnect(t.message ?: "连接失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "mux closed: $code $reason")
                scheduleReconnect(if (reason.isBlank()) "连接已断开" else reason)
            }
        })
    }

    private fun handleFrame(text: String) {
        val frame = JSONObject(text)
        if (frame.optString("streamId") != streamId) return

        when (frame.optString("type")) {
            "item" -> {
                val value = frame.optJSONObject("value") ?: return
                when (value.optString("type")) {
                    "ready" -> {
                        clientId = value.optString("clientId")
                        Log.d(TAG, "ready clientId=$clientId")
                        listener.onApprovalConnected()
                    }

                    "waterfall" -> {
                        val event = value.optString("event")
                        val eventId = value.optString("eventId")
                        if (eventId.isBlank()) return
                        // 只处理审批请求。$events 流上的其它 waterfall（如果有）与我们无关。
                        //
                        // 这里**没有** `approval/decided` 分支：实测这条流上只有 emit 和
                        // waterfall 两种帧，决定结果不会回头广播。所以要撤掉界面上的按钮，
                        // 只能靠 App 自己点完就撤，或者等 turn 结束一起清。
                        if (event == "approval/request") {
                            val agentId = value.optString("agentId")
                            val req = value.optJSONObject("request")
                            val tool = req?.optString("toolName").orEmpty().ifBlank { "工具" }
                            val reason = req?.optString("reason").orEmpty()
                            Log.d(TAG, "approval asked $eventId agent=$agentId tool=$tool")
                            listener.onApprovalAsked(eventId, agentId, tool, reason)
                        }
                    }

                    "emit" -> Unit
                }
            }

            "end" -> Log.d(TAG, "stream ended")
            "error" -> {
                val err = frame.optJSONObject("error")?.optString("message").orEmpty()
                Log.w(TAG, "stream error: $err")
            }
        }
    }

    /**
     * 把决定回传给服务端。
     *
     * [answer] 只能是 `allowed-once` / `rejected` / `cancelled` / `unavailable` 之一。
     */
    fun answer(eventId: String, answer: String, cb: (Boolean) -> Unit = {}) {
        val cid = clientId
        if (cid.isNullOrBlank()) {
            Log.w(TAG, "no clientId yet, cannot answer")
            cb(false)
            return
        }
        scope.launch {
            val body = JSONObject().apply {
                put("type", "client-request")
                put("rpcId", UUID.randomUUID().toString())
                put("method", "\$events/result")
                put(
                    "payload", JSONObject().put(
                        "args", JSONObject().apply {
                            put("clientId", cid)
                            put("eventId", eventId)
                            put(
                                "outcome", JSONObject().apply {
                                    put("kind", "result")
                                    put("value", answer)
                                }
                            )
                        }
                    )
                )
            }
            val ok = try {
                val res = ApiClient.postJson("$baseUrl/api/\$events/result", body)
                Log.d(TAG, "answer $answer -> $res")
                val obj = JSONObject(res)
                obj.optJSONObject("result")?.optBoolean("ok") ?: false
            } catch (e: Exception) {
                Log.w(TAG, "answer failed", e)
                false
            }
            cb(ok)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (closedByUser) return
        listener.onApprovalDisconnected(reason)
        val wait = BACKOFF_MS[retryCount.coerceAtMost(BACKOFF_MS.lastIndex)]
        retryCount++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            if (!closedByUser) openSocket()
        }
    }

    fun close() {
        closedByUser = true
        retryJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
    }
}
