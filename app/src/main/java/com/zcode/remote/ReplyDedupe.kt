package com.zcode.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * 回复去重：内容指纹 + 最后出现时间戳（7 天窗口，按条目独立计算）。
 * 页面重载/重连后检测基线会清零，历史回复行会被重新判定为"新回复"，
 * 这里保证同一句内容 7 天内只通知一次；窗口过期后允许再次通知。
 */
class ReplyDedupe(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("zcode_remote_replies", Context.MODE_PRIVATE)

    /** 指纹 → 最后出现时刻 */
    private val seen: HashMap<Long, Long> = HashMap()

    init {
        val raw = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        for (item in raw) {
            val sep = item.indexOf(':')
            if (sep <= 0) continue
            val h = item.substring(0, sep).toLongOrNull() ?: continue
            val ts = item.substring(sep + 1).toLongOrNull() ?: continue
            if (now - ts < TTL_MS) seen[h] = ts
        }
        // 载入时顺手清掉过期条目（只写一次，避免每次启动都写盘）
        if (seen.size != raw.size) persist()
    }

    /**
     * @return true = 7 天窗口内已出现过（本次应跳过）；false = 新内容，已记录本次出现时间。
     */
    fun markSeen(text: String): Boolean {
        val h = fnv1a64(text.trim())
        val now = System.currentTimeMillis()
        val ts = seen[h]
        if (ts != null && now - ts < TTL_MS) return true
        seen[h] = now
        prune(now)
        persist()
        return false
    }

    private fun prune(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= TTL_MS) it.remove()
        }
        // 兜底上限：条目过多时丢最旧（正常 7 天窗口内到不了这个量）
        while (seen.size > MAX_ENTRIES) {
            var oldestKey = -1L
            var oldestTs = Long.MAX_VALUE
            for ((k, v) in seen) {
                if (v < oldestTs) {
                    oldestTs = v
                    oldestKey = k
                }
            }
            if (oldestKey < 0) break
            seen.remove(oldestKey)
        }
    }

    private fun persist() {
        val raw = HashSet<String>(seen.size)
        for ((h, ts) in seen) raw.add("$h:$ts")
        prefs.edit().putStringSet(KEY_SEEN, raw).apply()
    }

    /** FNV-1a 64 位哈希：碰撞概率可忽略，纯 Kotlin 无依赖 */
    private fun fnv1a64(s: String): Long {
        var h = -3750763034362895579L // 14695981039346656037（无符号）的带符号形式
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFFL)
            h *= 1099511628211L
        }
        return h
    }

    companion object {
        private const val KEY_SEEN = "seen"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 200
    }
}
