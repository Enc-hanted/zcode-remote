# Changelog

本项目的所有重要变更记录在此。版本号遵循语义化版本（受早期开发阶段约束，主版本固定为 0）。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [未发布] — v81 工作区（bundleVer 58）

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
