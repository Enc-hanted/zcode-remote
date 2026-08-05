package com.zcode.remote

import android.content.Context

/** 会话页相关设置的快照（检测变化用：设置页返回后热更新页面，无需重进会话）。 */
data class PageSettings(
    val hideScrollbar: Boolean,
    val turnNavigator: Boolean,
    val notifyReply: Boolean,
    val floatingInput: Boolean,
)

/** 基础设置：会话页外观与后台保活策略。 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zcode_remote_settings", Context.MODE_PRIVATE)

    var hideScrollbar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SCROLLBAR, true)
        set(v) = prefs.edit().putBoolean(KEY_HIDE_SCROLLBAR, v).apply()

    var turnNavigator: Boolean
        get() = prefs.getBoolean(KEY_TURN_NAVIGATOR, true)
        set(v) = prefs.edit().putBoolean(KEY_TURN_NAVIGATOR, v).apply()

    /** 收到新回复时弹通知（前台时不弹，只提示切出后的回复） */
    var notifyReply: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_REPLY, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY_REPLY, v).apply()

    /** 0 = 关闭保活；否则为保活分钟数 */
    var keepAliveMinutes: Int
        get() = prefs.getInt(KEY_KEEP_ALIVE_MINUTES, 10)
        set(v) = prefs.edit().putInt(KEY_KEEP_ALIVE_MINUTES, v).apply()

    /** 会话页缩放百分比 */
    var textZoom: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, 100)
        set(v) = prefs.edit().putInt(KEY_TEXT_ZOOM, v).apply()

    /** 页面主题是否深色；null = 未上报过，跟随系统 */
    var pageThemeDark: Boolean?
        get() = if (prefs.contains(KEY_PAGE_THEME_DARK)) {
            prefs.getBoolean(KEY_PAGE_THEME_DARK, true)
        } else {
            null
        }
        set(v) {
            if (v == null) {
                prefs.edit().remove(KEY_PAGE_THEME_DARK).apply()
            } else {
                prefs.edit().putBoolean(KEY_PAGE_THEME_DARK, v).apply()
            }
        }

    /** 是否已申请过通知权限（只申请一次，跟电池优化白名单引导一样） */
    var notifPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_PERMISSION_ASKED, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_ASKED, v).apply()

    /** 浮动输入：隐藏底部输入框，单击 logo 唤出悬浮输入框（可展开全屏）。
     *  关闭时 logo 也不创建，输入框常驻底部 = 纯网页观感。 */
    var floatingInput: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_INPUT, true)
        set(v) = prefs.edit().putBoolean(KEY_FLOATING_INPUT, v).apply()

    /** 浮动 logo 是否被拖过位置（未拖过时 JS 按页面头部自动定位） */
    var logoPosSet: Boolean
        get() = prefs.getBoolean(KEY_LOGO_POS_SET, false)
        set(v) = prefs.edit().putBoolean(KEY_LOGO_POS_SET, v).apply()

    /** 浮动 logo 位置（屏幕比例：右距/宽、上距/高） */
    var logoX: Float
        get() = prefs.getFloat(KEY_LOGO_X, 0.98f)
        set(v) = prefs.edit().putFloat(KEY_LOGO_X, v).apply()

    var logoY: Float
        get() = prefs.getFloat(KEY_LOGO_Y, 0.02f)
        set(v) = prefs.edit().putFloat(KEY_LOGO_Y, v).apply()

    /** 会话页四开关快照：设置页返回后与上次注入值对比，有变化就热更新页面（动效已固定开启，不含） */
    fun pageSnapshot() = PageSettings(
        hideScrollbar = hideScrollbar,
        turnNavigator = turnNavigator,
        notifyReply = notifyReply,
        floatingInput = floatingInput,
    )

    companion object {
        private const val KEY_HIDE_SCROLLBAR = "hide_scrollbar"
        private const val KEY_TURN_NAVIGATOR = "turn_navigator"
        private const val KEY_NOTIFY_REPLY = "notify_reply"
        private const val KEY_KEEP_ALIVE_MINUTES = "keep_alive_minutes"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val KEY_PAGE_THEME_DARK = "page_theme_dark"
        private const val KEY_NOTIF_PERMISSION_ASKED = "notif_permission_asked"
        private const val KEY_FLOATING_INPUT = "floating_input"
        private const val KEY_LOGO_POS_SET = "logo_pos_set"
        private const val KEY_LOGO_X = "logo_x"
        private const val KEY_LOGO_Y = "logo_y"
    }
}
