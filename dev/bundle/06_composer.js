// ===== 06_composer.js (952-1371) =====
    // ---------- 三态机（v65，替代旧 logo + 胶囊）----------
    // 输入框(展开) ⇄ 左下角收纳图标(收纳)；上滑/右上角弧形钮 → 全屏输入。
    // 状态存 localStorage（记住上次）；dock 始终保持流内 sticky，图标 44px 撑起 dock，
    // 页面/虚拟列表的让位预留照常工作（v64 实证的防空白区方案不变）。
    function persistComposerState(v){
      try { localStorage.setItem('zcComposerState', v); } catch (e) {}
    }
    function storedComposerState(){
      try { return localStorage.getItem('zcComposerState') || 'min'; } catch (e) { return 'min'; }
    }
    // 页面加载后按上次状态自动恢复：'open' → 输入框直接展开（不聚焦，避免键盘突袭）
    var pendingAutoOpen = (storedComposerState() === 'open');
    // ---------- 系统手势豁免（方案A）：图标贴左下角，正落在 Android 返回手势区里，
    // 不豁免则右滑（开导航）会被系统截胡直接退出页面。API 29+ 生效，低版本静默跳过。
    // 1s 轮询会反复进来，用 key 缓存去重，矩形没变不打桥。
    var lastExclude = '';
    function updateGestureExclude(){
      try {
        var br = bridge();
        if (!br || !br.setGestureExclude) { return; }
        var d = getDock();
        var ic = doc.getElementById('zcode-composer-icon');
        var vis = !!(ic && ic.style.display !== 'none' && d &&
          doc.documentElement.classList.contains('zcode-float-on') &&
          !composerOpen && !d.classList.contains('zcode-popup-mode') &&
          !d.classList.contains('zcode-popup-present'));
        var key, r = null;
        if (vis) {
          r = ic.getBoundingClientRect();
          if (!(r.width > 0 && r.bottom > 0)) { vis = false; }
          else { key = Math.round(r.left) + ',' + Math.round(r.top) + ',' + Math.round(r.right) + ',' + Math.round(r.bottom); }
        }
        if (!vis) { key = 'off'; }
        if (key === lastExclude) { return; }
        lastExclude = key;
        if (vis) {
          // 外扩 6px：系统对手势区的判定贴得很紧，紧贴矩形边缘的起手仍可能漏出
          br.setGestureExclude(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6);
        } else {
          br.setGestureExclude(-1, -1, -1, -1);
        }
      } catch (e) {}
    }
    // ---------- 左下角收纳图标：点击/上滑 = 唤出输入框；右滑 = 对话问题导航 ----------
    // 触摸走 touch 系；桌面 Chrome 直接用鼠标时没有 touch 事件，pointer 系(mouse/pen)兜底。
    // pointer 处理器对 pointerType==='touch' 一律放行，避免移动端 touch+pointer 双触发。
    var iconTrack = null;
    function evXY(e){
      if (e.touches) { return e.touches[0]; }
      return e;
    }
    function iconStart(e){
      if (e.touches && e.touches.length !== 1) { iconTrack = null; return; }
      var t = evXY(e);
      iconTrack = { x: t.clientX, y: t.clientY, t0: Date.now(), done: false };
    }
    function iconMove(e){
      if (!iconTrack || iconTrack.done) { return; }
      var t = evXY(e);
      var dx = t.clientX - iconTrack.x, dy = t.clientY - iconTrack.y;
      if (dx > 48 && Math.abs(dy) < 40) {
        iconTrack.done = true;
        e.preventDefault();
        uiLog('icon-swiperight');
        vibrate();
        if (!navOn) { hint('对话问题导航已关闭'); }
        else { toggleNavigator(); }
      } else if (dy < -44 && Math.abs(dx) < 40) {
        iconTrack.done = true;
        e.preventDefault();
        uiLog('icon-swipeup');
        showComposer();
      }
    }
    function iconEnd(e){
      if (!iconTrack) { return; }
      var tr = iconTrack;
      iconTrack = null;
      if (tr.done) { return; }
      // touchend 用 changedTouches（手指离开位置），pointerup 用事件自身坐标
      var t = e.changedTouches ? e.changedTouches[0] : e;
      if (Date.now() - tr.t0 < 350 && Math.abs(t.clientX - tr.x) < 10 && Math.abs(t.clientY - tr.y) < 10) {
        uiLog('icon-tap');
        showComposer();
      }
    }
    function iconPtrDown(e){
      if (e.pointerType === 'touch') { return; }
      iconStart(e);
    }
    function iconPtrMove(e){
      if (e.pointerType === 'touch') { return; }
      iconMove(e);
    }
    function iconPtrUp(e){
      if (e.pointerType === 'touch') { return; }
      iconEnd(e);
    }
    // ---------- 输入框上的滑动：下滑 = 收纳成图标，上滑 = 全屏输入 ----------
    // 事件委托挂在 evRoot 上（React 重挂 dock 也始终有效）。打字/点按的手势不归我们：
    // textarea、输入框、按钮、链接一律放行，滑动只认输入框的边框留白与工具条区域。
    var dockTrack = null;
    function dockGestureArm(tg, x, y){
      if (!composerOpen || composerFull) { return null; }
      if (!tg || !tg.closest) { return null; }
      var dk = tg.closest('[data-v4-composer-dock="true"]');
      if (!dk || dk.classList.contains('zcode-popup-mode') || dk.classList.contains('zcode-popup-present')) { return null; }
      if (tg.closest('textarea, input, select, button, a, [contenteditable], [role="button"], #zcode-composer-expand')) { return null; }
      return { x: x, y: y, t0: Date.now(), done: false };
    }
    function dockGestureStart(e){
      if (e.touches && e.touches.length !== 1) { dockTrack = null; return; }
      var t = evXY(e);
      dockTrack = dockGestureArm(e.target, t.clientX, t.clientY);
    }
    function dockGestureMove(e){
      if (!dockTrack || dockTrack.done) { return; }
      var t = evXY(e);
      var dx = t.clientX - dockTrack.x, dy = t.clientY - dockTrack.y;
      if (dy > 44 && Math.abs(dx) < 40) {
        dockTrack.done = true;
        e.preventDefault();
        uiLog('composer-swipedown-min');
        vibrate();
        hideComposer();
        hint('已收纳，点左下角图标唤出');
      } else if (dy < -44 && Math.abs(dx) < 40) {
        dockTrack.done = true;
        e.preventDefault();
        uiLog('composer-swipeup-full');
        vibrate();
        if (!composerFull) { toggleFullComposer(); }
      }
    }
    function dockGestureEnd(){ dockTrack = null; }
    // ---------- 滑到底自动恢复输入框（v67，设置项"滑到底部恢复输入框"）----------
    // 收纳态内容满屏，滚到全部消息最底端 = 读完准备输入 → 输入框自动滑出（容器变换）。
    // 防误触：流式回复的自动跟滚一直贴着底部，不能一进收纳态就触发——
    // 只有"向上离开底部 ≥220px（页面跟滚此时已停）再滚回 ≤28px"才算"读完回来了"。
    var minUpDist = 0;
    var restoreScEl = null;
    function watchScrollRestore(){
      if (!restoreOnBottom || !floatInput || !UI_ON || composerOpen) { return; }
      if (!doc.documentElement.classList.contains('zcode-float-on')) { return; }
      var dock = getDock();
      if (!dock || dock.classList.contains('zcode-popup-mode') || dock.classList.contains('zcode-popup-present')) { return; }
      // 容器可能被 React 重挂，断了就重找。3.14.4 实测滚动容器是 v4-timeline
      //（min-h-0 flex-1 overflow-y-auto，15000px+ 内容全在里面）；v4-timeline-scroll 是候选兼容
      if (!restoreScEl || !restoreScEl.isConnected) {
        restoreScEl = root.querySelector('[data-testid="v4-timeline-scroll"]') ||
          root.querySelector('[data-testid="v4-timeline"]');
      }
      if (!restoreScEl || !(restoreScEl.scrollHeight > restoreScEl.clientHeight + 40)) { return; }
      var dist = restoreScEl.scrollHeight - restoreScEl.clientHeight - restoreScEl.scrollTop;
      if (dist > minUpDist) { minUpDist = dist; }
      if (dist <= 28 && minUpDist >= 220) {
        minUpDist = 0;
        uiLog('restore-on-bottom');
        showComposer(false);
      }
    }
    function dockGesturePtrDown(e){
      if (e.pointerType === 'touch') { return; }
      dockTrack = dockGestureArm(e.target, e.clientX, e.clientY);
    }
    function dockGesturePtrMove(e){
      if (e.pointerType === 'touch') { return; }
      dockGestureMove(e);
    }
    function dockGesturePtrUp(e){
      if (e.pointerType === 'touch') { return; }
      dockGestureEnd();
    }
    // 界面增强总开关：关闭后不注入图标/悬浮输入/导航面板等，保持纯网页观感
    if (UI_ON) {
      evRoot.addEventListener('touchstart', dockGestureStart, {passive:true});
      evRoot.addEventListener('touchmove', dockGestureMove, {passive:false});
      evRoot.addEventListener('touchend', dockGestureEnd, {passive:true});
      evRoot.addEventListener('touchcancel', function(){ dockTrack = null; }, {passive:true});
      evRoot.addEventListener('pointerdown', dockGesturePtrDown, {passive:true});
      evRoot.addEventListener('pointermove', dockGesturePtrMove, {passive:true});
      evRoot.addEventListener('pointerup', dockGesturePtrUp, {passive:true});
      // scroll 不冒泡但参与捕获：capture 挂根上可以收到内部滚动容器的滚动
      evRoot.addEventListener('scroll', watchScrollRestore, {passive:true, capture:true});
      window.addEventListener('resize', updateGestureExclude);
      syncComposer();
    }

    // ---------- 输入框劫持：有消息时收起原输入框，左下角图标/手势唤出，右上角 icon 可全屏 ----------
    // 关键设计：绝不缓存 dock 节点引用。React 重渲染会换掉节点，缓存引用会指向已脱离 DOM 的旧节点
    // → 加 class、挂 expand 按钮都打到旧节点上，live 节点上什么都没有（v36 的 hasExpand=0 就这病）。
    // 这里每次需要都重新 querySelector，并在 1s 轮询里把"展开/全屏"状态同步到 live 节点。
    var COMPOSER_SEL = '[data-v4-composer-dock="true"]';
    var composerOpen = false, composerFull = false, pendingOpen = false;   // pendingOpen: dock 未就绪时的待开标记
    var lastDockH = -1;   // v66：dock 流内高度跟踪（收纳 0 / 唤出悬浮 / 常驻 121），变化时通知列表重算
    var restoreOnBottom = true;   // v67 设置项"滑到底部恢复输入框"（原生 applySettings 热更）
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
      '[data-elicitation-dialog-card], [data-elicitation-dialog-body], ' +
      // v61：权限申请框（bash/写文件等工具执行授权）——与 ask 同属 elicitation 族，
      // 若 testid 不含上面的子串关键词则漏判，预防性补齐常见权限标记
      '[data-testid*="permission" i], [data-testid*="approve" i], [data-testid*="allow" i], ' +
      '[data-testid*="authorize" i], [data-testid*="can-use" i], [data-testid*="consent" i], ' +
      // v64：3.14.x 新增的待审/用户输入卡片——hook-pending 系列不含上面任何关键词，
      // 不补会静默漏检（桌面端等你授权时手机端无感知）；user-input 系列含 dialog 但显式补上更稳
      '[data-testid*="hook-pending" i], [data-testid*="user-input" i]';
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
        // 我们自己的注入层不算（诊断卡/导航/遮罩）；dock 内弹窗走 popup-mode 复活，也不算
        if (el.closest('#zcode-mask, #zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return false; }
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
    // ---------- 会话末尾垫片（v68） ----------
    // 每条 AI 回复（section）末尾自带 gap-5(20px) + min-h-5(20px) 垫片；最后一条回复的这组
    // 在收纳态露出成底部空白带（展开态被胶囊盖住看不出）。收纳态隐藏末尾垫片——隐藏后
    // flex gap 因失去相邻兄弟一并消失，共回收 40px，末条消息贴到屏幕底边；展开态恢复。
    // 虚拟列表随时增删 turn：每次轮询重查、按引用恢复旧节点（已卸载则无害）；
    // 并校验垫片确实位于内容末尾（末条是用户消息时不动），变更时 nudge 虚拟列表重算。
    var tailSpacerEl = null;
    function syncTailSpacer(dock){
      var minimized = dock && !dock.classList.contains('zcode-composer-float') &&
        !dock.classList.contains('zcode-composer-full') &&
        !dock.classList.contains('zcode-popup-mode');
      var want = null;
      if (minimized) {
        var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
        if (tl) {
          var all = tl.querySelectorAll('section .min-h-5');
          if (all.length) {
            var last = all[all.length - 1];
            try {
              var cr = tl.getBoundingClientRect();
              var sb = last.getBoundingClientRect().bottom - cr.top + tl.scrollTop;
              if (tl.scrollHeight - sb <= 60) { want = last; }   // 不在内容末尾的不动
            } catch (e) {}
          }
        }
      }
      if (want === tailSpacerEl) { return; }
      if (tailSpacerEl) { tailSpacerEl.style.display = ''; }
      tailSpacerEl = want || null;
      if (tailSpacerEl) { tailSpacerEl.style.display = 'none'; }
      try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
      try {
        var tl2 = root.querySelector('[data-testid="v4-timeline"]');
        if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
      } catch (e3) {}
    }
    // ---------- 虚拟列表空洞修补（v68b） ----------
    // 上滚/收纳翻转后，虚拟列表（@tanstack/react-virtual）按旧区间渲染：视口底部会留一段
    // 没有任何 section 覆盖的"空洞"（真机上渲染节流，可持续数秒、直到下次滚动才补——
    // 用户实测"划到上面还是有空白"）。检测：视口底缘命中元素向上走到根都没有 SECTION
    // （遮罩/图标/toast 不算内容），且不在内容末尾（距底 >40px）→ 1px 抖动滚动（异步回弹、
    // 逼出两次真实 scroll 事件）强制重算渲染区间。每个空洞期最多抖 6 次、500ms 一拍，
    // 覆盖恢复或状态翻转后重新计数——绝不持续跟用户抢滚动。
    var holeTries = 0;
    var holeLast = 0;
    function fixViewportHole(){
      if (composerOpen || composerFull) { holeTries = 0; return; }
      var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl) { return; }
      var el = null;
      try { el = doc.elementFromPoint(Math.round(window.innerWidth / 2), window.innerHeight - 2); } catch (e0) {}
      var covered = false;
      for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
        if (n.tagName === 'SECTION' || (n.getAttribute && (n.getAttribute('data-testid') || '').indexOf('v4-row') === 0)) { covered = true; break; }
      }
      var dist = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
      if (covered || dist <= 40) { holeTries = 0; return; }
      var now = Date.now();
      if (now - holeLast > 500 && holeTries < 6) {
        holeTries++; holeLast = now;
        if (tl.scrollTop > 0) { tl.scrollTop -= 1; }
        setTimeout(function(){ try { tl.scrollTop += 1; } catch (e1) {} }, 30);
        uiLog('hole-nudge');
      }
    }
    function holeBurst(){
      for (var i = 1; i <= 3; i++) { setTimeout(fixViewportHole, i * 400); }
    }
    function syncComposer(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      scanDockPopups();   // dock 内弹窗复活（不依赖打开状态）
      ensureDockSnapObs(dock);   // v55：dock 新增节点快照（弹窗结构事件捕获）
      if (composerFoundAt < 0) { composerFoundAt = Date.now() - injectAt2; }
      // 单击时 dock 还没渲染：轮询到后再自动打开（见 showComposer 的 pendingOpen）
      if (pendingOpen) { pendingOpen = false; showComposer(); return; }
      // 空对话（无消息）保留原输入框常驻，有了消息才收纳成左下角图标
      // 新 session 时 React 重挂载，无 v4-row → 输入框可见，符合"新对话不隐藏"的预期
      var hasRows = root.querySelector('[data-testid^="v4-row"]');
      var wasFloat = doc.documentElement.classList.contains('zcode-float-on');
      if (hasRows) { doc.documentElement.classList.add('zcode-float-on'); }
      else { doc.documentElement.classList.remove('zcode-float-on'); }
      // v62：dock 隐藏后触发虚拟列表重算——zcode 的 message 虚拟列表按"dock 在底部"预留空间，
      // dock display:none 后预留的 ~121px 不会被回收（最后一条消息不下移，底部空一块）。
      // dispatch resize 让虚拟列表（@tanstack/react-virtual 监听视口变化）重新测量并填满。
      // 仅在状态切换时触发（避免每秒轮询都 dispatch）；首帧 wasFloat 恒 false 但首帧无虚拟列表，无副作用
      var nowFloat = doc.documentElement.classList.contains('zcode-float-on');
      if (nowFloat !== wasFloat) {
        try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
        try {
          var tl = root.querySelector('[data-testid="v4-timeline"]');
          if (tl) { tl.dispatchEvent(new Event('scroll')); }
        } catch (e3) {}
      }
      // v65：记住上次的展开/收纳状态——上次没收起来就自动展开（不聚焦，键盘不突袭）
      if (pendingAutoOpen) {
        pendingAutoOpen = false;
        if (!composerOpen && hasRows) { showComposer(false); return; }
      }
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
      ensureIcon(dock);   // v66：收纳图标同步（悬浮层，跟随 float-on/open/popup 状态显隐 + 手势豁免上报）
      syncTailSpacer(dock);   // v68：收纳态隐藏会话末尾垫片，末条消息贴屏底
      // v66：dock 流内高度变化（收纳 0 ⇄ 唤出悬浮高 ⇄ 常驻 121）时通知虚拟列表重算，
      // 让位跟着新高度走，旧高度不会残留成底部空带
      var dh = dock.offsetHeight;
      if (dh !== lastDockH) {
        lastDockH = dh;
        try { window.dispatchEvent(new Event('resize')); } catch (e4) {}
        try {
          var tl2 = root.querySelector('[data-testid="v4-timeline"]');
          if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
        } catch (e5) {}
      }
      // v68b：收纳态视口底部空洞检测+抖动修补（上滚/翻转后虚拟列表没跟上时）
      fixViewportHole();
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
    // ---------- 容器变换（container transform）----------
    // 图标⇄输入框、浮动⇄全屏都是"同一个元素连续变形"，不是各自出场/退场：
    // 收纳 = 输入胶囊原地缩进图标落位（transform-origin 钉在图标中心，圆角随行变圆、内容快速淡出），
    // 落点瞬间才提交收纳态，图标无入场动画、像素无缝衔接；唤出反向播放（先缩放在图标处再弹出）。
    // 全屏⇄浮动用 FLIP（旧 rect → 新 rect），内容淡入淡出掩盖拉伸。只动 transform/opacity，不触发布局。
    var morphTok = 0;
    function rectOf(el){
      var r = el.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height };
    }
    function iconHomeRect(){
      // 收纳图标悬浮位（CSS: left:12px bottom:12px，44px）
      return { x: 12, y: window.innerHeight - 56, w: 44, h: 44 };
    }
    function pillChildrenFade(pill, op, dur, delay){
      var kids = pill.children;
      for (var i = 0; i < kids.length; i++) {
        if (!kids[i].style) { continue; }
        kids[i].style.transition = dur ? ('opacity ' + dur + 's ease ' + (delay || 0) + 's') : '';
        kids[i].style.opacity = op;
      }
    }
    function pillMorphCleanup(dock, pill){
      dock.classList.remove('zcode-composer-morph');
      pill.style.transformOrigin = '';
      pill.style.transition = '';
      pill.style.transform = '';
      pill.style.borderRadius = '';
      pillChildrenFade(pill, '', 0, 0);
    }
    function morphable(pill, dock){
      return ANIM_ON && !REDUCED && !!pill && !!dock &&
        !dock.classList.contains('zcode-popup-mode') && !dock.classList.contains('zcode-popup-present');
    }
    // 唤出：真身先就位（float 态），把起点缩放在图标处，松手长大
    function morphReveal(dock, pill, from){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pill.style.transformOrigin = (from.x + from.w / 2 - to.x) + 'px ' + (from.y + from.h / 2 - to.y) + 'px';
      pill.style.transform = 'scale(' + (from.w / Math.max(1, to.w)) + ',' + (from.h / Math.max(1, to.h)) + ')';
      pill.style.borderRadius = '50%';
      pillChildrenFade(pill, '0', 0, 0);
      void pill.offsetWidth;   // 先让起点帧生效，否则 transition 不触发
      pill.style.transition = 'transform 0.28s cubic-bezier(0.2,0.8,0.2,1), border-radius 0.28s cubic-bezier(0.2,0.8,0.2,1)';
      pill.style.transform = '';
      pill.style.borderRadius = '';
      pillChildrenFade(pill, '1', 0.26, 0.06);
      var tok = ++morphTok;
      setTimeout(function(){
        if (tok !== morphTok) { return; }
        pillMorphCleanup(dock, pill);
      }, 340);
    }
    // 收纳：原地缩进图标落位，落点瞬间提交收纳态（期间保持开态，避免轮询中途翻转）
    function morphMinimize(dock, pill){
      var to = iconHomeRect();
      var from = rectOf(pill);
      dock.classList.add('zcode-composer-morph');
      pill.style.transformOrigin = (to.x + to.w / 2 - from.x) + 'px ' + (to.y + to.h / 2 - from.y) + 'px';
      pill.style.transition = 'transform 0.26s cubic-bezier(0.2,0.8,0.2,1), border-radius 0.26s cubic-bezier(0.2,0.8,0.2,1)';
      pillChildrenFade(pill, '0', 0.16, 0);
      pill.style.transform = 'scale(' + (to.w / Math.max(1, from.w)) + ',' + (to.h / Math.max(1, from.h)) + ')';
      pill.style.borderRadius = '50%';
      var tok = ++morphTok;
      setTimeout(function(){
        if (tok !== morphTok) { return; }
        composerOpen = false;
        composerFull = false;
        pendingOpen = false;
        minUpDist = 0;   // 收纳态重新计数"离开底部的距离"
        persistComposerState('min');
        hideMask();
        dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
        pillMorphCleanup(dock, pill);
        syncComposer();
        holeBurst();   // v68b：收纳翻转露出 121px 新视口，快速查几拍空洞
        uiLog('composer-hide');
      }, 300);
    }
    // 浮动⇄全屏：同一元素 FLIP
    function morphFlip(dock, pill, from){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pill.style.transformOrigin = 'top left';
      pill.style.transform = 'translate(' + (from.x - to.x) + 'px,' + (from.y - to.y) + 'px) scale(' + (from.w / Math.max(1, to.w)) + ',' + (from.h / Math.max(1, to.h)) + ')';
      pillChildrenFade(pill, '0', 0, 0);
      void pill.offsetWidth;
      pill.style.transition = 'transform 0.26s cubic-bezier(0.2,0.8,0.2,1)';
      pill.style.transform = 'none';
      pillChildrenFade(pill, '1', 0.24, 0.05);
      var tok = ++morphTok;
      setTimeout(function(){
        if (tok !== morphTok) { return; }
        pillMorphCleanup(dock, pill);
      }, 320);
    }
    // ---------- 左下角收纳图标（v66 悬浮化）：有消息未唤出时的唯一底栏元素 ----------
    // 挂在 body 层 fixed 悬浮（不占流内空间，消息列表直接铺到屏幕底部）；
    // dock 收纳态高度为 0（见 fxStyle 零占位规则），页面让位计算随之归零。
    // 点击/上滑 = 唤出输入框，右滑 = 对话问题导航（手势处理在文件头部，这里只建节点）。
    function ensureIcon(dock){
      var floatOn = doc.documentElement.classList.contains('zcode-float-on');
      var ic = doc.getElementById('zcode-composer-icon');
      if (!floatOn) {
        // 常驻态（空对话等）：页面输入框本来就在，图标没有存在意义
        if (ic && ic.parentNode) { ic.parentNode.removeChild(ic); }
        updateGestureExclude();
        return;
      }
      if (composerOpen || dock.classList.contains('zcode-popup-mode') ||
          dock.classList.contains('zcode-popup-present')) {
        // 唤出态/弹窗态藏图标：与输入框的切换由容器变换负责，这里瞬时切换
        if (ic) { ic.style.display = 'none'; }
        updateGestureExclude();
        return;
      }
      if (!ic) {
        ic = doc.createElement('div');
        ic.id = 'zcode-composer-icon';
        ic.setAttribute('role', 'button');
        ic.setAttribute('aria-label', '展开输入框');
        ic.textContent = '✎';
        ic.addEventListener('touchstart', iconStart, {passive:true});
        ic.addEventListener('touchmove', iconMove, {passive:false});
        ic.addEventListener('touchend', iconEnd, {passive:false});
        ic.addEventListener('touchcancel', function(){ iconTrack = null; }, {passive:true});
        ic.addEventListener('pointerdown', iconPtrDown, {passive:true});
        ic.addEventListener('pointermove', iconPtrMove, {passive:true});
        ic.addEventListener('pointerup', iconPtrUp, {passive:false});
        ic.addEventListener('pointercancel', function(){ iconTrack = null; }, {passive:true});
        // fixed 定位必须挂在最外层，避免 dock 祖先的 transform 把它锚进局部坐标系
        (isShadow ? root : doc.body || doc.documentElement).appendChild(ic);
      }
      ic.style.display = 'flex';
      updateGestureExclude();
    }
    // 开关入口：开 → 收纳，关 → 唤出（保留旧名，预览页/诊断仍在用）
    function toggleComposer(){
      // v59：弹窗挂起（popup-mode）时不切换——dock 会在 popup-mode（全宽 100%）
      // 与 float（94vw/520px 收窄）两种布局间跳变，提问框跟着缩放抖动（用户实测"点按钮提问框变一下大小"）
      var dd = getDock();
      if (dd && dd.classList.contains('zcode-popup-mode')) { return; }
      if (composerOpen) { hideComposer(); }
      else { showComposer(); }
    }
    function showComposer(focus){
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
      minUpDist = 0;
      persistComposerState('open');
      dock.classList.add('zcode-composer-float');
      uiLog('composer-open fixed');
      showMask();
      // 容器变换起点：图标当前位置（图标还没建/不可见则用标准落位）
      var pill0 = dock.querySelector('.rounded-2xl');
      var ic0 = dock.querySelector('#zcode-composer-icon');
      var from = (ic0 && ic0.style.display !== 'none' && ic0.offsetWidth > 0) ? rectOf(ic0) : iconHomeRect();
      syncComposer();
      var pill = dock.querySelector('.rounded-2xl');
      if (morphable(pill, dock)) { morphReveal(dock, pill, from); }
      // 聚焦输入框：键盘跟随弹出；自动恢复态传 focus=false，不弹键盘
      if (focus !== false) {
        try {
          var ta = dock.querySelector('[data-testid^="v4-composer-input"], textarea, input[type="text"]');
          if (ta) { setTimeout(function(){ try { ta.focus(); } catch (e2) {} }, 150); }
        } catch (e) {}
      }
    }
    function toggleFullComposer(){
      var dock = getDock();
      if (!dock) { return; }
      var pill = dock.querySelector('.rounded-2xl');
      var from = morphable(pill, dock) ? rectOf(pill) : null;
      composerFull = !composerFull;
      uiLog(composerFull ? 'composer-full' : 'composer-shrink');
      syncComposer();
      if (from && pill && pill.offsetWidth > 0) { morphFlip(dock, pill, from); }
    }
    function hideComposer(){
      var dock = getDock();
      var pill = dock ? dock.querySelector('.rounded-2xl') : null;
      // 容器变换：胶囊原地缩进图标，落点瞬间才提交收纳态（全屏态先经收起钮回浮动，不走这条）
      if (dock && morphable(pill, dock) && !dock.classList.contains('zcode-composer-full')) {
        morphMinimize(dock, pill);
        return;
      }
      composerOpen = false;
      composerFull = false;
      pendingOpen = false;
      minUpDist = 0;
      persistComposerState('min');
      hideMask();
      if (dock) {
        if (ANIM_ON && !REDUCED) {
          // 收起动画：先加 closing（淡出下移），animationend 后移除状态类（动画事件兜底 600ms）
          dock.classList.add('zcode-composer-closing');
          var done = function(){
            dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
            syncComposer();   // 立刻恢复收纳图标，不等 1s 轮询
          };
          dock.addEventListener('animationend', done, {once: true});
          setTimeout(done, 600);
        } else {
          dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
          syncComposer();   // 同上
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

