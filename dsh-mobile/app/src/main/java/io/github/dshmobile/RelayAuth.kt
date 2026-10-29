package io.github.dshmobile

/**
 * 中转访问密钥。
 *
 * 中转除了 `/relay/ping`（扫网段发现用）之外，所有请求都要求带 `x-relay-key`。
 * 这个对象是各 HTTP 客户端的统一出处——ApiClient（unary RPC）、MuxClient（事件流）、
 * RelayApi（目录选择器）、文件管理器的 FileLoader、Downloads 全在这里读。
 *
 * 用 @Volatile：UI 线程写、几个 IO 线程读。
 */
object RelayAuth {
    @Volatile
    var key: String = ""

    /** 需要带密钥时返回对应的头，否则返回空 map（没启用校验的中转不需要） */
    fun headers(): Map<String, String> =
        if (key.isBlank()) emptyMap() else mapOf("x-relay-key" to key)
}
