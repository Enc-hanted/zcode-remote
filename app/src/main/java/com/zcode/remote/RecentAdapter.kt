package com.zcode.remote

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 最近会话卡片列表：会话名 + 相对时间 + 未读角标（二期），点击继续、长按删除。 */
class RecentAdapter(
    private val onClick: (Recent) -> Unit,
    private val onLongClick: (Recent) -> Unit,
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    private val items = mutableListOf<Recent>()

    fun submit(list: List<Recent>) {
        items.clear()
        items.addAll(list)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayLabel()
        holder.time.text = secondLine(holder.itemView, item)
        if (item.unread > 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = badgeText(item.unread)
        } else {
            holder.badge.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    /** 卡片第二行：有回复摘要时显示「摘要 · 相对时间」（时间取回复时间），没有则退化为
     *  现状的使用时间行——旧数据/未收到过回复的会话不受影响。 */
    private fun secondLine(view: View, item: Recent): String {
        if (item.lastReply.isBlank()) return relativeTime(view.context, item.last)
        val replyTime = relativeTime(view.context, item.lastReplyAt)
        // lastReplyAt 无效（<=0）时只剩摘要本身，不拼出空尾巴
        return if (replyTime.isEmpty()) {
            item.lastReply
        } else {
            view.context.getString(R.string.recent_last_reply, item.lastReply, replyTime)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.recentName)
        val time: TextView = itemView.findViewById(R.id.recentTime)
        val badge: TextView = itemView.findViewById(R.id.recentBadge)
    }

    companion object {
        /** 相对时间（台面卡与小卡共用同一套文案；ts<=0 视为无时间，返回空串不拼尾巴） */
        fun relativeTime(ctx: Context, ts: Long): String {
            if (ts <= 0L) return ""
            val diff = System.currentTimeMillis() - ts
            val minutes = diff / 60_000
            val hours = diff / 3_600_000
            return when {
                minutes < 1 -> ctx.getString(R.string.just_now)
                minutes < 60 -> ctx.getString(R.string.minutes_ago, minutes)
                hours < 24 -> ctx.getString(R.string.hours_ago, hours)
                hours < 48 -> ctx.getString(
                    R.string.yesterday,
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)),
                )
                else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ts))
            }
        }

        /** 未读角标文案：>9 收敛为 9+（台面卡与小卡同口径） */
        fun badgeText(count: Int): String = if (count > 9) "9+" else count.toString()
    }
}
