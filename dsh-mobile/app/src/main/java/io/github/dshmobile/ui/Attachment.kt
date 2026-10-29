package io.github.dshmobile.ui

/**
 * 一条待发送的附件（还没发出去，只在输入区里挂着）。
 *
 * 图片和其它文件在**协议上走两条路**，所以这里要区分：
 *   - 图片（png/jpeg/webp/gif）→ 读成 base64 直接内联进 `content`，
 *     网关的 `{type:'image', mediaType, data}` 就是这么定义的，不需要上传。
 *   - 其它文件 → 必须先 `fileUploads/upload` 换成 `receiptId`，
 *     再用 `{type:'file', receiptId}` 引用。
 *
 * 为什么不在本地就先读好字节：选中十几个文件时全部读进内存会直接 OOM
 * （手机上 100 MB 的附件很常见）。所以这里只留 URI 和元信息，
 * 真正读字节发生在「点发送」那一刻，一次只读一个。
 */
data class PendingAttachment(
    /** `content://` URI 字符串 */
    val uri: String,
    /** 显示名（带扩展名） */
    val name: String,
    /** MIME，例如 image/png；拿不到时按扩展名推 */
    val mime: String,
    /** 文件大小，未知为 -1 */
    val size: Long = -1L,
) {
    /**
     * 能不能走内联图片这条路。
     *
     * 网关只接受这四种 mediaType（schema 里是 enum），其它一律当普通文件上传。
     * 注意 `image/heic` 虽然在安卓相册里常见，但**不在白名单里**，
     * 硬发会被 boundary validation 拒掉，所以按文件走。
     */
    val isInlineImage: Boolean
        get() = mime in INLINE_IMAGE_TYPES

    /** 列表里显示的短标签，例如「IMG_2031.jpg · 1.2 MB」 */
    val label: String
        get() = if (size > 0) "$name · ${formatSize(size)}" else name

    companion object {
        /** 网关 `PromptContentPart` 的 image mediaType 枚举，多一个都不行。 */
        val INLINE_IMAGE_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
        )

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
