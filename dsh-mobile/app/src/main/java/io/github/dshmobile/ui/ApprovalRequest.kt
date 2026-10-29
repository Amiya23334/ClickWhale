package io.github.dshmobile.ui

/**
 * 一条等待用户决定的审批请求。
 *
 * [id] 是服务端下发的 eventId，回传决定时必须原样带回；
 * [sessionId] 实测取自 waterfall 帧的 `agentId` 字段（值是 `session-xxx`，
 * 不是子代理 id——字段名有误导性）。
 */
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val reason: String,
)
