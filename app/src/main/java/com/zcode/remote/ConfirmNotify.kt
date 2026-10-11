package com.zcode.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 等待确认通知（二期「系统集成」，evolution-plan §4.3 终态形态）：
 * 09_agent 上报 waitConfirm 上升沿 → 独立 HIGH 通知，正文带弹窗摘要（snippet），
 * 点击 contentIntent 直达会话——人进去自己按，通知只负责把人叫到现场。
 *
 * 边界（维护者 v1.2 拍板，附录 C 冻结）：
 *  - 不做任何远程批准/拒绝 action 按钮（ask/user-input 等形态下一键批准比误报更危险）；
 *  - 不走 ReplyDedupe（其同文本 7 天去重会吞同文案提醒——确认弹窗文案常重复出现）；
 *  - 不重复震动：页面侧弹窗出现时已 haptic('confirm')（P0① 落地），通知 channel 关震动；
 *  - 状态离开 waitConfirm（下降沿）时撤下本通知。
 *
 * 33+ 未授权 POST_NOTIFICATIONS 时 notify() 静默不显——入口页既有引导卡负责引导，
 * 不另建流程；ROM 差异 try-catch 静默（照 KeepAliveService 双启动降级先例）。
 */
object ConfirmNotify {

    private const val CHANNEL_ID = "confirm_notify"

    /** 独立通知 ID 段：保活=1001 / 回复横幅=2000-2019 / 等待确认=3001 */
    private const val NOTIF_ID = 3001

    /** contentIntent 独立 requestCode：PendingIntent 身份忽略 extras，与 KeepAliveService/
     *  WebActivity/widget 的 WebActivity 目标共用 requestCode=0 会互覆 extras（评审收尾修） */
    private const val PI_REQUEST = 3001

    /**
     * 状态沿消费：上升沿（进入 waitConfirm）发通知，下降沿（离开 waitConfirm）撤通知。
     * prev 由调用方在状态记账更新前取值（WebActivity.agentState）。
     */
    fun onEdge(context: Context, prev: String, state: String, snippet: String, url: String) {
        when {
            state == "waitConfirm" && prev != "waitConfirm" ->
                notify(context, snippet, url)
            prev == "waitConfirm" && state != "waitConfirm" ->
                cancel(context)
        }
    }

    private fun notify(context: Context, snippet: String, url: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.confirm_notif_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                // 页面侧弹窗出现时已 haptic('confirm')：通知侧不再震动，避免双重确认感
                channel.enableVibration(false)
                nm.createNotificationChannel(channel)
            }

            // 点击直达会话（照 KeepAliveService.buildNotification 的 contentIntent 姿势：
            // getActivity + NEW_TASK|SINGLE_TOP|CLEAR_TOP——WebActivity 活着就不重载）
            val target = if (!url.isNullOrBlank()) {
                Intent(context, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url)
            } else {
                Intent(context, EntryActivity::class.java)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                PI_REQUEST,
                target.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val body = if (snippet.isBlank()) {
                context.getString(R.string.confirm_notif_fallback)
            } else {
                snippet
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(context.getString(R.string.state_wait_confirm))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            // 33+ 未授权 / ROM 差异：静默，不影响会话主流程
        }
    }

    fun cancel(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (e: Exception) {
            // 撤不下就算了：autoCancel 兜底
        }
    }
}
