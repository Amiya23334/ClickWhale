package io.github.dshmobile.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 mux 下发的原始事件翻译成渲染用的模型。
 *
 * 字段依据 docs/dsh-mobile.md 第九节（2026-10-29 实测），不要凭记忆改。
 * 事件统一是 {type, seq, time, data}，seq 全 session 单调递增，去重排序靠它。
 */
object EventMapper {

    /** 一屏上的一个条目 */
    sealed class Item {
        abstract val seq: Int
    }

    /**
     * 消息里的一个**可保存的文件**。
     *
     * 以前文件块只拼成一行 `📄 名字` 塞进正文，`attachmentId` 直接丢掉，
     * 所以用户在手机上根本没法把它存下来。现在 id 留着，点了去中转取字节。
     */
    data class FileAttachment(
        val id: String,
        val name: String,
        val bytes: Long = 0L,
    )

    data class UserMsg(
        override val seq: Int,
        val text: String,
        val images: Int = 0,
        /** 图片附件的 `sha256:<hex>` 标识，按出现顺序。渲染时换成中转的读取地址。 */
        val attachmentIds: List<String> = emptyList(),
        val files: List<FileAttachment> = emptyList(),
    ) : Item()

    data class AssistantMsg(
        override val seq: Int,
        val text: String,
        val streaming: Boolean = false,
        val provider: String = "",
        val model: String = "",
        /** 助手输出的图片附件 id（工具产出的图可能这样回来） */
        val attachmentIds: List<String> = emptyList(),
        val files: List<FileAttachment> = emptyList(),
    ) : Item()

    data class Reasoning(
        override val seq: Int,
        val text: String,
    ) : Item()

    data class ToolCall(
        override val seq: Int,
        val callId: String,
        val name: String,
        val summary: String,
        val result: String? = null,
        val isError: Boolean = false,
    ) : Item()

    data class Notice(
        override val seq: Int,
        val text: String,
        val isError: Boolean = false,
    ) : Item()

    /**
     * 把一条事件转成**一组**条目。
     *
     * 一条 `assistant/message` 里可能同时有 reasoning 块和 text 块，
     * 要拆成「思考过程」+「正文」两条，按 content 里的先后顺序排。
     * 其它事件仍然是一对一，直接复用 `map`。
     */
    fun mapAll(event: JSONObject): List<Item> {
        if (event.optString("type") != "assistant/message") {
            return listOfNotNull(map(event))
        }
        val seq = event.optInt("seq", 0)
        val data = event.optJSONObject("data") ?: return emptyList()
        val msg = data.optJSONObject("message") ?: return emptyList()
        val content = msg.optJSONArray("content") ?: return emptyList()

        val textSb = StringBuilder()
        val reasonSb = StringBuilder()
        val imgIds = mutableListOf<String>()
        val files = mutableListOf<FileAttachment>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> {
                    if (textSb.isNotEmpty()) textSb.append('\n')
                    textSb.append(b.optString("text"))
                }
                "image" -> {
                    val id = b.optJSONObject("attachment")?.optString("attachmentId", "") ?: ""
                    if (id.isNotEmpty()) imgIds.add(id)
                }
                "file" -> {
                    val att = b.optJSONObject("attachment")
                    val id = att?.optString("attachmentId", "") ?: ""
                    if (id.isNotEmpty()) {
                        files.add(FileAttachment(
                            id,
                            att.optString("name").ifBlank { "未命名文件" },
                            att.optLong("bytes"),
                        ))
                    }
                }
                "reasoning" -> {
                    val t = b.optString("text")
                    if (t.isNotBlank()) {
                        if (reasonSb.isNotEmpty()) reasonSb.append('\n')
                        reasonSb.append(t)
                    }
                }
            }
        }

        val out = mutableListOf<Item>()
        if (reasonSb.isNotBlank()) out.add(Reasoning(seq, reasonSb.toString()))
        if (textSb.isNotBlank() || imgIds.isNotEmpty() || files.isNotEmpty()) {
            val src = msg.optJSONObject("source")
            out.add(
                AssistantMsg(
                    seq = seq,
                    text = textSb.toString(),
                    provider = src?.optString("provider", "") ?: "",
                    model = src?.optString("model", "") ?: "",
                    attachmentIds = imgIds,
                    files = files,
                )
            )
        }
        return out
    }

    /**
     * 把一条事件转成条目。返回 null 表示这条不渲染（内部记账事件一类）。
     */
    fun map(event: JSONObject): Item? {
        val type = event.optString("type")
        val seq = event.optInt("seq", 0)
        val data = event.optJSONObject("data") ?: return null

        return when (type) {
            "user/message" -> {
                // data.source.kind 区分真用户输入和 DSH 的各类注入：
                //   user              → 真人打字
                //   agent-instructions → AGENTS.md 全文（实测 36797 字），绝不能渲染
                //   runtime-context / skill-catalog / time-context → 同样是注入
                // 只认 kind == "user"；缺失 kind 的老数据按用户消息处理。
                val kind = data.optJSONObject("source")?.optString("kind", "") ?: ""
                if (kind.isNotEmpty() && kind != "user") return null
                val content = data.optJSONArray("content") ?: return null
                val (text, ids, files) = extractTextAndAttachments(content)
                if (text.isBlank() && ids.isEmpty() && files.isEmpty()) null
                else UserMsg(seq, text, ids.size, ids, files)
            }

            "assistant/message" -> {
                val msg = data.optJSONObject("message") ?: return null
                val content = msg.optJSONArray("content") ?: return null
                val (text, ids, files) = extractTextAndAttachments(content)
                // 只调工具、不说话的 assistant 轮次文字为空，留着一个空气泡
                // 会变成一串灰块（实测历史里大量存在），直接丢掉。
                if (text.isBlank() && ids.isEmpty() && files.isEmpty()) return null
                val src = msg.optJSONObject("source")
                AssistantMsg(
                    seq = seq,
                    text = text,
                    provider = src?.optString("provider", "") ?: "",
                    model = src?.optString("model", "") ?: "",
                    attachmentIds = ids,
                    files = files,
                )
            }

            "tool/call" -> {
                val callId = data.optString("callId", "")
                val name = data.optString("name", "tool")
                ToolCall(
                    seq = seq,
                    callId = callId,
                    name = name,
                    summary = summarize(name, data.optString("arguments", "")),
                )
            }

            "tool/result" -> {
                // 结果事件要并进对应的 tool/call 条目里，由调用方处理
                null
            }

            // 系统提示词注入（role=system，2000+ tokens，含 skills 目录）。
            // 会话开始时有一条，必须丢掉：渲染出来就是一屏英文 prompt，
            // 而且它随快照传到手机上纯属浪费流量。
            // 实测踩过：靠 else->null 兜底不算数，显式写死才不会被人改漏。
            "system/message" -> null

            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                val kind = reason?.optString("kind", "") ?: ""
                if (kind == "error") {
                    Notice(seq, reason?.optString("message", "出错了") ?: "出错了", isError = true)
                } else null
            }

            "approval/asked" -> {
                val tool = data.optString("toolName", "工具")
                Notice(seq, "等待审批：$tool", isError = false)
            }

            else -> null
        }
    }

    /** 从 tool/result 事件里取出 (callId, 文本, 是否错误) */
    fun toolResult(event: JSONObject): Triple<String, String, Boolean>? {
        if (event.optString("type") != "tool/result") return null
        val data = event.optJSONObject("data") ?: return null
        val msg = data.optJSONObject("message") ?: return null
        val callId = msg.optString("toolCallId", "")
        if (callId.isEmpty()) return null
        val content = msg.optJSONArray("content") ?: return Triple(callId, "", false)
        val sb = StringBuilder()
        var isError = false
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> sb.append(b.optString("text"))
                "image" -> sb.append("[图片]")
            }
            // DSH 把失败信息也塞在 text 里，前面带 [stderr] 之类的标记
            if (b.optString("text").contains("[exit code:", ignoreCase = true) ||
                b.optString("text").startsWith("[COMMAND_FAILED")
            ) isError = true
        }
        return Triple(callId, sb.toString(), isError)
    }

    /**
     * 取正文 + 附件 id。
     *
     * 事件里的图片块形状（2026-10-29 实测）：
     *   {type:"image", attachment:{attachmentId:"sha256:<hex>", mediaType, width, height, bytes, name}}
     * 文件块同构：{type:"file", attachment:{attachmentId, name, bytes}}
     *
     * **事件里没有 base64**，只有 id。字节要另外向中转取
     * （/relay/attachment/sha256:...）。这里只把 id 抠出来交给渲染层。
     */
    private fun extractTextAndAttachments(content: JSONArray): Triple<String, List<String>, List<FileAttachment>> {
        val sb = StringBuilder()
        val ids = mutableListOf<String>()
        val files = mutableListOf<FileAttachment>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(b.optString("text"))
                }
                "image" -> {
                    val id = b.optJSONObject("attachment")?.optString("attachmentId", "") ?: ""
                    if (id.isNotEmpty()) ids.add(id)
                }
                "file" -> {
                    // 不再只留一行文本：id 和大小都留着，渲染成可点的 chip。
                    val att = b.optJSONObject("attachment")
                    val id = att?.optString("attachmentId", "") ?: ""
                    val name = att?.optString("name", "") ?: ""
                    if (id.isNotEmpty()) {
                        files.add(FileAttachment(id, name.ifBlank { "未命名文件" }, att?.optLong("bytes") ?: 0L))
                    } else if (name.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append("📄 ").append(name)
                    }
                }
                "tool-call" -> { /* 工具调用走 tool/call 事件，这里跳过 */ }
                "reasoning" -> { /* 思考过程单独处理 */ }
            }
        }
        return Triple(sb.toString(), ids, files)
    }

    /**
     * 工具调用的一行摘要。
     * 目标是「不展开也知道在干什么」，所以优先取最有信息量的那个参数。
     */
    private fun summarize(name: String, argsJson: String): String {
        if (argsJson.isBlank()) return ""
        val args = try { JSONObject(argsJson) } catch (e: Exception) { return "" }
        val pick = when {
            args.has("file_path") -> args.optString("file_path")
            args.has("path") -> args.optString("path")
            args.has("command") -> args.optString("command").lineSequence().firstOrNull() ?: ""
            args.has("description") -> args.optString("description")
            args.has("pattern") -> args.optString("pattern")
            args.has("url") -> args.optString("url")
            args.has("query") -> args.optString("query")
            else -> ""
        }
        val oneLine = pick.replace('\n', ' ').trim()
        return when {
            oneLine.length <= 90 -> oneLine
            else -> oneLine.take(88) + "…"
        }
    }
}
