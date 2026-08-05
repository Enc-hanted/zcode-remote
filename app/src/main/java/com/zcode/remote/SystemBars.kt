package com.zcode.remote

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.core.view.WindowCompat

/** 系统栏外观：跟着系统浅色/深色模式走，远控页（深色内容）固定深色。 */
object SystemBars {

    fun isDarkSystem(activity: Activity): Boolean {
        val mode = activity.resources.configuration.uiMode
        return (mode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 浅色系统 → 深色图标；深色系统 → 浅色图标；栏背景交给主题背景色 */
    fun followTheme(activity: Activity) {
        val dark = isDarkSystem(activity)
        val wic = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        wic.isAppearanceLightStatusBars = !dark
        activity.window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            wic.isAppearanceLightNavigationBars = !dark
            activity.window.navigationBarColor = Color.TRANSPARENT
        }
    }

    /** 深色内容页：系统栏固定深色 + 浅色图标（远控页本身是深色 UI） */
    fun forceDark(activity: Activity) {
        applyColor(activity, 22, 22, 22, dark = true)
    }

    /** 初始状态：透明系统栏（底色透出 App 主题背景），图标明暗按页面主题，等 JS 边缘采样接管 */
    fun initTransparent(activity: Activity, dark: Boolean) {
        activity.window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.navigationBarColor = Color.TRANSPARENT
        }
        val wic = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        wic.isAppearanceLightStatusBars = !dark
        if (Build.VERSION.SDK_INT >= 28) {
            wic.isAppearanceLightNavigationBars = !dark
        }
    }

    /** 顶栏/底栏分别就近取色：状态栏用页面顶部附近的色，导航栏用底部附近的色，图标明暗自适应。
     *  某侧采样失败时传 -1，该侧保持原样不动。 */
    fun applyBars(
        activity: Activity,
        tR: Int, tG: Int, tB: Int, tDark: Boolean,
        bR: Int, bG: Int, bB: Int, bDark: Boolean,
    ) {
        val wic = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (tR >= 0) {
            activity.window.statusBarColor = Color.rgb(tR, tG, tB)
            wic.isAppearanceLightStatusBars = !tDark
        }
        if (Build.VERSION.SDK_INT >= 28 && bR >= 0) {
            activity.window.navigationBarColor = Color.rgb(bR, bG, bB)
            wic.isAppearanceLightNavigationBars = !bDark
        }
    }

    /** 按页面主题给系统栏上色：深色 22,22,22 / 浅色 248,248,248，图标颜色取反 */
    fun applyColor(activity: Activity, r: Int, g: Int, b: Int, dark: Boolean) {
        val color = Color.rgb(r, g, b)
        activity.window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.navigationBarColor = color
        }
        val wic = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        wic.isAppearanceLightStatusBars = !dark
        if (Build.VERSION.SDK_INT >= 28) {
            wic.isAppearanceLightNavigationBars = !dark
        }
    }
}
