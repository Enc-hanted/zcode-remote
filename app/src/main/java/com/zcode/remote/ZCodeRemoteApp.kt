package com.zcode.remote

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 启动时按 ZCode 页面已上报的主题设置深浅色模式：
 * 页面报过主题就跟着页面走，没报过就跟系统走。
 */
class ZCodeRemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val mode = when (SettingsStore(this).pageThemeDark) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
