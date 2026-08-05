package com.zcode.remote

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** 一条最近会话记录。name 取自远控链接里的 name= 参数（桌面端机器名）。 */
data class Recent(val url: String, val name: String, val last: Long) {
    fun displayLabel(): String {
        if (name.isNotBlank()) return name
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        return host ?: url
    }

    override fun toString(): String = displayLabel()
}

/** 用 SharedPreferences + JSON 保存最近使用过的远控链接，最多 10 条。 */
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
        val list = load()
        list.removeAll { it.url == url }
        val name = runCatching { Uri.parse(url).getQueryParameter("name") }
            .getOrNull()
            .orEmpty()
        list.add(0, Recent(url, name, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
    }

    fun remove(url: String) {
        val list = load()
        list.removeAll { it.url == url }
        save(list)
    }

    private fun save(list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("last", r.last),
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        // 记录存储格式版本：未来格式变更时在 load() 里按版本迁移
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_V1).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_SCHEMA = "schema_v"
        private const val SCHEMA_V1 = 1
        private const val MAX = 10
    }
}
