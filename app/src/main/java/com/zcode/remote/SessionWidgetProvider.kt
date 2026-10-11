package com.zcode.remote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 桌面小部件（二期「系统集成」，evolution-plan §4.3 单格会话状态）：
 * 会话名 + 状态色点（@color/status_* 语义槽位）+ 最近回复一行 + 相对时间。
 *
 * 数据全部只读既有落盘（RecentStore / AgentStateStore / KeepAliveService 已落盘的
 * KEY_REPLY_PREVIEW），不建第二条 z.ai 连接；回复到达（updateReplyPreview）与状态
 * 变化（onAgentState）经 push() 推刷新——无部件安装时 getAppWidgetIds 为空直接返回，
 * 「不添加即零影响」。
 *
 * 数据是「上次」快照：进程死后部件保留陈旧值，由 updatePeriodMillis 的 30 分钟周期
 * onUpdate 重渲染，数据龄超过阈值即标「上次」+ 灰点（陈旧值如实标注，不伪装实时）。
 * 不用列表型、不引 RemoteViewsService；点击 PendingIntent.getActivity 直达 WebActivity
 * （同 KeepAliveService contentIntent 姿势，避开磁贴不能指 singleTask 入口的限制）。
 */
class SessionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        try {
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        } catch (e: Exception) {
            // 部件渲染失败静默（缺件健全：App 其余部分照常）
        }
    }

    companion object {
        /** 数据龄阈值：与 updatePeriodMillis 同刻度（30 分钟），超过即视为陈旧快照 */
        private const val STALE_MS = 30L * 60_000

        /** 回复到达 / 状态变化时调用（KeepAliveService.updateReplyPreview 与
         *  WebActivity.onAgentState）：有部件才渲染，无部件零开销 */
        fun push(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, SessionWidgetProvider::class.java),
                )
                if (ids == null || ids.isEmpty()) return
                manager.updateAppWidget(ids, build(context))
            } catch (e: Exception) {
                // ROM 差异：静默
            }
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_session)
            val recent = try {
                RecentStore(context).load().firstOrNull()
            } catch (e: Exception) {
                null
            }
            if (recent == null) {
                views.setTextViewText(R.id.widgetName, context.getString(R.string.app_name))
                views.setImageViewResource(R.id.widgetDot, R.drawable.dot_offline)
                views.setTextViewText(R.id.widgetReply, context.getString(R.string.widget_no_session))
                views.setTextViewText(R.id.widgetTime, "")
                views.setOnClickPendingIntent(R.id.widgetRoot, contentIntent(context, ""))
                return views
            }

            views.setTextViewText(R.id.widgetName, recent.displayLabel())

            // 回复行：保活链路落盘的 KEY_REPLY_PREVIEW 优先（含流式中间进度，最新）；
            // 回会话时它被 clearReplyPreview 清空，退回最近列表落盘的末条回复摘要
            val preview = try {
                KeepAliveService.replyPreview(context).ifBlank { recent.lastReply }
            } catch (e: Exception) {
                recent.lastReply
            }
            views.setTextViewText(R.id.widgetReply, preview)

            // 相对时间与陈旧标记：活跃时刻取 max(打开/末条回复/状态上报)——与入口页
            // 台面卡同口径；进程死后无 push，仅剩 30 分钟周期 onUpdate 重渲染，届时
            // 数据龄超过阈值 → 标「上次」（陈旧快照如实告知）
            val agent = try {
                AgentStateStore(context)
            } catch (e: Exception) {
                null
            }
            val agentAt = agent?.loadAt() ?: 0L
            val ts = maxOf(recent.last, recent.lastReplyAt, agentAt)
            val rel = RecentAdapter.relativeTime(context, ts)
            val stale = ts <= 0L || System.currentTimeMillis() - ts > STALE_MS
            views.setTextViewText(
                R.id.widgetTime,
                if (stale && rel.isNotEmpty()) {
                    context.getString(R.string.widget_stale, rel)
                } else {
                    rel
                },
            )

            // 状态色点：与入口页状态芯片同口径（绿=活跃系/待命，amber=等待确认，
            // 灰=unknown/缺件——宁灰勿错）
            val dot = when (agent?.loadState()) {
                "thinking", "generating", "idle", "done" -> R.drawable.dot_live
                "waitConfirm" -> R.drawable.dot_warn
                else -> R.drawable.dot_offline
            }
            views.setImageViewResource(R.id.widgetDot, dot)

            views.setOnClickPendingIntent(R.id.widgetRoot, contentIntent(context, recent.url))
            return views
        }

        /** 整卡点击直达会话（照 KeepAliveService.kt 的 contentIntent 姿势：
         *  getActivity + NEW_TASK|SINGLE_TOP|CLEAR_TOP；无会话退回入口页）。
         *  requestCode 独立取 4001：PendingIntent 身份忽略 extras，与 KeepAliveService/
         *  WebActivity/ConfirmNotify 共用 0 会互覆 extras（评审收尾修） */
        private const val PI_REQUEST = 4001

        private fun contentIntent(context: Context, url: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                PI_REQUEST,
                (if (url.isNotBlank()) {
                    Intent(context, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url)
                } else {
                    Intent(context, EntryActivity::class.java)
                }).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
