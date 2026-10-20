# ZCode Remote 演进方案（调研合成稿）

- **日期**：2026-10-11 · **基线**：main @ `ad67a1c`（v81 / bundleVer 58，minSdk 26）
- **性质**：调研轮。本报告未改动 `app/`、`dev/` 下任何现有文件；文中代码骨架与桥协议均为**草案**，不是已落地代码。
- **输入**：视觉方案（Carbon Signal）、战略方案（Cockpit 双轴）、评审意见（10 条 findings，逐条回应于附录 A）、独立通读人终稿反馈（18 项，处理记录见附录 E；v1.1 修订稿）、维护者 2026-10-11 两项拍板（远程批准/拒绝不做、色彩语义槽位原则——落实为 **v1.2 修订稿**，逐条见文末「v1.2 变更记录」）。
- **证据约定**：标「本会话实证」的结论在本轮核对过仓库（命令与输出见附录 B）；标「待验证」的必须先经 `tools/usb_diag` 实机取证再写死；其余引自方案原文。

---

## 1. 执行摘要

演进方向：把「ZCode 桌面端的手机遥控」从浏览器封装升级为**系统级遥控台**——视觉收敛为「Carbon Signal 碳底绿信号」终端语言，能力沿双轴生长：**只读状态上行 + 可失败指令下行**，差异化全部落在页面之外的原生侧。为什么：注入链路已达 v81 稳定态，z.ai 页面不可控，演进空间只剩原生壳与系统集成；评审确认两方案守住附庸定位与缺件健全，但三处互斥（品牌色/启动屏/触感）与一处硬伤（composeAndSend 全仓 0 命中）必须先裁定。投入节奏（1 人全职粗估，§6.0）：P0 两三天（触感单点化＋回复摘要＋换色）；一期一周换肤与感知地基（取证不过只裁感知件）；二期两周仪表盘与系统集成；三期以 Lexical 写入取证为门，范围收敛为 RemoteInput 直回与分享发送的指令下行（远程批准/拒绝已否决，附录 C 冻结），不过止于二期，App 完整可用。

---

## 2. 定位原则（宪法五条）

后续每个迭代的每个提案，先过这五条再评价值。判据写死，评审时按判据逐条打勾。

### 原则 1 · 附庸定位
本 App 永远是 ZCode 的手机遥控，不改 z.ai 页面本身的 UI 与交互。注入层只做移动端可用性修复（键盘避让、布局修正），一切差异化做在「页面之外」的原生侧。
**判据**：新增页面元素 ✗；改写页面既有控件行为 ✗；伪造页面状态（如假在线）✗。「替用户按他本要按的钮」是下行指令的下限，且必须可失败、可降级、用户显式触发。
**本次体现**：状态上行严格 querySelector 只读、零 DOM 写入（§4.1）；指令下行只借用既有输入框与发送/确认按钮（§4.3）；仪表盘只显「上次活跃/最后已知状态」，不建第二条 z.ai 连接（§4.4）；分享入口只预填草稿、不伪造 DataTransfer（§4.3）。

### 原则 2 · 缺件健全
任何新功能缺位、关闭、失效时，App 其余部分完整可用；注入层与原生层只经桥松耦合，一侧缺席另一侧照常工作。
**判据**：每个新件必须能写出「被禁用/选择器漂移/桥缺失/进程死」四种场景下的降级终点，且终点 = v81 行为。写不出降级路径的提案不立项。
**本次体现**：§3/§4 每条规格附降级小节；09_agent 缺席 → 状态条 GONE、光点不渲染；队列失败 → 草稿 + 「未发出」通知；小部件/磁贴/分享「不启用即不存在」。

### 原则 3 · 模块化高内聚低耦合
延续 `dev/bundle/` 8 模块划分与 `07_settings.js` 的 `__zcSettings` 注入/热更链路（`WebActivity.kt:3641-3647` 写入、`:3663-3677` 热更，本会话实证），新能力各自成模块。
**判据**：新注入模块不得引用兄弟模块 IIFE 闭包内的局部函数/变量（评审 4 的教训）；新设置键必须走 `__zcSettings` 注入 + `__zcodeAPI.applySettings` 热更既有链路；原生新件各自成文件，不改 WebActivity 生成区。
**本次体现**：09_agent.js 自建状态判定、不引用 `runActive`/`streamingNow` 闭包（§4.1）；原生新件 AgentStateStore / CommandStore / TileService / WidgetProvider 各自成文件（§4.7）。

### 原则 4 · 工程化
延续诊断埋点、实机验证（`tools/usb_diag`）、CHANGELOG 版本节奏。
**判据**：任何页面选择器依赖先实机取证再写死（不猜）；页面写入路径必须 mock 同构用例 + 实机「发送/确认/失败」三链路回归全过才发版；新状态沿全部埋点进 replyTrace/getTrace；原生改动避开 WebActivity `96-3423` BUNDLE_JS 生成区（由 `_patch_web2.py` 拼接，手改即被覆盖；本会话实证 `WebActivity.kt:96` 起 `private val BUNDLE_JS =`）。
**本次体现**：§4.5 工程护栏；§7 待验证清单；路线图每期附验收标准。

### 原则 5 · 保持调性
克制、可靠、工具感，不堆砌装饰。
**判据**：动画 ≤300ms 且均为功能信号；全 App 阴影仅 FAB 一处；无渐变/彩影/粒子/装饰动效；触感仅状态确认、不超白名单；等宽只给状态值；Material You 动态取色不做（固定品牌色守住「原生铬件跟 z.ai 页面走」的视觉身份）。

---

## 3. 视觉进化 ·「Carbon Signal · 碳底绿信号」

### 3.0 冲突裁定表（先于一切视觉动工）

评审 1 指出两方案在三处直接矛盾。裁定如下，**动工前决策冻结**（重开条件见附录 C）：

| 议题 | 视觉方案 | 战略方案 | **裁定** | 理由 |
|---|---|---|---|---|
| 品牌色 | 绿 `#00E676` 唯一，`#2E6BE6` 全线退役 | 保固定品牌蓝 | **绿，唯一品牌色** | ① 深色现状 accent 本就是灰 `#9A9A9A` 非蓝（`values-night/colors.xml:7`，本会话实证），蓝只活在浅色一处（`values/colors.xml:7`，全仓 grep 仅此 1 命中）；② README 徽章已是 `00E676`（`README.md:5,10`，本会话实证）；③ 绿对黑 12.58:1 AAA（本会话按 WCAG 公式复核，见附录 B.13）；④ 设计论证：绿是唯一能同时承担品牌色与「活/在线」语义的彩笔——蓝无「活」的语汇，红/amber 已被失败/待确认占用，不引第二支绿的前提下别无候选，且终端荧光绿与 ZCode `>_` 身份同源（README 徽章先行采纳）；⑤ 语义色全表对比度已复算（附录 B.13）；⑥ 战略方案「保蓝」本意是不做 Material You 动态取色，与色相无涉，改写为跟绿即可 |
| 启动屏 | 不引 SplashScreen 库 | 引 `androidx.core:core-splashscreen` 双侧装 | **不引库** | 两主题同用 `Theme.ZCodeRemote`（`AndroidManifest.xml:25`），windowBackground 同走 `@color/background`（`themes.xml:12`），Entry autoResume 直跳 Web 时两帧同色，战略方案担心的「两种首帧」并不成立；12+ 系统默认启动屏本就生效；现依赖表仅 5 项（`build.gradle.kts:58-64`，本会话实证），少一个依赖符合原则 5。core-splashscreen 降为待验证备选（§7-#4：8-11 首帧观感不佳时再评估） |
| 触感 | 只留连接建立 + 等待确认 | 新增回复/发送/错误多处震动 | **单点白名单**（§3.6） | 评审 3 实证：现状无 VIBRATE 权限、8 处调用全是哑炮，「现状震动」根本不存在；加权限会静默激活全部按键/手势微震，违反原则 5。白名单 + 桥单点管理是唯一同时满足两案意图的解 |
| 字体阶 | 四档「沿用」 | —（未涉及） | **五档收敛**（§3.3） | 评审 9 实证 13sp/16sp 现存，「四档沿用」失实；改五档并附迁移清单 |

### 3.1 设计语言

近黑三阶碳灰（`#0A0A0A`/`#161616`/`#1E1E1E`）做仪表盘骨架；荧光绿 `#00E676` 收编为**唯一品牌色**，只以信号灯/细线/单颗实心 CTA 点状出现；层级 = 色阶 + 1px 描边，不用阴影。气质是 ZCode 的 Agent 遥控台，不是聊天软件。深色优先：`values-night` 现状本就是纯黑灰无蓝（`values-night/colors.xml:3` 注释自证），注入层 token 同构（`02_style.js:13-20` 黑灰系无蓝调，本会话实证），只缺绿与分层。

### 3.2 色彩 token

**顶层原则（v1.2 维护者拍板）**：色值只是出厂默认表，本体是**语义槽位抽象**——App 内所有颜色调用只引用语义名（`@color/background`/`@color/accent`/…），色值集中于深浅两张表；换主题 = 换表，零调用点改动；用户自定义调色板从此是纯增量功能（新增一张用户表即可，不动任何调用点）。

**深色（默认，改 `values-night/colors.xml`；该目录现仅此一件，本会话 git ls-files 实证）**

| token | 值 | 现状 | 用途 |
|---|---|---|---|
| `background` | `#0A0A0A` | 同值（:4） | 底 |
| `surface` | `#161616` | 同值（:5） | 面（卡） |
| `surface_high` | `#1E1E1E` | **新增** | 第三面（芯片/嵌套组） |
| `stroke` | `#262626` | 同值（:6） | 线 |
| `stroke_strong` | `#3A3A3A` | **新增** | 强线（选中态描边） |
| `text_primary` | `#F0F0F0` | 同值（:9） | 主字 |
| `text_secondary` | `#8F8F8F` | 同值（:10） | 次字 |
| `text_tertiary` | `#5A5A5A` | **新增** | 三级灰字（脚注/占位） |
| `accent` | `#00E676` | ← `#9A9A9A`（:7） | 唯一品牌色/信号绿 |
| `on_accent` | `#000000` | ← `#0A0A0A`（:8） | 绿底上黑字（12.58:1 AAA；白字仅 1.67:1，**禁用**——本会话复核） |
| 语义组 | 见下表 | **新增** | 信号灯语言 |

**浅色（改 `values/colors.xml`）**：底/面/线沿用现值（:4-6），`accent #2E6BE6→#00875A`（:7）、`on_accent` 保持白字（#00875A 对白 4.55:1 AA，本会话复核）。`themes.xml:4-5` 经 `@color` 引用自动换色，**全仓无散落硬编码**（`git grep 2E6BE6` 仅 1 命中，本会话实证），蓝退役即改一处。

**状态语义 · 信号灯语言**（品牌绿与在线绿合一，不引第二支绿，防「哪个绿是品牌」歧义；绿 =「活」且永不作装饰）

| 状态 | 深色 | 浅色 | 形态 |
|---|---|---|---|
| 待命（在线） | `#00E676` | `#00875A` | 常亮 |
| 流式中 | 同上 | 同上 | 1.2s 呼吸 |
| done（完成沿） | 同上 | 同上 | 常亮 2s 渐隐 |
| 离线/unknown | `#6A6A6A` | 同 | 灭灯 |
| 失败 | `#F23F43` | `#C63C40` | 常亮 |
| 待确认/引导 | `#F0B232` | `#B26A00` | 常亮 |

**状态条/光点映射（「三支彩笔、五个呈现态」，不引第二支绿）**：绿呼吸 1.2s = thinking|generating（两个中间态用户无需区分，同色同形态）；amber 常亮 = waitConfirm；红常亮 = error（取证通过后启用）；绿常亮 2s 渐隐 = done 沿；灰 = unknown/offline。§4.2 状态条与本表严格同源。
**验收口径**：状态色按 WCAG 1.4.11 非文本图形 ≥3:1（全表手算值见附录 B.13；amber 浅色 `#B26A00` 对白 4.24:1 达标，若挪作小号正文文本需加深）；accent 作文本按 1.4.3（深色 11.9:1 AAA / 浅色 4.55:1 AA）。

### 3.3 字体阶（五档收敛）

- **sans**：20（页题，现状 `activity_entry.xml:40`）/ **16（设备名·主 CTA——升格为正式档，`item_recent.xml:33` 现用）** / 14（正文·按钮）/ 12（辅助）/ 11（脚注·版本号右置）。
- **mono**（系统 mono + `tnum` 等宽数字，不打包字体）：13（主要状态值）/ 12（次级）。只给状态值：版本·bundleVer、时间戳、保活倒计时、mid/URL、诊断数值。标题/按钮/正文一律 sans——等宽是信号不是风格。
- **现状迁移清单**（评审 9 采纳；行号本会话实证）：`activity_entry.xml:49` 副标题 13sp→12sp；`:264` 空列表 13sp→12sp；`:75` 扫码钮 16sp→改 FAB 后该按钮消失；`item_recent.xml:18` 卡内边距 16dp→14dp。

### 3.4 间距 · 圆角 · 层级

4dp 栅格；卡距统一 12dp（现状 16/12 混用：`activity_entry.xml:87` 为 12dp、`:99` 为 16dp，本会话实证）；圆角：卡与按钮 14dp（`:76,101` 现值）、状态芯片全圆、FAB 16dp、卡内边距 14dp（`:111` 现值）；卡片恒 elevation 0（`:102` 现状），**全 App 阴影仅 FAB 一处 6dp**——层级靠色阶不靠投影。

### 3.5 各界面规格

**入口页 · 会话仪表盘**（两方案条目 7 撞同一块 UI，此处为合并后的**单一规格**，与 §4.4 同一实现）：
- 品牌行保留：`>_` 笔画 `ic_logo.xml:9,15` 引 `@color/accent` 自动换绿（本会话实证）；版本号 mono 11sp 右置；`strings.xml:3-4` 产品名与「手机远控 · 链接即连」副题不动（不新造产品名）。
- **台面卡**（Termius 主机卡范式）：设备名 16sp + mid/时间 mono 12sp + 右上状态芯片 + 末条回复摘要一行（`lastReply`，P0② 供数）；整卡热区续连。其余会话为两行小卡（`item_recent` 范式，卡片即按钮）。
- 右下 56dp 圆 FAB（扫码）：深色 `#00E676` 底 + 黑图标、浅色 `#00875A` 底 + 白图标——**每屏唯一实心绿**。现状满宽主按钮 `activity_entry.xml:69-79` 退役；「粘贴」降为台面卡下文字按钮；手动输入折叠为「+ 手动输入」入口，保证一屏直达会话。
- 诚实语义：只显「上次活跃 / 最后已知状态」，不建第二条 z.ai 连接、不假装在线。
- **验证前置**（通读人反馈采纳）：「Termius 主机卡范式」是设计参考而非依据；本仓无使用率数据（§7「数据限制」），信息架构重构落地前须 mock 截图自测；「一屏直达会话」是二期验收条件而非已证结论（真机默认字号下台面卡完整落首屏、粘贴/手动输入 ≤2 步可达——二期验收 6）。

**设置页与引导卡**：不动 SettingsRow/开关绑定（`activity_settings.xml` 模板 + `SettingsActivity.kt` 同构），仅分组卡化：每组裹 surface 卡 + 组标题 mono 12sp 灰；MaterialSwitch 选中 track 用绿。引导卡加左缘 3dp 语义竖条（电池=amber、通知=灰），图标随语义 tint，替换现状统一 accent tint（`activity_entry.xml:118,177`）。

**通知**（仅两处视觉改动，不动两条管线的行为与优先级）：小图标单色绿描形（现状 `ic_bolt`，`KeepAliveService.kt:170`）；保活预览倒计时文本 mono+tnum。

**品牌资产**：不引 SplashScreen 库（裁定见 §3.0）。12+ 走系统默认启动屏；8-11 用 `themes.xml:12` windowBackground，可选叠一层居中 logo 的 layer-list（仍不引库）。adaptive 前景（`drawable-nodpi/ic_launcher_foreground.png`）重绘为绿 `>_` 并补 13+ monochrome 层；README 徽章绿即唯一品牌色（已是，零改动）。

**动效**：三档 90/180/280ms（按压/状态切换/弹层），decelerate，硬上限 300ms；唯一常驻动画 = 状态灯呼吸（功能信号）。刻意不做：启动动画链、FAB morph、彩色阴影、粒子、列表级联、视差。**原生侧减弱动效自读 `ANIMATOR_DURATION_SCALE == 0`**（评审 10 采纳：`01_core.js:12-16` 的 UI_ON/ANIM_ON/REDUCED 是注入层 matchMedia 变量，原生读不到，本会话实证行号——UI_ON 在 :12、ANIM_ON 在 :13、REDUCED 在 :15-16），减弱时呼吸降级半透明常亮。

### 3.6 触感白名单（单点管理）

**现状实况**（评审 3 实证，本会话复核）：manifest 无 VIBRATE 权限（`AndroidManifest.xml:4-11` 仅六项权限），`dev/bundle` 内 `vibrate()` 助手定义在 `02_style.js:296-297`，7 个调用点全部经 `navigator.vibrate(15)` 静默哑炮——即**现状从未真正震过**，视觉方案「等待确认震动保留（06_composer.js:443 现状）」引用失实（:443 是 POPUP_HINT_SEL 定义行，实际调用在 :501）。

**裁定**：P0 加 VIBRATE 权限的同时，触感统一走新桥 `zcodeBridge.haptic(kind)` 单点管理；7 处旧调用逐一处置（P0① 附表）。**白名单**（全部短震，26-28 降级预置波形，五类与桥枚举 B1 一一对应）：连接建立（connect）/ waitConfirm 上升沿（confirm）/ 回复到达（done）/ 发送成功（sent）/ 任务失败（error）。**明确不做**：按键微震、手势震感、自定义 pattern。
**计数口径**（评审「8 处」vs 本报告「7 处调用」）：评审按代码位置计 8 处 = 1 处定义（`02_style.js:296-297` vibrate 助手本体）+ 7 处调用点；处置覆盖 7 个调用点 + 助手本体删除（P0① 表末行）。

### 3.7 与 04_theme 页面取色的共存（两域分治）

- **会话页内原样保留**：`04_theme.js:37-50` 页面取色只驱动 `zcode-ui-light` 挂摘（:43）与 `onTheme` 上报，`02_style.js:15-20` 注入 token 一键不改（均本会话实证）。
- **原生侧（入口/设置/通知）**：**保留 `pageThemeDark` 三态现状**——`null` 跟系统、`true/false` 覆盖并 recreate（`EntryActivity.kt:160-171` + `SettingsStore.kt:45-58`，本会话实证；评审 8 采纳：按字面「只跟系统」实施是行为回退，删除视觉方案「不喂页面色」的矛盾表述）。取色失败零波及。

---

## 4. 战略进化 ·「Cockpit 遥控台」双轴模型

### 4.0 模型

```
上行轴（只读感知）  注入层 querySelector 观测 ──零 DOM 写入──▶ br.onAgentState
                    ─▶ AgentStateStore ─▶ 状态条/光点/仪表盘/小部件/通知
下行轴（可失败指令） 原生 CommandStore 队列 ─▶ evaluateJavascript kick
                    ─▶ 09_agent composeAndSend（待建）借用页面既有输入框与按钮
                    ─▶ br.onCommandResult ─▶ 队列出件/重放
```

感知与操作解耦：上行永不依赖下行存在；下行失败不污染上行；两侧各自降级到 v81 行为。

**术语与缩写**（全表见附录 D）：`br` = `window.zcodeBridge`——原生暴露给注入层的桥对象（onReply/onTheme/getTrace 及未来 onAgentState 挂在此处，判空先例 `07_settings.js:129`）；`__zcodeAPI` = 注入层暴露给原生的全局对象（applySettings 及未来 kick/getPendingCommands 挂在此处，`WebActivity.kt:3669` 即此用法）。**期次对应**：上行轴自一期起（B2），下行轴自三期起（B3/B4/B6，仅 RemoteInput 直回与分享发送）；唯一例外是 B1 触感桥，P0 即引入。

### 4.1 方向一 · 注入层只读状态机（新模块 `09_agent.js`）

独立 1s 轻轮询，**不进 syncComposer 轮询中心**（规避审计指出的复杂度放大）。状态判定**自建**，只复用稳定 DOM 选择器、不引用兄弟模块闭包（评审 4 采纳：`runActive` 是 `07_settings.js:148` 闭包内函数、`streamingNow` 在 `06_composer.js:288`、`checkRunEnd`（`07_settings.js:174-183`）无返回值且依赖 `pendingReply/finalFired` 闭包态——本会话全部实证）。

```js
// 09_agent.js（草案骨架——选择器沿用 07 已实机验证的 v4-* 族，error 族待取证）
(function () {
  var setOn = window.__zcSettings ? (window.__zcSettings.agentStateOn !== false) : true;
  if (!setOn) { return; }                       // 缺件健全：设置键走 __zcSettings 链路
  var last = 'unknown';
  function classify() {
    // 自建判定（等价重写，不引 07 的 runActive）：
    //   generating : [data-testid^="v4-stop"], [data-testid^="chat-loading"] 存在
    //               （选择器出处：07_settings.js:149，已在真机结构上验证过）
    //   thinking   : 末行 assistant 含 [data-testid^="chat-reasoning"]（出处 :198）
    //   waitConfirm: POPUP_HINT_SEL 同族选择器命中（出处 06_composer.js:443-454，
    //               弹窗族形态分类待取证——§7-#3）
    //   done       : generating→非 generating 的下降沿（反馈按钮 v4-feedback-* 为行完成证据，:155）
    //   error      : 待取证（§7-#1），取证前不产出该态（宁漏报不误报）
    //   idle/unknown
  }
  setInterval(function () {
    var s = classify();
    if (s === last) { return; }                  // 只报变化沿
    last = s;
    var br = window.zcodeBridge;                 // 判空照 07_settings.js:129 先例
    if (br && br.onAgentState) { try { br.onAgentState(s, snippet()); } catch (e) {} }
  }, 1000);
})();
```

- 原生侧：`AgentStateStore`（prefs 落盘 + 时间戳，仿 `RecentStore` schema 模式）。
- **降级**：选择器随改版失效 → 停 `unknown` 不显示不误报；桥缺 → 判空跳过；`agentStateOn` 关 → 模块不启动。

### 4.2 方向二 · 原生状态呈现

- **状态条**：WebActivity 顶部 2dp 原生 View，按 §3.2「三支彩笔、五个呈现态」呈现（绿呼吸 = thinking|generating、amber = waitConfirm、红 = error〔取证后〕、绿渐隐 = done 沿、灰 = unknown），与信号灯表严格同源、不另设色。只消费 `onAgentState` 结果，不改页面任何内容。**降级**：unknown 或 `statusStripOn` 关 → GONE；UI_ON 关 → 纯页面零添加；状态条与光点同开时以原生条为唯一真源。
- **悬浮状态光点**：6px 呼吸点。归属裁定（评审 7 采纳）：`05_nav.js` 无任何图标代码（`git grep -i icon dev/bundle/05_nav.js` 0 命中，本会话实证），光点**挂在 `06_composer.js` 的 `#zcode-composer-icon`**（该元素创建于 `06_composer.js:1271`，`07_settings.js:37` 亦有引用，本会话实证）或由 09_agent 自绘，不在 05_nav 重造。`02_style.js` 加一枚呼吸动画 token。
- **降级**：同状态条。

### 4.3 方向三 · 通知与系统级入口

**等待确认 → 锁屏通知（终态，v1.2 收敛）**
- `waitConfirm` 上升沿 → 独立 HIGH 通知，正文带**确认内容摘要**（09_agent `snippet()` 供数）+ 白名单震动；点击复用 KeepAliveService contentIntent 姿势（`KeepAliveService.kt:148-162`，PendingIntent.getActivity + NEW_TASK|SINGLE_TOP|CLEAR_TOP，本会话实证）直达会话——**人进去自己按**，通知只负责把人叫到现场。按状态沿触发、独立通知 ID 段，**不走 ReplyDedupe 内容指纹**（避开同文本 7 天被吞语义）。
- **远程批准/拒绝 action 不做**（v1.2 维护者拍板，附录 C 冻结）：评审 5 已实证 POPUP_HINT_SEL 覆盖 dialog/ask/modal/popup/request/permission/approve/allow/authorize/consent/hook-pending/user-input 十余种形态（`06_composer.js:443-454`，本会话实证），其中 ask/user-input 是自由输入或选项列表——通知一键「批准」可能替用户按一个他没看过的选项；维护者据此裁定**确认不应被简化为盲按二元按钮**，原「二期 action / B5 `resolveConfirm`」分支整支删除（§4.6），弹窗族形态分类降为可选调研（§7-#3）。重开条件：设计出在通知内完整展示弹窗内容与选项的形态（附录 C）。

**RemoteInput 直回 + 指令队列（承重梁，三期门控）**
- 原生 `CommandStore`（prefs JSON 队列，仿 `RecentStore.kt:90-98` 的 `schema_v` 版本化模式，本会话实证）+ 回复通知挂 RemoteInput（receiver 显式 PendingIntent，不 trampoline 不 startActivity）+ 桥同步 `getPendingCommands()`（仿 `getTrace` 模式——同步返回 String 的桥方法，定义在 `WebActivity.kt:3795`，本会话实证；战略方案引 :3794 偏一行）+ 原生 `evaluateJavascript` 调 `__zcodeAPI.kick()` 通知取件 + 09_agent 消费（insertText → 点既有发送钮 → `onCommandResult(id, ok)`）。
- **前提**：composeAndSend 是**待建函数非既有通路**（评审 2 采纳：`git grep composeAndSend` 全仓 0 命中；`06_composer.js:226-229` composerTextOf 只读、`:1342-1347` 仅 focus，注入层现状无任何向输入框写入文本的代码——本会话全部实证）。effort L 评级作废，待 §7-#2 先导通过后重评。
- **降级**：写/发失败 → 队列保留 + 草稿预填不自动发 + 「未发出」通知；进程死 → 回前台按 id 重放去重。

**系统分享入口（ACTION_SEND 文字/链接）**
- manifest 给 EntryActivity 加 ACTION_SEND intent-filter；文字/链接入 CommandStore（`src='share'`）；EXTRA_TEXT 按**不可信输入**：默认只预填草稿，用户在通知上明确点「发送」才入正式队列。图片**暂不做**——理由有二：① 原则性：伪造 DataTransfer/向页面注入文件超出「替用户按他本要按的钮」的下限（原则 1），该理由独立于任何调研成立；② 如实声明：z.ai composer 是否支持图片输入、支持到什么程度，本报告未做任何调研，「侵入过深」目前是推断不是结论——故列 §7-#8 待验证，取证若发现页面原生支持图片且存在纯 UI 诱发路径，可重开。
- **评审 6 采纳**：`EntryActivity.kt:130-137` autoResume 现状无 intent action 判断（本会话实证），分享进 onCreate 会被直接弹进最近会话；且全类无 `onNewIntent`（grep 0 命中，本会话实证）。落地时：intent 含 ACTION_SEND/EXTRA_TEXT → 跳过 autoResume，并补 onNewIntent 路由。
- **降级**：通路不可用 → 复制进剪贴板 + 打开会话；路由异常 → 落普通入口页。

**桌面小部件（单格会话状态）**
- 单格 RemoteViews：会话名 + 状态色点 + 最近回复一行 + 相对时间；数据读 KeepAliveService 已落盘的 `KEY_REPLY_PREVIEW`（`KeepAliveService.kt:79`，现成零新增，本会话实证）；onReply/onAgentState 变化时 updateAppWidget；点击 PendingIntent.getActivity 直达 WebActivity（同 `:148-162` 姿势，避开「不能指 singleTask 入口页」限制）。
- **降级**：进程死显示陈旧值并标「上次」；不添加即零影响；不用列表型、不引 RemoteViewsService。

**快捷设置磁贴 + 应用快捷方式**
- TileService（`BIND_QUICK_SETTINGS_TILE`，exported=true 是系统绑定要求）：「回最近会话」+ 保活延长/停止，状态反射 `running` 标志（`KeepAliveService.kt:83`）；14+ 用 `startActivityAndCollapse(PendingIntent)` 新变体；shortcuts.xml 两条经已导出的 EntryActivity 路由（`WebActivity exported=false`，`AndroidManifest.xml:40`），与 `autoJumpedOnce` 协调。
- **降级**：ROM 限制 try-catch 静默（仿 `KeepAliveService.kt:91-100` 双启动降级，本会话实证）；全部只读现有状态。

### 4.4 方向四 · 入口页升级会话仪表盘

规格即 §3.5（合并后单一规格，两方案不再各写一版）。数据侧：RecentStore 扩 `lastState`/`unread`/`lastReplyAt`（opt* 向后兼容，照 `RecentStore.kt:36-39` 的 optString/optLong 读法）+「保活中·剩 X 分」（`KeepAliveService.running`）+ 未读角标（原生计数，WebActivity onResume 清零）。**降级**：无数据退化为现有列表；深链 `autoJumpedOnce` 门协调防双跳；不做 Material You 取色。

### 4.5 工程护栏（承重梁专项）

1. **composeAndSend 唯一页面写入路径**——待建。落地前：mock 场景补 **Lexical contenteditable 同构用例**（v80 教训自证：真页 v4-composer-input 是 Lexical contenteditable 而非 textarea，「mock 用 textarea 本地全绿真机翻车」，`06_composer.js:221-223`，本会话实证）。
2. **usb_diag 实机回归脚本化**：发送/确认/失败三链路全过才发版。
3. replyTrace/uiTrace 加 `cmd*`/`state*` 埋点并入 getTrace。
4. CHANGELOG 按 v82+ 节奏记录**页面选择器依赖清单**。
5. 原生改动避开 WebActivity `96-3423` 生成区；bundle 只改 `dev/` 源再拼接。
6. **错误态感知补档**：09_agent 增错误行识别（error/danger 类 testid 与失败文案特征）——先 `tools/usb_diag` 实机取证锚定真实选择器再写死（不猜）→ `onAgentState('error', snippet)` → 独立「任务失败·摘要」通知 + 白名单震动，填补审计指出的错误感知空白。**降级**：选择器失效 → 仅无错误通知，完成通知照常；宁漏报不误报；`errNotifyOn` 关 → 静默。

### 4.6 桥协议扩展草案

| # | 方向 | 方法 | 语义 | 引入期 | 降级 |
|---|---|---|---|---|---|
| B1 | JS→原生 | `zcodeBridge.haptic(kind)` | kind ∈ {connect, sent, done, confirm, error}（五值与 §3.6 白名单五类一一对应），单点触感 | P0 | 无权限/老 ROM 静默跳过；`hapticOn` 关 → no-op |
| B2 | JS→原生 | `br.onAgentState(state, snippet)` | 状态沿上报（state ∈ thinking/generating/waitConfirm/done/error/idle/unknown） | 一期 | JS 侧 `br && br.onAgentState` 判空（照 `07_settings.js:129` 先例）；原生侧消费方判空 |
| B3 | 原生→JS | `__zcodeAPI.getPendingCommands()` | 同步返回队列 JSON（仿 `getTrace`，`WebActivity.kt:3795`） | 三期（仅 RemoteInput 直回与分享发送） | 返回空数组即无操作 |
| B4 | 原生→JS | `__zcodeAPI.kick()` | 通知取件 | 三期（仅 RemoteInput 直回与分享发送） | 原生侧守卫式 evaluateJavascript（照 `WebActivity.kt:3669` 的 `window.__zcodeAPI && …` 先例）+ 哨兵回调（`:3655-3658`） |
| B6 | JS→原生 | `br.onCommandResult(id, ok)` | 指令回执 | 三期（仅 RemoteInput 直回与分享发送） | 超时未回执 → 队列保留 +「未发出」通知 |

> **B5**（原行：原生→JS `__zcodeAPI.resolveConfirm(accept)`，确认弹窗二元 resolve）：**v1.2 删除：维护者否决远程批准**（附录 C 冻结）。

新设置键（全走 `__zcSettings` 注入 + `__zcodeAPI.applySettings` 热更，缺省即旧行为）：`agentStateOn` / `statusStripOn` / `errNotifyOn` / `hapticOn`。默认值建议：只读观测类（agentStateOn/statusStripOn/errNotifyOn）默认开——零 DOM 写入且 unknown 自隐；触感类（hapticOn）默认开但本体受权限降级。落地期可调，一期先在诊断卡暴露开关。

### 4.7 原生新件清单

`AgentStateStore.kt` / `CommandStore.kt` / `ResumeTileService.kt` / `SessionWidgetProvider.kt` / `Haptics.kt`（桥方法薄封装）——各自成文件，不缠 WebActivity 主体，且不触生成区。

---

## 5. P0 最小可见改进包（约 2-3 人日，1 人全职 ≈ 两三天）

纯原生三小件 + 触感统一（评审 3 裁定的合并版），全部可独立提交、独立回滚；估算口径见 §6.0。

### ① 触感统一（权限 + 桥 + 旧调用处置，同一提交内完成）
- manifest 加 `VIBRATE` 权限；桥加 `zcodeBridge.haptic(kind)`（B1）；26-28 降级 `VibrationEffect` 预置波形；回复到达/发送成功各一短震（done/sent）。
- **7 处旧调用逐一处置**（语义本会话逐处实读）：

| 调用点 | 语义（本会话实读） | 处置 |
|---|---|---|
| `06_composer.js:501` | 弹窗出现提醒（2s 防抖） | 改调 `haptic('confirm')`，保留防抖 |
| `05_nav.js:130` / `:396` | 导航解锁/自建面板唤出成功 | 删（手势操作反馈，不进白名单） |
| `06_composer.js:86` / `:166` / `:173` | 图标右滑/下滑收纳/上滑全屏 | 删（手势震感） |
| `06_composer.js:1431` | 组合滑动唤出诊断卡 | 删（手势震感） |
| `02_style.js:296-297` | `vibrate()` 助手本体（评审计数的第 8 处位置） | 删（7 个调用点清零后即死码） |

- 删除的安全性：这些调用现状是哑炮（无权限静默失败），**删除 = 维持现状体感，零回归风险**；加权限与清理在同一提交，避免「权限先到、微震复活」的窗口期。
- 降级：无权限/老 ROM 静默跳过；桥缺判空；`hapticOn` 关 → 全静默。

### ② RecentStore 加 `lastReply`/`lastReplyAt`
- opt* 向后兼容（照 `RecentStore.kt:36-39` 读法）；`updateReplyPreview`（`KeepAliveService.kt:108-121`）落盘时顺手写；`item_recent` 第二行显「上次回复 · 时间」。降级：无数据退化为现时间行。

### ③ 色彩 token 第一批（纯资源、无行为耦合，可并行）
- 两份 colors.xml 增改键值（§3.2 全表）：accent 换绿、蓝退役、新增第三面/强线/三级灰字/语义色组；`ic_logo`/引导卡 tint 经 `@color` 引用自动换色，无需动布局。
- 降级：资源缺失回落 `Theme.Material3.DayNight` 默认。

**P0 验收**：mock 全绿（接线回归沿用 v79 的九项基线）；`tools/usb_diag` 回归 4/4 非回归（沿用 v80 基线）；真机白名单五类触感生效、旧调用 0 震；`git grep 2E6BE6 -- app dev` 归零（排除 docs/——本报告自身的论证记载含该字面量，v1.2 修正：原「全仓」口径自指矛盾）。
**P0 回滚**：三件各自独立提交，`git revert` 单项即回 v81 行为。

---

## 6. 路线图（三期）

### 6.0 资源与估算假设（通读人反馈采纳）

- **人力**：1 名熟悉本仓的工程师，全职（依据：git log 全史单一作者节奏，本会话 `git log` 实证）。
- **折算口径**：S ≈ 0.5-1 人日、M ≈ 2-3 人日、L ≈ 5+ 人日（L 待三期先导后重评，评审 2）。据此：P0 ≈ 2-3 人日；一期 ≈ 5-6 人日；二期 ≈ 8-10 人日；三期 = 先导 2-3 人日 +（通过后）8-12 人日。**全部为粗估 ±50%，非排期承诺**——三期 effort 待重评的审慎（评审 2）同等适用于一、二期，故只给区间不给精确值。
- **取证等待不计入人日、计入日历周期**：waitConfirm/error 场景需真实 z.ai 会话自然触发，时间不可控——采用「日常使用挂 usb_diag 待触发」与开发并行，不阻塞同期货其他条目。
- **版本映射建议**：P0→v82；一期余下→v83；二期按可独立回滚批次 v84±；三期 v86±。以 CHANGELOG 实际节奏为准，每可独立回滚的批次一个版本。
- **决策时点与决策人**：`hapticOn` 默认值 P0 动工前定；`agentStateOn`/`statusStripOn`/`errNotifyOn` 一期动工前定（建议值见 §4.6）；决策人 = 维护者（单人仓库）。
- **33+ 通知权限拒授引导**：复用入口页既有引导卡（`EntryActivity.kt:148-158` + `activity_entry.xml:154-210`），文案随新通知语义微调计入二期条目，不另占排期。

### 一期 · 换肤与感知地基（约 1 周）
**目标**：品牌基线落定（绿）；上行轴最小闭环（状态机 → 状态条/光点）；触感单点化。视觉与感知互不阻塞。
**条目**：P0 三小件；色彩 token 全量 + 蓝退役；字体五档收敛（§3.3 迁移清单）；间距/圆角统一；`09_agent.js` 只读状态机（自建判定 + 选择器取证 §7-#1）；原生状态条 + 悬浮光点（挂 composer 图标）；错误行选择器取证（可只取证不落地）；设置页分组卡化；触感白名单落地。
**取证失败出口**（通读人反馈采纳，与三期同等明确）：若 §7-#1 取证发现三态不可稳定区分或误报不可清零 → **只裁感知件**：09_agent 不注册（或降级为仅报取证最稳的单态，如 waitConfirm），状态条/光点 GONE，仪表盘 `lastState` 退空（仍显 lastReply/时间，P0② 供数）；换肤/触感/字体等视觉件**不受影响照常发版**。一期按「感知件与视觉件可拆」设计，取证失败不拖累整期。
**验收标准**：
1. usb_diag 回归 4/4 非回归 + mock 全绿；
2. 取证窗口 = 真机日常使用累计 ≥2 小时且覆盖 thinking/generating/waitConfirm 三场景，每态 ≥10 次状态沿人工核对、误报 0，状态机才上版；不达标走失败出口（判据化验收替代原「30 分钟」无来源数字）；
3. `git grep 2E6BE6 -- app dev` 0 命中（排除 docs/ 自身论证字面量，v1.2 修正口径）；深浅色下 §3.2 语义色对比度全表复算通过；
4. `agentStateOn`/`statusStripOn`/`hapticOn` 全关后，行为与 v81 逐项一致；
5. CHANGELOG v82 记录 + 选择器依赖清单首版。
**回滚**：设置键三关 → 回 v81 行为；资源类改动 git revert 单提交；09_agent 不注册即不存在。

### 二期 · 仪表盘与系统集成（约 2 周）
**目标**：入口从「列表」升级为「遥控台台面」；App 长入系统（通知/分享/桌面/磁贴）。
**条目**：入口会话仪表盘（§3.5/§4.4 单一规格：台面卡 + FAB + 未读 + 保活剩余）；等待确认通知（v1.2 终态：内容摘要 + 点击直达，§4.3）；错误态通知（一期取证通过后）；桌面小部件；分享入口（含 ACTION_SEND 跳过 autoResume + onNewIntent 路由，评审 6）；磁贴 + 快捷方式；品牌资产（图标重绘 + monochrome + 启动屏统一）。
**验收标准**：
1. 分享 → 预填草稿 → 明确点「发送」才入队，全链路真机过；
2. 小部件点击直达会话；杀进程后显示陈旧值并标「上次」；
3. autoResume 与分享/深链/磁贴三入口无一次双跳（真机各 5 次冷热启动）；
4. waitConfirm 通知显确认内容摘要、点击直达会话（contentIntent 姿势），不走 ReplyDedupe；
5. usb_diag 三链路（发送/确认/失败）非回归；
6. 「一屏直达会话」核验（§3.5 验证前置的兑现）：真机默认字号下有历史会话时台面卡完整落首屏（usb_diag 截图取证），粘贴/手动输入 ≤2 步可达。
**回滚**：小部件/磁贴/分享「不启用即不存在」；仪表盘布局 revert 单提交，RecentStore 扩键向后兼容不动旧字段。

### 三期 · 指令下行（2-3 周，先导验证门控）
**目标**：先导通过后建成可失败、可回滚的指令下行承重梁；**不通过则本形态止于二期，App 完整可用**（原则 2 兜底）。
**条目**：
0. **先导验证（门）**：usb_diag 实机取证 Lexical contenteditable 程序化写入三路线（`execCommand('insertText')` / beforeinput dispatchEvent / 剪贴板路径）+ 点既有发送钮的完整链路。**通过判据**（任一路线满足即过门，不要求三路线全过）：① 写入后页面状态与用户手输不可区分（输入框文本、发送后行为一致）；② 连续 ≥20 次稳定（无 focus 丢失、无 IME 冲突、Lexical 编辑器状态不破坏）；③ 页面控制台无新增错误；④ mock 同构用例可复现。**有条件通过**：仅剪贴板路线可行时（会覆盖用户剪贴板、体验有损）→ 指令下行降级为「仅预填草稿 + 用户在页面手动点发送」形态，不建 composeAndSend 自动链路，B3/B4/B6 缩窄为草稿下发。**通过才立项后续、才重评 effort**；
1. composeAndSend 建设 + mock Lexical 同构用例（三分支：写入成功/发送成功/失败）；
2. CommandStore + RemoteInput 直回 + 队列（B3/B4/B6）；
3. 分享「发送」入正式队列。
**验收标准**：
1. mock Lexical 同构用例覆盖写入/发送/失败三分支全绿；
2. usb_diag 三链路脚本化全过才发版；
3. 任一步失败 → 草稿 + 「未发出」通知，回前台按 id 去重重放；
4. 全程 `cmd*`/`state*` 埋点进 getTrace，可诊断每一次失败。
**回滚**：RemoteInput/队列走设置键 → 回纯通知形态；composeAndSend 先导不过即不入版（门控的意义）；等待确认通知「仅提醒」为终态语义（v1.2），不受三期任何下行能力影响。

---

## 7. 风险与未决问题

### 风险表

| 风险 | 等级 | 缓解 |
|---|---|---|
| Lexical 程序化写入不可行/不稳定（改版、输入法交互） | 高 | 三期先导门控（§7-#2）；不通过止于二期形态 |
| 页面选择器漂移（状态机/错误行/弹窗族） | 高 | unknown 自隐不误报；选择器依赖清单入 CHANGELOG；先取证再写死。**漂移史实测**（本会话 git 考古，附录 B.14）：dev/bundle 可考窗口（2026-08-10 起，13 提交）内 POPUP_HINT_SEL 本体零改动，v4-\* 主干自 v61（2026-08-11）以来约 2 个月未失效；历史适应性维护 ≈ 每 1-2 个月一次且多为扩展而非失效（v57/v61/v64 三次弹窗族扩展、v65/v69 导航两轮翻车后 v70 改自建，均注释留痕）。维护带宽与 unknown 自隐的静默退化窗口按此上限估算 |
| VIBRATE 权限激活既有哑炮微震 | 高→已消解 | P0① 权限与清理同一提交（§5①） |
| 两方案撞同一批 UI 返工 | 高→已消解 | §3.0 裁定表先行，决策冻结 |
| 通知权限（33+）被拒 | 中 | 通知类功能全降级为状态条/仪表盘呈现，核心链路不依赖通知 |
| 双启动/ROM 差异（磁贴、collapse 变体） | 中 | try-catch 静默，仿 `KeepAliveService.kt:91-100` |
| BUNDLE_JS 生成区手改被覆盖 | 中 | 改 `dev/` 源 + 拼接，原生侧避让（§4.5-5） |
| pageThemeDark 语义误读致行为回退 | 中→已消解 | §3.7 保留三态现状（评审 8） |

### 待验证清单（全部走 `tools/usb_diag` 实机取证，先取证后写死）

1. **状态特征选择器**：reasoning 行 / 运行中（v4-stop·chat-loading 已有出处）/ done 沿 / **error 族（现状完全无证据）**——09_agent 前提。
2. **Lexical 写入三路线**：execCommand insertText / beforeinput / 剪贴板——三期门（评审 2）。
3. **弹窗族形态分类**（v1.2 降级）：POPUP_HINT_SEL 十余形态中哪些确证二元——**可选调研，不阻塞任何期**（远程 action 已否决、附录 C 冻结；结论仅存档，供重开条件评估用）。
4. **8-11 首帧观感**：不引 splashscreen 库是否达标；不达标再评估 core-splashscreen（升级非补缺）。
5. **悬浮光点可见性**：6px 呼吸点在真机 dock/键盘场景下是否可辨、是否干扰。
6. **语义色对比度工具复核**：深浅两模式下 §3.2 全部状态色以对比度工具复核（主要值本报告已手算，见附录 B.13）；
7. **发版前检查项固化**：`git grep 2E6BE6 -- app dev` = 0（排除 docs/——本报告论证记载含该字面量，v1.2 修正：原全仓口径自指矛盾）；`git grep -i vibrat dev/bundle` 只剩白名单桥调用；
8. **z.ai composer 图片输入能力**（分享图片重开的门）：页面是否原生支持图片、是否存有纯 UI 诱发路径——未取证前维持「暂不做」（§4.3）。

### 未决问题（需产品决策，落地期定）

- 新设置键默认值（建议值见 §4.6；决策时点见 §6.0，决策人 = 维护者）。
- ~~未读角标计数口径：回复到达即 +1，还是 waitConfirm/error 才 +1。~~ **2026-10-12 维护者拍板**：计数口径 = 回复到达即 +1；呈现 = 彩色状态点，按当前态取色——错误红 > 等待确认橘 > 「回复已到、仍在跑」蓝 > 「回复全部完成」绿；红点在 error 态取证落地（§7-#1）前保持不渲染（宁缺毋滥）。需新增 `status_info` 语义槽（蓝**仅作状态语义**回归，绿仍是唯一品牌色，§3.1 冻结不受影响）。
- 小部件是否进引导流程（暂定不进）。**2026-10-12 维护者反馈**：现版小部件「没啥功能也不好看」——重做已列入建议清单（Carbon 座舱卡方向）。
- ~~可选补数手段：诊断卡内加本地使用计数（随 usb_diag 导出、不联网）——默认不做（原则 5），待维护者决策。~~ **2026-10-12 维护者拍板：不加**。

### 数据限制（如实声明，通读人反馈采纳）

本 App **无任何遥测**：manifest 仅 INTERNET 供 WebView 加载（`AndroidManifest.xml:4`），无 analytics 依赖（`build.gradle.kts:58-64` 五项依赖实证），全部存储为本地 prefs（RecentStore / SettingsStore / ReplyDedupe）。因此活跃用户数、入口页扫码/粘贴/手输路径使用率、通知点击率、waitConfirm/error 实际发生频率**均不可得**；本报告的价值排序依据是维护者自用场景 + git log 功能演进轨迹（v46 弹窗感知、v57/v61/v64 三次弹窗族扩展均为真实痛点驱动的间接证据）。P0 与二期的优先级无法用数据校验——这是本报告的固有限制，如实声明而非以 plausible 数字填补。

---

## 8. 附录

### 附录 A · 评审意见处理记录（10/10 逐条）

| # | finding（摘要） | 严重度 | 处理 | 落点 |
|---|---|---|---|---|
| 1 | 两方案品牌色/启动屏/触感三处直接矛盾，且撞同一批入口 UI | high | **采纳**。单一品牌基线 = 绿（视觉方案先落地、战略条目改写为跟随后）；启动屏与触感合入决策表 | §3.0 裁定表、§3.6 白名单、P0①；附录 C 冻结 |
| 2 | composeAndSend 全仓不存在、注入层无任何写入文本代码，effort L 失实 | high | **采纳**。明确标注待建；Lexical 写入列独立先导验证项，通过后才评 effort；RemoteInput/队列全部押后三期 | §4.3、§4.5-1、§6 三期条目 0、§7-#2 |
| 3 | 无 VIBRATE 权限，8 处 navigator.vibrate 全是哑炮；加权限会激活微震；「06:443 现状震动」引用失实（实为 :501） | high | **采纳**（含行号修正：POPUP_HINT_SEL 定义 :443、实际调用 :501，本会话实证）。权限 + 桥单点 + 旧调用逐处处置，同一提交 | §3.6、P0① 处置表 |
| 4 | checkRunEnd 非谓词（无 return、依赖闭包态）；「复用谓词」要跨模块引闭包函数 | medium | **采纳**。09_agent 只复用稳定 DOM 选择器、自建判定 | §4.1 + 骨架注释（选择器出处标明 07:149/198/155） |
| 5 | 「批准/拒绝」假设弹窗可二元 resolve，ask/user-input 等形态下一键批准比误报更危险 | medium | **采纳**；v1.2 升级：维护者进一步否决远程 action 整支（不做，附录 C 冻结），原「仅二元形态开放 action」分支取消，通知收敛为摘要 + 直达终态 | §4.3（v1.2 终态）、§7-#3（降级可选调研）、附录 C |
| 6 | ACTION_SEND 与 autoResume 冲突（无 action 判断、无 onNewIntent） | medium | **采纳**。含 ACTION_SEND/EXTRA_TEXT 时跳过 autoResume + 补 onNewIntent 路由 | §4.3 分享入口、二期验收 3 |
| 7 | 「05_nav 悬浮图标」归属错误（05_nav 无图标，图标属 06_composer） | medium | **采纳**。光点挂 `#zcode-composer-icon`（06_composer.js:1271 创建）或 09_agent 自绘 | §4.2 |
| 8 | 「只跟系统」与 pageThemeDark 三态现状矛盾，两读必返工其一 | medium | **采纳**。写成「保留三态现状（null 跟系统）」，删「不喂页面色」表述 | §3.7 |
| 9 | 字体阶「四档沿用」失实（13sp/16sp 现存，阶数不自洽） | low | **采纳**。五档（含 16sp）+ 收敛迁移清单 | §3.3 |
| 10 | 「对齐 01_core.js ANIM_ON/REDUCED」跨层引用不可实现；行号漂移（UI_ON 在 :12） | low | **采纳**。原生侧自读 `ANIMATOR_DURATION_SCALE==0`，删跨层引用 | §3.5 动效段 |

**不采纳：无。** 理由：10 条的仓库证据在本会话全部独立复核成立（附录 B），无一证伪；个别行号有 1-4 行漂移（item_recent padding 实为 :18 非 :22、16sp 实为 :33 非 :31；getTrace 实为 WebActivity.kt:3795 非 :3794），实质判断不受影响。

### 附录 B · 本报告复核记录（本会话执行）

**命令与结果**（工作目录 `D:\Codes\workspaces\zcode-remote`）：
1. `git grep -n "composeAndSend"` → **0 命中**（全仓库）。
2. `git grep -n -i "vibrat" -- dev/bundle` → 定义 `02_style.js:296-297` + 调用 7 处：`05_nav.js:130,396`、`06_composer.js:86,166,173,501,1431`（评审「8 处」= 此 1 处定义 + 7 处调用，口径见 §3.6）。
3. `git grep -n "2E6BE6"` → 仅 `app/src/main/res/values/colors.xml:7` 一处。（v1.2 注记：此为报告撰写时（P0③ 改前）快照；P0③ 落地后 `git grep 2E6BE6 -- app dev` 已归零，残留命中仅本报告文档自身的论证字面量。）
4. `git grep -n "onNewIntent" -- app` → 0 命中。
5. `git grep -n -i "icon" -- dev/bundle/05_nav.js` → 0 命中；`git grep -n "zcode-composer-icon"` → `06_composer.js:23,1255,1271,1323` + `07_settings.js:37`。
6. `git ls-files dev/bundle app/src/main/res/values-night` → values-night 仅 `colors.xml` 一件；bundle 为 8 模件。

**实读核对**（人读，非截图）：`AndroidManifest.xml`（权限 :4-11、exported :28/:40）；`EntryActivity.kt`（autoResume :130-137、syncPageTheme :160-171，全文无 onNewIntent）；`SettingsStore.kt:45-58`（pageThemeDark 三态）；`activity_entry.xml` 全文（:40 20sp、:49 13sp、:69-79 满宽扫码钮、:75 16sp、:76/:101 14dp、:87 12dp/:99 16dp 混用、:102 elevation 0、:111 14dp、:118/:177 accent tint、:264 13sp、:274 11sp）；`item_recent.xml`（:18 padding 16dp、:33 16sp）；`ic_logo.xml:9,15`（@color/accent）；`strings.xml:3-4`；`README.md:5,10`（徽章 00E676）；`build.gradle.kts:58-64`（依赖 5 项，无 splashscreen）；`themes.xml:4-12`；两份 `colors.xml` 全文；`KeepAliveService.kt`（:79 KEY_REPLY_PREVIEW、:91-100 双启动降级、:148-162 contentIntent、:170 ic_bolt）；`RecentStore.kt:90-98`（schema_v）；`WebActivity.kt`（:96 BUNDLE_JS 起、:3624-3677 判空守卫/哨兵回调/热更、:3795 getTrace）；`01_core.js:12-16`、`02_style.js:15-20,296-297`、`04_theme.js:37-50`、`05_nav.js:122-135,388-401`、`06_composer.js:215-239,280-297,440-459,495-506,1338-1353,1422-1434`、`07_settings.js:122-201`。

**B.13 WCAG 对比度手算**（本会话按 WCAG 2.x 相对亮度公式逐值计算；v1.1 应通读人反馈补齐全表）：
- `#00E676` L≈0.579 → 对纯黑 **12.58:1**（AAA）；对 `#0A0A0A`（L≈0.0030）≈ **11.9:1**（AAA）；其上白字 ≈ **1.67:1**（禁用，故 on_accent 用黑）；其上黑字 12.58:1。
- `#00875A` L≈0.181 → 对白 **4.55:1**（AA；视觉方案报 4.56，舍入差）。
- 深色状态色对 `#0A0A0A`：`#F23F43`（L≈0.228）≈ **5.25:1**；`#F0B232`（L≈0.506）≈ **10.5:1**；`#6A6A6A`（L≈0.144）≈ **3.66:1**（非文本 3:1 达标，离线灯有意弱化）。
- 浅色降档对白：`#C63C40` ≈ **5.10:1**；`#B26A00` ≈ **4.24:1**（灯点/图形达标 3:1，挪作小号正文文本需加深）；`#6A6A6A` ≈ **5.41:1**。
- 以上均为手算，落地时以对比度工具复核（§7-#6）。

**B.14 选择器漂移史考古**（v1.1 本会话执行）：`git log --format="%h %ad %s" --date=short -S "v4-row" -- dev/bundle` → 5 个提交（7d8dadf 2026-08-10 / c77e57b v61 2026-08-11 / 854ec62 v70+71、0475f42 v79、141fa60 v80，后三者均 2026-10-09）；`git log -S "POPUP_HINT_SEL" -- dev/bundle` → 仅 7d8dadf 一处（可考窗口内本体零改动）。dev/bundle 路径全史 13 提交，跨 2026-08-10 → 2026-10-09。局限：git 历史在 2026-08-10 仓库整理提交处断裂（`-S` 不跨重命名追踪），更早漂移率只能靠代码注释考古——`06_composer.js:445-453` 的 v57/v61/v64 三次弹窗族扩展注记、`05_nav.js:388-391` 的 v65/v69→v70 导航翻车史注记（均本会话实读）。

**B.15 ReplyDedupe 实读**（v1.1 本会话，应通读人术语表需求）：`ReplyDedupe.kt:6-9`（7 天窗口类注释）、`:34-45`（`markSeen`：7 天窗口内已见返回 true 即跳过通知）、`:85`（TTL_MS = 7 天）——「同文本 7 天被吞」语义实证，waitConfirm 通知须独立 ID 段 + 状态沿触发的原因成立。

**未做**（如实声明）：未跑实机（tools/usb_diag 属落地期动作）；未构建 APK；§6 各期验收标准为「到时执行」的验收条件，本轮未执行。

### 附录 C · 决策冻结表

| 决策 | 结论 | 重开条件 |
|---|---|---|
| 品牌色 | 绿 `#00E676`/`#00875A` 唯一，蓝退役 | 用户明确否决；对比度复算不达标 |
| 启动屏 | 不引 SplashScreen 库 | §7-#4 取证发现 8-11 首帧观感不佳 |
| 触感 | 桥单点 + 五类白名单，旧 7 调用处置如 P0① 表 | 无（白名单增删走 P0 同级评审） |
| 字体阶 | 五档 + mono 两档 | 无 |
| pageThemeDark | 保留三态现状 | 无 |
| Material You 取色 | 不做 | 品牌策略变化 |
| 09_agent 判定 | 自建，不引兄弟模块闭包 | 无（结构性约束） |
| 远程批准/拒绝（锁屏通知 action） | **不做**（v1.2 维护者否决：确认不应被简化为盲按二元按钮） | 设计出在通知内完整展示弹窗内容与选项的形态 |

### 附录 D · 术语表（v1.1，通读人反馈采纳）

| 术语 | 释义 | 出处 |
|---|---|---|
| `zcodeBridge` | 原生暴露给注入层 JS 的桥对象（onReply/onTheme/getTrace…，未来 onAgentState/haptic 挂此） | JS 侧经 `window.zcodeBridge` 取用，判空先例 `07_settings.js:129` |
| `__zcodeAPI` | 注入层暴露给原生的全局对象（applySettings…，未来 kick/getPendingCommands 挂此） | 用法见 `WebActivity.kt:3669` |
| `__zcSettings` | 原生随注入串写入的设置快照，bundle 读它做初始值；applySettings 热更 | `WebActivity.kt:3644-3647` |
| ReplyDedupe「同文本 7 天被吞」 | 回复通知按内容指纹去重：同一句内容 7 天内只通知一次（TTL=7 天），窗口过后才允许再通知——若 waitConfirm 通知走此管线，同文案确认提醒会被吞，故须独立 ID 段 + 状态沿触发 | `ReplyDedupe.kt:6-9,34-45,85`（附录 B.15） |
| autoJumpedOnce / 双跳 | 进程内单次标记：冷启动直跳最近会话后不再自动跳，防「从会话返回入口又被弹回」。多入口（分享/磁贴/深链）与 autoResume 并存时会互相抢直跳目标，即「双跳」 | `EntryActivity.kt:74-77,130-137` |
| 双启动降级 | startForegroundService 失败 → 退 startService → 再失败静默放弃的 try-catch 套路，ROM 差异场景的通用降级姿势 | `KeepAliveService.kt:91-100` |
| mid | 远控链接携带的机器 ID 参数；同 mid 即同一台桌面设备，RecentStore 据此合并会话（无 mid 时退回按机器名合并） | `RecentStore.kt:55-63` |
| BUNDLE_JS 生成区 | WebActivity.kt:96 起由 `_patch_web2.py` 从 dev/bundle 拼接生成的大字符串；手改该区即被下次拼接覆盖，原生改动须避让 | `WebActivity.kt:94-98` |
| v4-\* 选择器族 | z.ai 页面 `data-testid` 前缀族（v4-row / v4-stop / chat-loading / chat-reasoning / v4-feedback-\*），注入层全部页面观测的锚点 | `07_settings.js:149-155,198` |
| usb_diag | tools/ 下的实机取证通道（截图/诊断导出），本报告「待验证」清单的统一验证载体 | 原则 4、§7 |

### 附录 E · 独立通读人反馈处理记录（v1.1，18 项）

| # | 反馈（类别 · 摘要） | 处理 | 落点 / 理由 |
|---|---|---|---|
| Q1 | likelyQuestions：一期取证失败的分支缺失，为何只有三期有失败出口 | 采纳 | 一期新增「取证失败出口」：只裁感知件（09_agent 不注册/降级单态、状态条光点 GONE、lastState 退空），视觉件照常发版 |
| Q2 | 白名单五类 vs B1 四值，「连接建立」无 kind；8 处 vs 7 处口径差 | 采纳 | B1 kind 扩为五值 {connect, sent, done, confirm, error}（§4.6）；「8 = 1 定义 + 7 调用」口径在 §3.6/附录 B.2 写明；P0① 处置表补 helper 本体行 |
| Q3 | Lexical 先导「通过」无判据；仅剪贴板可行算不算过 | 采纳 | §6 三期条目 0 补四条通过判据（任一路线满足即过）+「有条件通过 → 降级仅预填草稿」分支 |
| M1 | 排期无资源假设；取证等待不可控未计入 | 采纳 | 新增 §6.0：1 人全职、S/M/L 折算人日、±50% 声明、取证并行不阻塞、版本映射、决策时点/决策人、通知权限引导复用现有卡 |
| M2 | 无使用数据支撑价值排序 | 部分采纳 | 数据不可得是仓库客观现状（无遥测，manifest/依赖表实证），无法补数据入报告；采纳「声明限制」部分：新增 §7「数据限制」小节 + 可选补数手段列待决。不采纳隐含的「先补数据再排期」——单人自用工具，等待数据建设违背轻量原则 |
| M3 | 选择器漂移频率无数据，维护带宽无法估算 | 采纳 | 本会话 git 考古（附录 B.14）：v4-\* 主干 2 个月未失效、历史维护 ≈1-2 月一次多为扩展非失效；写入 §7 风险表；如实注明 git 史 2026-08-10 断裂的局限 |
| M4 | 一期取证失败分支缺失（同 Q1） | 采纳 | 同 Q1 |
| M5 | 版本号映射、设置键决策时点/决策人、33+ 权限引导排期缺失 | 采纳 | §6.0 三条补齐（v82/v83/v84±/v86± 建议映射；动工前决策、决策人=维护者；复用现有引导卡不另占排期） |
| U1 | §4.0 缩写提前使用、期次对应需跨节拼图 | 采纳 | §4.0 图下补术语与期次对应注；术语全表进附录 D |
| U2 | 哑炮 7 vs 8 对不上 | 采纳 | 同 Q2：口径统一为「8 位置 = 1 定义 + 7 调用」 |
| U3 | 触感枚举两处不合 | 采纳 | 同 Q2：五值对五类一一对应 |
| U4 | 状态条「四色」与信号灯表对不齐 | 采纳 | 收敛为「三支彩笔、五个呈现态」：thinking/generating 同绿呼吸不另设色（§3.2 表注 + §4.2 改写） |
| U5 | ReplyDedupe/autoJumpedOnce/双跳/双启动降级/mid 零定义 | 采纳 | 新增附录 D 术语表；ReplyDedupe 语义本会话实读实证（附录 B.15） |
| S1 | 全部工期数字无推算依据 | 采纳 | §6.0 给折算口径与人日拆解、标注 ±50% 非承诺；三期 L 待重评的审慎同等适用于一/二期，故只给区间 |
| S2 | 选绿理由是「改动成本低」非「绿更优」；对比度仅算两值 | 采纳 | §3.0 补设计论证（绿是唯一可兼任品牌 +「活」语义的彩笔、与 `>_` 身份同源）；附录 B.13 补齐全表手算（v1.1） |
| S3 | 入口页信息架构重构无数据/可用性验证；「一屏直达」是断言 | 采纳 | §3.5 加「验证前置」：Termius 范式降为设计参考、「一屏直达」改为二期验收条件 6、mock 截图自测前置；数据限制引 §7 |
| S4 | 「30 分钟零误报」数字无来源、预诺取证结果 | 采纳 | 改为判据化验收（≥2 小时窗口、每态 ≥10 次状态沿、误报 0 才上版）+ 失败出口；原无源数字删除 |
| S5 | 「图片不做」是无调研的硬结论 | 部分采纳 | 「暂不做」结论保留——原则性理由（伪造文件注入超出「替用户按按钮」下限）独立于调研成立；但采纳「承认无调研」：修正理由表述、新增 §7-#8 待验证与重开条件 |

---

### v1.2 变更记录（2026-10-11，落实维护者两项拍板）

1. 附录 C 冻结表新增一行：「远程批准/拒绝（锁屏通知 action）」= 不做——确认不应被简化为盲按二元按钮（维护者否决），重开条件为设计出在通知内完整展示弹窗内容与选项的形态。
2. §4.3「等待确认→锁屏通知」收敛为终态——通知带确认内容摘要 + 点击直达会话、人进去自己按，删除「二期 action / B5 resolveConfirm」分支表述（§6 二期条目与验收 4 同步改写）。
3. §4.6 桥协议表删除 B5 行、留注记「v1.2 删除：维护者否决远程批准」，B1-B4/B6 保留，B3/B4/B6 引入期改为「三期（仅 RemoteInput 直回与分享发送）」（§4.0 期次对应同步改为 B3/B4/B6）。
4. §6 三期条目 3（waitConfirm 二期 action）删除、后续条目上移，§7-#3（弹窗族形态分类）降级为「可选调研，不阻塞任何期」，执行摘要三期描述相应收敛，附录 A 评审 5 落点同步注记。
5. §3.2 表前增补顶层原则（维护者拍板）：色值只是出厂默认表，本体是语义槽位抽象——App 内所有颜色调用只引用语义名，色值集中于深浅两张表，换主题 = 换表零调用点改动，用户调色板从此是纯增量功能。

---

*本报告为调研轮产出（v1.2）。落地从 P0 起每项先过 §2 五条判据，再按 §6 节奏执行；所有「待验证」项在写死前必须留 usb_diag 取证记录。*
