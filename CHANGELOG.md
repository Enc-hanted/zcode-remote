# Changelog

本项目的所有重要变更记录在此。版本号遵循语义化版本（受早期开发阶段约束，主版本固定为 0）。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [未发布] — v86 工作区（bundleVer 60）

3.16.x 功能盘点后的 P1 三件（盘点全文见 `docs/adapter-plan-remote-v4.md` v1.3：官方 changelog + 远控页 bundle 全量 82 资源 + 桌面端开源仓库 `zai-org/ZCode@main`，含 `docs/web-remote-control/` 设计文档 11 篇与**官方 Android 端源码 `packages/android`**）。全部改动在 BUNDLE_JS 生成区之外，`_patch_web2.py` 无需重跑。

- **主题跟随系统（D1）**：页面主题读同源 `localStorage["zcode-theme"]`（system/zai-dark/zai-light）且**默认 zai-dark**（官方 mobile-theme-control.md：QR 已去 theme 参数、两端互不耦合）——不种子则浅色手机永远深底。WebActivity 新增 `seedPageTheme(url)`：仅 `zcode.z.ai` 域、仅「未设置」时按系统深浅写一次（`SystemBars.isDarkSystem`），页面自带主题菜单故用户在页面内的选择不碰、此后系统切换不回改（要跟随可在页面选 system）；onPageStarted 早写（本地 eval 通常赶在页面网络 JS 之前）+ onPageFinished 兜底补写（输给内联脚本则下一跳生效）。种子即守卫（页面侧 `if(!getItem)`），无需 app 侧标记
- **多任务口径校准（D3）**：3.16 手机端已是多任务客户端（首页/聊天两页 shell，可切换/新建任务，手机选中态独立于桌面）——本 app 的回复通知/未读/小部件只覆盖**当前打开的任务**（onReply/onAgentState 均源自当前渲染的聊天页），其他任务的完成只在页面任务列表有徽标、app 不提醒。文案如实声明：登录引导卡补一行「手机页面可切换/新建任务；提醒与小部件只覆盖当前打开的任务」，设置页「新回复通知」副题改「切出后当前任务收到新回复时弹出提醒」
- **桌面自动恢复提示（D7）**：桌面 3.16 起远控启用意图持久化（一次启动成功后应用重启/崩溃自动恢复 device relay，用户手动停止才清除）——兜底页文案补「桌面端刚重启的话远控会自动恢复，稍候再试即可」
- **版本**：versionCode 49 / versionName 0.0.8

## [未发布] — v85 工作区（bundleVer 60）

zcode 3.16 远控改版适配 P0（调研与方案见 `docs/adapter-plan-remote-v4.md`，本轮不含注入层选择器重建）。

- **背景（全部本会话实证）**：新版远控改为「账号登录 + 服务端配对」模型——`/remote/v4` 打开先渲染页内登录中转页（title 固定 `ZCode - Web Remote Control Sign In`），登录经 OAuth（Z.ai 或 BigModel 双账号体系）完整导航回跳 `code/state` 换 JWT 后才进会话；真实二维码（v3.16.2）只含 `sid/mid/name/app_version`，不再携带配对密钥，鉴权完全靠账号 JWT；旧格式链接（`/remote?mid=…`）现 302 到 `/cn/remote` 404 页
- **白名单放行 bigmodel.cn**（修「跳系统浏览器回不来」）：`shouldOverrideUrlLoading` 的 `isZai` 扩为 `z.ai || bigmodel.cn`（含子域）——BigModel 与 Z.ai 同为智谱主体，桌面端用 BigModel 账号时手机端必须走同一账号体系（官方登录页亦明示「请选择与电脑端相同的登录平台，并使用同一账号」），不放行即登录死循环
- **注入门控**：`onPageFinished` 不再无条件注入——延后 1.2s 探测 `document.title`，登录中转页不注入（悬浮图标会叠在登录按钮上，Toast 提示一次「登录后自动返回；账号需与桌面端一致」）；会话/连接中页补上注入。SPA React 挂载晚于 onPageFinished，探测只认正向特征、不中不动作；+2.3s 次拍只补失败检测；换页 `onPageStarted` 撤销未决探测。pairing ~30s 超时晚于两拍，该场景页面自带「重新连接」按钮可用，不接管
- **失效/死链原生兜底**：探测命中「校验失败/手机连接已失效」正文特征或 `404: This page could not be found` 标题时，接管为原生 errorView（复用既有布局零新增）——文案指回桌面端重开「移动端远程控制」，`btnRetry` 重载（桌面端已在等待时一键重连）、`btnHome` 回入口
- **入口页**：新增静态登录引导卡（与电池/通知引导卡同构，灰语义竖条；无常开/关闭状态信号故不设可见性逻辑）；副题「链接即连」→「扫码连接」（链接不再是凭证）
- **真机返修（v85a 失守）**：登录/配对链路真机已通，但「连接上以后弹原生兜底」——首版失败探测用 body.innerText 全文匹配、一拍命中即全屏接管，瞬态渲染即误伤活会话。收紧三道闸：①只认失败屏 h1 精确文案（不再全文匹配）；②页内失败屏需连续两拍命中才接管（404 静态标题一拍即接管）；③接管后每 2.5s 复检，失败屏消失自动撤下原生层；其余失败屏（桌面端已离线等）交页面自带文案与「重新连接」，探测改三拍（约 1.2s/3.5s/6.5s）
- **版本**：versionCode 48 / versionName 0.0.7；README/SECURITY 域名清单同步（bigmodel.cn 放行说明）

## [未发布] — v84 工作区（bundleVer 60）

evolution-plan v1.2 二期批次（入口仪表盘 / 品牌资产 / 系统集成四件）+ 收尾评审三修 + 发版收尾（versionCode 47 / versionName 0.0.6）。

- **入口页升级会话仪表盘**（§3.5/§4.4）：台面卡 = recents[0] 大卡（设备名 + mid·上次活跃 mono + 状态芯片 + lastReply 摘要一行，整卡热区续连、长按删除与小卡同规），其余会话两行小卡；扫码收为右下 56dp FAB（accent 底 = 全 App 唯一实心绿，elevation 6dp = 全 App 唯一阴影且按压不抬升），粘贴降为文字按钮、手动输入折叠「+手动输入」。状态芯片只读消费 AgentStateStore（缺件/unknown/error 隐藏宁缺勿错 = 一期失败出口降级），活跃时间取 max(打开/末条回复/状态上报)；RecentStore 扩 `unread`（后台回复 +1、回会话 onResume 清零并落盘刷新，前台观看中的流式回复不算未读；optInt 缺省 0 向后兼容不 bump schema_v，WebActivity 仅动 onResume/onReply 两处）；「保活中·剩 X 分」只读 KeepAliveService 新增纯读访问器——X 与保活通知同源同口径（时长原值；服务未存启动时间戳，只读前提下无精确倒计时）
- **品牌资产**（§3.5）：dev/_make_icons.py v2 重写为程序绘制品牌绿 `>_` 前景（几何与 ic_logo.xml 逐值同源：M6,6l6,6-6,6 + M13,18h6 / stroke 2.4 圆帽圆角，24× 超采样 LANCZOS；绿取深色 accent #00E676、黑磁贴 #0A0A0A 两模式同值不随昼夜切换；按墨迹外接圆半径适配 66dp 安全圆 95%）；新增 13+ monochrome 单色层（drawable-nodpi/ic_launcher_monochrome.png，系统按 alpha 着色）挂进两个 adaptive XML（26-32 忽略 / 13+ 主题图标生效）；启动屏不引库——新 layer-list splash_window.xml（@color/background 打底 + 居中 96dp `>_` 语义引 @color/accent 随 DayNight 昼 #00875A 夜 #00E676），drawable-v31 同名资源把 12+ 还原纯底色保系统默认启动屏，themes.xml 仅 :12 一行。12 个图标产物重跑字节级幂等，几何/同构/良构程序化验证通过
- **系统集成① 等待确认通知**：waitConfirm 上升沿独立 HIGH 通知——新 `ConfirmNotify.kt`，channel confirm_notify + 通知 ID 3001 段（避开回复横幅 2000-2019），正文 snippet 摘要 + BigText，点击 contentIntent 直达会话（照 KeepAliveService 姿势 NEW_TASK|SINGLE_TOP|CLEAR_TOP），channel 关震动不与页面 haptic('confirm') 重复，下降沿撤下；零 action 按钮（远程批准/拒绝已否决，附录 C 冻结）、不走 ReplyDedupe（同文本 7 天去重会吞同文案提醒）；接线在 WebActivity.onAgentState 桥方法内做沿检测（先取 prev 再 renderAgentStrip）
- **系统集成② 单行小部件（2×1）**：`SessionWidgetProvider`——会话名 + 状态色点（三 dot drawable 走 @color/status_* 随深浅两表）+ 最近回复一行 + 相对时间 mono；数据只读 RecentStore/AgentStateStore/KEY_REPLY_PREVIEW（经 KeepAlive 新增只读访问器 replyPreview，回会话清空退 recent.lastReply），回复与状态变化双推（push=getAppWidgetIds 空即返回零开销）；30 分钟 onUpdate 重渲染、数据龄超 30 分前缀「上次·」（进程死显陈旧值如实标注）；整卡点击直达 WebActivity 同 flags 姿势；无 RemoteViewsService。取舍：最小占位 110×40dp（2×1）——严格 40×40 单格放不下规格四要素，已在 XML 注释声明
- **系统集成③ 分享入口**：ACTION_SEND text/plain **暂存 + 剪贴板形态**——EXTRA_TEXT 按不可信输入截 5000 字：ShareDraft 轻量 prefs 暂存 + 复制剪贴板 + Toast「草稿已复制，粘贴发送」+ 有最近会话直达并一次性消费草稿，无会话停留入口由台面位待发草稿卡承接（amber 竖条 + 点击复制 + 有会话进入一次性消费 + 长按删除），复制失败保留卡片 = 重试降级链；零向 z.ai 输入框写文本代码。EntryActivity singleTop + onCreate（仅 savedInstanceState == null 全新冷启动，recreate 重走不加此门防双跳）/onNewIntent 双入口共用 handleIncomingIntent，与 autoJumpedOnce/autoResume 互斥防双跳。**升级路径**：三期以 Lexical 程序化写入取证为门（报告 §7-#2），通过后升级为「分享 → 预填草稿 → 用户明确点『发送』才入正式队列」的指令下行形态（§6 三期条目）；取证不过则止于当前形态，App 完整可用
- **系统集成④ 磁贴 + 快捷方式**：`ResumeTileService` 回最近会话（exported=true + BIND_QUICK_SETTINGS_TILE，状态只读反射 KeepAliveService.running，14+ startActivityAndCollapse(PendingIntent) 新变体 / 老版 deprecated 变体 try-catch 静默照双启动降级先例，无最近会话退 EntryActivity 永不死链）；res/xml/shortcuts.xml 两条（回最近会话/扫码连接）经已导出 EntryActivity 显式 intent 路由，扫码 onNewIntent 后挪 onResume 发起相机权限 pendingScan 一次性消费
- **收尾评审三修**：① handleShareText 复制失败且有会话仍直达、与注释降级链不符——launchWeb 移入复制成功分支，复制失败一律停留入口由草稿卡承接（剪贴板无内容不把人带进会话）；② ConfirmNotify/widget 的 contentIntent 各分独立 requestCode（3001/4001）——PendingIntent 身份忽略 extras，本期把 WebActivity+EXTRA_URL 的消费者从 2 家扩到 4 家，共用 0 会互覆 extras；③ launchWeb 补 SINGLE_TOP|CLEAR_TOP（与 widget/磁贴/通知三处直达姿势对齐——分享到达时 Entry 在栈中但不在顶，不再多叠一层同会话 WebActivity）

## [未发布] — v83 工作区（bundleVer 60）

evolution-plan v1.2 一期批次：视觉收敛（设置页卡化 + 字体五档）+ 状态感知轴（只读状态机闭环）。

- **设置页分组卡化**：三组（会话页/后台保活/显示）各裹 surface 卡——`@color/surface` 底、14dp 圆角、1dp `@color/stroke` 描边、elevation 0，样式收敛进 styles.xml 新增 `SettingsGroupCard`/`SettingsCardRows`；SettingsRow 结构/id/开关绑定原样未动，`SettingsDivider` 两处退役。MaterialSwitch 选中 track 经主题 `colorPrimary`（= @color/accent）间接生效，layout 目录 hex grep 零命中
- **字体五档收敛**：全 App 字号归档 20/16/14/12/11 五档——设置页行标题 16→14sp 归正文档（activity_settings.xml 与 item_recent.xml 落地），item_recent 卡内边距 16→14dp
- **状态感知轴·注入侧**：新增 dev/bundle/09_agent.js 只读状态机——独立 1s 轮询不进 syncComposer 中心；`agentStateOn` 门控（注入关 = 整个 IIFE 不启动，热更关 = 空拍自读零跨闭包调用）；判定自建（runActive/checkRunEnd 是 07 的闭包函数不可引，选择器字符串复制常量）：generating = v4-stop|chat-loading，thinking = 末条 assistant 行含 chat-reasoning，done = 活跃下降沿 + 反馈按钮 v4-feedback-(like|dislike)-<rid> 行外关联，waitConfirm = dock 内确认弹窗命中（POPUP_HINT_SEL 全量复制 + v60 可见性检查），error 本期不产出留注释位（选择器未经实机取证，宁漏报不误报），idle/unknown = 有行/无行；只报变化沿；`br.onAgentState` 判空 try-catch 静默（老 APK 无桥照常跑光点不上报）。悬浮光点自绘而非挂 #zcode-composer-icon——waitConfirm 恰是最需要被看见的状态而 ensureIcon 在弹窗态藏 icon；呼吸绿 1.2s（全 App 唯一 >300ms 动画例外）/ amber 常亮 / done 绿 2s 渐隐 / prefers-reduced-motion 降级半透明。局限如实注明：本段在顶层文档执行不被 08 的 BODY 广播带进 shadow DOM，该场景退化为 unknown（漏报不误报）
- **状态感知轴·原生侧**：新建 `AgentStateStore`（宽松落盘 state + snippet + 时间戳，仿 RecentStore）；WebActivity 桥方法区 `onAgentState`（runOnUiThread 落盘 + 驱动状态条，异常静默）+ 顶部 2dp 状态条（绿呼吸 / amber 常亮 / done 绿 2s 渐隐后 GONE，其余 GONE；`statusStripOn` 门控关 = GONE；自读 ANIMATOR_DURATION_SCALE==0 降级半透明不读注入层变量；色走 @color/status_live|status_warn 语义槽位）；SettingsStore 三键 `agentStateOn`/`statusStripOn`/`errNotifyOn` 默认全开，走 `__zcSettings` 注入序列化 + applySettings 热更全链路（errNotifyOn 键先落地无消费——错误态取证通过接错误通知时才消费）；04_theme 诊断卡 feats 暴露三开关行；bundleVer 59→60
- **⚠ 上版门槛（如实声明）**：状态机选择器稳定性**未经实机取证窗口**（evolution-plan §6 一期验收 2）——上版前须过：**≥2 小时真机窗口、每态 ≥10 次状态沿、误报 0**。取证不过走「只裁感知件」失败出口（09_agent 不注册 / 状态条光点 GONE / 仪表盘芯片退空仍显摘要时间，视觉件照常发版）
- **收尾评审修**：09_agent.js 选择器出处注释引用 07_settings.js 的 7 处行号系统性漂移 5-9 行，逐处校正（runActive 158-159 / chat-reasoning 208（isAnswerRow 205）/ rowIdOf 201-204 / notify 134-141 及同源 3 处：feedback 查询 165、send 取法 169-175、done 证据 164-165），`_patch_web2.py` 已重跑再生成。冒烟扩容：smoke x5→x6 注入 + x10→x14 热更（补 agentStateOn=false 门控场景与三键 ZSET 写回断言）

## [未发布] — v82 工作区（bundleVer 59）

evolution-plan v1.2 的 P0 批次三件套（触感 / 最近列表回复摘要 / 色彩语义槽位第一批）+ 发版收尾（versionCode 46 / versionName 0.0.5）。

- **P0① 触感收口**：manifest 补 `VIBRATE`（normal 级装即授予）——权限若先落而旧 JS 未清会激活既有哑炮微震，故权限与 JS 清理同批进 v82，窗口期为零。新增 `Haptics`（五类短震 connect/sent/done/confirm/error，API 29+ 预置波形、26-28 短震回退，`hapticOn` 门控、无权限/异常静默）；`SettingsStore` 加 `hapticOn` 默认开（照 notifyReply 模式，本 P0 无设置 UI）。WebActivity 手工改动如实记三处（收尾评审更正口径，原报"两处"）：桥方法区 `haptic(kind)` 白名单（未知 kind 静默）、`__zcSettings` 注入与 applySettings 热更序列化两处、回复链路落盘调用点补 `replyArrived`/`url` 参数。dev/bundle 旧 `navigator.vibrate` 7 处调用处置：5 处手势哑炮震感纯删（连同 vibrate 助手定义），2 处状态确认改经 `zcHaptic` 桥——confirm=等待确认弹窗（2s 防抖保留）、sent=发送成功（复用 v79① 新用户行+输入框清空双证据，门控拆分独立于归位开关）；`git grep -i vibrat dev/bundle` 已零命中。done 震接在 KeepAliveService 保活通知真正 post 处（去重通过链路传 `replyArrived=true`；前台会话服务必不在跑、走不到 post 不额外震，onProgress 中间进度不震）；bundleVer 58→59
- **P0② 最近列表带回复摘要**：`RecentStore` 加 `lastReply`/`lastReplyAt`（opt* 宽松读旧数据取缺省，不 bump schema_v；upsert 按 mid 合并时继承摘要不因链接轮换清零），`item_recent` 第二行显「摘要 · 相对时间」，无摘要退化现状时间行（缺件健全）。收尾评审三修：a) 仅最终回复（`replyArrived=true`）落 RecentStore——中间流式进度只滚动通知预览，摘要不再恒为"最后一次进度文本"，桥线程每拍全量 load/save 一并消解；b) 会话定位改按桥持有的会话 url 走 `sameSession` 匹配（回复横幅深链直启 WebActivity 不经 upsert、"头部=当前会话"失真时会写错卡片；无匹配宁可不写）；c) 写路径加类级锁（主线程 upsert/remove 与桥线程 updateLastReply 跨实例 load→改→save 竞态关窗）
- **P0③ 色彩语义槽位第一批**：深浅两表补齐 surface_high/stroke_strong/text_tertiary/status_live/status_warn/status_error/status_offline 七槽位（键集 15:15 一致且顺序一致）；品牌换绿 accent（深 `#00E676`/浅 `#00875A`）、on_accent 深色改 `#000000`；`#2E6BE6` 在 app/ 与 dev/ 归零（`git grep 2E6BE6 -- app dev` 零命中，文档残留见下条）；ic_logo 与引导卡 tint 走 `@color/accent` 间接引用自动换色；status_* 四键仅落表无调用方（一期状态条消费）；未动布局/themes/drawable/JS
- **报告 v1.2**：evolution-plan 修订 v1.2——维护者两项拍板落地：远程批准/拒绝不做（附录 C 冻结、§4.3 锁屏通知收敛为摘要+点击直达）；色彩语义槽位顶层原则入 §3.2。收尾顺修：发版检查条款 grep 口径改 `git grep 2E6BE6 -- app dev`（原"全仓归零"被文档自身 6 处论证字面量自指矛盾），附录 B 改前快照补注记
- 冒烟基线扩容：`_smoke_test.js` applySettings 组合 x8→x12（后补三开关 hapticOn/sendMinimize/amoledBlack 各一组 + 恢复默认一组，ZSET 跨场景存活故试毕复位）；dev/bundle 本轮未再动（bundleVer 59 由 P0① 落定并已重跑 `_patch_web2.py`，WebActivity 生成区实证）——_validate/_smoke/定向 grep 校验门由主流程统一跑，状态以彼为准

v80 实机二验三条反馈的修复轮：两条居中根修 + 两个功能整套删除。

- **⑦ ↓ 圆钮"不居中"根修——translate 属性与 transform 属性叠加**：Tailwind v4 的 `-translate-x-1/2` 编译成**独立 CSS `translate` 属性**（不是 transform），z.ai 的 ↓ 钮水平居中靠它；v80 的避让规则写的是 `transform: translate(-50%, …)`，两属性**叠加生效** → 二次左移半个钮宽（14px，实机截图偏左量与理论值精确吻合）。规则改为只写 Y 分量 `translateY(calc(-6px - var(--zc-safe-b)))`，水平完全不碰
- **全屏（半屏）输入面板"不居中"中和**：注入层全屏几何本是对称（inset 0 + flex 居中 + 宽 min(94vw,640px)），偏移来自 z.ai 原生 dock 内层（`[data-v4-composer-dock-content]`）在全屏态残留的 padding/margin——float/full 两态一并归零并补 `width:100%`（对齐真页 w-full，dock 是 flex 缺了它内容层 shrink-to-fit 塌缩），全屏面板另加 `margin-inline:auto` 强制居中；dock 自身 `translate:none` 与 `transform:none` 并列（同源教训：位移工具类走 translate 属性，transform 归零拦不住）
- **② 图标双击直进全屏：整套删除**（用户淘汰，"跟单击抢同一手势，真机做不稳"）：双击判定分支、`lastIconTapAt/XY`、`ensureIcon` 的 520ms 隐形宽限 hack、遮罩落点回退（05_nav）、`whenMorphDone` 帮手函数全链路移除；图标回归"唤出即隐"，双击第二击落遮罩=收起（点外部语义，不再误进全屏）
- **③ 键盘跟随·实验：整套删除**（用户淘汰，"收益从未被实机证明，两轮翻车"）：JS 侧 `calcKbLift/applyKbLift/setKbH/__zcKb` 桥、visualViewport 兜底、`zc-kb-on` CSS 规则、`kbCalls/kbAgo` 埋点、设置开关（Kotlin `reportKb`/insets 分支/PageSettings/设置页/XML/strings）、mock f_kb 全链路移除；WebView 恢复 v72 行为（IME inset 垫 content，页面自己坐到键盘上方）。`kbFollowExp` 注入键退役（旧存档多出的键无害）；mock ↓ 钮居中改 `translate:-50%` 属性（Tailwind v4 真页同构）并新增带恶意不对称 padding 的 dock-content 包装层（验证中和规则）
- mock 回归全绿：↓ 钮中心 341.3 vs 视口中心 341.5（safeB=26 时恰抬 32px、X 不动）、悬浮胶囊/全屏面板居中顶着恶意 padding 通过、双击不进全屏、发送归位①（contenteditable 路径）、AMOLED 热更、零 jsErrors

## [未发布] — v80 工作区（bundleVer 57）

v79 实机首验四条反馈的修复轮。

- **⑧ 长按消息菜单：整套删除**（用户淘汰）：bundle 手势/动作条/复制引用、设置开关（Kotlin/XML/strings）、注入键、诊断字段、mock 钩子全链路移除
- **① 发送后归位真机失效根修**：实机诊断 `dockHtml` 实锤 z.ai 输入区是 **Lexical contenteditable div**（`data-lexical-editor`），不是 textarea——v79 的输入跟踪只认 `TEXTAREA/INPUT.value`，真机上 `sendHadText` 永假、①从不触发（mock 用 textarea 所以本地全绿，又一次"mock 同构性"教训）。文本读取改双形态兼容（`.value` / `.textContent`），事件过滤放宽 contenteditable；mock 输入区同步换 contenteditable
- **② 双击直进全屏加宽**：窗口 330→500ms、tap 位移容差 10→14px（真机手指起落天然漂移十几像素，mock 合成事件零漂移测不出）；新增遮罩落点回退——第二击落在 44px 图标之外的遮罩上（距首击 120px 内）也认双击，直进全屏
- **⑦ ↓ 圆钮抬升根修**：v79 的几何解析器整棵排除 dock 子树，而实机诊断实锤 ↓ 钮（`v4-timeline-bottom`，28×28，absolute/bottom-full 悬在 0 高 dock 上方 8px）**就在 dock 内部**——被自己排除了。选择器已知（testid 稳定），几何解析器删除，换 02_style 静态 CSS 直抬 `translate(-50%, calc(-6px - var(--zc-safe-b)))`（复刻其 Tailwind 居中；mock 实测 Y −6→−32，底距 8→40px）
- **③ 键盘跟随·实验加固**（实机"键盘盖住输入框"）：原生侧 onProgress 动画期按 ≥8px 步进补报（v79 只在 onApply 报终值，任一环没送到 JS 就全程不知键盘来了）；JS 侧 visualViewport 高度差兜底（部分 WebView 版本会更新 vv）；诊断 `feats` 新增 `kbCalls/kbAgo` 桥接埋点（kbCalls=0=桥断、≥100=vv 兜底生效过），下次实机一发诊断即可分辨"原生没报"vs"报了没生效"
- ⑥ 阅读线经全链路探针回归 4/4 通过（间歇失败为测试脚手架内容高度不足，非代码问题；实机诊断 uiTrace 本有 readline-place 成功记录）；⑤ 骨架/⑨ AMOLED 无回归；全套 validate/smoke/Playwright 绿

## [已推送 03e510a] — v79（bundleVer 56）

小屏交互提案批次落地：用户从 10 条提案中划掉 ④（音量键翻页）与 ⑩（边缘手势分区），其余 ①②③⑤⑥⑦⑧⑨ 全部实现。设置页新增四个开关（发送后归位 / 长按消息菜单 / 键盘跟随·实验 / 纯黑 AMOLED·实验），经 `window.__zcSettings` 注入、`applySettings` 热更，不重进会话即生效。

- **① 发送后归位（默认开，可关）**：发送消息后输入框自动收纳、回复全屏阅读。触发用双证据签名——"输入框刚有字 → 新用户行出现 且 输入框已清空"，手动删光字不会误触（没有新用户行）；归位前先 blur 收键盘（IME 逐帧 resize 不与 morph 重叠），延迟 420ms 让 z.ai 自己的发送动画先走完
- **② 图标双击直进全屏**：单击唤出后 380ms 宽限窗内图标"隐形但可接"（opacity:0 仍收触摸，display:none 会收不到第二击），morph 替身从图标位起飞同时图标淡出无双影；第二击落图标 → `whenMorphDone` 等飞行结束再切全屏（两段 morph 的 morphTok 会互相顶掉替身清理）
- **③ 键盘跟随·实验（默认关）**：键盘起落时 WebView 不再缩放（原生 insets 监听按开关跳过 ime 垫高，页面零重排、虚拟列表不再逐帧重算），onApply 只报一次键盘终值给 JS（`__zcKb`），bundle 换算 `--zc-kb-lift`（胶囊底缘越过键盘顶沿多少抬多少、封顶到胶囊顶缘−8px），CSS 过渡让胶囊贴键盘顶沿升降。CSS 选择器带 `html.zcode-float-on` 前缀压过基础 float 规则的 transform（同特异性会静默失效，mock 实测抓出）
- **⑤ 会话切换骨架屏**：包装 `history.pushState/replaceState` + popstate（SPA 换会话不触发导航事件）→ 铺一层与页面底色一致（边缘采样色）的微光条骨架，首条 v4-row 渲染即淡出、1.4s 硬上限；淡入 140ms（快切几乎不可见）；主文档独占路由钩子（shadow root 共享 history，双包装会双触发）；骨架期自动收起导航/面板/阅读线
- **⑥ 流式阅读位置线**：流式输出中上滑离开底部 ≥260px → 在离开时刻的内容底边插"新内容 ↓"细线（锚在最后一个可见行后，内容只在尾部追加故锚点稳定），新内容持续生长在线下方；滚回底部 ≤160px 或流式结束（scrollHeight 2.2s 无增长）即淡出；锚行被虚拟化卸载顺手收线不重建；判据 scrollHeight 增长由 1s 轮询跟踪（v75 的 tailShLast 同源思路）
- **⑦ z.ai 原生 ↓ 圆钮避让小白条（用户实测补充）**：未连 USB 无法锚定选择器，走几何解析——收纳态里"fixed/absolute 且悬在视口底 170px 内的 18-46px 近方圆钮"逐个测底缘与 `safeB()+10` 的差值，差多少 `translateY` 补多少（transform 不挑定位方案）；幂等（rect 反映已应用位移）；行内静态定位的消息操作按钮被 position 过滤器排除不会误伤；命中记 `liftLog` 进诊断卡，下次连 USB 可钉死选择器换静态 CSS
- **⑧ 长按消息快捷菜单（默认开，可关）**：长按 520ms（位移 <10px）出"复制全文 / 引用"动作条。引用 = 唤出输入框 + 原生 value setter 写入 `> 摘录` + 派发 input（React 受控兼容）+ 聚焦；复制走 clipboard API（execCommand 兜底）。与原生长按选择互斥：菜单出现后 420ms 内发生 selectionchange/contextmenu 自动让位收起；落点在 textarea/按钮/代码块时长按有原生语义，直接不触发
- **⑨ 纯黑 AMOLED·实验（默认关）**：注入层 token 全套换纯黑系（`html.zc-amoled`）+ body/timeline 底面强制 `#000`；z.ai 气泡/代码块等具体表面色需实机调色（诊断卡 `feats.amoled` 可查状态）
- 诊断卡新增 `feats` 块（八个特性的开关态 + kbH/kbLift/skShown/readLine/liftLog）；`bundleVer` 56；mock 预览页新增"模拟发送/模拟会话切换/模拟长按/模拟 safeB"钩子 + 假 ↓ 圆钮，九项特性接线回归全过、既有导航/热更/零 jsErrors 回归通过

## [未发布] — v78 工作区（bundleVer 55）

- **输入框过渡质感根修——"中途丑陋椭圆帧"消灭**：三个容器变换（唤出/收纳/全屏切换）旧方案用 `transform: scale` 缩放胶囊——非均匀缩放（sx≠sy）会把圆角与描边粗细压扁拉长，飞行中途就是那帧椭圆。改为**幽灵替身**：真实胶囊全程 `visibility:hidden`，一个复刻其背景/描边/阴影/圆角的空壳 div 用 left/top/width/height **尺寸动画**飞行（几何全程正确），落位瞬间换回真身、内容 0.12s 淡入；morphTok 防飞行叠加
- **垫与滚动补偿时序重排（消除开合时的内容跳变）**：唤出时让位垫不再与飞行同帧写入（真身隐藏中布局变化会穿帮）——挪到落位回调；tl 的 `padding-bottom` 加 0.22s 过渡（仅收纳态），"列表让位"从瞬时跳变变成连续滑动；保底滚动从瞬时 `scrollTop=` 跳变更改为 `scrollTo({behavior:'smooth'})`，与替身飞行同曲线并行同向。收纳时 1s 轮询撞进飞行窗口由 setOverlayPad 的 morph 闸挡住，落位统一补
- **上拉展开手柄（低可视）**：胶囊上内缘（右上角）26×3 半透明小横条（`--zc-text-3` @ opacity 0.18，按压时 0.4），提示可上拉展开半屏输入。挂 dock（fixed 定位锚）的 `::after` 伪元素——样式表注入 React 抹不掉，不碰 z.ai 胶囊本体；上拉展开手势本就由胶囊整体纵向拖动承担，手柄纯提示
- mock 接线回归全过：收纳/唤出双向替身飞行中帧与落位态、垫"落位后 116px"、手柄 ::after 0.18、v77 无焦点、零 jsErrors

## [未发布] — v77 工作区（bundleVer 54）

- **打开输入框只弹胶囊、不再同时拉起输入法（实机反馈"弹出+键盘同时起来远远不够平滑"）**：此前 showComposer 在容器变换落定后自动 focus 输入框→键盘弹出，胶囊 morph、让位垫、滚动补偿、IME 逐帧 resize 四件事叠在同一瞬间。现参数反转：只有显式传 `true` 才聚焦（现无调用方传，留作以后"打开自动弹键盘"设置项钩子）——图标点按/上滑、待开自动弹、设置面板开关全部变为只弹胶囊；键盘改为用户亲手点输入框才起来（原生 focus 由点击触发，系统动画跟手）。自动恢复态两处本来就传 false 不受影响

## [未发布] — v76 工作区（bundleVer 53）

- **1 秒轮询瘦身（减少每拍强制布局读）**：①空洞看门狗 covered 态（常态）不再每秒全量扫 section 取证——gapPx 改为诊断手势时刻现算（04_theme），看门狗只在真检出空洞时才扫；②body 弹窗扫描从 400ms 全文档轮询改为事件驱动——Radix 弹窗 portal 挂/卸 body 直接子层，childList 观察器即时触发（延迟反而更低），400ms 轮询降级为 2s 安全网兜非 portal 场景；③尾清静止闸门（v75）本就短路了流式期的全部测量
- **tools/usb_diag/ —— 实机 USB 取证通道固化成仓库工具**（本轮破案方法论沉淀）：零依赖 Node≥22 脚本，CDP 直连 WebView。子命令：`status`（设备→pid→socket→转发→页面一条龙）/`eval`（真实页执行 JS，输出自动脱敏）/`webshot`/`screen`（物理屏真相）/`scan`+`blank`（像素级量化底部空白带，避开悬浮图标干扰位）/`frames`（帧采样振荡检测，自动报标准差与主周期）/`mut`（变异监听按 t%1000 相位聚合，点名内联样式攻防双方）/`mask`（底部渐隐遮罩同步差）/`log`（console/异常收集）/`fling`（⚠️ 真实触摸惯性滚动复现+自动回位）。README 含两桩破案实录与流程模板：**视觉异常必须物理屏像素级取证，DOM 探针只能证伪不能证实**

## [未发布] — v75 工作区（bundleVer 52）

- **修复"回复中（思考中/执行中）消息页每秒反复上下闪动位移"（实机 USB 变异日志抓的现行）**：高频帧采样实锤 scrollTop 以 1Hz 方波在 ±40px 间跳变（14806↔14846，两种状态都正好钉在 scrollHeight−视口高）；DOM 变异监听抓到攻防双方——我们的 1 秒轮询尾部清理把流式回合尾部的空占位条（`.min-h-5` 等）写 `display:none`（内容 −40px），React 流式重渲染每秒整段抹掉我们写的内联样式（+40px 回来），下一拍再收……页面钉底跟随，肉眼即"每秒上下闪"。且签名含 scrollHeight，流式期每拍必变、去重永远失效，攻防战不停
- **修复＝静止闸门**：`syncTailBlank` 入口比对内容高度——与上一拍不同（流式输出中/滚动未停稳）整轮跳过、绝不动 DOM；连续两拍相同才清理一次。流式期间占位条留着无害（内容一直在长），停稳后一次性收掉只发生一次位移；自身改动导致的高度变化同样只多等一拍，幂等收敛。副产收益：流式期不再派发 resize/scroll（不再给 z.ai 虚拟列表火上浇油）、"滚动到底部"圆钮不再因 40px 跳变每秒显隐切换
- 诊断卡 `maskState`（v74）继续可用；bundleVer 52

## [未发布] — v74 工作区（bundleVer 51）

- **"上滚后底部空白占位"真因确认并根修（实机 USB + Chrome DevTools 协议直连取证）**：空白不是虚拟列表丢内容，是 z.ai 自己给时间线内容列挂的**滚动跟随底部渐隐 CSS mask**——`mask-image: linear-gradient(black 0, black 566px, transparent 590px, …); mask-position: 0px <scrollTop>px; mask-size: 100% <视口高>px`，可见区底部恒定 ~120px 渐隐成透明；惯性滚动后 mask-position 与实际 scrollTop 脱同步时透明带更大（实机照片实测 ~190px）。此前所有 DOM 探针（elementFromPoint / innerText）都被它骗过——hit-test 与 innerText 均无视 mask，probe 报"有内容"而屏幕是白的，这是该问题拖了多版的直接原因
- **修复**：收纳态（`zcode-float-on`）注入 `-webkit-mask-image:none!important` 摘除遮罩，消息直接画到底边；原生 dock 态（float off）保留 z.ai 原设计（渐隐用于与输入框过渡）。`[style*="mask-position"]` 属性选择器自门控——z.ai 卸掉 mask 时属性串消失即不匹配。已在实机页面注入同款 CSS 即时验证：物理屏像素扫描空白带 304px→113px（残余为抓帧瞬间的渲染尾差，DOM 内容一直到底）
- 诊断卡新增 `maskState`（mask 存在性 / mask-position 与 scrollTop 同步差 syncDelta / 摘除是否生效 removed）——以后"底部发白"一发诊断即可分辨是遮罩回潮还是渲染空洞
- 取证过程副产物（均为 z.ai 页面自身行为，非本项目缺陷）：fling 停稳后偶发整列表卸载重建（主线程卡 ~1s，scrollHeight 塌缩到视口高，~750ms 自愈）；`v4-timeline` 与 `v4-timeline-scroll` 两种 data-testid 会在不同会话状态下互换，注入选择器需两者都兼容（本次 CSS 已兼容）

## [未发布] — v73 工作区（bundleVer 50）

- **修复滚到上方后底部空白不消失（虚拟列表空洞）**：z.ai 页面用 @tanstack/react-virtual，上滚后渲染区间跟不上、视口底部留出未渲染空洞；v68b 的看门狗有三个缺陷——探测点只有底部中央（会被页面自带的"滚动到底部"圆钮和自家面板/遮罩挡成假空洞）、手段只有 1px 抖动（治不了"渲染区间算错"型）、6 次耗尽后永久沉默（空洞就这么留着）。升级：30%/50%/70% 三点探测任一命中内容即算覆盖、自家浮层（zcode-* 前缀）盖住的点判"不定"不出手；抖动三档升级（1px×2 → resize+scroll 双派发逼重测视口 → ±8px 真位移逼区间重算）；持续补到 24 拍，用户一亲手滚动即清零重来、自己抖动的回声（300ms 窗口）不误判为用户输入
- 诊断卡新增 `holeState`（covered 检测态 / gapPx 空洞高度实测 / tries 当前档位 / userScrollAgo）——滚到上面复现空白后发一次诊断即可定位到档位

## [未发布] — v72 工作区（bundleVer 49）

- **修复键盘盖住输入框（v70 边到边改造的回归）**：`setDecorFitsSystemWindows(false)` 之后 `adjustResize` 不再自动缩放窗口，键盘直接压在 WebView 上（实机"输入法完全遮住输入框"）——insets 监听把 IME inset 垫进 content 底部，键盘期 WebView 缩到键盘上方并随起落动画平滑过渡；键盘期底部安全区归零（WebView 底缘已落在键盘顶上，不再二次避让）
- **修复悬浮输入框挡住消息结尾**：悬浮/弹窗态 dock 是 fixed 脱流，消息列表不知道底部被占——唤出时给滚动容器垫"胶囊实高+安全区"的让位垫（输入框变高时轮询自适应），在底部时会自动把最后一条消息顶到输入框上方；收纳即撤垫
- **底部空白带根修（容器外一半）**：此前的回收只量得到时间线容器内部，容器底缘到屏底的占位（dock 槽位圈：空壳条 + 槽壳自身的 padding/min-height）够不着——新增外底带回收（`屏底 − 容器底缘` 直接触发）；空壳判断从 `textContent` 换 `innerText`（display:none 的 dock 子树文字不再被算作"有正文"，包着隐形 dock 的占位壳不再漏判）；包着 dock 的槽壳只收 padding/min-height、绝不 display:none（popup 弹窗复活靠 dock）；唤出态不还原外层壳（避免与让位垫叠成双重预留）
- 诊断卡新增 `bottomBand` 底带取证（容器底缘/屏高/dock 几何 + 底带逐行元素探测链）；mock 预览页升级出容器外占位场景

## [未发布] — v71 工作区（bundleVer 48）

- **修复底部空白/填满每秒交替闪烁**（0.0.4 实机反馈）：虚拟列表换窗/重渲染会瞬时卸载全部消息行，1 秒轮询单次空查询就把收纳态摘掉——dock 弹回原生高度、底部 padding 回弹，下一秒行回来又收回去，正好一秒一闪。现给消息存在性加迟滞：有行立即确认；无行需连续两轮且不在展开/弹窗/动画态，才认定空会话翻回常驻输入框
- **动画期冻结布局反馈**：收起/容器变换动画进行中不再重写 dock 状态类、不再按中间高度通知虚拟列表重算（落定后的提交回调在稳定帧补测），消除过渡被轮询掐断成跳变
- **尾部清理去重与互斥**：末节与空白量都没变时整轮跳过（签名去重）；修复被恢复（唤出态）后签名自动作废，再收纳能重新回收；尾部清理通知重算后 600ms 内空洞看门狗让位，两个修复器不再抢同一帧
- **诊断卡新增 floatState / tailFix**：float 翻转时刻与原因、连续缺行轮数、dock 高度、尾部修复时刻与空白量——实机可一眼区分"消息虚拟化卸载""布局振荡""清理重复触发"
- **右滑导航改为自建面板为主**：原生轨道两轮解锁在实机均不可靠（36px 无字细条），右滑直接弹出问题原文面板（点按跳转、点外部收起），原生解锁降为行选择器失效时的后备；条目正文从克隆移除操作按钮读取，不再混入"复制/编辑"
- **手势实机修复**：图标 `touch-action:none` + 6px 即 `preventDefault`——浏览器 ~10px slop 就抢走手势流是"右滑完全没反应、跟手动画会卡"的元凶；输入胶囊纵向拖动同样提前拦默认
- **边到边布局**（原生侧）：去掉 `fitsSystemWindows` 底部垫高，WebView 铺到物理屏底；导航栏 inset 换算 CSS px 上报 JS（`window.__zcodeSafeB`），图标/悬浮输入/全屏输入按安全区抬升不压手势条；导航栏保持透明不再盖住铺底内容
- mock 预览页补 `section` 真盒模型结构与超屏填充消息，尾部清理回收分支可本地回归

## [0.0.4] — 2026-10-08

- **修复手机端问题导航打不开**：原生导航在窄屏下被 `display:none` 容器查询隐藏，此前的解锁样式只恢复了可见性未恢复 display（桌面测试视口宽、容器查询天然激活，掩盖了问题）——补齐 display 解锁；若轨道仍无法显示，自动回退到应用内自建导航面板，保证任何情况下右滑图标都有可见导航
- **全屏输入框加大**：输入区从 32vh 提升到 56vh
- **开合动效更顺滑**：键盘弹出等容器变形落定后再聚焦，消除打开闪烁；收起淡出动画不再被轮询打断成瞬间消失
- **收纳后不再误唤出**：手动收起输入框后的 4 秒内，滚动到消息底部不再自动恢复输入框（需先向上离开底部 220px 再回底 + 过冷却期双条件）
- **消息列表铺满屏幕**：底部空白从"只收末尾垫片"升级为通用测量回收（容器内边距 / 末条外边距 / 尾部空占位逐一识别回收），唤出输入框时全部原样恢复；并加 240px 上限保护虚拟列表
- **手势有了确定性反馈**：左下角图标与输入胶囊拖动时跟手平移（方向即意图），越阈值动画从当前位置接力，未越阈带过渡回弹
- CI 工具链升级：actions/checkout@v5、actions/setup-java@v5（消除 runner 弃用告警）

## [0.0.3] — 2026-10-08

- **安全加固**：诊断卡脱敏正则从 `sid|hash` 扩展为 `sid|hash|remote|token|ticket|mid`——新版页面存在 `?remote=<id>`（WebSocket 凭证）与 `/ws/remote-control/window/<token>` 凭证形态，`mid` 为设备稳定标识，粘贴诊断前全部打码（bundleVer 37）
- **修复悬浮 logo 消失**：① 拖动结束改用当下视口测量并把落位/保存值钳进视口（此前用 logo 创建时的旧视口尺寸，键盘开合/旋屏后可存出视口外坐标，logo 永久消失）；② 恢复时越界存档直接弃用回默认位；③ 1s 轮询自愈：logo 被页面重挂摘掉自动重建、跑出视口自动拉回
- **修复 logo 调暗不恢复**：亮度恢复收进 `hideNav()` 统一处理——此前点遮罩关闭导航、设置里关导航都会把 logo 留在 0.15 透明度（暗背景上近似消失）
- **最近会话按设备合并**：桌面端每次配对轮换链接（sid/hash/t 变）不再堆出多张同名卡片——`mid`（机器 ID）相同即同一设备，新链接覆盖旧链接；无 `mid` 的旧链接退回按机器名合并

## [0.0.2] — 首个 Release

- 首个正式 Release 版本（versionCode 43）
- 图标重新设计：黑底 + 终端绿 Z 字母（取自官方 mark，经视觉模型验证 figure-ground）
- 新增浅色主题 token（`zcode-ui-light`，跟随页面主题）+ hover/outline 变量迁移
- 开源准备：MIT LICENSE、README 重写、官方 logo 全套图标（自适应 + 5 密度）、`.gitignore` 收敛

## [0.0.1] — 开发期（v29 – v61）

开发期的内部版本号（bundleVer / vX）不计入对外语义化版本，这里按里程碑整理关键技术演进。

### 弹窗交互（v53 – v60）

- **v60**：修复弹窗点选后关不掉（移除复活 CSS `!important` + `findPopup` 只认开着的弹窗）
- **v59**：弹窗挂起时 logo 单击不再切换输入框（避免布局抖动）
- **v56 – v58**：ask / 确认弹窗可见性根治（弹窗渲染在 `v4-composer` 兄弟节点，隐藏规则收敛）
- **v53**：修复二级弹窗（Radix portal）被注入层盖住——body 弹窗检测 + z-index 压层

### 输入框 / composer（v31 – v52）

- **v52**：expand 圆弧按钮滞后根治（挂进胶囊作 absolute 子节点，零漂移）
- **v49 – v51**：expand 图标几何迭代（Qwen demo 圆弧、同心同弧度）
- **v35 – v37**：composer 渲染时序竞态修复（幂等 setup + 重试 + stale-node 处理）
- **v31**：毛玻璃 UI + composer 劫持 + 双击进设置

### 诊断与调试（v54 – v56）

- **v56**：调试双通道——`WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`（chrome://inspect 真机 F12）+ 诊断卡 `dockHtml`
- **v54 – v55**：诊断卡新增 `popupDiag` / `popupSnap` 字段，弹窗卸载后仍可读结构

### 工程化（v39 – v51）

- **v51**：注入 JS 源码拆分为 `dev/bundle/` 8 个模块（拼接产物逐字节一致），`_bundle.js` 转为生成物
- **v41**：全量审查优化（性能 / 崩溃防护 / 存储容错 / URL 白名单 / 可见性门控 / token 化）
- **v39b**：修复 `BUNDLE_JS` 超出 JVM 常量池上限（UTF-8 字符串过大）

### 其他

- **v43**：诊断手势改为组合手势（左缘上滑 ×3 + 右缘下滑 ×3）
- **v42**：expand 按钮展开漂移修复 + em 化随界面缩放 + 注入 UI/原生深色全黑灰化（去蓝调）
- **v34**：修复顶层 BODY 调用漏掉 floatInput（composer 劫持曾失效）
- **v30**：logo 死锁修复、手势与面板 UI 打磨

---

> 更早的细节见 git 历史：`git log --oneline`。
