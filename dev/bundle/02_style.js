// ===== 02_style.js (67-228) =====
    // ---------- 动效与输入框劫持样式（毛玻璃、去蓝、渐入动画） ----------
    var fxStyle = null;
    // anim() 生成 animation 值：开 = 指定动画；关 = none（动效开关 + 系统减弱动效双门控）
    function anim(name, dur, ease){
      return (ANIM_ON && !REDUCED) ? ('animation:' + name + ' ' + dur + (ease ? ' ' + ease : '') + ';') : '';
    }
    function ensureFxStyle(){
      if (fxStyle && fxStyle.parentNode) { return; }
      fxStyle = doc.createElement('style');
      fxStyle.id = 'zcode-fx';
      fxStyle.textContent =
        // 设计 token：注入 UI 共用一套颜色/圆角（黑灰系，无蓝调；对齐原生 colors-night.xml）
        // z-index 阶梯：999 真导航解锁 / 99995 遮罩 / 99996 原生导航解锁态 / 99997 面板 / 99998 悬浮输入+预览 / 99999 全屏输入+提示+诊断
        ':root, :host{--zc-bg:rgba(30,30,30,0.85);--zc-bg-strong:rgba(26,26,26,0.94);' +
        '--zc-bg-deep:rgba(22,22,22,0.92);--zc-hint-bg:rgba(20,20,20,0.94);' +
        '--zc-stroke:rgba(255,255,255,0.14);--zc-stroke-faint:rgba(255,255,255,0.08);--zc-stroke-strong:rgba(255,255,255,0.20);' +
        '--zc-text:#F2F2F2;--zc-text-2:#FAFAFA;--zc-text-dim:#CFCFCF;--zc-text-3:#909090;' +
        '--zc-hover:rgba(255,255,255,0.08);--zc-hover-strong:rgba(255,255,255,0.12);--zc-outline:rgba(255,255,255,0.85);' +
        '--zc-radius:12px;--zc-radius-lg:16px;--zc-radius-xl:22px;}' +
        // 浅色主题：页面检测为浅色时 JS 给 <html> 挂 zcode-ui-light，整套 token 换白底黑字（04_theme.js reportTheme 驱动）
        'html.zcode-ui-light{' +
        '--zc-bg:rgba(252,253,255,0.92);--zc-bg-strong:rgba(255,255,255,0.97);' +
        '--zc-bg-deep:rgba(255,255,255,0.96);--zc-hint-bg:rgba(255,255,255,0.97);' +
        '--zc-stroke:rgba(10,18,34,0.12);--zc-stroke-faint:rgba(10,18,34,0.06);--zc-stroke-strong:rgba(10,18,34,0.18);' +
        '--zc-text:#1B2230;--zc-text-2:#0F1522;--zc-text-dim:#3A4356;--zc-text-3:#8A93A6;' +
        '--zc-hover:rgba(10,18,34,0.06);--zc-hover-strong:rgba(10,18,34,0.10);--zc-outline:rgba(20,35,60,0.80);' +
        'color-scheme:light;}' +
        '@keyframes zcodeFadeUp{from{opacity:0;transform:translateX(-50%) translateY(16px)}to{opacity:1;transform:translateX(-50%)}}' +
        '@keyframes zcodeFadeUpNoX{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}' +
        '@keyframes zcodeFadeIn{from{opacity:0}to{opacity:1}}' +
        '@keyframes zcodeFadeOut{from{opacity:1}to{opacity:0}}' +
        '@keyframes zcodeFadeDown{from{opacity:1;transform:translateX(-50%) translateY(0)}to{opacity:0;transform:translateX(-50%) translateY(14px)}}' +
        '@keyframes zcodeFadeDownNoX{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(14px)}}' +
        '@keyframes zcodeFadePanel{from{opacity:0;transform:translateX(-50%) translateY(10px)}to{opacity:1;transform:translateX(-50%)}}' +
        '@keyframes zcodeFadePanelOut{from{opacity:1;transform:translateX(-50%) translateY(0)}to{opacity:0;transform:translateX(-50%) translateY(10px)}}' +
        '@keyframes zcodeFadeHint{from{opacity:0;transform:translateX(-50%) translateY(8px) scale(0.96)}to{opacity:1;transform:translateX(-50%)}}' +
        // 浮动输入（v65 图标化）：有消息未唤出时不再把整个 dock display:none——
        // 新版(3.14.4)页面给 composer 让位的 padding 挂在 timeline 内容列等节点上，
        // dock 一消失让位空间就失去锚点，底部留大块空白（真机实测+线上 bundle 取证）。
        // 改为：只在"未唤出且无弹窗"态收起 v4-composer 子树，由注入的 #zcode-composer-icon
        // （44px 圆钮，左下角）撑起 dock——dock 保持流内 sticky，页面/虚拟列表的预留逻辑照常工作。
        // 悬浮态/全屏态不变：dock 只负责"定位 + 抬起"，页面原生 .rounded-2xl 输入胶囊
        // （自带 bg+border+圆角）作为唯一视觉框架。z.ai 的 ask/确认弹窗渲染在 dock 内 →
        // 弹窗出现时临时显示 dock（zcode-popup-mode），只露弹窗、藏输入与图标，消失后回收纳态。
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode) [data-testid="v4-composer"]{display:none!important}' +
        // 收纳态零占位（v66）：dock 压到 0 高、content 边距清零——消息列表直接铺到屏幕底部，
        // 不再留 60px 空带；图标改悬浮（见下），页面自己的让位计算随 dock 高度归零。
        // 注意 :not(.zcode-popup-mode)：弹窗态 dock 要正常尺寸
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode){height:0!important;min-height:0!important;' +
        'padding:0!important;overflow:visible!important}' +
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode) [data-v4-composer-dock-content]{padding:0!important}' +
        // 收纳图标：悬浮在左下角内容之上（fixed，不属于任何流），黑灰毛玻璃小圆钮。
        // 右滑开导航（配合原生手势豁免）；与输入框的切换走容器变换（morphReveal/morphMinimize）
        // v70：bottom 抬升底部安全区（边到边后不压系统手势条）；
        // touch-action:none——实机右滑/上滑完全没反应的元凶：默认 auto 下浏览器在 ~10px
        // slop 后就把手势流抢去滚动/ overscroll（touchcancel），跟手反馈与 48px 阈值全被掐死；
        // 44px 圆钮自身没有任何可滚动内容，关掉浏览器手势处理零代价
        '#zcode-composer-icon{position:fixed;left:12px;bottom:calc(12px + var(--zc-safe-b, 0px));z-index:99996;margin:0;' +
        'display:flex;align-items:center;justify-content:center;' +
        'width:44px;height:44px;border-radius:22px;background:var(--zc-bg-strong);' +
        '-webkit-backdrop-filter:blur(18px) saturate(1.4);backdrop-filter:blur(18px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke-strong);color:var(--zc-text-2);font-size:19px;line-height:1;' +
        'box-shadow:0 4px 18px rgba(0,0,0,0.35);cursor:pointer;user-select:none;-webkit-user-select:none;' +
        'touch-action:none;' +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.15s,transform 0.12s;' : '') +
        '}' +
        '#zcode-composer-icon:active{opacity:0.7;' + (ANIM_ON && !REDUCED ? 'transform:scale(0.92);' : '') + '}' +
        // 唤出态/全屏态/弹窗态藏图标（JS 侧同步 display，这里是样式兜底）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-present #zcode-composer-icon{display:none!important}' +
        // 清除底部残留占位（v61）：dock 隐藏后，消息列表 section 的 pb-5 仍留 20px 底部留白。
        // 实测 dock 自身用 display:none 已不占位，但 section 的 padding-bottom 与输入框常驻时
        // 配合是合理的（避免最后一条消息贴着输入框），dock 隐藏后这段就成了多余占位，清除。
        'html.zcode-float-on [data-testid="v4-timeline"] section{padding-bottom:0!important}' +
        // 底部渐隐遮罩摘除（v74，实机 CDP 取证）：z.ai 给时间线内容列挂了跟随滚动的 CSS mask——
        // mask-image: linear-gradient(black 0, black 566px, transparent 590px, …100%);
        // mask-position: 0px <scrollTop>px; mask-size: 100% <视口高>px
        // 可见区底部 ~120px 恒定渐隐成空白；惯性滚动后 mask-position 与 scrollTop 脱同步时
        // 透明带更大（实机照片实测 190px）——这就是"上滚后底部空白占位"的真身。
        // DOM 探针全被它骗过：hit-test/innerText 都无视 mask，probe 报"有内容"但屏幕是白的。
        // 收纳态（float-on）dock 已隐藏，这层渐隐只剩空白，直接摘掉让消息画到底边；
        // 原生 dock 态（float off）保留 z.ai 原设计（渐隐用于和输入框过渡）。
        // [style*="mask-position"] 属性选择器：z.ai 卸掉 mask 时属性串消失即不匹配，自门控。
        'html.zcode-float-on [data-testid="v4-timeline"] [style*="mask-position"],' +
        'html.zcode-float-on [data-testid="v4-timeline-scroll"] [style*="mask-position"]' +
        '{-webkit-mask-image:none!important;mask-image:none!important}' +
        // v80⑦：z.ai 原生"滚动到底部"圆钮避让系统手势条（实机诊断 dockHtml/popupSnap 取证：
        // v4-timeline-bottom 挂在 dock 内 data-v4-back-to-bottom-anchor 锚上，absolute/
        // bottom-full/mb-2 悬在 0 高 dock 上方，收纳态离屏底仅 ~8px 压小白条）。transform
        // 整体重写并复刻它自带的 -translate-x-1/2 居中：抬高 安全区+6px（原 8px 底距 +
        // 抬升量 ≥ safeB+10 的避让口径）。safeB=0（桌面/旧壳）时只抬 6px，无感
        'html.zcode-float-on [data-testid="v4-timeline-bottom"]' +
        '{transform:translate(-50%, calc(-6px - var(--zc-safe-b, 0px)))!important}' +
        // v78 让位垫过渡：胶囊唤出/收纳时 tl 的 padding-bottom 变化不再是瞬时跳变，
        // 而是短滑过渡——垫的写入时机已挪到 morph 落位之后（06_composer），配合这条
        // 过渡整个"消息列表为胶囊让位"的动作是连续的。仅收纳态生效，不干扰 z.ai
        // 原生模式的任何 padding 行为；过渡期间 scrollHeight 逐帧变化由 v75 静止闸门兜住。
        'html.zcode-float-on [data-testid="v4-timeline"],' +
        'html.zcode-float-on [data-testid="v4-timeline-scroll"]' +
        '{transition:padding-bottom 0.22s cubic-bezier(0.2,0.8,0.2,1)}' +
        // v78 上拉手柄：胶囊上内缘（右上角）低可视小横条——提示"可上拉展开半屏输入"。
        // 挂 dock（fixed=定位锚）的 ::after 伪元素：样式表注入，React 重渲染抹不掉；
        // 不碰 z.ai 的胶囊本体（不改它的 position，零回归面）。点按无感（pointer-events
        // none），拖动展开的手势本来就由胶囊整体的纵向 dockGesture 承担。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float::after{content:"";' +
        'position:absolute;top:3px;right:10px;width:26px;height:3px;border-radius:2px;' +
        'background:var(--zc-text-3);opacity:0.18;pointer-events:none}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float:active::after{opacity:0.4}' +
        // v78 morph 幽灵替身基样式（动画值由 JS 内联写入）：真实胶囊飞行期间隐藏，
        // 替身用尺寸动画飞行——scale 非均匀缩放会把圆角与描边压成椭圆（"中途丑陋
        // 椭圆帧"的来源），尺寸动画几何全程正确
        '.zcode-morph-ghost{position:fixed;z-index:99999;pointer-events:none;box-sizing:border-box}' +
        // v79⑤ 会话切换骨架屏：路由切换 → 整列表卸载重建（实机 ~1s 主线程冻结），冻结期
        // 铺一层与页面底色一致的微光条，首条 v4-row 渲染即淡出（05_nav route watch 驱动）。
        // 淡入 140ms：快速切换时骨架几乎不可见；pointer-events:none 不挡任何点击
        '#zcode-skeleton{position:fixed;inset:0;z-index:99990;pointer-events:none;opacity:0;transition:opacity 0.14s ease-out}' +
        '#zcode-skeleton.zc-sk-in{opacity:1}' +
        '#zcode-skeleton.zc-sk-out{opacity:0;transition:opacity 0.18s ease-in}' +
        '.zc-sk-bar{position:absolute;height:13px;border-radius:7px;' +
        'background:linear-gradient(90deg,rgba(255,255,255,0.04),rgba(255,255,255,0.10),rgba(255,255,255,0.04));' +
        'background-size:200% 100%;animation:zcodeSkShimmer 1.3s linear infinite}' +
        'html.zcode-ui-light .zc-sk-bar{background:linear-gradient(90deg,rgba(10,18,34,0.05),rgba(10,18,34,0.12),rgba(10,18,34,0.05));background-size:200% 100%}' +
        '@keyframes zcodeSkShimmer{from{background-position:200% 0}to{background-position:-200% 0}}' +
        // v79⑥ 流式阅读位置线：流式中上滑离开底部，在离开时刻的内容底边插一条低可视
        // 细线（"新内容都在线下方"），滚回底部/流式结束淡出。挂在内容流里（行后插节点），
        // 内容只在尾部追加，锚点稳定；行被虚拟化卸载则顺手收线
        '.zc-readline{position:relative;height:2px;margin:10px 2px;border-radius:1px;' +
        'background:linear-gradient(90deg,transparent,var(--zc-stroke-strong) 12%,var(--zc-stroke-strong) 88%,transparent);' +
        'opacity:0.7;pointer-events:none;transition:opacity 0.4s ease}' +
        '.zc-readline.zc-rl-out{opacity:0}' +
        '.zc-readline i{position:absolute;right:2px;top:-15px;font-style:normal;font-size:10px;' +
        'color:var(--zc-text-3);opacity:0.75;letter-spacing:1px}' +
        // v79⑨ AMOLED 纯黑（实验开关）：注入层 token 换纯黑系；页面侧 best-effort
        //（body/timeline 底面），z.ai 气泡/代码块等具体表面色需实机调色（HANDOFF 待办）
        'html.zc-amoled{--zc-bg:rgba(0,0,0,0.86);--zc-bg-strong:rgba(0,0,0,0.92);--zc-bg-deep:rgba(0,0,0,0.96);' +
        '--zc-hint-bg:rgba(0,0,0,0.95);--zc-stroke:rgba(255,255,255,0.10);--zc-stroke-faint:rgba(255,255,255,0.05);' +
        '--zc-stroke-strong:rgba(255,255,255,0.16)}' +
        'html.zc-amoled body,html.zc-amoled [data-testid="v4-timeline"],html.zc-amoled [data-testid="v4-timeline-scroll"]' +
        '{background-color:#000!important}' +
        // v79③ 键盘跟随·实验：WebView 不随 IME 缩放（原生 exp 模式不垫 ime padding，页面
        // 零重排、虚拟列表不再逐帧重算），键盘起/落各报一次最终高度（__zcKb），JS 换算
        // --zc-kb-lift，胶囊/全屏用 CSS 过渡贴着键盘顶沿升降。掉帧根修的实验路径。
        // 特定性注意：选择器必须比上面的 float/full 基础规则多一级（html.zcode-float-on
        // 前缀），否则同为 (0,3,2) 时基础规则的 transform 会盖掉这里的 translateY
        'html.zcode-float-on.zc-kb-on [data-v4-composer-dock="true"].zcode-composer-float{' +
        'transform:translateX(-50%) translateY(calc(0px - var(--zc-kb-lift, 0px)))!important;' +
        'transition:transform 0.26s cubic-bezier(0.2,0.8,0.2,1)!important}' +
        'html.zcode-float-on.zc-kb-on [data-v4-composer-dock="true"].zcode-composer-full{' +
        'transform:translateY(calc(0px - var(--zc-kb-lift, 0px)))!important;' +
        'transition:transform 0.26s cubic-bezier(0.2,0.8,0.2,1)!important}' +
        // 弹窗模式：dock 临时显示为底部容器（不遮挡全屏），输入部分隐藏，弹窗可见可点
        // 容器变换进行中：压掉 float/full 态的入场 animation，几何交给 JS 的 transform 过渡
        '[data-v4-composer-dock="true"].zcode-composer-morph{animation:none!important}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'display:block!important;visibility:visible!important;pointer-events:auto!important;' +
        'position:fixed!important;left:50%!important;right:auto!important;bottom:var(--zc-safe-b, 0px)!important;top:auto!important;' +
        'transform:translateX(-50%)!important;width:100%!important;max-width:760px!important;height:auto!important;' +
        'padding:0!important;margin:0!important;background:transparent!important;border:none!important;' +
        'box-shadow:none!important;z-index:99998!important;' +
        'overflow:visible!important;' +
        (ANIM_ON && !REDUCED ? 'animation:zcodeFadeIn 0.18s ease-out;' : '') + '}' +
        // 弹窗模式：只露弹窗——只藏 v4-composer 整棵子树（胶囊/输入框/expand 全在它里面）。
        // 注意：绝不能再用 .rounded-2xl 选择器藏胶囊——ask 弹窗卡片自身也带 rounded-2xl 类
        // （诊断 dockHtml 实锤），会被一起藏掉（v58 修：v57 就是这个坑导致弹窗 display:none）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode [data-testid="v4-composer"]' +
        '{display:none!important}' +
        // 弹窗复活说明（v60）：不再给弹窗节点加 !important 可见性——v46 起 dock 用 display:none 隐藏、
        // popup-mode 用 display:block 显示整棵子树，弹窗节点在打开的 dock 里自然可见。
        // 曾经的 visibility:visible!important 会压制页面自身的关闭样式（弹窗点选后关不掉，v60 修）。
        // .zcode-popup-visible 只作检测标记保留，不干预样式。
        // 注意：悬浮/全屏规则必须带 html.zcode-float-on 前缀提升特异性，否则会被上面的隐藏规则压住
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float{' +
        'display:flex!important;visibility:visible!important;pointer-events:auto!important;position:fixed!important;left:50%!important;right:auto!important;' +
        'bottom:calc(14px + var(--zc-safe-b, 0px))!important;top:auto!important;transform:translateX(-50%)!important;' +
        'width:min(94vw,520px)!important;max-width:none!important;height:auto!important;' +
        'z-index:99998!important;pointer-events:auto!important;' +
        'padding:0!important;background:transparent!important;border:none!important;box-shadow:none!important;' +
        anim('zcodeFadeUp', '0.22s', 'cubic-bezier(0.2,0.8,0.2,1)') +
        '}' +
        // 弹窗存在时（含悬浮输入打开态）不显示 expand 按钮：弹窗与输入按钮不应同时出现
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-present #zcode-composer-expand{display:none!important}' +
        // body 级弹窗让位（v53）：z.ai 的 Radix 下拉/菜单 portal 到 body 根部，z-index 仅 z-50，
        // 远低于我们的遮罩（99995）/dock（99998+）。不让位时遮罩吞掉点击、全屏暗层盖住视觉
        // ——"对话框按钮的二级弹窗被盖住"。检测到 body 弹窗（html.zcode-body-popup）时把注入层
        // 全部压到弹窗之下：z-40 高于页面内容(z-20)、低于页面弹窗(z-50)，logo 让到 45。
        'html.zcode-body-popup #zcode-mask{z-index:40!important}' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-float,' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-full,' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'z-index:40!important;' +
        '}' +
        // 全屏暗层会让弹窗"看得见但黑纱罩着"：让位时去掉背景，弹窗完整可见
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-full{background:transparent!important}' +
        // 收起态：淡出下移后由 JS 移除类（避免 display:none 瞬间消失，呆板）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float.zcode-composer-closing{' +
        'pointer-events:none!important;' + anim('zcodeFadeDown', '0.18s', 'ease-in') + '}' +
        // 中和 v4-composer 那层不透明背景块（rgb 22,22,22），否则会和原生气泡叠成双框
        'html.zcode-float-on [data-v4-composer-dock="true"] [data-testid="v4-composer"]{' +
        'background:transparent!important;' +
        '}' +
        // 原生气泡（.rounded-2xl）加一层投影抬起，其余沿用页面自带样式。
        // overflow:visible：胶囊自带 Tailwind overflow-hidden，会把 expand 按钮岔出圆角的外弧裁掉（v52 按钮挂进胶囊后必须放行）
        'html.zcode-float-on [data-v4-composer-dock="true"] [data-testid="v4-composer"] .rounded-2xl{' +
        'box-shadow:0 12px 40px rgba(0,0,0,0.5)!important;overflow:visible!important;' +
        '}' +
        // 全屏态：dock 变全屏遮罩 + 底部居中；气泡放大、加投影
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full{' +
        'display:flex!important;visibility:visible!important;pointer-events:auto!important;position:fixed!important;left:0!important;right:0!important;bottom:0!important;top:0!important;' +
        'transform:none!important;width:100%!important;max-width:none!important;height:100%!important;' +
        'z-index:99999!important;pointer-events:auto!important;align-items:flex-end!important;justify-content:center!important;' +
        'padding:0 0 calc(20px + var(--zc-safe-b, 0px))!important;background:rgba(0,0,0,0.55)!important;border:none!important;' +
        anim('zcodeFadeIn', '0.18s', 'ease-out') +
        '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full.zcode-composer-closing{' +
        'pointer-events:none!important;' + anim('zcodeFadeOut', '0.18s', 'ease-in') + '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid="v4-composer"]{' +
        'width:min(94vw,640px)!important;' +
        '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid="v4-composer"] .rounded-2xl{' +
        'width:min(94vw,640px)!important;box-shadow:0 16px 50px rgba(0,0,0,0.6)!important;' +
        '}' +
        // 全屏关键：输入区放大（页面原生 min-h-10 max-h-40 会把输入框锁死在 40px，
        // 不加这条全屏只是"暗遮罩+底部小输入框"，v37 就是这个坑）。
        // v69（实机"全屏输入框不够大"）：32vh 只占三分之一屏，提到 56vh——输入区是全屏态的主体。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid^="v4-composer-input"]{' +
        'min-height:56vh!important;max-height:none!important;height:56vh!important;font-size:16px!important;' +
        '}' +
        // expand：纯 SVG 圆弧双态按钮（Qwen demo 几何）。SVG 根 pointer-events:none → 方块不挡文字，
        // 命中区 = 弧线两侧 14px 透明粗描边（pointer-events:stroke），只有弧环可点；
        // 外弧 R+4（悬浮态）= 扩大，内弧 R−4（全屏态）= 缩小，同圆心同跨角，opacity/visibility 互斥切换。
        // 尺寸由 positionExpand 按胶囊圆角半径动态计算（JS px，跟随界面缩放），这里只兜底。
        '#zcode-composer-expand{position:absolute;top:0.25em;right:0.25em;width:2.75em;height:2.75em;font-size:13px;' +
        'color:var(--zc-text-dim);z-index:99997;pointer-events:none;}' +
        '#zcode-composer-expand:hover{color:var(--zc-text-2);}' +
        '#zcode-composer-expand svg{width:100%;height:100%;overflow:visible;}' +
        '#zcode-composer-expand .zc-hit{fill:none;stroke:transparent;stroke-width:14;pointer-events:stroke;cursor:pointer;}' +
        '#zcode-composer-expand .zc-line{fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;opacity:0.6;' +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.25s;' : '') + '}' +
        '#zcode-composer-expand:hover .zc-line{opacity:0.9;}' +
        '#zcode-composer-expand .zc-arc{' + (ANIM_ON && !REDUCED ? 'transition:opacity 0.3s,visibility 0.3s;' : '') + '}' +
        // 互斥切换：SVG 的 pointer-events:stroke 写死在 path 上，g 的 visibility:none 都不管用
        // （继承会被 path 自身值覆盖，实测隐藏环仍可命中）→ 必须直接置空隐藏环的命中环；
        // :not(.zc-full) 限定：全屏态下内弧是可见的，命中环必须保留（否则缩回收不回）
        '#zcode-composer-expand .zc-shrink{opacity:0;visibility:hidden;}' +
        '#zcode-composer-expand:not(.zc-full) .zc-shrink .zc-hit{pointer-events:none;}' +
        '#zcode-composer-expand.zc-full .zc-expand{opacity:0;visibility:hidden;}' +
        '#zcode-composer-expand.zc-full .zc-expand .zc-hit{pointer-events:none;}' +
        '#zcode-composer-expand.zc-full .zc-shrink{opacity:1;visibility:visible;}' +
        // 自建导航面板：出现淡入（panel 内联已带 FadePanel），收起淡出下移
        '[aria-label="对话问题导航"].zcode-closing{' + anim('zcodeFadePanelOut', '0.18s', 'ease-in') + '}';
      styleRoot().appendChild(fxStyle);
    }
    ensureFxStyle();
    var lastThemeKey = '', lastThemeR = -1, lastThemeG = -1, lastThemeB = -1;
    var replyTrace = [];   // 回复链路埋点（检测/通知每次尝试都记，诊断时随 JSON 上报）

    function hint(text){
      var old = doc.getElementById('zcode-nav-hint');
      if (old && old.parentNode) { old.parentNode.removeChild(old); }
      var h = doc.createElement('div');
      h.id = 'zcode-nav-hint';
      h.textContent = text;
      // 悬浮输入开着时上移，避免和输入胶囊重叠；v70：再叠加底部安全区（边到边后不压手势条）
      var up = ((doc.documentElement.classList.contains('zcode-float-on') && getDock()) ? 128 : 88) + safeB();
      h.style.cssText = 'position:fixed;left:50%;bottom:' + up + 'px;transform:translateX(-50%);' +
        'background:var(--zc-hint-bg);color:var(--zc-text);font-size:13px;line-height:1.4;padding:11px 18px;' +
        'border-radius:999px;z-index:99999;border:1px solid var(--zc-stroke);box-shadow:0 8px 26px rgba(0,0,0,0.4);' +
        'pointer-events:none;max-width:min(84vw,420px);text-align:center;overflow-wrap:break-word;' +
        'animation:zcodeFadeHint 0.18s ease-out;';
      (isShadow ? root : doc.body || doc.documentElement).appendChild(h);
      // 淡出：1600ms 后播 180ms fadeOut，animationend 移除（动画事件兜底 400ms）
      setTimeout(function(){
        if (!h.parentNode) { return; }
        if (ANIM_ON && !REDUCED) {
          h.style.animation = 'zcodeFadeOut 0.18s ease-in';
          var done = function(){ if (h.parentNode) { h.parentNode.removeChild(h); } };
          h.addEventListener('animationend', done, {once: true});
          setTimeout(done, 400);
        } else {
          h.parentNode.removeChild(h);
        }
      }, 1600);
    }
    function vibrate(){
      try { if (navigator.vibrate) { navigator.vibrate(15); } } catch (e) {}
    }
    function styleRoot(){
      return isShadow ? root : (doc.head || doc.documentElement);
    }
    function bridge(){
      return window.zcodeBridge || (window.parent && window.parent.zcodeBridge) || null;
    }

