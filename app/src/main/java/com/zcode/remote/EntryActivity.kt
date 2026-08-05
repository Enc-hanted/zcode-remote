package com.zcode.remote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * 入口页：扫码 / 粘贴获取 ZCode 远控链接，展示最近会话卡片，
 * 并引导加入电池优化白名单（进程不被杀，切出回来才是真"有缓存"）。
 * 远控链接即凭证，只保存在本机 SharedPreferences，绝不上传。
 */
class EntryActivity : AppCompatActivity() {

    private lateinit var inputUrl: TextInputEditText
    private lateinit var recentsList: RecyclerView
    private lateinit var recentsHeader: TextView
    private lateinit var recentsEmpty: TextView
    private lateinit var batteryCard: View
    private lateinit var notifyCard: View
    private lateinit var store: RecentStore
    private var recents = mutableListOf<Recent>()
    private lateinit var adapter: RecentAdapter

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (!contents.isNullOrBlank()) {
            openUrl(contents)
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchScanner() else toast(R.string.camera_denied)
    }

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
    }

    companion object {
        /** 进程内只自动跳转一次，避免从远控页返回入口后又被弹回去 */
        private var autoJumpedOnce = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry)
        SystemBars.followTheme(this)

        store = RecentStore(this)
        inputUrl = findViewById(R.id.inputUrl)
        recentsList = findViewById(R.id.recentsList)
        recentsHeader = findViewById(R.id.recentsHeader)
        recentsEmpty = findViewById(R.id.recentsEmpty)
        batteryCard = findViewById(R.id.batteryCard)
        notifyCard = findViewById(R.id.notifyCard)

        findViewById<View>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<View>(R.id.btnOpen).setOnClickListener {
            openUrl(inputUrl.text?.toString().orEmpty())
        }
        findViewById<View>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<View>(R.id.btnNotify).setOnClickListener {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        adapter = RecentAdapter(
            onClick = { openUrl(it.url) },
            onLongClick = { confirmDelete(it) },
        )
        recentsList.layoutManager = LinearLayoutManager(this)
        recentsList.adapter = adapter

        autoResumeLastSession(savedInstanceState)
        prefillFromClipboard()
        requestNotifPermissionOnce()
    }

    /** 首次启动申请一次通知权限（新回复提醒需要；跟电池优化白名单引导同一套路） */
    private fun requestNotifPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val settings = SettingsStore(this)
        if (settings.notifPermissionAsked) return
        settings.notifPermissionAsked = true
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 冷启动时如果最近有会话，直接进入，省去每次再点一下。 */
    private fun autoResumeLastSession(savedInstanceState: Bundle?) {
        if (autoJumpedOnce || savedInstanceState != null) return
        val last = store.load().firstOrNull() ?: return
        autoJumpedOnce = true
        toast(R.string.auto_resumed)
        launchWeb(last.url)
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
        refreshBatteryCard()
        refreshNotifyCard()
        syncPageTheme()
    }

    /** 通知权限没开就显示引导卡片（Android 13+） */
    private fun refreshNotifyCard() {
        if (Build.VERSION.SDK_INT < 33) {
            notifyCard.visibility = View.GONE
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        notifyCard.visibility = if (granted) View.GONE else View.VISIBLE
    }

    /** 页面主题在会话里变过的话，回入口页时跟着切（触发一次重建） */
    private fun syncPageTheme() {
        val desired = when (SettingsStore(this).pageThemeDark) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired)
            recreate()
        }
    }

    private fun startScan() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    /** 剪贴板是合法远控链接就直接打开；否则填入输入框供编辑。 */
    private fun pasteFromClipboard() {
        val text = readClipboard()?.trim()
        when {
            text.isNullOrBlank() -> toast(R.string.clipboard_empty)
            looksLikeRemoteLink(text) -> openUrl(text)
            else -> {
                inputUrl.setText(text)
                inputUrl.setSelection(inputUrl.text?.length ?: 0)
            }
        }
    }

    /** 启动时如果剪贴板里正好是一条远控链接，直接预填，省一步。 */
    private fun prefillFromClipboard() {
        val text = readClipboard()?.trim() ?: return
        if (looksLikeRemoteLink(text)) {
            inputUrl.setText(text)
        }
    }

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun looksLikeRemoteLink(url: String): Boolean {
        return url.startsWith("https://") && url.contains("zcode.z.ai/remote")
    }

    private fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("zcode.z.ai")) url = "https://$url"
        return url
    }

    private fun openUrl(raw: String) {
        val url = normalize(raw)
        when {
            url.isEmpty() -> toast(R.string.url_empty)
            !url.startsWith("http://") && !url.startsWith("https://") ->
                toast(R.string.url_invalid)
            looksLikeRemoteLink(url) -> launchWeb(url)
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.warn_title)
                .setMessage(getString(R.string.warn_not_zcode, url))
                .setPositiveButton(R.string.open_anyway) { _, _ -> launchWeb(url) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launchWeb(url: String) {
        store.upsert(url)
        startActivity(
            Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url),
        )
    }

    private fun refreshRecents() {
        recents = store.load()
        val empty = recents.isEmpty()
        recentsHeader.visibility = if (empty) View.GONE else View.VISIBLE
        recentsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        adapter.submit(recents)
    }

    private fun confirmDelete(item: Recent) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_recent, item.displayLabel()))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.remove(item.url)
                refreshRecents()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 已加入电池优化白名单就隐藏引导卡片。 */
    private fun refreshBatteryCard() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryCard.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    /** 跳系统设置请求忽略电池优化；个别 ROM 没有该页面则退到列表页。 */
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast(R.string.settings_unavailable)
            }
        }
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
