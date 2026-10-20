# ZCode 远控 v4 改版适配方案（调研合成稿）

- **日期**：2026-10-19 · **基线**：main @ `fa1d816`（v84 / versionCode 47，minSdk 26）
- **性质**：调研轮。本报告未改动 `app/`、`dev/` 下任何现有文件；文中改动点均为**方案**，不是已落地代码。
- **触发**：ZCode 桌面端升级后（官网 Release v3.15.3「优化手机远控服务」，本机实装 3.16.1），手机端打开远控链接改为**需要登录 + 登录后回跳一次校验**，且远控前端 UI 全面重构，现有注入层与原生假设大面积失效。
- **证据约定**：标「本会话实证」的结论附了取证来源（附录 A：官网实测 / 桌面端 asar 反编译 / 新版远控页 bundle 反编译 / 实际访问）；标「待验证」的必须先经 `tools/usb_diag` / `chrome://inspect` 实机取证再写死（延续 evolution-plan 原则 4）。

---

## 1. 执行摘要

ZCode 新版把手机远控从「免登录的长期 bearer-link」改成了**「账号登录 + 一次性配对凭证」**模型：二维码编码 `https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…`（设备 ID + 配对密钥 + 时间戳，网页解析函数 `pO` 实证三者必填），打开页面必须先经 OAuth 登录（Z.ai 或 BigModel 双账号体系），登录后带 `code/state` **回跳 `/remote/v4` 本身完成一次校验**（换取 JWT），再经 `wss://…/api/v1/remote/ws2` 与桌面端握手配对；二维码参数/鉴权失效即进「校验失败·手机连接已失效」终态页，官方文案明确**旧链接不可复用**。

对本 App 的冲击分三层：① 原生层——BigModel 登录域 `bigmodel.cn` 被现有 `*.z.ai` 白名单踢出 WebView（登录死路）；`onPageFinished` 无条件注入会污染登录页/失败页；autoResume/最近会话/小部件/磁贴/通知深链指向的旧链接**全部死链**。② 注入层——新前端是全新 React SPA，`v4-*` 选择器族基本消失，09_agent 状态机、06_composer 悬浮输入与回复监听、05_nav 导航、04_theme 取色**全部失锚**（unknown 自隐兜底不误报，但功能归零）。③ 保活模型——页面自带 visibility 感知重连，`_alive` 谎报可见性可能与之冲突，需实机重评。

适配分三期：**P0 兼容热修**（约 2-3 人日，让现有 APK 在新版下可用：白名单 + 注入门控 + 死链兜底 + 登录引导，不动注入层选择器）；**P1 连接生命周期**（约 2-3 人日：SPA 路由级连接状态感知 + 死链入口全链路降级 + 保活策略实测）；**P2 注入层 v2**（约 5-8 人日，取证门控：按新 UI 锚点重建选择器层，恢复状态机/悬浮输入/回复通知）。P0 完成即可扫码登录连上并正常收发消息（页面原生能力），P2 完成才恢复「App 增强」全部能力。

---

## 2. 调研发现（全部本会话实证，来源见附录 A）

### 2.1 新链接与配对模型

**链接格式（v1.1 按真实二维码更正）**：`https://zcode.z.ai/remote/v4?sid=<deviceSid>&mid=<deviceMid>&name=<deviceName>&app_version=<版本>`

- **v1.1 修正**：真实二维码（桌面端 3.16.2，cv2 解码 + 页面行为实测）**不含 `hash`/`t`**，与桌面端 `buildWebRemoteControlExternalQrUrl`（set sid/mid/name/app_version）完全吻合。初版报告据网页 bundle `pO` 函数（`src-wmk2orCZ.js`，`sid`/`hash`/`t` 必填）推断「三者必填」——该函数属**旧格式/其他分支**的解析器，不是本形态的路径；无参直访报 `Missing or invalid relay parameters` 是因为 `sid` 缺失，不证明 `hash`/`t` 必填。
- **凭证模型更正**：链接本身**不再携带配对密钥**，鉴权完全依赖账号 JWT（服务端按 `userId + endpointOrigin` 校验设备归属）；`sid` 是持久设备标识（桌面端设置键 `webRemoteControlRelayV2Device{deviceSid,userId,endpointOrigin}`）。推论：新格式链接在设备注册存续期间**可复用**（仍需桌面端开启远控等待 + 已登录），「死链」主体是旧格式路径 404 与「桌面端未在等待」两种场景。WS 握手中 `calculateProof(passHash,…)` 的 `passHash` 来源待取证（§5-#1 修正后遗留项：URL 不再携带，应为登录后服务端下发或派生）。
- 旧版路径 `/remote` 已 **404**（实测重定向 `/cn/remote` 后 404）；桌面端按 `appVersion` 选 `/remote/v4` 或 `/remote/v3`（`buildZCodeEndpointUrls`：`F$(appVersion)?"v4":"v3"`），且新版二维码构造函数 `buildWebRemoteControlExternalQrUrl` 显式 **`searchParams.delete("hash")`、`delete("t")`** 清除旧参数——**存量旧链接全部死链**。
- 官方失败页文案实证：「不要复用旧截图或旧链接」「回到桌面端扫描最新二维码」——二维码是**每次开启远控时重新生成的短时效配对凭证**（`t` 时间戳 + 30 秒 waiting 配对超时「Desktop did not match this mobile connection before the waiting timeout」）。

**传输与握手**：`wss://zcode.z.ai/api/v1/remote/ws2`（Relay V2，桌面端设置键 `webRemoteControlRelayV2Device`）。网页端握手序列（bundle 实证）：

```
WS open → auth_init{role:"terminal", device_sid, meta:{platform:"web",version,name:"mobile-browser"}}
        ← auth_challenge{nonce}
        → auth_response{device_sid, proof:HMAC-SHA256(passHash, `${nonce}|terminal|${device_sid}`)}
        ← auth_ack / pair_status_ack{pair_status: waiting|matched} → paired
心跳 pair_status_query；失败码 KICKED/AUTH_FAILED/WRONG_PARAM/INTERNAL/DEVICE_OFFLINE
WS close codes：4004 SessionNotFound · 4009 SessionConflict · 4010 DesktopDisconnected
              4011 SessionExpired · 4012 WorkspaceClosed · 4013 InvalidMobileConnection
```

**账号绑定**：桌面端远控设备按 `userId + endpointOrigin` 绑定（`webRemoteControlRelayV2Device` schema 实证），未登录直接抛 `auth-required`；登录身份变化（`signed-in`）会踢掉 `userId/endpointOrigin` 不匹配的既有连接。**推论（写进用户引导）：手机端登录的账号体系必须与桌面端一致，否则配不上对。**

### 2.2 登录与「返回一次校验」流程（用户报告的机制实锤）

新版远控页的 OAuth 配置（bundle 常量实证）：

| 项 | 值 |
|---|---|
| Z.ai 账号 | `https://chat.z.ai/auth?response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&redirect_uri=…&state=…`（**在 `.z.ai` 域内**） |
| BigModel 账号 | `https://bigmodel.cn/login?redirect=…&appId=zcode&state=…`（**不在 `.z.ai` 域内**） |
| redirect_uri | `https://zcode.z.ai/remote/v4`（`webRemoteCallbackUrl`——**登录后回跳远控页本身**） |
| token 交换 | `POST /api/v1/oauth/token`，JSON `{provider:"zai"|"bigmodel", code, redirect_uri}` |
| 回调参数 | `state`（必填，base64 JSON，含 `app_return_to`）+ `code`（或 `authCode`）+ `error` |

完整用户流程（v1.1 按真实链接实测更正）：扫码打开 `/remote/v4?sid=…&mid=…` → 未登录时**页内渲染登录中转页**（URL 不变，`document.title` = `ZCode - Web Remote Control Sign In`（JS 写死不随语言），页面明示「请选择与电脑端相同的登录平台，并使用同一账号」，两按钮「连接 Z.ai（全球）/ 连接 BigModel（中国）」）→ 选定后跳 IdP 认证 → **携带 `code/state` 重定向回 `/remote/v4`**（这就是「返回一次校验」）→ 页面换 JWT → WS 配对进入会话。任一步失败 → 「校验失败 / 手机连接已失效」终态页（含「重新连接」按钮 = `location.reload()`）；桌面端未开启远控等待时为 ~30s 配对超时，同入失败页。

### 2.3 前端 UI 与协议变化

- 新远控页是**全新 Vite + React SPA**（`/remote/v4/latest/assets/`），Tailwind 样式体系，非老页面换皮。
- **`v4-*` data-testid 族基本消失**：全 bundle 仅存 `chat-input`、`chat-send-button`、`v4-composer-plan-marker`、`v4-conversation-telemetry`、`v4-session-quota-banner`、`v4-subagent-readonly-error` 六个可读锚点；消息区改用 `data-section-id` + `data-status`（`loading`/`error`/`empty`）模式，其余 testid 为压缩常量（真实运行值待实机取证，§5-#4）。composer 仍是 **Lexical**（`data-e2e-lexical-bridge`），三期先导的门控前提不变。
- 页面**自带移动端适配与自愈逻辑**：监听 `visibilitychange/pageshow/online/resume`，带 `recoverConnection()/recoverInBackground()/markDegraded()` 降级恢复链——与 App 的 `_alive` 谎报可见性可能互相干扰（§5-#6 实测定夺）。
- 另有第二形态 `?remoteControlToken=…&relayOrigin=…`（Windows「Web 远程控制」流式模式，走 REST `/api/remote-control/platform/*`），入口路由优先级低于 relay 参数分支；**本方案不覆盖该形态**（待验证 §5-#9，桌面若生成该类二维码则行为未知）。

### 2.4 新旧对照

| 维度 | 旧版（App 现有假设） | 新版（实证） |
|---|---|---|
| 凭证 | 链接即凭证，长期有效 | 账号 JWT（服务端按 userId 绑定设备）；链接不含密钥 |
| 登录 | 无 | OAuth 必选，账号须与桌面一致（官方中转页明示） |
| 链接 | `zcode.z.ai/remote?mid=…` | `zcode.z.ai/remote/v4?sid=…&mid=…&name=…&app_version=…` |
| 会话入口 | 打开即用 | 登录 → 回跳校验 → WS 配对（30s 超时） |
| UI 锚点 | `v4-*` 族全量存在 | 仅 6 个可读 testid 存活，其余换新 |
| 断连恢复 | 靠 `_alive` 谎报可见性 | 页面自带 visibility 感知 + 降级恢复链 |

---

## 3. 冲击面清单

### 3.1 原生层

| # | 位置 | 冲击 | 严重度 |
|---|---|---|---|
| N1 | `WebActivity.kt:3774-3796` `shouldOverrideUrlLoading` | 白名单只放行 `*.z.ai`；BigModel 登录跳 `bigmodel.cn` 被踢到系统浏览器，**WebView 内永远完成不了 BigModel 登录**（浏览器里的登录态与 WebView Cookie 不互通，回跳发生在浏览器里，App 内仍是未登录死循环） | 高 |
| N2 | `WebActivity.kt:3803-3806` `onPageFinished` 无条件注入 | 登录页（`chat.z.ai/auth`、`bigmodel.cn/login`）、404 页、校验失败页全被注入——02_style 全局 CSS/06_composer 对 Lexical 输入框的改造会**干扰登录表单**与失败页观感 | 高 |
| N3 | `EntryActivity.kt:318-324` autoResume + `RecentStore` + 小部件/磁贴/通知深链 | 全部指向旧格式链接 → 404 或校验失败页；`btnRetry`（`WebActivity.kt:3729-3732`）对死链 reload = 死循环 | 高 |
| N4 | `WebActivity` 无连接状态感知 | 登录中/配对中/校验失败/会话失效对原生不可见——状态条 unknown 自隐（不误报），但用户面对裸页面无引导 | 中 |
| N5 | `KeepAliveService` + `_alive` 谎报可见性 | 页面自带的 visibility 感知重连逻辑与谎报可能冲突（degraded 判定、恢复触发点变化）——行为未知，待实机 | 中 |
| N6 | `EntryActivity.kt:419-421` `looksLikeRemoteLink` | 旧格式链接仍判合法并入库；同 mid 新链接能否按既有 `sameSession` 合并待验证（`mid` 参数名未变，理论兼容） | 低 |

### 3.2 注入层（`dev/bundle/`）

| # | 模块 | 失效锚点 | 后果 |
|---|---|---|---|
| J1 | `09_agent.js:35-45` | `RUN_SEL`(`v4-stop`,`chat-loading`)/`ROW_SEL`(`v4-row`)/`REASON_SEL`(`chat-reasoning`)/`DOCK_SEL`/`POPUP_SEL`(`data-elicitation-dialog-card` 族) 全失锚 | 状态机停 `unknown`（自隐不误报）：状态条/光点/芯片/等待确认通知/小部件状态**全部沉默** |
| J2 | `06_composer.js` | `v4-composer`/`v4-composer-input`/dock/弹窗族失锚 | 悬浮输入、征询弹窗卡、发送收纳失效；**回复监听（`onReply` 管线）失效 → 回复通知/未读/保活预览/小部件摘要数据源全断** |
| J3 | `05_nav.js` | `v4-row` 行收集失效 | 问题导航不可用 |
| J4 | `04_theme.js` | 取色锚点/深浅判定基于老 DOM | 系统栏跟随可能失准（`zai-light/zai-dark` 为新主题值，待取证） |
| J5 | `02_style.js`/`03_fix.js` | 布局修正针对老布局 | 新 UI 自带移动适配，旧修正可能**反向破坏**（需逐条重评，宁删勿留） |
| J6 | `07_settings.js` | `v4-stop`/`chat-reasoning`/`v4-feedback-*` | 运行态检测、设置页探针失效 |

### 3.3 系统集成（二期成果）

回复通知、ConfirmNotify、未读角标、`SessionWidgetProvider`、`ResumeTileService` 快捷方式——数据源分别是 J1/J2 的选择器与深链目标（N3），**P2 完成前整体退化为「显陈旧值/沉默」**；机制本身（沿检测、去重、通知 ID 段）不需要改，等新锚点供数即可（缺件健全按设计降级，非返工）。

---

## 4. 适配方案（三期）

### 4.0 原则重申（延续 evolution-plan 宪法）

只读观测零 DOM 写入；缺件健全（降级终点 = 本次「页面原生可用、增强全关」）；先取证后写死；原生改动避开 BUNDLE_JS 生成区；新 UI 自带能力优先，注入层只补「页面做不到」的。

### P0 · 兼容热修（约 2-3 人日，独立提交可回滚）

目标：**老 APK 在新版 zcode 下「扫码 → 登录 → 校验 → 连上 → 正常收发」全链路走通**，死链不再死循环。不动任何选择器。

1. **白名单放行登录域**（修 N1）：`WebActivity.kt:3782` 的 `isZai` 扩为 `z.ai || bigmodel.cn`（含子域）。理由：BigModel 与 Z.ai 同为智谱主体，桌面端若用 BigModel 账号登录，手机端**必须**用同一账号体系（§2.1 账号绑定），不放行即死路。同步更新 `SECURITY.md` 域名清单与 `README` 安全节。降级出口：无（纯放行，不注入）。
2. **注入门控**（修 N2）：`injectMobileFix()` 前按 URL path 判断——仅 `path.startsWith("/remote/")` 注入；登录页/OAuth 回调中转/404 一律不注入。第二道门（页面内会话就绪信号）留 P2 取证后加，P0 先 path 单门。注意 `onPageFinished` 对 SPA 同页跳转不重触发，登录**回跳**到 `/remote/v4` 时若恰好触发过 `onPageFinished`（完整导航会触发）则正常注入；纯 SPA 内路由的注入时机问题归 P1（§4.2-2）。
3. **死链/失败页原生兜底**（修 N3/N4）：新增原生只读探测——`onPageFinished` 后 `evaluateJavascript` 查失败页特征（`document.title` 与「校验失败/手机连接已失效」文案，或失败 state 的 `invalid-mobile-connection`，取证后写死 §5-#3），命中则显示原生 banner（复用 `agentStrip` 同层的顶部条/卡片姿势，零 DOM 写入）：「链接已失效 · 请回桌面端重新扫码」+「返回入口」按钮；此态下 `btnRetry` 改为返回入口页而非 reload 死链。
4. **入口页登录引导卡**（承接 §2.1 账号绑定结论）：`activity_entry` 引导卡族（电池/通知同构）加一张：「新版远控需登录，账号须与桌面端一致（Z.ai 或 BigModel），登录一次后本应用内记住」。
5. **文案与文档**：入口页副题「链接即连」→「扫码连接 · 登录一次」；`README` 功能/安全节、`SECURITY.md` 凭证模型（链接 = 短时效配对凭证 + 账号登录）更新；`CHANGELOG` 记 v85± + **选择器依赖清单**沿用惯例追加新版失效记录。
6. **RecentStore 不动 schema**：旧链接保留（死链有 #3 兜底）；同 mid 合并逻辑按 §5-#5 验证后决定是否微调。

**P0 验收**：真机全链路（扫码 → 登录[两账号体系各一次] → 回跳校验 → 配对 → 收发消息）通过；登录页/失败页无注入痕迹（样式未被改写）；死链进入显示原生 banner 且「返回入口」可达；`_validate.py` 全过；设置键全关时行为与本版一致（无新增设置键）。
**P0 回滚**：三个独立提交（白名单+门控 / 兜底 banner / 引导卡+文案），`git revert` 单项即回 v84。

### P1 · 连接生命周期（约 2-3 人日，与 P2 取证并行）

目标：原生侧「看见」连接状态机，死链入口全链路优雅降级，保活策略实测落定。

1. **连接状态上行（新桥事件，只读）**：复用注入层既有 SPA 路由钩子（`WebActivity.kt:1476` 起 v79 骨架屏的 history 包装）+ 轻轮询，判定 `login|auth_callback|pairing|failed|session|unknown` 六态，经 `zcodeBridge.onConnState(state)`（B2 同款判空先例）上报原生；消费端：状态条新映射（login/failed = amber、其余按既有语义）+ 诊断埋点 `conn*` 入 `getTrace`。零 DOM 写入，`connStateOn` 设置键门控，缺桥判空。
2. **死链入口全链路降级**：小部件/磁贴/通知深链进死链 → 走 #P0-3 banner；`RecentStore` 台面卡对「上次连接失败」的会话显示弱化标记（读 P1-1 落盘的失败态，宁缺勿错）。
3. **保活策略实测**（§5-#6）：三种姿态实机对比——现状（谎报+FGS）/ 仅 FGS 不谎报 / 页面自愈不动——按「后台 30 分钟消息到达率 + 回前台恢复时延」判据定版；若页面自愈可靠，`_alive` 拦截降为设置键默认关（保留逃生门）。
4. **登录过期路径**：实测 JWT 过期/`4011 SessionExpired` 场景下页面行为（静默续期还是跳登录），确认 WebView 内免打扰恢复；不行则在 `onConnState('login')` 时 Toast 提示「登录已过期，请重新登录」。

**P1 验收**：六态在真机各触发 ≥1 次且原生呈现正确；后台保活窗口（≥2 小时）消息到达率不低于改版前基线；深链死链 4 入口（widget/tile/shortcut/notification）全部落 banner 不裸奔。
**P1 回滚**：`connStateOn` 关 + `_alive` 开关回默认 = 行为回 P0。

### P2 · 注入层 v2（约 5-8 人日，取证门控，宁分期不发版）

目标：按新 UI 锚点重建选择器层，恢复 App 增强能力。**先导取证不过则整期不发，App 停在 P1 形态（页面原生可用）**。

0. **先导取证（门）**：`chrome://inspect`（debug 构建既有通道）挂真机会话，产出**新版选择器清单**（§5-#4）：消息行/角色区分（替代 `group/assistant-row`）、流式中/思考中/行完成特征（`data-status` 族与压缩 testid 运行值）、composer dock、确认弹窗族、错误行、主题锚点。**通过判据**：每个旧锚点都有新替代或明确判定「页面已原生覆盖、无需注入」；任一关键链路（回复监听/运行态）无替代 → 缩期。
1. **回复监听重做**（J2 核心）：新锚点上重建 `onReply` 管线——通知/未读/保活预览/小部件摘要全链路恢复；`ReplyDedupe` 不动。
2. **09_agent v2**：按新清单重写 `RUN_SEL/ROW_SEL/REASON_SEL/DOCK_SEL/POPUP_SEL`，沿用「自建判定、只报变化沿、unknown 自隐、选择器出处注释」既有纪律；error 族一并取证（旧 §7-#1 欠账）。
3. **05_nav/06_composer/07_settings 重锚**：逐模块替换选择器；02/03 的布局修正按「新 UI 已覆盖即删」原则裁剪（宁删勿留，防反向破坏 J5）。
4. **04_theme 重锚定**：新主题值 `zai-light/zai-dark` 纳入深浅判定与取色采样点。
5. **发送链路先导**（三期 Lexical 写入门控的既有欠账）：新 UI 仍是 Lexical（`data-e2e-lexical-bridge` 实证），三期方案继续有效，但三路线取证须在新 DOM 上重做。

**P2 验收**：usbd_diag 回归脚本化（发送/确认/失败三链路）全过；选择器清单入 `CHANGELOG`；全部新设置键关断后与 P1 行为一致；回复通知真机端到端到达且去重 0 误报。
**P2 回滚**：按模块独立提交；`agentStateOn/turnNav/floatInput/notifyReply` 全关 = 回 P1 形态。

---

## 5. 待验证清单（全部先取证后写死）

1. ~~**真实二维码内容**~~ **已完成（v1.1，2026-10-20）**：真实 QR = `?sid=d_…&mid=<uuid>&name=HUI&app_version=3.16.2`（cv2 解码 + 页面行为双验证，无 `hash`/`t`、无 `theme`）；同设备重复扫码是否更换 `sid` 待对比（桌面 asar 证据指向持久设备标识，预期不变）。遗留：WS 握手 `passHash` 的实际来源（登录后服务端下发/派生）。
2. **BigModel 登录在 WebView 的可用性**：`bigmodel.cn/login` 的验证码/滑块/短信组件在 Android WebView 的兼容性；不可用则登录引导卡须写明「建议桌面与手机都用 Z.ai 账号」。
3. **失败页 DOM 特征**：校验失败页稳定锚点（文案/testid/state）——P0-3 banner 的判定依据。
4. **新 UI 选择器全清单**（P2 门）：见 §4.3-0；含 `xo(Bi, sectionId)` 压缩 testid 的运行时真实值。
5. **同 mid 合并**：新版链接（mid 参数名未变）在 `RecentStore.sameSession` 下的合并行为；同设备反复扫码产生的死链清理策略。
6. **`_alive` 谎报 vs 页面自愈**：三姿态对比实验（§4.2-3）。
7. **登录态持久性**：JWT/Cookie 在 `CookieManager` 跨进程重启、跨天的存活；「登录一次后免登」的真实有效期。
8. **`theme` 参数**：`zai-light/zai-dark` 与新 UI 深浅模式、04_theme 采样锚点的关系。
9. **`remoteControlToken` 形态**：桌面是否会在手机场景生成该类二维码（§2.3 末）；若会，P1 内补一条入口分流。

---

## 6. 风险表

| 风险 | 等级 | 缓解 |
|---|---|---|
| BigModel 登录组件在 WebView 不可用（验证码/风控） | 高 | P0 前实测（§5-#2）；引导文案兜底「用 Z.ai 账号」；白名单放行与引导卡同一提交可回滚 |
| 注入门控误伤（SPA 回跳不触发 `onPageFinished` 致漏注入） | 中 | 完整导航（OAuth 重定向）必触发；纯 SPA 场景由 P1-1 路由钩子补注入口（守卫式二次注入，bundle 防重入已有 `__zcodeDone` 语义） |
| 选择器取证窗口不可控（需真实会话自然触发各态） | 中 | 沿用「日常使用挂 usb_diag 待触发」并行法；P2 门控不过即不发，P0/P1 不受拖累 |
| `_alive` 与页面恢复链互相干扰致后台断连 | 中 | P1-3 实测定版；最坏姿态 = 仅 FGS 不谎报，页面自愈 |
| 存量用户升级后所有深链失效引发「坏了」观感 | 中 | P0-3 banner + 引导卡文案一次说清；CHANGELOG 注明 |
| `remoteControlToken` 形态混入（行为未知） | 低 | §5-#9 取证；入口分流兜底 |

---

## 附录 A · 证据记录（本会话执行）

**A1 官网实测**（Playwright 实访）：`https://zcode.z.ai/remote` → 302 `/cn/remote` → **404**；`https://zcode.z.ai` → `ZCode | GLM-5.3 官方 Harness`（Web 版主站，登录按钮）；`/login?redirectPath=%2Fcn` → 两按钮「使用 BigModel 账号登录 / 使用 Z.ai 账号登录」；点 BigModel → `bigmodel.cn/login?redirect=…%2Fzcode.z.ai%2Flogin&appId=zcode&state=eyJub25jZSI6…`（state 为 base64 JSON 含 `app_return_to`/`redirect_uri`）；点 Z.ai → `chat.z.ai/auth?response_type=code&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&redirect_uri=https%3A%2F%2Fzcode.z.ai%2Flogin…`；`https://zcode.z.ai/remote/v4`（无参数直访）→ 「校验失败 / 手机连接已失效 / Missing or invalid Web remote control relay parameters / 不要复用旧截图或旧链接 / 重新连接」失败页；changelog `Release v3.15.3` 含「优化手机远控服务」。

**A2 桌面端 asar 反编译**（本机 `D:\Program Files\ZCode` v3.16.1，`resources/app.asar` 字符串上下文提取）：`buildZCodeEndpointUrls`（`remoteUrl:${r}/remote/${c}`、`webRemoteCallbackUrl:${r}/remote/v4`、`webShareCallbackUrl`、`relayWsUrl:${i}/api/v1/remote/ws2`、`/api/v1/oauth/token`）；`buildWebRemoteControlExternalQrUrl`（`set("sid",n)`、`delete("hash")`、`delete("t")`、`set("mid",r)`、`set("name",a)`、`set("app_version",o)`）；`getLogin`（`{userId, jwt}`，无则 `auth-required`）；设置 schema `webRemoteControlRelayV2Device{deviceSid,userId,endpointOrigin}`；auth-identity-changed 踢不匹配连接；`web-remote-control:external-relay:pass_hash` 为 legacy 清理项。

**A3 新版远控页 bundle 反编译**（`/remote/v4/latest/assets/` 公开静态资源，curl 下载后 grep）：`pO`（`src-wmk2orCZ.js`：`sid`/`hash`/`t` 必填 → `{deviceSid,passHash,timestamp,deviceMid?,deviceName?,appVersion?,theme?}`）；`lO`（URLSearchParams.get）；WS 握手（`auth_init`/`auth_challenge`/`auth_response`→`calculateProof`=HMAC-SHA256(passHash,`${nonce}|terminal|${device_sid}`)；错误码 `KICKED/AUTH_FAILED/WRONG_PARAM/INTERNAL/DEVICE_OFFLINE`；close codes 4004/4009/4010/4011/4012/4013）；OAuth 客户端（`authorizeUrl`=Z.ai、`bigmodelAuthorizeUrl`、`clientId=client_P8X5CMWmlaRO9gyO-KSqtg`、`redirectUri=webRemoteCallbackUrl`、`POST /api/v1/oauth/token {provider,code,redirect_uri}`、callback 参数 `state`+`code|authCode`+`error`）；入口路由 `K4t`（失败页 → relay 分支 → remoteControlToken 分支 → 恢复缓存会话 → 本地 dev）；`v4-*` testid 仅存 6 个可读值；Lexical composer（`data-e2e-lexical-bridge`）；页面自愈（visibility/pageshow/online/resume → `recoverConnection/recoverInBackground/markDegraded`）。

**A4 本仓实读**：`WebActivity.kt:3774-3796`（白名单）、`:3803-3806`（onPageFinished 注入）、`:3729-3732`（btnRetry reload）、`:3856-3886`（injectMobileFix）、`:1476`（v79 SPA 路由钩子先例）；`EntryActivity.kt:318-324`（autoResume）、`:419-421`（looksLikeRemoteLink）、`:445-457`（launchWeb）；`09_agent.js:35-45`（四组选择器 + POPUP_SEL）；`RecentStore.kt`（sameSession 按 mid 合并）。

**A5 未做（如实声明）**：未真机扫码（§5-#1 待办）；未登录任何账号（无凭据且不代用户登录）；未构建 APK；各期验收标准为「到时执行」。

---

## v1.1 修订记录（2026-10-20，真实二维码实测后更正）

维护者提供了真实二维码截图与链接（桌面端 3.16.2，`name=HUI`），cv2 解码与页面行为实测推翻初版两处推断，并实锤两处结论：

1. **更正**：「`sid`/`hash`/`t` 三者必填」不成立——真实链接只含 `sid/mid/name/app_version`；bundle 里的 `pO` 解析器属旧格式/其他分支。凭证模型改写为「账号 JWT + 服务端设备绑定」，链接不再承载密钥（§2.1/§2.4 已就地改写）。
2. **更正**：登录墙不是先跳 IdP——`/remote/v4` 先在**同页**渲染登录中转页（title `ZCode - Web Remote Control Sign In`，写死不随语言），官方页面明示账号须与桌面端一致（§2.2 已改写）。该 title 特征同时成为 App 注入门控与登录提示的可靠锚点。
3. **实锤（维护者确认）**：N1——App 内点击 BigModel 登录被踢到系统浏览器，登录完成后不会回到 App（Cookie 不互通），「只会往浏览器跳，并不会回传任何信息到 app」；P0-1 白名单放行 `bigmodel.cn` 即为此而设。
4. **P0 已落地**（versionCode 48 / versionName 0.0.7）：白名单放行 `bigmodel.cn`；注入门控（登录中转页不注入 + Toast 提示一次）；失效/404 页原生兜底（复用 errorView，重试/回入口）；入口页静态登录引导卡；README/SECURITY/CHANGELOG 同步。
5. **新知**：`sid` 为持久设备标识 → 新格式链接在设备注册存续期间可复用（仍需桌面端等待 + 已登录）；autoResume/最近会话对**新格式**链接依然有效，死链主体是旧格式 404 与「桌面端未等待」。P1-2 的深链降级与 P0-3 兜底继续有效。
6. **v1.2 真机返修（2026-10-20）**：真机实证登录/配对全链路已通（P0 目标达成），但暴露接管误报——「连接上以后弹原生兜底」。§4.1-3 的失败探测从「body 全文一拍接管」收紧为「h1 精确文案 + 连续两拍确认 + 接管后复检自动撤下」，其余失败屏（桌面端已离线等）不接管。
