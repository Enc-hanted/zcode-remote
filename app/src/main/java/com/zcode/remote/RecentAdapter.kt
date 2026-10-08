package com.zcode.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 最近会话卡片列表：会话名 + 相对时间，点击继续、长按删除。 */
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
        holder.time.text = relativeTime(holder.itemView, item.last)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun relativeTime(view: View, ts: Long): String {
        if (ts <= 0L) return ""
        val diff = System.currentTimeMillis() - ts
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val ctx = view.context
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

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.recentName)
        val time: TextView = itemView.findViewById(R.id.recentTime)
    }
}
