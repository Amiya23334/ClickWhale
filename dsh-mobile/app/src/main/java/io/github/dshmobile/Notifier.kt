package io.github.dshmobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 本机通知：回合结束、等待审批、连接掉线时提醒一下。
 *
 * 为什么不做前台服务常驻：
 * - 这是「电脑在跑 agent，手机看进度」的场景，不是 IM。真需要秒回的场景用户就在看屏幕。
 * - targetSdk 37 上 Android 15+ 对前台服务按类型限时，`dataSync` 每 24 小时只能跑 6 小时
 *   （官方原文：`The system permits dataSync and mediaProcessing foreground services to run for
 *   a total of 6 hours in a 24-hour period`），超时抛 `ForegroundServiceStartNotAllowedException`。
 *   要长连接得走 `specialUse` 并接受 Google Play 审核，对一个局域网工具来说不值得。
 * - 现在的策略：App 在前台就弹呼吸灯式通知；切后台靠 MuxClient 的退避重连扛着，
 *   真被系统杀了下次打开重连即可，不做假活。
 */
object Notifier {

    private const val CHANNEL_ID = "dsh_mobile_events"
    private const val CHANNEL_NAME = "会话提醒"

    /**
     * 保活服务的常驻通知用单独通道。
     *
     * 不能和「会话提醒」共用一个：常驻通知是 LOW + 不可滑动清除的，
     * 混在一起会让用户想静音会话提醒时必须连着把常驻条一起静音。
     */
    const val CHANNEL_ID_KEEPALIVE = "dsh_mobile_keepalive"
    private const val CHANNEL_NAME_KEEPALIVE = "后台连接"
    private const val REQUEST_CODE = 1001

    /** 通知权限申请码，Activity 侧用同一个常量。 */
    const val REQ_NOTIFICATION = 1003

    /** Android 13+ 需要 POST_NOTIFICATIONS 运行时权限；低于此版本装了就有。 */
    fun needsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    /** 建通道。重复调用是幂等的，Android 会在已经存在时忽略。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "agent 回合结束、等待审批时提醒"
            // 亮一下但不要声音，干活时被通知音打断很烦
            ch.enableVibration(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }

        if (nm.getNotificationChannel(CHANNEL_ID_KEEPALIVE) == null) {
            val ka = NotificationChannel(
                CHANNEL_ID_KEEPALIVE, CHANNEL_NAME_KEEPALIVE, NotificationManager.IMPORTANCE_LOW
            )
            ka.description = "保持与电脑的连接，避免后台收不到消息"
            ka.enableVibration(false)
            ka.setSound(null, null)
            ka.setShowBadge(false)
            nm.createNotificationChannel(ka)
        }
    }

    /**
     * 弹一条通知。
     *
     * @param tag 用来区分多条通知（可按 tag 覆盖或取消），传 null 用固定 id
     * @param title 标题
     * @param text 正文
     */
    fun show(context: Context, tag: String?, title: String, text: String) {
        if (needsPermission(context)) return
        ensureChannel(context)

        val intent = Intent(context, ChatActivity::class.java).apply {
            // SINGLE_TOP + CLEAR_TOP：点通知时如果 ChatActivity 已经在栈顶就复用它
            // （走 onNewIntent），否则清掉中间页面只留它。
            //
            // 注意：这两个 flag 都**不会**重建 Activity，所以不会清掉 currentSessionId。
            // 我一度以为它会，那是误判——真正的元凶是别的（见下）。
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, REQUEST_CODE, intent, flags)

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(tag, if (tag == null) REQUEST_CODE else 0, n)
        } catch (_: SecurityException) {
            // 权限在弹之前被撤销了，静默跳过——通知失败不该让 App 崩
        }
    }

    /**
     * 连接掉线时提醒。
     *
     * 单独一个 tag：重连成功要能精确取消它，不能把回合结束那条也一起清掉。
     */
    fun showDisconnected(context: Context, reason: String) {
        show(context, TAG_DISCONNECTED, "已断开与电脑的连接", "$reason\n正在自动重连…")
    }

    fun clearDisconnected(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(TAG_DISCONNECTED, 0) }
    }

    const val TAG_DISCONNECTED = "disconnected"
}
