// ===== 06_composer.js (952-1371) =====
    // ---------- 浮动 logo（替代旧的"左缘三次滑动"唤出方式） ----------
    // 半隐藏悬浮在右上角：单击=设置页；长按后拖动=移动位置（持久化）；
    // 下拉=对话问题导航；上滑=快速跳到最后一条消息。
    function setLogoDim(dim){
      if (!logoEl) { return; }
      // 只调透明度，绝不禁用触摸：禁用后只能靠再次触摸恢复，会永久死锁（logo 再也点不动）
      logoEl.style.opacity = dim ? '0.15' : '';
    }
    function jumpBottom(){
      var rows = root.querySelectorAll('[data-testid^="v4-row"]');
      var last = null;
      for (var i = 0; i < rows.length; i++) { last = rows[i]; }
      if (!last) { hint('还没有消息'); return; }
      jumpTo(last);
    }
    function createLogo(){
      var br = bridge();
      var saved = '';
      try { if (br && br.getLogoPos) { saved = String(br.getLogoPos()); } } catch (e) {}
      var vw = window.innerWidth, vh = window.innerHeight;
      var logoTop = 14, logoRight = 8;
      if (saved && saved.length > 2) {
        var sp = saved.split(',');
        var fx = parseFloat(sp[0]), fy = parseFloat(sp[1]);
        if (!isNaN(fx) && !isNaN(fy)) {
          logoRight = Math.max(6, Math.round(fx * vw));
          logoTop = Math.max(4, Math.round(fy * vh));
        }
      } else {
        // 从未拖过：贴页面头部下沿（页头本身已避让状态栏）
        try {
          var hdr = root.querySelector('[data-testid="workspace-header"]');
          if (hdr) {
            var hr = hdr.getBoundingClientRect();
            if (hr.bottom > 20) { logoTop = hr.bottom + 6; }
          }
        } catch (e) {}
      }
      var logo = doc.createElement('div');
      logo.id = 'zcode-logo';
      logo.textContent = 'Z';
      logo.style.cssText = 'position:fixed;z-index:99996;width:40px;height:40px;border-radius:50%;' +
        'background:var(--zc-hover-strong);' +
        '-webkit-backdrop-filter:blur(18px) saturate(1.4);backdrop-filter:blur(18px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke-strong);' +
        'color:var(--zc-text-2);font-size:18px;font-weight:700;font-family:system-ui,-apple-system,sans-serif;' +
        'display:flex;align-items:center;justify-content:center;' +
        'box-shadow:0 4px 18px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.16);' +
        'user-select:none;-webkit-user-select:none;' +
        anim('zcodeLogoIn', '0.35s', 'cubic-bezier(0.2,0.8,0.2,1)') +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.15s, transform 0.12s;' : '') +
        'opacity:0.38;';
      logo.style.right = logoRight + 'px';
      logo.style.top = logoTop + 'px';
      logoEl = logo;
      (isShadow ? root : doc.body || doc.documentElement).appendChild(logo);

      var t0 = 0, x0 = 0, y0 = 0, longPress = false, dragging = false, pressTimer = null, tapTimer = null;
      logo.addEventListener('touchstart', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
        t0 = Date.now(); x0 = t.clientX; y0 = t.clientY;
        longPress = false; dragging = false;
        logo.style.opacity = '1';
        logo.style.transform = 'scale(0.92)';
        logo.style.transition = 'none';   // 拖动期间禁用 transform 过渡，避免跟手滞后
        clearTimeout(pressTimer);
        pressTimer = setTimeout(function(){
          longPress = true;
          vibrate();
          hint('拖动 logo 调整位置');
        }, 400);
      }, {passive:true});
      logo.addEventListener('touchmove', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
        var dx = t.clientX - x0, dy = t.clientY - y0;
        if (!longPress) {
          // 长按未生效前不进入拖动：移动超过阈值即取消长按，交给 touchend 判滑动方向
          if (Math.abs(dx) > 10 || Math.abs(dy) > 10) { clearTimeout(pressTimer); }
          return;
        }
        dragging = true;
        e.preventDefault();
        // 拖动只写 transform（不触发布局）；结束拖动时再提交为 left/top
        logo.style.right = 'auto';
        logo.style.transform = 'translate3d(' + (t.clientX - x0) + 'px,' + (t.clientY - y0) + 'px,0) scale(0.92)';
      }, {passive:false});
      logo.addEventListener('touchend', function(e){
        clearTimeout(pressTimer);
        if (dragging) {
          dragging = false;
          var rect = logo.getBoundingClientRect();
          var fx2 = (vw - (rect.left + rect.width)) / vw;
          var fy2 = rect.top / vh;
          // 提交位置：left/top 落位（此后拖动/单击都基于固定定位）
          logo.style.left = rect.left + 'px';
          logo.style.top = rect.top + 'px';
          logo.style.right = 'auto';
          logo.style.transform = '';
          logo.style.transition = '';
          try {
            var br2 = bridge();
            if (br2 && br2.saveLogoPos) { br2.saveLogoPos(fx2, fy2); }
          } catch (e2) {}
          hint('位置已保存');
          logo.style.opacity = ''; logo.style.transform = '';
          return;
        }
        var dt = Date.now() - t0;
        var t = e.changedTouches[0];
        var dx = t.clientX - x0, dy = t.clientY - y0;
        if (dt < 350 && Math.abs(dx) < 10 && Math.abs(dy) < 10) {
          // 单击 → 浮动输入框；300ms 内第二击 → 设置页（双击进入）
          uiLog('logo-tap');
          if (tapTimer) {
            clearTimeout(tapTimer);
            tapTimer = null;
            uiLog('logo-dbltap-settings');
            try {
              var br3 = bridge();
              if (br3 && br3.openSettings) { br3.openSettings(); }
            } catch (e3) {}
          } else {
            tapTimer = setTimeout(function(){
              tapTimer = null;
              if (floatInput) { toggleComposer(); }
              else { hint('输入框常驻底部，双击 logo 进设置'); }
            }, 300);
          }
          logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
          return;
        }
        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 40) {
          if (dy > 0) {
            // 下拉 → 对话问题导航
            uiLog('logo-swipedown');
            if (!navOn) { hint('对话问题导航已关闭'); }
            else { toggleNavigator(); }
          } else {
            // 上滑 → 快速到最后一条消息
            uiLog('logo-swipeup');
            jumpBottom();
          }
        }
        logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
      }, {passive:true});
      logo.addEventListener('touchcancel', function(){
        clearTimeout(pressTimer);
        longPress = false;
        dragging = false;
        logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
      }, {passive:true});
    }
    // 界面增强总开关：关闭后不注入 logo/悬浮输入/导航面板等，保持纯网页观感
    if (UI_ON) {
      createLogo();
      syncComposer();
    }

    // ---------- 输入框劫持：默认隐藏底部输入框，单击 logo 唤出悬浮框，右上角 icon 可全屏 ----------
    // 关键设计：绝不缓存 dock 节点引用。React 重渲染会换掉节点，缓存引用会指向已脱离 DOM 的旧节点
    // → 加 class、挂 expand 按钮都打到旧节点上，live 节点上什么都没有（v36 的 hasExpand=0 就这病）。
    // 这里每次需要都重新 querySelector，并在 1s 轮询里把"展开/全屏"状态同步到 live 节点。
    var COMPOSER_SEL = '[data-v4-composer-dock="true"]';
    var composerOpen = false, composerFull = false, pendingOpen = false;   // pendingOpen: dock 未就绪时的待开标记
    var composerFoundAt = -1, injectAt2 = Date.now();   // 渲染时序埋点（dock 首次找到耗时，诊断用）
    var uiTrace = [];   // UI 手势链路埋点（诊断用）
    function uiLog(act){
      var d = new Date();
      var hh = ('0' + d.getHours()).slice(-2), mm = ('0' + d.getMinutes()).slice(-2), ss = ('0' + d.getSeconds()).slice(-2);
      uiTrace.push({ t: hh + ':' + mm + ':' + ss, act: act });
      if (uiTrace.length > 6) { uiTrace.shift(); }
    }
    function getDock(){
      var d = root.querySelector(COMPOSER_SEL);
      return (d && d.parentNode) ? d : null;   // 必须仍在 DOM 中才算数
    }
    // 圆弧图标（Qwen demo 几何）：一个 SVG 内两条弧——外弧 R+gap（悬浮态=扩大）、内弧 R−gap（全屏态=缩小），
    // 同圆心同跨角：46° 短弧跨在右上角 45° 对角线上（68°→22°，从水平轴起算），弱视觉贴合圆角。
    // 命中区：透明粗描边 zc-hit（stroke-width 14，pointer-events:stroke），只有弧环可点，文字区不被方块遮挡。
    // 状态互斥由 CSS class（zc-full）切换 opacity/visibility，见 fxStyle。
    var ARC_GAP = 4;
    function arcIconSvg(radius){
      var half = radius + ARC_GAP + 9;   // 中心到盒边：弧线外径 + 命中环半宽(7) + 2px 余量
      var size = Math.max(40, Math.ceil(half * 2));
      function arcPath(R){
        var a1 = 68 * Math.PI / 180, a2 = 22 * Math.PI / 180;
        var x1 = half + R * Math.cos(a1), y1 = half - R * Math.sin(a1);
        var x2 = half + R * Math.cos(a2), y2 = half - R * Math.sin(a2);
        return 'M ' + x1.toFixed(1) + ' ' + y1.toFixed(1) + ' A ' + R + ' ' + R + ' 0 0 1 ' + x2.toFixed(1) + ' ' + y2.toFixed(1);
      }
      function group(cls, R){
        var d = arcPath(R);
        return '<g class="zc-arc ' + cls + '"><path class="zc-hit" d="' + d + '"/><path class="zc-line" d="' + d + '"/></g>';
      }
      return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" fill="none">' +
        group('zc-expand', radius + ARC_GAP) +
        group('zc-shrink', Math.max(radius - ARC_GAP, 4)) +
        '</svg>';
    }
    // ---------- dock 内弹窗复活（z.ai 的 ask/确认弹窗渲染在输入框容器里） ----------
    // dock 平时 display:none（不占位）。检测到弹窗 → 加 zcode-popup-mode 临时显示 dock
    // （只露弹窗、隐藏输入，v46），弹窗消失自动收起。悬浮输入打开时不做（弹窗本来就可见）。
    var lastPopupSeen = 0;
    var POPUP_HINT_SEL = '[role="dialog"], [role="alertdialog"], [data-testid*="ask" i], [data-testid*="confirm" i], ' +
      '[data-testid*="dialog" i], [data-testid*="modal" i], [data-testid*="popup" i], [data-testid*="request" i], [aria-modal="true"], ' +
      // v57：z.ai 的 ask/确认弹窗实测标记是 data-elicitation-dialog-card（诊断 dockHtml 实锤），
      // 且是 position:relative——只靠 role/testid/aria-modal 和 fixed 兜底永远扫不到它
      '[data-elicitation-dialog-card], [data-elicitation-dialog-body]';
    function findPopup(dock){
      try {
        var hit = dock.querySelectorAll(POPUP_HINT_SEL);
        for (var i = 0; i < hit.length; i++) {
          var el = hit[i];
          if (el.id && el.id.indexOf('zcode-') === 0) { continue; }
          // v60：只认"开着"的弹窗——页面关闭弹窗后节点可能残留（data-state=closed 或
          // display:none / visibility:hidden / opacity:0），残留节点不能继续触发 popup-mode，
          // 否则弹窗关不掉、dock 不收起（"选了没反应"的元凶之一）。
          // 注意：不能查 rect——composer 关闭时 dock 整体 display:none，子孙 rect 全为 0
          try {
            var st2 = el.getAttribute('data-state');
            if (st2 === 'closed') { continue; }
            var cs2 = getComputedStyle(el);
            if (cs2.display === 'none' || cs2.visibility === 'hidden' || parseFloat(cs2.opacity) === 0) { continue; }
          } catch (e2) {}
          return el;
        }
      } catch (e) {}
      // 兜底：无特征 testid 的弹窗 —— fixed 定位节点，或超过 60px 的 absolute 节点
      try {
        var all = dock.querySelectorAll('*');
        for (var j = 0; j < all.length && j < 300; j++) {
          var e2 = all[j];
          if (e2.closest('.rounded-2xl, [data-testid^="v4-composer-input"], #zcode-composer-expand')) { continue; }
          if (e2.id && e2.id.indexOf('zcode-') === 0) { continue; }
          var pos = '';
          try { pos = getComputedStyle(e2).position; } catch (e3) {}
          if (pos === 'fixed' || (pos === 'absolute' && (e2.offsetHeight || 0) > 60)) { return e2; }
        }
      } catch (e) {}
      return null;
    }
    function scanDockPopups(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      var pop = findPopup(dock);
      if (pop) {
        pop.classList.add('zcode-popup-visible');
        dock.classList.add('zcode-popup-present');   // 弹窗存在标记：隐藏 expand 按钮（悬浮态也生效）
        if (!composerOpen && !dock.classList.contains('zcode-popup-mode')) {
          dock.classList.add('zcode-popup-mode');
          // 弹窗出现时轻提示（防抖，避免流式渲染期间反复震）
          if (Date.now() - lastPopupSeen > 2000) {
            lastPopupSeen = Date.now();
            vibrate();
            hint('有请求需要确认');
          }
        }
      } else {
        dock.classList.remove('zcode-popup-mode', 'zcode-popup-present');
      }
    }
    // ---------- dock 新增节点快照（v55） ----------
    // ask 弹窗渲染瞬间被记录（结构链 + 定位/可见性），弹窗卸载后诊断卡仍能读到——
    // 用于确认弹窗实际挂在 dock 的哪个位置（弹窗已消失时 findPopup 只能给出 null）。
    var dockSnap = [];
    var dockSnapObs = null;
    function snapDockNode(el, dock){
      var chain = [];
      var anc = el;
      while (anc && anc !== dock) {
        var tag = anc.tagName || '';
        var tid = anc.getAttribute && anc.getAttribute('data-testid');
        if (tid) { tag += '[' + tid.slice(0, 30) + ']'; }
        if (anc.className && typeof anc.className === 'string' && anc.className) { tag += '.' + anc.className.split(' ')[0]; }
        chain.unshift(tag.slice(0, 44));
        anc = anc.parentNode;
      }
      var r = el.getBoundingClientRect();
      var cs = getComputedStyle(el);
      dockSnap.push({
        t: new Date().toTimeString().slice(0, 8),
        tag: el.tagName,
        tid: (el.getAttribute && el.getAttribute('data-testid')) || '',
        role: (el.getAttribute && el.getAttribute('role')) || '',
        cls: ((el.className || '').toString() || '').slice(0, 40),
        chain: chain.join('>').slice(0, 200),
        pos: cs.position + '/z' + cs.zIndex,
        disp: cs.display + '/v' + cs.visibility,
        rect: Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.x) + ',' + Math.round(r.y)
      });
      if (dockSnap.length > 12) { dockSnap.shift(); }
    }
    function ensureDockSnapObs(dock){
      if (dockSnapObs || !dock) { return; }
      try {
        dockSnapObs = new MutationObserver(function(muts){
          // v55b：不设 composerOpen 门控——弹窗可能是在打开输入框时才渲染的，
          // 门控会正好跳过采样窗口（首轮 popupSnap 只捕到滚到底按钮就是这个坑）
          for (var i = 0; i < muts.length; i++) {
            var added = muts[i].addedNodes;
            for (var j = 0; j < added.length; j++) {
              var n = added[j];
              if (n.nodeType !== 1) { continue; }
              if (n.id && n.id.indexOf('zcode-') === 0) { continue; }
              var MARK = '[data-testid],[role],[aria-modal],[data-state]';
              var hasMark = n.getAttribute && (n.getAttribute('data-testid') || n.getAttribute('role') || n.getAttribute('aria-modal') || n.getAttribute('data-state'));
              if (hasMark) { snapDockNode(n, dock); }
              else if (n.querySelector && n.querySelector(MARK)) {
                // React 一次性插入整个子树：根节点无标记但内部有——快照根，链上能看出挂点
                snapDockNode(n, dock);
              }
            }
          }
        });
        dockSnapObs.observe(dock, { childList: true, subtree: true });
      } catch (e) {}
    }
    // ---------- body 级弹窗检测（v53） ----------
    // z.ai 的 Radix 下拉/菜单/选择器 portal 到 body 根部（z-50），被我们的遮罩（99995）与
    // dock（99998+）盖住/吞点击。检测到即给 html 加 zcode-body-popup，CSS 把注入层压到弹窗之下。
    // 只扫特征选择器（不扫全树），400ms 采样足以跟上菜单开合。
    var PAGE_POPUP_SEL = '[role="menu"], [role="listbox"], [role="dialog"], [role="tooltip"], ' +
      '[data-radix-popper-content-wrapper], [data-radix-menu-content], [data-radix-select-content], [data-state="open"]';
    function isPagePopup(el){
      if (!el || el.nodeType !== 1) { return false; }
      if (el.id && el.id.indexOf('zcode-') === 0) { return false; }
      try {
        // 我们自己的注入层不算（诊断卡/导航/logo/遮罩）；dock 内弹窗走 popup-mode 复活，也不算
        if (el.closest('#zcode-mask, #zcode-logo, #zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return false; }
        if (el.closest('[data-v4-composer-dock="true"]')) { return false; }
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') { return false; }
        var z = parseInt(cs.zIndex, 10);
        // 高于页面内容（z-20/30），低于我们的注入层（99990）——页面自己的弹窗区间
        if (!(z > 25 && z < 99990)) { return false; }
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 24) { return false; }
        if (r.right < 0 || r.left > window.innerWidth || r.bottom < 0 || r.top > window.innerHeight) { return false; }
        return true;
      } catch (e) { return false; }
    }
    function scanBodyPopups(){
      if (!UI_ON) { return; }
      var found = false;
      try {
        var all = doc.querySelectorAll(PAGE_POPUP_SEL);
        for (var i = 0; i < all.length; i++) {
          if (isPagePopup(all[i])) { found = true; break; }
        }
      } catch (e) {}
      var html = doc.documentElement;
      if (!html) { return; }   // shadow root 没有 documentElement
      if (found) { html.classList.add('zcode-body-popup'); }
      else { html.classList.remove('zcode-body-popup'); }
    }
    // 每次调用都基于 live dock：确保 expand 按钮在、当前 open/full 状态 class 正确。
    // 这是幂等的，可被轮询反复调用以跟随 React 重挂载。
    function syncComposer(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      scanDockPopups();   // dock 内弹窗复活（不依赖打开状态）
      ensureDockSnapObs(dock);   // v55：dock 新增节点快照（弹窗结构事件捕获）
      if (composerFoundAt < 0) { composerFoundAt = Date.now() - injectAt2; }
      // 单击时 dock 还没渲染：轮询到后再自动打开（见 showComposer 的 pendingOpen）
      if (pendingOpen) { pendingOpen = false; showComposer(); return; }
      // 默认隐藏底部输入框（html 门控 class，诊断卡片可一键恢复）
      doc.documentElement.classList.add('zcode-float-on');
      // 弧线图标随胶囊圆角半径动态生成；半径变了才重建（两条弧共存，状态用 class 切，无 innerHTML churn）
      var pill = dock.querySelector('.rounded-2xl');
      var radius = 16;
      if (pill) { try { radius = parseFloat(getComputedStyle(pill).borderTopRightRadius) || 16; } catch (e) {} }
      // 按钮挂进胶囊内部（.rounded-2xl 自身是 relative）：与胶囊同体移动，动画/重排期间零滞后。
      // 位置只由半径算，不再 getBoundingClientRect 测量（v52：v42 的"切换后定位"仍有窗口期滞后——
      // 打开瞬间读到的是动画起始位，要等 1s 轮询才纠正，表现为"滞后一瞬间才跟过去"）。
      var exp = pill ? pill.querySelector('#zcode-composer-expand') : null;
      if (pill && !exp) {
        exp = doc.createElement('div');
        exp.id = 'zcode-composer-expand';
        exp.setAttribute('role', 'button');
        exp.setAttribute('aria-label', composerFull ? '收起输入框' : '展开全屏输入框');
        exp._r = radius;
        exp.innerHTML = arcIconSvg(radius);
        // 关键：用 pointerup 触发，不要 onclick。移动端 touchstart preventDefault 会吞掉合成 click。
        // 根盒 pointer-events:none，事件来自弧线命中环（zc-hit）冒泡到这里。
        exp.addEventListener('touchstart', function(e){ e.stopPropagation(); }, {passive:true});
        exp.addEventListener('pointerup', function(e){ e.stopPropagation(); toggleFullComposer(); });
        // 防御：Tailwind relative 类若被 React 重挂载丢掉，inline 补上，保证 absolute 锚定胶囊
        try { pill.style.position = 'relative'; } catch (e) {}
        pill.appendChild(exp);
        positionExpand(exp, radius);
      } else if (exp && exp._r !== radius) {
        exp._r = radius;
        exp.innerHTML = arcIconSvg(radius);
        positionExpand(exp, radius);
      }
      // 状态互斥：zc-full 时隐藏外弧（扩大）、显示内弧（缩小）
      if (exp) {
        exp.classList.toggle('zc-full', composerFull);
        exp.setAttribute('aria-label', composerFull ? '收起输入框' : '展开全屏输入框');
      }
      // 把当前状态落到 live 节点（重挂载后 class 会丢，这里补回来）。
      // 必须先移除两个类再只加一个：如果只 add，从全屏缩回浮动时 zcode-composer-full
      // 会残留，CSS 里 full 规则排在 float 之后且特异性相同 → full 永远赢 → 合不上（v38 修）。
      dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
      if (composerOpen) {
        dock.classList.add(composerFull ? 'zcode-composer-full' : 'zcode-composer-float');
      }
      // 按钮是胶囊的子节点，位置不随状态变化（只依赖半径），无需再定位
    }
    // 按钮中心对准胶囊右上角圆角圆心。按钮是胶囊的 absolute 子节点，所以只按半径算偏移，
    // 不读任何 rect —— 胶囊怎么动（打开/全屏/React 重挂）按钮就怎么跟，零测量零滞后（v52）
    function positionExpand(exp, radius){
      try {
        // 按钮盒 ≥ 弧线外径 + 命中环外延（radius+gap+9，与 arcIconSvg 的 half 一致），保证整条命中环都在盒内
        var size = Math.max(40, Math.ceil((radius + ARC_GAP + 9) * 2));
        exp.style.width = size + 'px';
        exp.style.height = size + 'px';
        // 圆心对齐：中心 = (胶囊右缘−radius, 胶囊上缘+radius) → 相对胶囊的 top/right 偏移 = radius − size/2
        var off = (radius - size / 2).toFixed(1);
        exp.style.top = off + 'px';
        exp.style.right = off + 'px';
      } catch (e2) {}
    }
    // logo 单击 = 开关：开 → 收起，关 → 唤起（v38 起 toggle，之前打开状态下点 logo 被静默忽略）
    function toggleComposer(){
      // v59：弹窗挂起（popup-mode）时 logo 单击不切换——dock 会在 popup-mode（全宽 100%）
      // 与 float（94vw/520px 收窄）两种布局间跳变，提问框跟着缩放抖动（用户实测"点按钮提问框变一下大小"）
      var dd = getDock();
      if (dd && dd.classList.contains('zcode-popup-mode')) { return; }
      if (composerOpen) { hideComposer(); }
      else { showComposer(); }
    }
    function showComposer(){
      var dock = getDock();
      if (!dock) {
        // dock 还在渲染中：标记待开，1s 轮询到位后自动弹出（不给用户静默无反馈）
        pendingOpen = true;
        hint('输入框还没就绪，稍候自动弹出');
        return;
      }
      pendingOpen = false;
      if (composerOpen) { return; }
      composerOpen = true;
      dock.classList.add('zcode-composer-float');
      uiLog('composer-open fixed');
      showMask();
      syncComposer();
      // 聚焦输入框：键盘跟随弹出
      try {
        var ta = dock.querySelector('[data-testid^="v4-composer-input"], textarea, input[type="text"]');
        if (ta) { setTimeout(function(){ try { ta.focus(); } catch (e2) {} }, 150); }
      } catch (e) {}
    }
    function toggleFullComposer(){
      var dock = getDock();
      if (!dock) { return; }
      composerFull = !composerFull;
      uiLog(composerFull ? 'composer-full' : 'composer-shrink');
      syncComposer();
    }
    function hideComposer(){
      var dock = getDock();
      composerOpen = false;
      composerFull = false;
      pendingOpen = false;
      hideMask();
      if (dock) {
        if (ANIM_ON && !REDUCED) {
          // 收起动画：先加 closing（淡出下移），animationend 后移除状态类（动画事件兜底 600ms）
          dock.classList.add('zcode-composer-closing');
          var done = function(){
            dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
          };
          dock.addEventListener('animationend', done, {once: true});
          setTimeout(done, 600);
        } else {
          dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
        }
      }
      uiLog('composer-hide');
    }

    // ---------- 兜底手势：左缘上滑×3 + 右缘下滑×3（组合手势，诊断入口，不在任何 UI 文案里暴露） ----------
    var track = null, swipes = [], lastTrigger = 0;
    var EDGE = 44, SWIPE_MIN = 46, SWIPE_MAX_MS = 450, WINDOW_MS = 3500, COOLDOWN = 2000;
    function onStart(e){
      var t = e.touches[0];
      var vw = window.innerWidth;
      var side = t.clientX < EDGE ? 'L' : (t.clientX > vw - EDGE ? 'R' : null);
      if (!side) { track = null; return; }
      track = { side: side, x0: t.clientX, y0: t.clientY, t0: Date.now() };
    }
    function onMove(e){
      if (!track) { return; }
      var t = e.touches[0];
      if (Math.abs(t.clientX - track.x0) > Math.abs(t.clientY - track.y0) + 24) { track = null; }
    }
    function onEnd(e){
      if (!track) { return; }
      var t = e.changedTouches[0];
      var dy = t.clientY - track.y0;
      var dt = Date.now() - track.t0;
      var side = track.side;
      track = null;
      var dir = dy < 0 ? 'up' : 'down';
      // 左侧只认上滑、右侧只认下滑，其余方向直接忽略
      if ((side === 'L' && dir !== 'up') || (side === 'R' && dir !== 'down')) { return; }
      if (Math.abs(dy) < SWIPE_MIN || dt > SWIPE_MAX_MS) { return; }
      var now = Date.now();
      swipes.push({ t: now, side: side, dir: dir });
      swipes = swipes.filter(function(s){ return now - s.t <= WINDOW_MS; });
      var lu = 0, rd = 0;
      for (var i = 0; i < swipes.length; i++) {
        if (swipes[i].side === 'L' && swipes[i].dir === 'up') { lu++; }
        else if (swipes[i].side === 'R' && swipes[i].dir === 'down') { rd++; }
      }
      if (lu < 3 || rd < 3) { return; }
      swipes = [];
      if (now - lastTrigger < COOLDOWN) { return; }
      lastTrigger = now;
      vibrate();
      uiLog('combo-swipe-diag');
      showDiagCard();
    }
    evRoot.addEventListener('touchstart', onStart, {passive:true});
    evRoot.addEventListener('touchmove', onMove, {passive:true});
    evRoot.addEventListener('touchend', onEnd, {passive:true});

    evRoot.addEventListener('touchmove', dragMove, {passive:false});
    evRoot.addEventListener('touchend', dragEnd, {passive:true});

