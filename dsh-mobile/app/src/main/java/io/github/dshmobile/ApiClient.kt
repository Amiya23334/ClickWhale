package io.github.dshmobile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * DSH 的 unary HTTP API 客户端（不经过 WebSocket）。
 *
 * 信封格式（实测 2026-10-29，写错会 404 或静默失败）：
 *   POST /api/<前缀>/<方法>，路径用斜杠，不是点
 *   body: {"type":"client-request","rpcId":"<uuid>","method":"<前缀>/<方法>","payload":{"args":{...}}}
 *   返回: {"rpcId":"...","result":{"ok":true,"value":{...}}}
 *
 * 三个必踩的坑：
 *   1. method 必须用斜杠（sessions/list），点号会报 "method x.y does not match endpoint x/y"
 *   2. 参数必须包在 payload.args 里，不能直接放在 payload 下
 *   3. rpcId 必须回传（这里每次生成新的即可，只要在 body 里带上）
 */
class ApiClient(private val baseUrl: String) {

    private val pool = Executors.newFixedThreadPool(4)

    data class SessionSummary(
        val sessionId: String,
        val title: String,
        val running: Boolean,
        val blank: Boolean,
        val updatedAt: Long,
        val cwd: String,
    )

    fun listSessions(workspaceId: String, cb: (Result<List<SessionSummary>>) -> Unit) {
        pool.execute {
            val r = try {
                val json = call("session/list", JSONObject().apply {
                    put("workspaceId", workspaceId)
                })
                val value = valueOf(json)
                val items = value.optJSONArray("items") ?: JSONArray()
                val out = mutableListOf<SessionSummary>()
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val proj = o.optJSONObject("projections")
                        ?.optJSONObject("values")
                    out.add(
                        SessionSummary(
                            sessionId = o.optString("sessionId"),
                            title = proj?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
                                ?: "未命名会话",
                            running = o.optBoolean("running", false),
                            blank = o.optBoolean("blank", false),
                            updatedAt = o.optLong("updatedAt", 0L),
                            cwd = o.optString("cwd", ""),
                        )
                    )
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 建/取工作区。
     *
     * 手机端没法自己挑目录（DSH 的目录选择器是电脑原生的），所以先问中转
     * `/relay/default-workspace` 要一个目录，再拿它去 workspace/create。
     * 直接传空路径会 EPERM；传盘根（`D:\`）会让 DSH `mkdir D:` 报错。
     */
    fun listWorkspaces(cb: (Result<List<Pair<String, String>>>) -> Unit) {
        pool.execute {
            val r = try {
                val dir = fetchDefaultWorkspace()
                val json = call("workspace/create", JSONObject().put("path", dir))
                val value = valueOf(json)
                val ws = value.optJSONObject("workspace")
                val id = ws?.optString("workspaceId") ?: ""
                val path = ws?.optString("path") ?: dir
                Result.success(listOf(id to path))
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    // ───────────── 模型目录 ─────────────

    /** 一个可选模型。provider 是 selectModel 必填的字段，必须跟着一起传回去。 */
    data class ModelOption(
        val provider: String,
        val providerName: String,
        val model: String,
        val name: String,
    )

    /**
     * 读会话当前模型。`session/projections` 参数 `{sessionId}`，
     * 结果里 `values.modelSelection` = `{provider, model, reasoningEffort?}`。
     */
    fun getModelSelection(sessionId: String, cb: (Result<Pair<String, String>>) -> Unit) {
        pool.execute {
            val r = try {
                val json = call("session/projections", JSONObject().put("sessionId", sessionId))
                val value = valueOf(json)
                val values = value.optJSONObject("values")
                val sel = values?.optJSONObject("modelSelection")
                // 真实结构是 {lastUsed:{provider,model,reasoningEffort}, next:{...}}，
                // 不是扁平的。优先取 next（下一个回合要用的），退回 lastUsed。
                val cur = sel?.optJSONObject("next") ?: sel?.optJSONObject("lastUsed") ?: sel
                Result.success(
                    (cur?.optString("provider") ?: "") to (cur?.optString("model") ?: "")
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 拉模型目录。`session/modelCatalog` 无参数（params 为空数组），
     * 返回按 provider 分组的 `groups[]`，每组 `{id, name, models[]}`。
     * 同时会带 `failures[]`（某个 provider 拉不到时的原因），这里忽略。
     */
    fun listModels(cb: (Result<List<ModelOption>>) -> Unit) {
        pool.execute {
            val r = try {
                val json = call("session/modelCatalog", JSONObject())
                val value = valueOf(json)
                val groups = value.optJSONArray("groups") ?: JSONArray()
                val out = mutableListOf<ModelOption>()
                for (i in 0 until groups.length()) {
                    val g = groups.optJSONObject(i) ?: continue
                    val pid = g.optString("id")
                    val pname = g.optString("name").ifBlank { pid }
                    val ms = g.optJSONArray("models") ?: JSONArray()
                    for (j in 0 until ms.length()) {
                        val m = ms.optJSONObject(j) ?: continue
                        val mid = m.optString("id")
                        if (mid.isBlank()) continue
                        out.add(
                            ModelOption(
                                provider = pid,
                                providerName = pname,
                                model = mid,
                                name = m.optString("name").ifBlank { mid },
                            )
                        )
                    }
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /** 中转给的默认目录；拿不到就空串（由调用方处理） */
    private fun fetchDefaultWorkspace(): String {
        val fallback = ""
        return try {
            val conn = URL("$baseUrl/relay/default-workspace").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            RelayAuth.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optJSONObject("result")
                ?.optJSONObject("value")?.optString("dir")
                ?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    fun createSession(workspaceId: String, cb: (Result<String>) -> Unit) {
        pool.execute {
            val r = try {
                val json = call("session/create", JSONObject().put("workspaceId", workspaceId))
                val value = valueOf(json)
                val sid = value.optString("sessionId").ifEmpty {
                    value.optJSONObject("session")?.optString("sessionId") ?: ""
                }
                if (sid.isEmpty()) Result.failure(IllegalStateException("没拿到 sessionId"))
                else Result.success(sid)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 发一条消息。参数名是 content 数组 + mode，不是 text —— 写成 text 会静默无效。
     */
    /**
     * 一条要随消息发出去的附件。
     *
     * 网关的 `PromptContentPart` 只有三种（asar 里
     * `@deepseek-ai/dsh-api-session-controller/types#SessionPromptRequest` 原文）：
     *
     *     { type:'text',  text }
     *     { type:'image', mediaType:'image/png'|'image/jpeg'|'image/webp'|'image/gif', data:<base64>, name? }
     *     { type:'file',  receiptId }
     *
     * 图片**直接内联 base64**，不用上传；其它文件必须先走 [uploadFile] 换成
     * receiptId。这就是为什么两类附件在界面上可以统一，在协议上却分两条路。
     */
    sealed class OutgoingPart {
        data class Text(val text: String) : OutgoingPart()
        data class Image(val mediaType: String, val base64: String, val name: String?) : OutgoingPart()
        data class FileRef(val receiptId: String, val name: String) : OutgoingPart()
    }

    /**
     * 上传一个文件，换回 receiptId。
     *
     * 参数形状是 **`{agentId, request:{data, name?}}`** —— 两个坑都在这里：
     *   ① `agentId` 在**外层**，请求体包在 `request` 里（不是平铺，也不是只有 request）；
     *   ② `agentId` 填的是 **sessionId**（descriptor 里 `scope.context="agent"`,
     *      `wire="agentId"`, `lookup="agent"`，类型是 `SessionId`）。
     * 传错任一个都会报 `args fields do not match the descriptor`。
     *
     * 返回 `{receiptId, file:{attachmentId, name, bytes}}`，attachmentId 形如
     * `sha256:...`，是内容寻址的存储 id，不是文件路径。
     */
    fun uploadFile(
        sessionId: String,
        bytes: ByteArray,
        name: String?,
        cb: (Result<String>) -> Unit,
    ) {
        pool.execute {
            val r = try {
                val req = JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (!name.isNullOrBlank()) req.put("name", name)
                val json = call("fileUploads/upload", JSONObject().apply {
                    put("agentId", sessionId)
                    put("request", req)
                })
                val receipt = valueOf(json).optString("receiptId", "")
                if (receipt.isBlank()) throw IllegalStateException("上传没返回 receiptId")
                Result.success(receipt)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /** 纯文本发送，保留旧签名，内部走 [sendParts]。 */
    fun sendPrompt(sessionId: String, text: String, cb: (Result<Unit>) -> Unit) =
        sendParts(sessionId, listOf(OutgoingPart.Text(text)), cb)

    /**
     * 发一条带附件（或纯文本）的消息。
     *
     * `content` 是**有序数组**，附件按用户排列的顺序跟在文字后面。网关要求
     * 「至少一个非空白文本部分或附件」，所以全都是空白文本时不能发。
     */
    fun sendParts(
        sessionId: String,
        parts: List<OutgoingPart>,
        cb: (Result<Unit>) -> Unit,
    ) {
        pool.execute {
            val r = try {
                val content = JSONArray()
                for (p in parts) {
                    content.put(
                        when (p) {
                            is OutgoingPart.Text -> JSONObject().apply {
                                put("type", "text"); put("text", p.text)
                            }
                            is OutgoingPart.Image -> JSONObject().apply {
                                put("type", "image")
                                put("mediaType", p.mediaType)
                                put("data", p.base64)
                                if (!p.name.isNullOrBlank()) put("name", p.name)
                            }
                            is OutgoingPart.FileRef -> JSONObject().apply {
                                put("type", "file"); put("receiptId", p.receiptId)
                            }
                        }
                    )
                }
                call("session/prompt", JSONObject().apply {
                    put("sessionId", sessionId)
                    put("mode", "queue")
                    put("content", content)
                })
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 取消当前正在跑的 prompt。args 必须包在 `request` 里（2026-10-29 实测：
     * 不包会报 `missing "request"; unexpected "sessionId"`）。
     * 会话必须在网关上有 live 连接，否则报 `session/not-found (not attached)`。
     */
    /**
     * 取消当前正在跑的 Agent 回合。
     *
     * 只传 `{sessionId}`，**不要自己包 `request`**——`call()` 会照
     * ARG_WRAPPERS 里的 `"session/cancel" to "request"` 自动包一层。
     * 手动再包一次就变成 `{request:{request:{...}}}`，网关报
     * `args fields do not match the descriptor`，表现是「点了停止没反应」。
     *
     * 网关侧返回 `{accepted: true}` 即已受理。实测（2026-10-29）：
     * 取消后 transcript 长度不再增长，`running` 变 false。
     */
    fun cancelPrompt(sessionId: String, cb: (Result<Unit>) -> Unit) {
        pool.execute {
            val r = try {
                call("session/cancel", JSONObject().put("sessionId", sessionId))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 重命名会话。args 包 `request`，成功返回 `{title, seq}`（2026-10-29 实测）。
     */
    fun renameSession(sessionId: String, title: String, cb: (Result<Unit>) -> Unit) {
        pool.execute {
            val r = try {
                call("session/rename", JSONObject().apply {
                    put("sessionId", sessionId)
                    put("title", title)
                })
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 归档会话（网关没有 session/delete，隐藏会话的正式形态就是归档）。
     * request = `{sessionId, stopActivity?}`；`stopActivity:true` 会停掉会话的
     * 进行中工作（turn/子代理/后台任务/定时）而不是拒绝归档。
     * 返回 `archivedSessionIds` = 归档后的完整列表（往返验证过，目标会移出/移入）。
     */
    fun archiveSession(sessionId: String, cb: (Result<Unit>) -> Unit) {
        pool.execute {
            val r = try {
                call("workspace/archiveSession", JSONObject().apply {
                    put("sessionId", sessionId)
                    put("stopActivity", true)
                })
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 撤销归档，把会话放回列表。request = `{sessionId}`，返回归档后的完整集合。
     * 对应的归档是 [archiveSession]（走的是 workspace 控制器同一个 service）。
     */
    fun unarchiveSession(sessionId: String, cb: (Result<Unit>) -> Unit) {
        pool.execute {
            val r = try {
                call("workspace/unarchiveSession", JSONObject().put("sessionId", sessionId))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 查一批会话的标题（归档列表要用：这些会话已经不在 `session/list` 里了，
     * 只能单独问 projections）。
     *
     * **`session/projections` 对已删除的会话返回 `value: null`**（实测），
     * 这种会话的标题给空串，UI 层显示成「已删除的会话」，不要当成错误。
     */
    fun fetchTitles(sessionIds: List<String>, cb: (Result<Map<String, String>>) -> Unit) {
        pool.execute {
            val r = try {
                val out = HashMap<String, String>()
                for (sid in sessionIds) {
                    try {
                        val raw = call("session/projections", JSONObject().put("sessionId", sid))
                        val v = raw.optJSONObject("value")
                        val vals = v?.optJSONObject("values")
                        out[sid] = vals?.optString("title") ?: ""
                    } catch (e: Exception) {
                        // 单条失败不影响整批
                        out[sid] = ""
                    }
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 往回翻一页历史（"cold-safe, message-aligned" 分页）。request schema 从 asar 的
     * `SessionPageRequest` 读出：
     *
     *   address      = {kind:"session", sessionId}
     *   throughSeq   = 从 follow 开场帧拿到的「包含式日志切点」，翻页期间保持不变
     *   beforeSeq    = 要往它之前翻；第一次传 throughSeq+1
     *   maxMessages  = **数的是消息，不是事件**（实测传 20 回来 122 条记录，
     *                  因为一条消息带 tool/call、tool/result 等多个事件）
     *   turnWindow   = {minMessages, minTurns}，可选，按完整回合切页
     *
     * 返回 `{records:[{type:"event", event:{...}}], hasMore:Boolean}`。
     * **seq 在 `record.event.seq` 里，不在 record 上**（我第一次取错了，翻页一直失败）。
     * 下一页的 beforeSeq 传本页 `min(event.seq)`，实测相邻页差 1、无重叠。
     */
    fun loadPage(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
        maxMessages: Int,
        cb: (Result<PageResult>) -> Unit
    ) {
        pool.execute {
            val r = try {
                val raw = call("session/page", JSONObject().apply {
                    put("address", JSONObject().apply {
                        put("kind", "session")
                        put("sessionId", sessionId)
                    })
                    put("throughSeq", throughSeq)
                    put("beforeSeq", beforeSeq)
                    put("maxMessages", maxMessages)
                })
                val v = valueOf(raw)
                val arr = v.optJSONArray("records") ?: JSONArray()
                val events = ArrayList<JSONObject>(arr.length())
                var minSeq = Long.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val rec = arr.optJSONObject(i) ?: continue
                    val ev = rec.optJSONObject("event") ?: continue
                    events.add(ev)
                    val sq = ev.optLong("seq", -1L)
                    if (sq in 0 until minSeq) minSeq = sq
                }
                Result.success(PageResult(
                    events = events,
                    hasMore = v.optBoolean("hasMore", false),
                    minSeq = if (minSeq == Long.MAX_VALUE) -1L else minSeq
                ))
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /** [loadPage] 的返回：事件列表 + 是否还有更老的 + 本页最小 seq（下一页的 beforeSeq）。 */
    data class PageResult(val events: List<JSONObject>, val hasMore: Boolean, val minSeq: Long)

    /**
     * 插件列表。
     *
     * 走的是插件市场自己的 HTTP 接口 `GET /dsh-market/installed`（不是网关的
     * client-request 那套），返回：
     *
     *   installed  = {包名: 版本}，版本可能是 `link:D:/...` 或 `github:owner/repo#sha`
     *   activation = {包名: {state, reasons, bundle, hot}}，state 为 `live` 表示真在跑
     *   present    = 实际装了的包名数组
     *
     * 插件页只读这三样就够——没有「开关」是因为切换要重启 DSH 才生效，
     * 手机上做这件事体验很差，留给桌面端。
     */
    fun listPlugins(cb: (Result<List<PluginInfo>>) -> Unit) {
        pool.execute {
            val r = try {
                val conn = URL("$baseUrl/dsh-market/installed").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code == 401) throw RelayUnauthorized()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(160)}")
                val root = JSONObject(body)
                val installed = root.optJSONObject("installed") ?: JSONObject()
                val activation = root.optJSONObject("activation") ?: JSONObject()
                val present = root.optJSONArray("present")
                val presentSet = HashSet<String>()
                if (present != null) for (i in 0 until present.length()) {
                    present.optString(i).takeIf { it.isNotBlank() }?.let { presentSet.add(it) }
                }
                val out = ArrayList<PluginInfo>(installed.length())
                for (name in installed.keys()) {
                    val version = installed.optString(name) ?: ""
                    val act = activation.optJSONObject(name)
                    out.add(
                        PluginInfo(
                            name = name,
                            version = version,
                            live = act?.optString("state") == "live",
                            bundle = act?.optBoolean("bundle", false) ?: false,
                            // present 里没有 = 装是装了但包不在了（pnpm 被 prune 掉或路径失效）
                            present = presentSet.isEmpty() || presentSet.contains(name),
                            source = when {
                                version.startsWith("link:") -> version.removePrefix("link:")
                                version.startsWith("github:") -> version.removePrefix("github:")
                                else -> ""
                            },
                        )
                    )
                }
                out.sortBy { it.name }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /** 一个插件的展示信息。[source] 只有 link/github 装法的才有值。 */
    data class PluginInfo(
        val name: String,
        val version: String,
        val live: Boolean,
        val bundle: Boolean,
        val present: Boolean,
        val source: String,
    )

    /**
     * 切换会话模型。request schema（从 asar 里 zod descriptor 读出）：
     * `{sessionId, provider, model, reasoningEffort?}` —— **provider 必填**，只传 model 会报
     * `wire field "request" failed boundary validation`。
     */
    /**
     * 执行一条 slash 命令（`/permission`、`/compact` 之类），不经过模型。
     *
     * 权限切换**没有 RPC**，只能走这条命令通道：`commands/execute` 的
     * descriptor 原文是 `Parse and execute a known command without sending it to the model.`
     * 参数是 `{agentId, line, submittedAttachments}` —— `agentId` 就是 sessionId，
     * `line` 是完整命令串（含参数），**`submittedAttachments` 是必填**（传空数组）。
     *
     * 返回值的 `value.result` 形如 `{kind:"success", text:"current preset read-only
     * (available: read-only, workspace-write, danger-full-access, qq-full-ask)"}`。
     */
    fun executeCommand(
        sessionId: String,
        line: String,
        cb: (Result<CommandResult>) -> Unit,
    ) {
        pool.execute {
            val r = try {
                val raw = call("commands/execute", JSONObject().apply {
                    put("agentId", sessionId)
                    put("line", line)
                    put("submittedAttachments", JSONArray())
                })
                val v = valueOf(raw)
                val result = v.optJSONObject("result") ?: JSONObject()
                Result.success(
                    CommandResult(
                        ok = result.optString("kind") == "success",
                        text = result.optString("text"),
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }

    /**
     * 读当前会话的权限预设，并从 `/permission` 的输出里解析出可选项。
     *
     * 输出格式（实测）：`current preset workspace-write (available: read-only, ...)`
     * 或切换成功时的 `preset read-only`。
     */
    fun getPermissionPreset(
        sessionId: String,
        cb: (Result<PermissionState>) -> Unit,
    ) {
        executeCommand(sessionId, "/permission") { res ->
            res.fold(
                onSuccess = { cr ->
                    val text = cr.text
                    val current = Regex("""current preset (\S+)""")
                        .find(text)?.groupValues?.get(1)
                        ?: Regex("""^preset (\S+)""").find(text)?.groupValues?.get(1)
                        ?: ""
                    val avail = Regex("""available: ([^)]+)""")
                        .find(text)?.groupValues?.get(1)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                    cb(Result.success(PermissionState(current, avail)))
                },
                onFailure = { cb(Result.failure(it)) },
            )
        }
    }

    data class CommandResult(val ok: Boolean, val text: String)

    /** 当前权限预设 + 可选项。 */
    data class PermissionState(val current: String, val available: List<String>)

    fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null, cb: (Result<Unit>) -> Unit) {
        pool.execute {
            val r = try {
                val req = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("provider", provider)
                    put("model", model)
                    if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
                }
                call("session/selectModel", req)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            cb(r)
        }
    }
    /**
     * 发 RPC。**每个端点对 args 的包装名不一样，不能统一**（实测 + 对照
     * `dsh-qq/src/dsh-client.js` 的 METHOD_ARG_WRAPPERS）：
     *
     *   session/list -> `_request`（带下划线，唯一一个）
     *   session/create|prompt|cancel|rename|selectModel… -> `request`
     *   agentPresets/list、settings/describe -> 不包装
     *
     * 再错一次：`session/prompt` 的 request 里必须带 `requestId`（新版强制），
     * 少了就报 `wire field "request" failed boundary validation`，看不出是缺哪个字段。
     */
    private fun call(method: String, args: JSONObject): JSONObject {
        val endpoint = method.replace('.', '/')
        var body = args
        if (endpoint == "session/prompt" && !body.has("requestId")) {
            body.put("requestId", UUID.randomUUID().toString())
        }
        val wrapper = ARG_WRAPPERS[endpoint]
        val finalArgs = when {
            wrapper == null && ARG_WRAPPERS.containsKey(endpoint) -> JSONObject()
            wrapper == null -> body
            else -> JSONObject().put(wrapper, body)
        }

        val payload = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", UUID.randomUUID().toString())
            put("method", endpoint)
            put("payload", JSONObject().put("args", finalArgs))
        }.toString()

        val url = URL("$baseUrl/api/$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            RelayAuth.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code == 401) throw RelayUnauthorized()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
            val root = JSONObject(text)
            // 有些端点外面还包一层 result
            if (root.has("result")) {
                val result = root.optJSONObject("result") ?: return root
                if (!result.optBoolean("ok", false)) {
                    val err = result.optJSONObject("error")
                    throw IllegalStateException(err?.optString("message") ?: "调用失败")
                }
                return result
            }
            return root
        } finally {
            conn.disconnect()
        }
    }

    private fun valueOf(result: JSONObject): JSONObject =
        result.optJSONObject("value") ?: JSONObject()

    companion object {
        /** 当前工作区路径（连接成功后由中转下发） */
        @Volatile
        var currentWorkspacePath: String = ""

        /**
         * 直接 POST 一个已经拼好的 client-request 信封，返回原始响应文本。
         *
         * 给 [ApprovalChannel] 用：审批回传走 `/api/$events/result`，不是普通 RPC，
         * 而且它**必须**由调用方自己准备好 `clientId`/`eventId`/`outcome` 三层嵌套，
         * 塞不进 [call] 那套「端点 + args 包装表」的模型里。
         */
        fun postJson(url: String, body: JSONObject): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 25000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            RelayAuth.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            } finally {
                conn.disconnect()
            }
        }

        /**
         * 端点 -> args 包装名。抄自 `dsh-qq/src/dsh-client.js` 的 METHOD_ARG_WRAPPERS，
         * 那边是逐项实测过的。`null` 表示这个端点不用包装（args 就是空对象）。
         * 表里没有的端点默认不包装（`ARG_WRAPPERS[x] == null` 且不是 key）。
         */
        private val ARG_WRAPPERS: Map<String, String?> = mapOf(
            "session/list" to "_request",
            "session/create" to "request",
            "session/prompt" to "request",
            "session/cancel" to "request",
            "session/selectModel" to "request",
            "session/rename" to "request",
            "session/fork" to "request",
            "session/updateQueue" to "request",
            "session/page" to "request",
            "session/search" to "request",
            "session/follow" to "request",
            "session/projections" to "request",
            "workspace/create" to "request",
            "workspace/rename" to "request",
            "workspace/delete" to "request",
            "workspace/archiveSession" to "request",
            "workspace/unarchiveSession" to "request",
            "workspace/pinSession" to "request",
            "workspace/unpinSession" to "request",
            "workspace/insertBefore" to "request",
            "workspace/insertSessionBefore" to "request",
            "agentPresets/list" to null,
            "settings/describe" to null,
            "session/modelCatalog" to null,
        )
    }
}

/**
 * 中转要求密钥而请求没带（或带错）。
 *
 * 单独一个类型是为了让上层能把它跟「中转没跑」「网络不通」分开——
 * 三者都表现为「拉不到数据」，但只有这一种该提示用户去填密钥。
 */
class RelayUnauthorized : Exception("需要访问密钥")
