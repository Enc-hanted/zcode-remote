package com.zcode.remote

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴（二期「系统集成」，evolution-plan §4.3）：「回最近会话」。
 *
 * 状态只读反射 KeepAliveService.running 标志（服务存活=磁贴点亮），不主动改任何状态；
 * 点击回最近会话（无最近会话时退回入口页，磁贴永不死链）。exported=true 是系统绑定
 * TileService 的要求（BIND_QUICK_SETTINGS_TILE 权限保护，第三方绑不上）。
 *
 * 14+ 用 startActivityAndCollapse(PendingIntent) 新变体（老变体在 34+ 直接抛异常）；
 * 老版本走 deprecated Intent 变体并 try-catch 静默（ROM 后台启动限制，照
 * KeepAliveService.kt:91-100 双启动降级先例）。不启用即不存在：用户不添加磁贴，
 * 本服务零调用。
 */
class ResumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        try {
            val tile = qsTile ?: return
            tile.state = if (KeepAliveService.isRunning()) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.updateTile()
        } catch (e: Exception) {
            // 磁贴状态刷新失败静默
        }
    }

    override fun onClick() {
        super.onClick()
        try {
            val last = try {
                RecentStore(this).load().firstOrNull()
            } catch (e: Exception) {
                null
            }
            val intent = if (last != null) {
                Intent(this, WebActivity::class.java)
                    .putExtra(WebActivity.EXTRA_URL, last.url)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
            } else {
                Intent(this, EntryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                // 14+：PendingIntent 变体（锁屏/后台限制由系统按 PendingIntent 放行）
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            // 个别 ROM 限制磁贴启动 Activity：静默放弃，不影响系统面板
        }
    }
}
