package com.zcode.remote

import android.content.Context

/**
 * 会话 Agent 状态落盘（一期「状态感知轴」，evolution-plan §4.1/§4.7）：09_agent.js
 * 只读状态机经桥 onAgentState(state, snippet) 上报变化沿，这里存最近一次状态+摘要+
 * 时间戳，供状态条之外的后续消费方（二期仪表盘/小部件）读取。
 *
 * 状态是易逝的运行时信号：落盘失败不影响状态条（内存态即时呈现），读侧对陈旧值
 * 自行按时间戳甄别。宽松读写（仿 RecentStore）：任何异常静默返回缺省值，绝不抛出。
 */
class AgentStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun update(state: String, snippet: String) {
        try {
            prefs.edit()
                .putString(KEY_STATE, state)
                .putString(KEY_SNIPPET, snippet.take(120))
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // 落盘失败：状态条照常工作，静默
        }
    }

    fun loadState(): String = try {
        prefs.getString(KEY_STATE, UNKNOWN) ?: UNKNOWN
    } catch (e: Exception) {
        UNKNOWN
    }

    fun loadSnippet(): String = try {
        prefs.getString(KEY_SNIPPET, "") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun loadAt(): Long = try {
        prefs.getLong(KEY_AT, 0L)
    } catch (e: Exception) {
        0L
    }

    companion object {
        private const val PREFS = "zcode_remote_agent_state"
        private const val KEY_STATE = "state"
        private const val KEY_SNIPPET = "snippet"
        private const val KEY_AT = "at"
        const val UNKNOWN = "unknown"
    }
}
