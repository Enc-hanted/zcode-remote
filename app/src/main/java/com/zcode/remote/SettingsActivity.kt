package com.zcode.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var notifyStatusRow: View
    private lateinit var notifyStatusText: TextView
    private lateinit var btnNotifyStatus: View

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // 被永久拒绝：跳系统通知设置页
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            } catch (e: Exception) {
                // 个别 ROM 没有该页面，忽略
            }
        }
        refreshNotifStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.followTheme(this)
        settings = SettingsStore(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // 隐藏滚动条
        val switchScrollbar = findViewById<MaterialSwitch>(R.id.switchScrollbar)
        switchScrollbar.isChecked = settings.hideScrollbar
        switchScrollbar.setOnCheckedChangeListener { _, checked ->
            settings.hideScrollbar = checked
        }

        // 对话问题导航
        val switchNavigator = findViewById<MaterialSwitch>(R.id.switchNavigator)
        switchNavigator.isChecked = settings.turnNavigator
        switchNavigator.setOnCheckedChangeListener { _, checked ->
            settings.turnNavigator = checked
        }

        // 浮动输入
        val switchFloatingInput = findViewById<MaterialSwitch>(R.id.switchFloatingInput)
        switchFloatingInput.isChecked = settings.floatingInput
        switchFloatingInput.setOnCheckedChangeListener { _, checked ->
            settings.floatingInput = checked
        }

        // 后台保活
        val switchKeepAlive = findViewById<MaterialSwitch>(R.id.switchKeepAlive)
        val radioDuration = findViewById<RadioGroup>(R.id.radioDuration)
        val radio5 = findViewById<RadioButton>(R.id.radio5)
        val radio10 = findViewById<RadioButton>(R.id.radio10)
        val radio30 = findViewById<RadioButton>(R.id.radio30)

        fun refreshDurationVisibility() {
            radioDuration.visibility =
                if (switchKeepAlive.isChecked) View.VISIBLE else View.GONE
        }

        switchKeepAlive.isChecked = settings.keepAliveMinutes > 0
        when (settings.keepAliveMinutes) {
            5 -> radio5.isChecked = true
            30 -> radio30.isChecked = true
            else -> radio10.isChecked = true
        }
        refreshDurationVisibility()

        switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            refreshDurationVisibility()
            if (checked) {
                val minutes = when (radioDuration.checkedRadioButtonId) {
                    R.id.radio5 -> 5
                    R.id.radio30 -> 30
                    else -> 10
                }
                settings.keepAliveMinutes = minutes
                requestNotifPermissionIfNeeded()
                Toast.makeText(
                    this,
                    getString(R.string.keep_alive_enabled_toast, minutes),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                settings.keepAliveMinutes = 0
                KeepAliveService.stop(this)
            }
        }

        radioDuration.setOnCheckedChangeListener { _, checkedId ->
            if (!switchKeepAlive.isChecked) return@setOnCheckedChangeListener
            settings.keepAliveMinutes = when (checkedId) {
                R.id.radio5 -> 5
                R.id.radio30 -> 30
                else -> 10
            }
        }

        // 新回复通知
        val switchNotifyReply = findViewById<MaterialSwitch>(R.id.switchNotifyReply)
        switchNotifyReply.isChecked = settings.notifyReply
        switchNotifyReply.setOnCheckedChangeListener { _, checked ->
            settings.notifyReply = checked
            if (checked) requestNotifPermissionIfNeeded()
        }

        // 通知权限状态（API 33 以下无运行时权限，整行隐藏）
        notifyStatusRow = findViewById(R.id.notifyStatusRow)
        notifyStatusText = findViewById(R.id.notifyStatusText)
        btnNotifyStatus = findViewById(R.id.btnNotifyStatus)
        notifyStatusRow.visibility =
            if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        btnNotifyStatus.setOnClickListener {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refreshNotifStatus()

        // 界面缩放
        val sliderZoom = findViewById<Slider>(R.id.sliderZoom)
        val zoomValue = findViewById<TextView>(R.id.zoomValue)
        sliderZoom.value = settings.textZoom.toFloat()
        zoomValue.text = getString(R.string.zoom_percent, settings.textZoom)
        sliderZoom.addOnChangeListener { _, value, _ ->
            zoomValue.text = getString(R.string.zoom_percent, value.toInt())
        }
        sliderZoom.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                settings.textZoom = slider.value.toInt()
            }
        })
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshNotifStatus() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        notifyStatusText.setText(
            if (granted) R.string.settings_notify_status_on else R.string.settings_notify_status_off,
        )
        btnNotifyStatus.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时同步最新授权状态
        refreshNotifStatus()
    }
}
