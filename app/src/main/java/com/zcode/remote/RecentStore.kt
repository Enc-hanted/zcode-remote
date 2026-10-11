package com.zcode.remote

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** 一条最近会话记录。name 取自远控链接里的 name= 参数（桌面端机器名）。
 *  lastReply/lastReplyAt 是该会话最近一条回复的摘要与时间（保活链路收到回复时写入），
 *  缺省 空串/0 表示还没有记录过回复。
 *  unread（二期仪表盘）：会话不在前台时到达的回复计数，回到会话（WebActivity.onResume）
 *  清零；缺省 0 = 无未读，旧数据 optInt 取缺省即无角标。 */
data class Recent(
    val url: String,
    val name: String,
    val last: Long,
    val lastReply: String = "",
    val lastReplyAt: Long = 0L,
    val unread: Int = 0,
) {
    fun displayLabel(): String {
        if (name.isNotBlank()) return name
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        return host ?: url
    }

    override fun toString(): String = displayLabel()
}

/** 用 SharedPreferences + JSON 保存最近使用过的远控链接，最多 10 条。
 *  v82：出现两个写者（主线程 upsert/remove、WebView 桥线程 updateLastReply，且每次
 *  调用方都新建实例），load→改→save 无锁会互吞更新——写路径统一走类级锁 LOCK。 */
class RecentStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zcode_remote_recents", Context.MODE_PRIVATE)

    fun load(): MutableList<Recent> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return mutableListOf()
        // 单条损坏只跳过该条，不清空整表（防止一次坏数据丢掉全部最近会话）
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Recent>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Recent(
                            url = o.getString("url"),
                            name = o.optString("name"),
                            last = o.optLong("last"),
                            // 旧版本数据没有这些字段：opt* 取缺省值，等价于"还没收到过回复/无未读"
                            lastReply = o.optString("lastReply"),
                            lastReplyAt = o.optLong("lastReplyAt"),
                            unread = o.optInt("unread"),
                        ),
                    )
                } catch (e: Exception) {
                    // 跳过损坏条目
                }
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun upsert(url: String) {
        synchronized(LOCK) { upsertLocked(url) }
    }

    private fun upsertLocked(url: String) {
        val list = load()
        val name = param(url, "name")
        val mid = param(url, "mid")
        // 同一设备只保留一条：桌面端每次配对都会轮换链接（sid/hash/t 变），直接 upsert 会
        // 堆出多张同名卡片。mid（机器 ID，3.14.4 链接实测携带）相同即同一台设备，新链接覆盖旧链接；
        // 链接没有 mid 时退回按机器名合并（两台设备同名会被误并，可接受）
        // 摘要继承：链接轮换只覆盖链接本身，同会话旧条目里最近一次回复带进新条目，不丢"上次回复"
        val inherited = list.filter { sameSession(url, mid, name, it) }
            .maxByOrNull { it.lastReplyAt }
        list.removeAll { sameSession(url, mid, name, it) }
        list.add(
            0,
            Recent(
                url = url,
                name = name,
                last = System.currentTimeMillis(),
                lastReply = inherited?.lastReply.orEmpty(),
                lastReplyAt = inherited?.lastReplyAt ?: 0L,
                unread = inherited?.unread ?: 0,
            ),
        )
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
    }

    /** 更新某会话的最新回复摘要（回复链路最终回复到达时调用，v82 起中间流式进度不再落盘）。
     *  按 [url] 用 upsert 同款 sameSession 规则定位目标条目：回复横幅深链直启 WebActivity
     *  不经 upsert，"头部 = 当前会话"在深链路径失真（会写错卡片）；url 缺省（防御，现无
     *  此类调用方）退回头部条目保持旧行为；url 有值但无匹配（该会话不在最近列表）时
     *  宁可不写也不写错卡片；列表为空（还没打开过会话）时静默忽略。 */
    fun updateLastReply(reply: String, url: String = "") {
        if (reply.isBlank()) return
        synchronized(LOCK) {
            val list = load()
            if (list.isEmpty()) return
            val idx = if (url.isNotBlank()) {
                val mid = param(url, "mid")
                val name = param(url, "name")
                val hit = list.indexOfFirst { sameSession(url, mid, name, it) }
                if (hit < 0) return   // 无匹配：宁缺勿错
                hit
            } else {
                0
            }
            list[idx] = list[idx].copy(lastReply = reply, lastReplyAt = System.currentTimeMillis())
            save(list)
        }
    }

    fun remove(url: String) {
        synchronized(LOCK) {
            val list = load()
            list.removeAll { it.url == url }
            save(list)
        }
    }

    /** 二期未读计数：会话不在前台时回复到达（去重后）+1。定位与 updateLastReply 同一套
     *  sameSession 规则（深链直启不经 upsert，头部不一定是当前会话）；无匹配宁可不加。 */
    fun incrementUnread(url: String) = mutateUnread(url) { it + 1 }

    /** 回到会话即视为已读：清零该会话未读计数（WebActivity.onResume 调用）。 */
    fun clearUnread(url: String) = mutateUnread(url) { 0 }

    private fun mutateUnread(url: String, transform: (Int) -> Int) {
        if (url.isBlank()) return
        synchronized(LOCK) {
            val list = load()
            if (list.isEmpty()) return
            val mid = param(url, "mid")
            val name = param(url, "name")
            val idx = list.indexOfFirst { sameSession(url, mid, name, it) }
            if (idx < 0) return   // 无匹配：宁缺勿错
            list[idx] = list[idx].copy(unread = transform(list[idx].unread))
            save(list)
        }
    }

    /** 读链接里的查询参数；缺失或解析失败返回空串（不抛出）。 */
    private fun param(url: String, key: String): String =
        runCatching { Uri.parse(url).getQueryParameter(key) }.getOrNull().orEmpty()

    /** 判断已有记录是否与该链接指向同一台设备（upsert 的去重合并与摘要继承共用这一套匹配）：
     *  链接完全相同；或 mid 相同；链接没有 mid 时退回按机器名相同。 */
    private fun sameSession(url: String, mid: String, name: String, other: Recent): Boolean {
        if (other.url == url) return true
        if (mid.isNotBlank()) return param(other.url, "mid") == mid
        return name.isNotBlank() && param(other.url, "name") == name
    }

    private fun save(list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("last", r.last)
                    .put("lastReply", r.lastReply)
                    .put("lastReplyAt", r.lastReplyAt)
                    .put("unread", r.unread),
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        // 记录存储格式版本：未来格式变更时在 load() 里按版本迁移。
        // lastReply/lastReplyAt/unread 属纯增量可选字段（opt* 宽松读，旧数据取缺省值即
        // "无回复/无未读"），load() 不需要按版本迁移分支，因此不 bump schema_v
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_V1).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_SCHEMA = "schema_v"
        private const val SCHEMA_V1 = 1
        private const val MAX = 10

        /** 写路径互斥锁：跨实例生效（调用方各自 new RecentStore），方法级 synchronized 不够 */
        private val LOCK = Any()
    }
}
