package com.zcode.remote

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触感反馈（P0①「触感收口」）：五类状态确认震，全部短震、预置波形优先。
 *
 * 白名单语义（不做装饰性震感，手势/面板震感已随 navigator.vibrate 一并删除）：
 *  - done    回复到达（保活通知真正 post 时，后台会话；前台不额外震）
 *  - sent    发送成功（composer 新用户行 + 输入框清空双证据，经桥 haptic('sent')）
 *  - confirm 等待确认弹窗出现（经桥 haptic('confirm')，JS 侧自带 2s 防抖）
 *  - connect / error 本 P0 无触发源不接线，枚举与实现先齐（一期/二期再接）
 *
 * 总开关 hapticOn（SettingsStore，默认开）；无 VIBRATE 权限/无震动器/ROM 异常
 * 一律静默跳过——震感是锦上添花，任何失败都不许影响主流程。
 */
object Haptics {

    enum class Kind { CONNECT, SENT, DONE, CONFIRM, ERROR }

    fun connect(context: Context) = perform(context, Kind.CONNECT)

    fun sent(context: Context) = perform(context, Kind.SENT)

    fun done(context: Context) = perform(context, Kind.DONE)

    fun confirm(context: Context) = perform(context, Kind.CONFIRM)

    fun error(context: Context) = perform(context, Kind.ERROR)

    private fun perform(context: Context, kind: Kind) {
        try {
            if (!SettingsStore(context).hapticOn) return
            val vibrator = vibratorOf(context) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(effectOf(kind))
        } catch (e: Exception) {
            // 无权限（SecurityException）/服务缺失/ROM 差异：静默跳过
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** API 29+ 走预置波形；26–28 用等长的 oneShot/waveform 近似（低版本机型占比已极小） */
    private fun effectOf(kind: Kind): VibrationEffect =
        if (Build.VERSION.SDK_INT >= 29) {
            VibrationEffect.createPredefined(
                when (kind) {
                    Kind.CONNECT -> VibrationEffect.EFFECT_CLICK
                    Kind.SENT -> VibrationEffect.EFFECT_TICK
                    Kind.DONE -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Kind.CONFIRM -> VibrationEffect.EFFECT_CLICK
                    Kind.ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
                },
            )
        } else {
            when (kind) {
                // oneShot 时长按轻/重微调；error 用双脉冲保住"出错"的辨识度
                Kind.SENT, Kind.CONFIRM -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.CONNECT, Kind.DONE -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 25, 80, 25), -1)
            }
        }
}
