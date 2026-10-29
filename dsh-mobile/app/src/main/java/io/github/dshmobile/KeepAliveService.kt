package io.github.dshmobile

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 保活用的前台服务。
 *
 * 为什么必须有它（实测数据）：
 * App 切到后台后进程被降级成 cached（`/proc/<pid>/oom_score_adj` 从 0 跳到 700~900），
 * 随即 WebSocket 被系统掐断——logcat 原文 `mux disconnected: Software caused connection abort`，
 * 接着每次重连都 `failed to connect ... after 10000ms`。结果是后台完全收不到
 * `turn/end`，通知逻辑再正确也没事件可触发。
 *
 * 为什么用 specialUse 而不是 dataSync：
 * targetSdk 37 上 Android 15+ 按类型限时，官方原文
 * `The system permits dataSync and mediaProcessing foreground services to run for a total of
 * 6 hours in a 24-hour period`，超时回调 `Service.onTimeout()` 并抛
 * `ForegroundServiceStartNotAllowedException`。specialUse 不在这个限制列表里，
 * 运行时前置条件也是 None。代价是上架 Google Play 时要在 manifest 里声明用途，
 * 本地侧载无影响。
 *
 * 这个服务只负责「让进程别被降级」，不持有连接——
 * WebSocket 仍归 ChatActivity 的 MuxClient 管，进程活着它就能正常收发。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户从通知栏点「停止」时用这个 action 收尾
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        Notifier.ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        // START_STICKY：进程被杀后系统会重建服务，重连逻辑在 Activity 那边兜
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_ID_KEEPALIVE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DSH 正在后台保持连接")
            .setContentText("agent 跑完会通知你")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "keepalive service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DshKeepAlive"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "io.github.dshmobile.STOP_KEEPALIVE"

        fun start(context: android.content.Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
