package com.zcode.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 切出 App 后的短时保活服务：WebView 会话本质上靠进程存活，
 * 用前台服务顶住系统的后台回收，到时间自动停止。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val stopRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        // 被系统杀掉后重启时 intent 为 null：从本地恢复会话信息继续保活
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val minutes = (intent?.getIntExtra(EXTRA_MINUTES, -1) ?: -1)
            .takeIf { it > 0 } ?: prefs.getInt(KEY_MINUTES, DEFAULT_MINUTES)
        val url = (intent?.getStringExtra(EXTRA_URL) ?: prefs.getString(KEY_URL, "")) ?: ""
        if (intent != null) {
            prefs.edit()
                .putInt(KEY_MINUTES, minutes)
                .putString(KEY_URL, url)
                .apply()
        }
        startForegroundWithNotification(minutes, url)
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, minutes * 60_000L)
        // START_STICKY：服务被系统回收后自动重启，保活更稳
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(stopRunnable)
        super.onDestroy()
    }

    private fun startForegroundWithNotification(minutes: Int, url: String) {
        val preview = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPLY_PREVIEW, null)
        val notification = buildNotification(this, minutes, url, preview)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIF_ID = 1001
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_URL = "url"
        private const val DEFAULT_MINUTES = 10
        private const val PREFS = "zcode_remote_keep_alive"
        private const val KEY_MINUTES = "minutes"
        private const val KEY_URL = "url"
        private const val KEY_REPLY_PREVIEW = "reply_preview"

        /** 服务是否存活：reply 预览更新时据此决定要不要重发保活通知 */
        @Volatile
        private var running = false

        /** 二期入口仪表盘只读消费：保活周期是否存活（纯读 running 标志，零行为变更） */
        fun isRunning(): Boolean = running

        /** 二期入口仪表盘只读消费：本轮保活时长（分钟，KEY_MINUTES 原值——与保活通知
         *  「剩 X 分」同源同口径：通知自建成不递减，这里保持一致；非实时倒计时）。 */
        fun keepAliveMinutes(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MINUTES, DEFAULT_MINUTES)

        /** 二期小部件只读消费：最近一次回复预览（KEY_REPLY_PREVIEW 原值；回会话时被
         *  clearReplyPreview 清空，消费方自行退回最近列表的末条回复摘要）。纯读。 */
        fun replyPreview(context: Context): String = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPLY_PREVIEW, "") ?: ""
        } catch (e: Exception) {
            ""
        }

        /** 在 WebActivity 还位于前台时调用（onPause 阶段），绕开后台启动 FGS 的限制。 */
        fun start(context: Context, minutes: Int, url: String) {
            if (minutes <= 0) return
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_URL, url)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 个别 ROM 限制前台服务启动，退一步用普通启动（部分 ROM 接受）
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    // 都不行就静默放弃，不影响正常切出
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /** 收到新回复时调用：保活通知带上最近回复预览（锁屏可见）；服务未运行时仅存档，启动时带上。
         *  [replyArrived]=true 表示来自去重通过的回复链路（横幅已发）：仅此时把摘要写进最近会话
         *  （RecentStore.lastReply/lastReplyAt，[url] 按会话定位目标卡片）——onProgress 的中间
         *  流式进度只滚动更新通知预览，不落最近列表（否则摘要恒为"最后一次进度文本"且桥线程
         *  每拍全量 load/save）。保活通知真正 post 后给一次 done 短震——前台会话服务必不在跑
         *  （onStart 已 stop），走不到 post，不额外震。 */
        fun updateReplyPreview(
            context: Context,
            text: String,
            replyArrived: Boolean = false,
            url: String = "",
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val preview = text.trim().take(60)
            prefs.edit().putString(KEY_REPLY_PREVIEW, preview).apply()
            if (replyArrived) {
                RecentStore(context).updateLastReply(preview, url)
            }
            // 二期小部件：回复/进度文本变化即推刷新（无部件安装时零开销直接返回），
            // 放在存档之后——服务不在跑也要刷新部件（部件不依赖保活存活）
            try {
                SessionWidgetProvider.push(context)
            } catch (e: Exception) {
                // 部件刷新失败不影响保活链路
            }
            if (!running) return
            try {
                val minutes = prefs.getInt(KEY_MINUTES, DEFAULT_MINUTES)
                val url = prefs.getString(KEY_URL, "") ?: ""
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(context, minutes, url, preview))
                if (replyArrived) Haptics.done(context)
            } catch (e: Exception) {
                // 通知重建失败不影响主流程（done 震感随通知一起放弃）
            }
        }

        /** 回到会话时调用：清掉预览，避免下一个保活周期显示上一轮的旧回复。 */
        fun clearReplyPreview(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_REPLY_PREVIEW).apply()
        }

        private fun buildNotification(
            context: Context,
            minutes: Int,
            url: String,
            replyPreview: String?,
        ): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_keep_alive),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }

            // 点击通知直接回到还在运行的会话页（WebActivity 活着就不重载）；
            // 不能指向 singleTask 的入口页，否则任务栈上方的会话页会被系统清掉
            val target = if (url.isNotBlank()) {
                Intent(context, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url)
            } else {
                Intent(context, EntryActivity::class.java)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                target.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val text = if (replyPreview.isNullOrBlank()) {
                context.getString(R.string.keep_alive_notif_text, minutes)
            } else {
                context.getString(R.string.keep_alive_notif_reply, minutes, replyPreview)
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(context.getString(R.string.keep_alive_notif_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}
