package com.zcode.remote

import android.content.Context

/**
 * 系统分享入口的待发草稿（二期形态：暂存 + 直达 + 剪贴板，evolution-plan §4.3）。
 *
 * 分享进来的 EXTRA_TEXT 按不可信输入处理：只暂存本机 prefs 与系统剪贴板，绝不向
 * z.ai 输入框写任何文本（页面写入属三期 Lexical 门控，本仓注入层无任何写入代码）。
 * 一次性消费：有会话直达且复制成功即清（剪贴板接棒）；无会话停留入口由台面位草稿卡
 * 承接，点卡复制成功时消费；新分享覆盖旧草稿。宽松读写，异常静默（降级终点=旧行为）。
 */
object ShareDraft {

    private const val PREFS = "zcode_remote_share_draft"
    private const val KEY_TEXT = "text"

    fun save(context: Context, text: String) {
        try {
            prefs(context).edit().putString(KEY_TEXT, text).apply()
        } catch (e: Exception) {
            // 暂存失败：剪贴板路径仍可用，静默
        }
    }

    fun read(context: Context): String? = try {
        prefs(context).getString(KEY_TEXT, null)
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_TEXT).apply()
        } catch (e: Exception) {
            // 清不掉无害：下次 save 覆盖
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
