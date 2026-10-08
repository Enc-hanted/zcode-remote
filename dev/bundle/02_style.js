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
        '#zcode-composer-icon{position:fixed;left:12px;bottom:12px;z-index:99996;margin:0;' +
        'display:flex;align-items:center;justify-content:center;' +
        'width:44px;height:44px;border-radius:22px;background:var(--zc-bg-strong);' +
        '-webkit-backdrop-filter:blur(18px) saturate(1.4);backdrop-filter:blur(18px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke-strong);color:var(--zc-text-2);font-size:19px;line-height:1;' +
        'box-shadow:0 4px 18px rgba(0,0,0,0.35);cursor:pointer;user-select:none;-webkit-user-select:none;' +
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
        // 弹窗模式：dock 临时显示为底部容器（不遮挡全屏），输入部分隐藏，弹窗可见可点
        // 容器变换进行中：压掉 float/full 态的入场 animation，几何交给 JS 的 transform 过渡
        '[data-v4-composer-dock="true"].zcode-composer-morph{animation:none!important}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'display:block!important;visibility:visible!important;pointer-events:auto!important;' +
        'position:fixed!important;left:50%!important;right:auto!important;bottom:0!important;top:auto!important;' +
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
        'bottom:14px!important;top:auto!important;transform:translateX(-50%)!important;' +
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
        'padding:0 0 20px!important;background:rgba(0,0,0,0.55)!important;border:none!important;' +
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
        // 不加这条全屏只是"暗遮罩+底部小输入框"，v37 就是这个坑）。32vh 约 1/3 屏高。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid^="v4-composer-input"]{' +
        'min-height:32vh!important;max-height:none!important;height:32vh!important;font-size:16px!important;' +
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
      // 悬浮输入开着时上移，避免和输入胶囊重叠
      var up = (doc.documentElement.classList.contains('zcode-float-on') && getDock()) ? 128 : 88;
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

