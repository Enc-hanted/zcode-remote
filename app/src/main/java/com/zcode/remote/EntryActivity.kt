package com.zcode.remote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
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
 * 入口页（二期「会话仪表盘」，evolution-plan §3.5/§4.4）：台面卡 = 最近会话大卡
 * （设备名/mid/时间/最后已知状态芯片/末条回复摘要，整卡热区续连），其余会话两行小卡；
 * 扫码收为右下 FAB，粘贴降为文字按钮，手动输入折叠为入口。状态芯片只读消费
 * AgentStateStore（最后已知状态，不建第二条 z.ai 连接），状态缺件退化为只显摘要/时间。
 *
 * 二期系统集成入口（§4.3）也在此路由：系统分享（ACTION_SEND，暂存+直达+剪贴板，
 * 待发草稿卡承接无会话场景）与应用快捷方式（回最近会话/扫码连接）——onCreate（全新
 * 冷启动）与 onNewIntent（singleTop 复用栈顶）双入口，经 autoJumpedOnce 与自动续连
 * 互斥防双跳。
 */
class EntryActivity : AppCompatActivity() {

    private lateinit var inputUrl: TextInputEditText
    private lateinit var recentsList: RecyclerView
    private lateinit var recentsHeader: TextView
    private lateinit var recentsEmpty: TextView
    private lateinit var batteryCard: View
    private lateinit var notifyCard: View
    private lateinit var stageCard: View
    private lateinit var stageName: TextView
    private lateinit var stageMeta: TextView
    private lateinit var stageReply: TextView
    private lateinit var stageChip: TextView
    private lateinit var stageBadge: TextView
    private lateinit var keepAliveLine: TextView
    private lateinit var manualArea: View
    private lateinit var draftCard: View
    private lateinit var draftText: TextView
    private lateinit var draftAction: TextView
    private lateinit var store: RecentStore
    private var recents = mutableListOf<Recent>()
    private lateinit var adapter: RecentAdapter

    /** 快捷方式「扫码连接」在 onCreate/onNewIntent 期到达：RESUMED 后再发起
     *  （相机权限申请需要处于前台生命周期），发一次消费一次 */
    private var pendingScanOnResume = false

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

        /** 应用快捷方式动作（res/xml/shortcuts.xml 同串；经已导出的 EntryActivity 路由，
         *  WebActivity exported=false 不直接暴露） */
        const val ACTION_RESUME_LAST = "com.zcode.remote.action.RESUME_LAST"
        const val ACTION_START_SCAN = "com.zcode.remote.action.START_SCAN"
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
        stageCard = findViewById(R.id.stageCard)
        stageName = findViewById(R.id.stageName)
        stageMeta = findViewById(R.id.stageMeta)
        stageReply = findViewById(R.id.stageReply)
        stageChip = findViewById(R.id.stageChip)
        stageBadge = findViewById(R.id.stageBadge)
        keepAliveLine = findViewById(R.id.keepAliveLine)
        manualArea = findViewById(R.id.manualArea)
        draftCard = findViewById(R.id.draftCard)
        draftText = findViewById(R.id.draftText)
        draftAction = findViewById(R.id.draftAction)

        // 版本号（状态值）：mono 11sp 右置
        findViewById<TextView>(R.id.entryVersion).text =
            getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        findViewById<View>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<View>(R.id.btnOpen).setOnClickListener {
            openUrl(inputUrl.text?.toString().orEmpty())
        }
        findViewById<View>(R.id.btnManualInput).setOnClickListener { toggleManualInput() }
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

        // 二期系统集成入口路由（分享/快捷方式）：必须先于 autoResume——显式路由会置
        // autoJumpedOnce，把随后的自动续连变成一次跳转（防双跳，评审 6）。
        // 只在全新冷启动路由一次：recreate()（主题跟随切换）会带原 intent 重走 onCreate，
        // savedInstanceState != null 即跳过（与 autoResume 同判据），否则分享直达会双跳
        if (savedInstanceState == null) handleIncomingIntent(intent)
        autoResumeLastSession(savedInstanceState)
        prefillFromClipboard()
        requestNotifPermissionOnce()
    }

    /**
     * 二期系统集成入口路由：系统分享（ACTION_SEND，二期形态=暂存+直达+剪贴板）与
     * 应用快捷方式（回最近会话/扫码连接，res/xml/shortcuts.xml）。
     * onCreate 与 onNewIntent（singleTop 复用栈顶实例时）双入口共用。
     * 任一路由后置 autoJumpedOnce=true：本次进程已完成显式导航，后续返回入口不再
     * 自动续连跳转（与 autoResumeLastSession 协调防双跳）。
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val shared = shareTextOf(intent)
        when {
            shared != null -> handleShareText(shared)
            intent.action == ACTION_RESUME_LAST -> {
                autoJumpedOnce = true
                val last = store.load().firstOrNull()
                if (last != null) {
                    launchWeb(last.url)
                } else {
                    toast(R.string.no_recent_session)
                }
            }
            intent.action == ACTION_START_SCAN -> {
                autoJumpedOnce = true
                // onCreate/onNewIntent 期未到 RESUMED：相机权限申请挪到 onResume 发起
                pendingScanOnResume = true
            }
        }
    }

    /** 分享文本提取：ACTION_SEND + text/*（manifest intent-filter 收 text/plain）；
     *  EXTRA_TEXT 按不可信输入：截断到 5000 字防 prefs 膨胀，空白视为无分享。 */
    private fun shareTextOf(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("text/")) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(5000)
            ?.takeIf { it.isNotEmpty() }
    }

    /** 分享到达：暂存草稿（一次性消费）→ 复制进剪贴板 + Toast「草稿已复制，粘贴发送」
     *  → 有最近会话直达；无会话停留入口，台面位草稿卡承接（点击复制/进入）。
     *  复制失败（ROM 差异）不直达不 Toast，停留入口由草稿卡承接（重试入口，降级链完整）——
     *  直达必须以复制成功为前提：剪贴板无内容时不把人带进会话（评审收尾修）。 */
    private fun handleShareText(text: String) {
        autoJumpedOnce = true
        ShareDraft.save(this, text)
        val last = store.load().firstOrNull()
        if (!copyToClipboard(text)) {
            refreshDraftCard()
            return
        }
        toast(R.string.draft_copied_toast)
        if (last != null) {
            ShareDraft.clear(this)   // 直达即消费：剪贴板已接棒
            launchWeb(last.url)
        } else {
            refreshDraftCard()
        }
    }

    /** 写系统剪贴板（分享草稿的唯一外发通道——绝不向 z.ai 输入框写文本）；失败返回 false */
    private fun copyToClipboard(text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        true
    } catch (e: Exception) {
        false
    }

    /** 待发草稿卡（分享无会话停留/复制失败兜底时可见，台面卡位上方）：点击=复制
     *  （+有会话则进入并一次性消费），长按删除；不动台面卡既有结构。 */
    private fun refreshDraftCard() {
        val draft = try {
            ShareDraft.read(this)
        } catch (e: Exception) {
            null
        }
        if (draft.isNullOrBlank()) {
            draftCard.visibility = View.GONE
            return
        }
        draftCard.visibility = View.VISIBLE
        draftText.text = draft
        val hasSession = store.load().firstOrNull() != null
        draftAction.setText(if (hasSession) R.string.draft_copy_open else R.string.draft_copy_only)
        draftCard.setOnClickListener { consumeDraft() }
        draftAction.setOnClickListener { consumeDraft() }
        draftCard.setOnLongClickListener {
            confirmClearDraft()
            true
        }
    }

    /** 草稿卡消费：复制成功才清草稿（一次性消费），有会话则直达；失败保留卡片重试 */
    private fun consumeDraft() {
        val draft = try {
            ShareDraft.read(this)
        } catch (e: Exception) {
            null
        }
        if (draft.isNullOrBlank()) {
            refreshDraftCard()
            return
        }
        if (!copyToClipboard(draft)) return
        ShareDraft.clear(this)
        toast(R.string.draft_copied_toast)
        refreshDraftCard()
        store.load().firstOrNull()?.let { launchWeb(it.url) }
    }

    private fun confirmClearDraft() {
        AlertDialog.Builder(this)
            .setMessage(R.string.draft_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                ShareDraft.clear(this)
                refreshDraftCard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** singleTop（二期分享/快捷方式路由配套）下复用栈顶实例时，新 intent 经此再来一遍 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 展开/收起手动输入（折叠为「+ 手动输入」入口，保证一屏直达会话） */
    private fun toggleManualInput() {
        val show = manualArea.visibility != View.VISIBLE
        manualArea.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnOpen).visibility = manualArea.visibility
        if (show) inputUrl.requestFocus()
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

    /** 冷启动时如果最近有会话，直接进入，省去每次再点一下。
     *  分享/快捷方式入口先经 handleIncomingIntent 显式路由并置 autoJumpedOnce——
     *  首行守卫据此跳过自动续连，一次 intent 只跳一次（防双跳）。 */
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
        refreshKeepAlive()
        refreshDraftCard()
        if (pendingScanOnResume) {
            pendingScanOnResume = false
            startScan()
        }
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
            Intent(this, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_URL, url)
                // 与 widget/磁贴/通知三处直达同姿势：Entry 在栈中但不在顶（分享到达）时
                // CLEAR_TOP 收掉旧 WebActivity 实例，返回栈不多一层同会话冗余（评审收尾修）
                .addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
        )
    }

    private fun refreshRecents() {
        recents = store.load()
        val empty = recents.isEmpty()
        // 第一条上台面卡（大卡），其余进小卡列表；列表头只在确有小卡时出现
        recentsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        recentsHeader.visibility = if (recents.size > 1) View.VISIBLE else View.GONE
        bindStageCard(recents.firstOrNull())
        adapter.submit(recents.drop(1))
    }

    /** 台面卡（最近一条会话）：整卡热区续连，长按删除与小卡同规则。
     *  状态芯片只读 AgentStateStore——它落的是最近一次会话上报的最后已知状态，
     *  台面卡恰为最近会话；缺件/unknown 隐藏芯片（宁缺勿错），时间/摘要照常。 */
    private fun bindStageCard(item: Recent?) {
        if (item == null) {
            stageCard.visibility = View.GONE
            return
        }
        stageCard.visibility = View.VISIBLE
        stageName.text = item.displayLabel()

        val chip = agentChip()
        if (chip == null) {
            stageChip.visibility = View.GONE
        } else {
            stageChip.visibility = View.VISIBLE
            stageChip.text = chip.first
            stageChip.setTextColor(ContextCompat.getColor(this, chip.second))
        }

        if (item.unread > 0) {
            stageBadge.visibility = View.VISIBLE
            stageBadge.text = RecentAdapter.badgeText(item.unread)
        } else {
            stageBadge.visibility = View.GONE
        }

        // 上次活跃时间：取 max(打开时间, 末条回复时间, 状态上报时间)——waitConfirm 等
        // 状态变化也是活跃，且可能晚于末条回复；三者皆无效时只剩 mid
        val agentAt = if (chip != null) AgentStateStore(this).loadAt() else 0L
        val activeAt = maxOf(item.last, item.lastReplyAt, agentAt)
        val rel = RecentAdapter.relativeTime(this, activeAt)
        val mid = param(item.url, "mid")
        stageMeta.text = when {
            mid.isBlank() && rel.isEmpty() -> ""
            mid.isBlank() -> rel
            else -> "$mid · $rel"
        }
        stageMeta.visibility = if (stageMeta.text.isEmpty()) View.GONE else View.VISIBLE

        if (item.lastReply.isBlank()) {
            stageReply.visibility = View.GONE
        } else {
            stageReply.visibility = View.VISIBLE
            stageReply.text = item.lastReply
        }

        stageCard.setOnClickListener { openUrl(item.url) }
        stageCard.setOnLongClickListener {
            confirmDelete(item)
            true
        }
    }

    /** 状态芯片数据：AgentStateStore 只读。返回 标签+语义色；null = 不显示芯片——
     *  store 从未落过状态（缺件降级）、unknown（选择器失焦后停 unknown，不误报）、
     *  error（本期 JS 不产出，防御按不显示处理）。
     *  done→待命：任务完成沿之后代理即回待命，归入待命口径；其余绿系=思考/流式。 */
    private fun agentChip(): Pair<String, Int>? {
        val agent = AgentStateStore(this)
        if (agent.loadAt() <= 0L) return null
        return when (agent.loadState()) {
            "idle", "done" -> getString(R.string.state_idle) to R.color.status_live
            "generating" -> getString(R.string.state_streaming) to R.color.status_live
            "thinking" -> getString(R.string.state_thinking) to R.color.status_live
            "waitConfirm" -> getString(R.string.state_wait_confirm) to R.color.status_warn
            else -> null
        }
    }

    /** 「保活中 · 剩 X 分」：只读 KeepAliveService（running 标志 + 本轮时长），不改服务。
     *  X 与保活通知同源同口径（时长原值，通知自建成后亦不递减）；服务不在跑即隐藏。 */
    private fun refreshKeepAlive() {
        val running = try {
            KeepAliveService.isRunning()
        } catch (e: Exception) {
            false
        }
        if (!running) {
            keepAliveLine.visibility = View.GONE
            return
        }
        val minutes = try {
            KeepAliveService.keepAliveMinutes(this)
        } catch (e: Exception) {
            0
        }
        if (minutes <= 0) {
            keepAliveLine.visibility = View.GONE
            return
        }
        keepAliveLine.text = getString(R.string.keep_alive_left, minutes)
        keepAliveLine.visibility = View.VISIBLE
    }

    /** 读链接查询参数（mid 等）；缺失/解析失败返回空串，不抛出 */
    private fun param(url: String, key: String): String =
        runCatching { Uri.parse(url).getQueryParameter(key) }.getOrNull().orEmpty()

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
