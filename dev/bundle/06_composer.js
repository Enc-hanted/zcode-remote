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
    var lastIconTapAt = 0;   // v79②：最近一次图标单击时刻（双击直进全屏的判据）
    var lastIconTapXY = null;   // v80②：该次单击的落点（第二击落在图标旁的遮罩上也算双击）
    function evXY(e){
      if (e.touches) { return e.touches[0]; }
      return e;
    }
    // v69（实机"按钮拖动要有对应方向的小动画，需要交互触发的确定感"）：
    // 手势进行中图标跟手平移（0.35 倍率、±16px 封顶），触发后向上滑交给容器变换接力
    // （morph 从含偏移的实时位置起变），右滑/未触发回弹归位——跟手、接力、回弹是同一动作的三段。
    function iconFeedback(ic, dx, dy){
      if (!ic || !ANIM_ON || REDUCED) { return; }
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) { return; }
      var lim = function(v){ return Math.max(-16, Math.min(16, v)); };
      ic.style.transition = 'none';
      ic.style.transform = 'translate(' + lim(dx * 0.35).toFixed(1) + 'px,' + lim(dy * 0.35).toFixed(1) + 'px)';
    }
    function iconRelease(ic){
      if (!ic) { return; }
      ic.style.transition = '';
      ic.style.transform = '';
    }
    function iconStart(e){
      if (e.touches && e.touches.length !== 1) { iconTrack = null; return; }
      var t = evXY(e);
      iconTrack = { x: t.clientX, y: t.clientY, t0: Date.now(), done: false, dir: '' };
    }
    function iconMove(e){
      if (!iconTrack || iconTrack.done) { return; }
      var t = evXY(e);
      var dx = t.clientX - iconTrack.x, dy = t.clientY - iconTrack.y;
      iconFeedback(e.currentTarget, dx, dy);
      // v70：6px 即 preventDefault——等越阈(48px)才拦太晚，浏览器在 ~10px slop 后就把
      // 手势流抢走发 touchcancel（实机"右滑完全没反应、跟手动画会卡"的元凶）；
      // 图标 CSS 已带 touch-action:none，这里是双保险（老 WebView 不认 touch-action 时兜底）
      if (Math.abs(dx) > 6 || Math.abs(dy) > 6) { e.preventDefault(); }
      if (dx > 48 && Math.abs(dy) < 40) {
        iconTrack.done = true; iconTrack.dir = 'right';
        e.preventDefault();
        uiLog('icon-swiperight');
        vibrate();
        iconRelease(e.currentTarget);   // 右滑开导航：图标回弹归位
        if (!navOn) { hint('对话问题导航已关闭'); }
        else { toggleNavigator(); }
      } else if (dy < -44 && Math.abs(dx) < 40) {
        iconTrack.done = true; iconTrack.dir = 'up';
        e.preventDefault();
        uiLog('icon-swipeup');
        showComposer();   // 不回弹：morphReveal 以图标当前位置（含偏移）为变换起点，动作接力
      }
    }
    function iconEnd(e){
      if (!iconTrack) { return; }
      var tr = iconTrack;
      iconTrack = null;
      var ic = e.currentTarget;
      if (tr.done) {
        if (tr.dir !== 'up') { iconRelease(ic); }
        return;
      }
      iconRelease(ic);
      // touchend 用 changedTouches（手指离开位置），pointerup 用事件自身坐标
      var t = e.changedTouches ? e.changedTouches[0] : e;
      // v80②：容差 10→14px——真机手指起落天然带十几像素漂移，紧容差把正常双击拍成
      // "非 tap"（mock 合成事件零漂移所以本地测不出）；双击窗口 330→500ms 同理
      if (Date.now() - tr.t0 < 400 && Math.abs(t.clientX - tr.x) < 14 && Math.abs(t.clientY - tr.y) < 14) {
        var now = Date.now();
        if (now - lastIconTapAt < 500) {
          // v79②：双击直进全屏。首击已唤出浮动胶囊（图标在宽限窗内隐形但可接——见
          // ensureIcon），第二击落点仍是图标：等 morph 飞行结束再切全屏，避免两段
          // 变换的 morphTok 互相打架（替身清理被顶掉会漏出隐藏的真身）
          uiLog('icon-dbltap-full');
          vibrate();
          lastIconTapAt = 0;
          whenMorphDone(function(){
            if (composerOpen && !composerFull) { toggleFullComposer(); }
          });
          return;
        }
        lastIconTapAt = now;
        lastIconTapXY = { x: tr.x, y: tr.y };   // v80②：遮罩回退用（第二击落图标旁也算双击）
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
    var dockDragTy = 0;   // v69：胶囊拖动反馈的当前纵向偏移，morph 接力时要并进起始 transform
    function dockGestureArm(tg, x, y){
      if (!composerOpen || composerFull) { return null; }
      if (!tg || !tg.closest) { return null; }
      var dk = tg.closest('[data-v4-composer-dock="true"]');
      if (!dk || dk.classList.contains('zcode-popup-mode') || dk.classList.contains('zcode-popup-present')) { return null; }
      if (tg.closest('textarea, input, select, button, a, [contenteditable], [role="button"], #zcode-composer-expand')) { return null; }
      return { x: x, y: y, t0: Date.now(), done: false, pill: dk.querySelector('.rounded-2xl') };
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
      // v69：方向拖动反馈——胶囊跟手平移（0.45 倍率、±110px 封顶），越阈正式动画从跟手位置
      // 接力（morph 读实时 rect 并保留偏移），未越阈松手回弹——"这次滑动会不会触发"有了确定答案
      // v70：进入纵向拖动就 preventDefault（不论动效开关）——手势落点已排除 textarea/按钮等
      // 交互件，这里拦默认只挡浏览器抢手势（滚动/overscroll→touchcancel），不影响页面点击
      if (Math.abs(dy) > 6 && Math.abs(dx) < 56) {
        e.preventDefault();
        if (dockTrack.pill && ANIM_ON && !REDUCED) {
          dockDragTy = Math.max(-110, Math.min(110, dy * 0.45));
          dockTrack.pill.style.transition = 'none';
          dockTrack.pill.style.transform = 'translateY(' + dockDragTy.toFixed(1) + 'px)';
        }
      }
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
    function dockGestureEnd(){
      if (dockTrack && !dockTrack.done && dockTrack.pill && dockDragTy) {
        // 未越阈：带过渡回弹，和触发路径在观感上明确区分
        dockTrack.pill.style.transition = 'transform 0.18s ease';
        dockTrack.pill.style.transform = '';
      }
      dockTrack = null;
      dockDragTy = 0;
    }
    // ---------- 滑到底自动恢复输入框（v67，设置项"滑到底部恢复输入框"）----------
    // 收纳态内容满屏，滚到全部消息最底端 = 读完准备输入 → 输入框自动滑出（容器变换）。
    // 防误触：流式回复的自动跟滚一直贴着底部，不能一进收纳态就触发——
    // 只有"向上离开底部 ≥220px（页面跟滚此时已停）再滚回 ≤28px"才算"读完回来了"。
    var minUpDist = 0;
    var lastMinAt = 0;   // v69：最近一次手动收纳的时刻——收纳后短期内不自动恢复（见 watchScrollRestore）
    var restoreScEl = null;
    function watchScrollRestore(){
      if (!restoreOnBottom || !floatInput || !UI_ON || composerOpen) { return; }
      if (!doc.documentElement.classList.contains('zcode-float-on')) { return; }
      // v69（实机"点输入框外关闭，此时上滑也会再唤起一次输入框"）：刚手动收纳完的第一次
      // 上滑往往只是"滚下去看看"，不是"读完想输入"。≥220px 离底 + 4s 冷却双门槛，
      // 两个都满足才算"读完回来了"，"刚关完就被弹回"的路径被掐断
      if (lastMinAt && Date.now() - lastMinAt < 4000) { return; }
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
    // ---------- v79①：发送后归位（v80 修真机失效） ----------
    // 触发签名（双证据）：composer 开着 + 输入框刚有字 → 新的用户行出现 且 输入框已清空。
    // "手动删光字"不会有新用户行，不误触。z.ai 发送即清输入框（先于行挂载），所以"清空"
    // 只作证据不作触发——触发点始终是新用户行的出现。
    // v80 教训：真页 v4-composer-input 是 Lexical **contenteditable div**（诊断 dockHtml
    // 实锤），不是 textarea——旧版只认 TEXTAREA/INPUT.value，真机上 sendHadText 永假、
    // ①从不触发（mock 用 textarea 所以本地全绿）。文本读取改双形态兼容。
    // 归位延迟 420ms：让 z.ai 自己的发送动画先走一步，morph 收纳接在其后，两段不叠帧。
    var sendHadText = false, sendHadTextAt = 0;
    function composerTextOf(t){
      if (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT') { return t.value || ''; }
      return (t.textContent || '');   // contenteditable（真页形态）
    }
    function onComposerInput(e){
      var t = e.target;
      if (!t || !t.tagName || !t.closest) { return; }
      if (!t.closest('[data-v4-composer-dock="true"]')) { return; }
      if (t.tagName !== 'TEXTAREA' && t.tagName !== 'INPUT' &&
          !t.isContentEditable && !t.closest('[data-testid^="v4-composer-input"]')) { return; }
      if (composerTextOf(t).trim()) { sendHadText = true; sendHadTextAt = Date.now(); }
      else if (Date.now() - sendHadTextAt > 600) { sendHadText = false; }   // 发送瞬间的清空（600ms 内）不灭旗
    }
    var sendObs = null;
    try {
      sendObs = new MutationObserver(function(muts){
        if (!sendMinimize || !composerOpen || !sendHadText) { return; }
        if (Date.now() - sendHadTextAt > 6000) { sendHadText = false; return; }
        for (var i = 0; i < muts.length; i++) {
          if (muts[i].type !== 'childList') { continue; }
          var ns = muts[i].addedNodes;
          for (var j = 0; j < ns.length; j++) {
            var n = ns[j];
            if (n.nodeType !== 1) { continue; }
            var row = null;
            var tid = n.getAttribute ? (n.getAttribute('data-testid') || '') : '';
            if (tid.indexOf('v4-row') === 0) { row = n; }
            else if (n.querySelector) { row = n.querySelector('[data-testid^="v4-row"]'); }
            if (!row) { continue; }
            var cls = ' ' + (row.className || '') + ' ';
            if (cls.indexOf('group/user-row') < 0) { continue; }
            var dk = getDock();
            var ta = dk ? dk.querySelector('[data-testid^="v4-composer-input"], textarea') : null;
            if (ta && composerTextOf(ta).trim()) { continue; }   // 有字=不是发送
            sendHadText = false;
            uiLog('send-minimize');
            setTimeout(function(){
              try {
                var ae = doc.activeElement;
                if (ae && ae.blur) { ae.blur(); }   // 先收键盘：IME 逐帧 resize 与 morph 重叠是旧痛点
              } catch (e1) {}
              if (composerOpen) { hideComposer(); }
            }, 420);
            return;
          }
        }
      });
      sendObs.observe(root, {childList: true, subtree: true});
    } catch (eSO) {}

    // ---------- v79⑥：流式阅读位置线 ----------
    // 流式输出中用户上滑离开底部 → 在"离开那一刻的内容底边"插一条低可视细线，新内容
    // 继续在线下方生长；滚回底部或流式结束即淡出。锚在最后一个可见行后面（内容只在
    // 尾部追加，锚点位置稳定）；锚行被虚拟化卸载则顺手收线，不重建。
    var readLineEl = null, lastShSeen = -1, lastShGrowAt = 0;
    function trackStreamGrow(){
      var tl = overlayTl();
      if (!tl) { return; }
      var sh = tl.scrollHeight;
      if (lastShSeen < 0 || sh > lastShSeen + 2) { lastShGrowAt = Date.now(); }
      lastShSeen = sh;
    }
    function streamingNow(){ return (Date.now() - lastShGrowAt) < 2200; }
    function removeReadLine(now){
      if (!readLineEl) { return; }
      var el = readLineEl; readLineEl = null;
      if (ANIM_ON && !REDUCED && !now) {
        el.classList.add('zc-rl-out');
        setTimeout(function(){ if (el.parentNode) { el.parentNode.removeChild(el); } }, 450);
      } else {
        try { el.parentNode.removeChild(el); } catch (e0) {}
      }
    }
    function placeReadLine(tl){
      var vb = window.innerHeight;
      var rows = tl.querySelectorAll('[data-testid^="v4-row"]');
      var anchor = null;
      for (var i = rows.length - 1; i >= 0; i--) {
        var rb = rows[i].getBoundingClientRect().bottom;
        if (rb <= vb - 4 && rb > 0) { anchor = rows[i]; break; }
      }
      if (!anchor || !anchor.parentNode) { return; }
      var el = doc.createElement('div');
      el.className = 'zc-readline';
      var lab = doc.createElement('i');
      lab.textContent = '新内容 ↓';
      el.appendChild(lab);
      anchor.parentNode.insertBefore(el, anchor.nextSibling);
      readLineEl = el;
      uiLog('readline-place');
    }
    function onReadScroll(){
      var tl = overlayTl();
      if (!tl) { return; }
      if (readLineEl) {
        var dist = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
        if (dist <= 160 || !streamingNow() || !readLineEl.isConnected) { removeReadLine(); }
        return;
      }
      if (!streamingNow()) { return; }
      var dist2 = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
      if (dist2 > 260) { placeReadLine(tl); }
    }

    // ---------- v79⑦→v80：↓ 圆钮避让小白条 ----------
    // v79 走几何解析（无 USB 锚不到选择器），实机诊断实锤翻车原因：↓ 钮（v4-timeline-
    // bottom）挂在 dock 内部 data-v4-back-to-bottom-anchor 锚上（absolute/bottom-full，
    // 悬在 0 高 dock 上方 ~8px），lifter 把"整棵 dock 子树"排除时把它一起排掉了。
    // 现在选择器已知（testid 稳定），改为 02_style 的静态 CSS 直抬（transform 重写需
    // 复刻它自带的 -translate-x-1/2 居中），零轮询零测量，React 重渲染也盖不掉。

    function dockGesturePtrDown(e){
      if (e.pointerType === 'touch') { return; }
      dockTrack = dockGestureArm(e.target, e.clientX, e.clientY);
    }    function dockGesturePtrMove(e){
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
      evRoot.addEventListener('scroll', onReadScroll, {passive:true, capture:true});   // v79⑥：阅读位置线
      evRoot.addEventListener('input', onComposerInput, {passive:true, capture:true});   // v79①：发送检测的输入跟踪
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
    // v79 会话页新开关（原生层注入 window.__zcSettings，applySettings 热更；mock 预览页同形）
    // v80：⑧长按菜单用户淘汰已整套删除（longPressMenu 键随之退役，旧存档多出的键无害）
    var ZSET = (window.__zcSettings = window.__zcSettings || {});
    var sendMinimize = (ZSET.sendMinimize !== false);      // ① 发送后归位（默认开）
    var kbFollowExp = (ZSET.kbFollowExp === true);          // ③ 键盘跟随·实验（默认关）
    var amoledBlack = (ZSET.amoledBlack === true);          // ⑨ 纯黑 AMOLED（默认关）
    function applyAmoled(){
      try { doc.documentElement.classList.toggle('zc-amoled', amoledBlack); } catch (e) {}
    }
    applyAmoled();
    // v71：虚拟列表换窗时可能短暂卸载全部 v4-row；不能把一次空 query 当成"新空会话"。
    // 首次见到消息后，连续两轮（约 2 秒）都无行才允许摘 zcode-float-on，避免 dock
    // 在 0 高/原生高度之间来回跳，进而触发 resize→虚拟列表重算→再次卸载的反馈回路。
    var rowsEverSeen = false;
    var missingRowsTicks = 0;
    var ROW_MISS_LIMIT = 2;
    var lastFloatState = null;
    var lastFloatAt = 0;
    var lastFloatReason = '';
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
    function setFloatState(on, reason){
      var de = doc.documentElement;
      if (!de) { return false; }
      var prev = de.classList.contains('zcode-float-on');
      if (prev === on) { return false; }
      if (on) { de.classList.add('zcode-float-on'); }
      else { de.classList.remove('zcode-float-on'); }
      lastFloatState = on;
      lastFloatAt = Date.now();
      lastFloatReason = reason || '';
      uiLog('float-' + (on ? 'on-' : 'off-') + (reason || 'state'));
      return true;
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
    var bodyPopupObs = null;   // v76：body 弹窗监听事件化（见 scanBodyPopups）
    function scanBodyPopups(){
      if (!UI_ON) { return; }
      // v76 瘦身：Radix 弹窗 portal 挂/卸在 body 直接子层——childList 观察器即时触发
      // 扫描（比 400ms 轮询延迟更低）；400ms 轮询降级为 2s 安全网，兜非 portal 场景与
      // 观察器异常。观察器只看 childList，与 class 写入无反馈环。
      if (!bodyPopupObs && doc.body) {
        try {
          bodyPopupObs = new MutationObserver(function(){
            scanBodyPopups();
            setTimeout(scanBodyPopups, 120);   // portal 挂载后内容/几何晚一拍到位
            setTimeout(scanBodyPopups, 400);
          });
          bodyPopupObs.observe(doc.body, { childList: true });
        } catch (e0) { bodyPopupObs = null; }
      }
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
    // ---------- 底部残留清理（v68/v69/v70） ----------
    // 收纳态的底部空白来源不止一处，逐一测量回收，唤出态全部原样恢复：
    // A) 容器级残留（v70 升级为祖先链）：实机 0.0.4 空白带依旧——v64 取证就写明 3.14.4
    //    把让位 padding 挂在 data-v4-timeline-content-column 等「末节的祖先」节点上，
    //    v69 只收 timeline 自身 padding / 末节 margin / 末节后空兄弟，够不着祖先链。
    //    现改为：末节 margin-bottom → 沿 lastSec 的祖先链逐层上溯到滚动容器（含），
    //    收每层 padding-bottom + 每层在路径节点之后的无正文空壳兄弟。
    //    口径不变：phantom = scrollHeight − 末节实际底边，>12px 才动手；
    //    每笔回收落 uiLog（tail-pad/tail-margin/tail-el），诊断卡可查到底收掉了什么。
    // B) 末节内部的 min-h-5 垫片（v68）：隐藏后 flex 尾随 gap 一并消失，共回收 40px。
    // 虚拟列表随时增删 turn：每次轮询重查、按引用恢复旧节点（已卸载则无害）；
    // 并校验垫片确实位于内容末尾（末条是用户消息时不动）；有变更才 nudge 虚拟列表重算。
    var tailSpacerEl = null;
    var tailFixed = [];   // v69：A 类回收记录 [{el, prop, val}]，唤出态恢复
    // v71：去重与互斥状态——签名未变整轮跳过；nudge 时刻供空洞看门狗让位
    var tailSig = null;
    var tailFixAt = 0;
    var tailFixPhantom = 0;
    var tailNudgeAt = 0;
    var tailShLast = -1;   // v75：上拍内容高度（静止闸门，见 syncTailBlank）
    function tailRestoreAll(){
      if (tailSpacerEl) { tailSpacerEl.style.display = ''; tailSpacerEl = null; }
      for (var i = 0; i < tailFixed.length; i++) {
        try { tailFixed[i].el.style[tailFixed[i].prop] = tailFixed[i].val; } catch (eF) {}
      }
      tailFixed = [];
      tailSig = null;   // v71：修复被恢复 = 状态回退，作废签名让下轮重新评估（否则签名锁死、残留永不重收）
    }
    function tailNudge(){
      tailNudgeAt = Date.now();
      try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
      try {
        var tl2 = root.querySelector('[data-testid="v4-timeline"]');
        if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
      } catch (e3) {}
    }
    // ---------- 悬浮输入/弹窗让位垫（v72） ----------
    // dock 悬浮(fixed)/弹窗期脱离文档流，消息列表不知道底部被盖住——最后一条消息
    // 滚不出输入框上方（实机"关掉键盘后输入框挡住文本结尾"）。悬浮/弹窗态给滚动
    // 容器垫 paddingBottom，收纳态撤掉。只认自己写的 inline 值，与尾清的配合固定：
    // · 收纳态在 syncTailBlank 之前撤垫（防止垫被算进 phantom 乱收页面节点）
    // · 悬浮态在 syncTailBlank 还原之后落垫（防止还原把垫盖掉）
    var overlayPadPx = 0;
    function overlayTl(){
      return root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
    }
    function needOverlayPad(dock){
      if (!dock) { return 0; }
      if (composerOpen && !composerFull) {
        // 悬浮胶囊：胶囊实高 + 底部偏移14 + 安全区 + 呼吸位；胶囊内 textarea 变高
        // 时 offsetHeight 跟着变，1s 轮询自适应
        var dh = dock.offsetHeight;
        if (dh > 8 && dh < window.innerHeight * 0.6) { return dh + 14 + safeB() + 10; }
        return 0;
      }
      if (!composerOpen && (dock.classList.contains('zcode-popup-mode') || dock.classList.contains('zcode-popup-present'))) {
        // 弹窗态 dock 同样 fixed 铺在底部
        var ph = dock.offsetHeight;
        if (ph > 8 && ph < window.innerHeight * 0.6) { return ph + safeB() + 8; }
      }
      return 0;
    }
    function setOverlayPad(want){
      // v78：morph 飞行中不落垫（真身隐藏中，垫的变化会穿帮）——落位回调的
      // syncComposer 会补上；1s 轮询在飞行窗口撞进来也由此挡住
      var dk = getDock();
      if (dk && dk.classList.contains('zcode-composer-morph')) { return; }
      var t = overlayTl();
      if (!t || want <= 0) { return; }
      var vs = want + 'px';
      if (overlayPadPx === want && t.style.paddingBottom === vs) { return; }
      overlayPadPx = want;
      try { t.style.paddingBottom = vs; } catch (e0) { return; }
      tailNudge();
    }
    function clearOverlayPad(){
      if (overlayPadPx <= 0) { return; }
      overlayPadPx = 0;
      var t = overlayTl();
      if (t) { try { t.style.paddingBottom = ''; } catch (e1) {} }
      tailSig = null;   // 撤垫后页面自己的 padding 会露回来，让尾清重新评估
      tailNudge();
    }
    function syncTailBlank(dock){
      var minimized = dock && !dock.classList.contains('zcode-composer-float') &&
        !dock.classList.contains('zcode-composer-full') &&
        !dock.classList.contains('zcode-popup-mode');
      if (!minimized) {
        tailSig = null;   // v71：状态翻转后作废签名，回收纳态时重新评估
        // v72：悬浮/全屏/弹窗态不还原外层壳——dock 是 fixed，槽位用不上；还原会
        // 和悬浮让位垫叠成双重预留（胶囊下面一段死区，实机截图 B 的形态）。
        // 只在退回原生常驻态（float-on 关闭，页面输入框回到底部）时全部还原。
        if (!doc.documentElement.classList.contains('zcode-float-on') && (tailSpacerEl || tailFixed.length)) {
          tailRestoreAll(); tailNudge();
        }
        return;
      }
      var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl) { return; }
      // v71：缺行待确认/动画进行中跳过——虚拟列表正在换窗，此刻测量不可信，
      // 收了也会被重挂载弹回（"每秒收一遍-弹一遍"脉动的另一半）
      if (missingRowsTicks > 0 || dock.classList.contains('zcode-composer-morph') ||
          dock.classList.contains('zcode-composer-closing')) { return; }
      // v75 静止闸门（回复中每秒上下闪动的根修，实机变异日志抓的现行）：
      // 流式输出期尾清把空占位条 display:none（内容 −40px）→ React 流式重渲染整段
      // 抹掉我们写的内联样式（+40px 回来）→ 下一秒轮询再收……攻防战让钉底页面每秒
      // ±40px 上下跳。占位条在流式期间留着无害（内容一直在长，尾部本来就看不精确），
      // 一律不动手；scrollHeight 与上一拍相同（连续两拍稳定 = 流式停、滚动停稳）才清理。
      // 自身改动造成的高度变化同样只多等一拍，幂等收敛；撤垫（tailSig=null）的重评估
      // 也顺延一拍，代价可忽略。
      var shNow = tl.scrollHeight;
      if (shNow !== tailShLast) { tailShLast = shNow; return; }
      var changed = false;
      try {
        var secs = tl.querySelectorAll('section');
        if (secs.length) {
          var cr = tl.getBoundingClientRect();
          var lastSec = secs[secs.length - 1];
          var phantom = tl.scrollHeight - (lastSec.getBoundingClientRect().bottom - cr.top + tl.scrollTop);
          // v71：签名去重——末节与 phantom 都没变 = 上一轮修复仍在位（回收本身幂等），
          // 整轮跳过，不再测量/改写/通知；垫片状态随签名未变也必然稳定，一并跳过
          var sig = (((lastSec.getAttribute('data-testid') || lastSec.className || '')) + '').slice(0, 60) +
            '|' + Math.round(phantom) + '|' + tl.scrollHeight;
          if (sig === tailSig) { return; }
          tailSig = sig;
          // 上限闸门：>240px 的"空白"多半不是垫片/容器机制，而是虚拟列表只渲染了顶部区间
          // （未渲染区没有节点可回收）——那是 v68b 空洞看门狗的管辖，乱收会炸掉虚拟化
          if (phantom > 12 && phantom < 240) {
            tailFixAt = Date.now();
            tailFixPhantom = Math.round(phantom);
            var mb = parseFloat(getComputedStyle(lastSec).marginBottom) || 0;
            if (mb > 4 && mb < phantom) {
              tailFixed.push({ el: lastSec, prop: 'marginBottom', val: lastSec.style.marginBottom });
              lastSec.style.marginBottom = '0px';
              phantom -= mb; changed = true; uiLog('tail-margin');
            }
            // v70：祖先链上溯（含滚动容器本身）——每层收 padding-bottom，以及该层在
            // 路径节点之后的无正文空壳兄弟（测高残留/占位条）；SECTION（真消息）与
            // zcode 自家节点、有正文的节点绝不动。
            // v72：空壳判断 textContent → innerText——textContent 不看渲染，display:none
            // 的 dock 子树文字也算"有正文"，包着隐形 dock 的占位壳永远漏判；innerText
            // 按渲染取文，隐形即空壳
            var child = lastSec;
            var anc = lastSec.parentElement;
            while (anc && phantom > 12) {
              var pbc = parseFloat(getComputedStyle(anc).paddingBottom) || 0;
              if (pbc > 4 && pbc <= phantom + 12) {
                tailFixed.push({ el: anc, prop: 'paddingBottom', val: anc.style.paddingBottom });
                anc.style.paddingBottom = '0px';
                phantom -= pbc; changed = true; uiLog('tail-pad');
              }
              var kids = anc.children, after = false;
              for (var k = 0; k < kids.length; k++) {
                var ke = kids[k];
                if (ke === child) { after = true; continue; }
                if (!after || ke.tagName === 'SECTION' || (ke.id && ke.id.indexOf('zcode-') === 0)) { continue; }
                if (dock && ke.contains && ke.contains(dock)) { continue; }   // v72：dock 壳交给外底带逻辑
                var kt = '';
                try { kt = (ke.innerText === undefined ? ke.textContent : ke.innerText) || ''; } catch (eI) { kt = ke.textContent || ''; }
                if ((kt || '').replace(/\s+/g, '').length) { continue; }   // 渲染出文字=真内容，绝不动
                var kh = ke.offsetHeight;
                if (kh > 4 && kh <= phantom + 12) {
                  tailFixed.push({ el: ke, prop: 'display', val: ke.style.display });
                  ke.style.display = 'none';
                  phantom -= kh; changed = true; uiLog('tail-el');
                }
              }
              if (anc === tl) { break; }
              child = anc;
              anc = anc.parentElement;
            }
          }
        }
        // ---------- 容器外底带（v72） ----------
        // 实机"收纳态底部仍有 ~70px 空带"的另一半：phantom 只量得到 tl 内部，tl 视口
        // 底缘到屏底的占位（dock 槽位圈的壳）在容器外。收纳态（float-on 且未唤出，
        // 图标/胶囊全 fixed 不占流）直接量 outerGap = 屏底 − tl 底缘，>12px 才动外层，
        // 从 tl 父层起最多上探三层：
        // · 每层收 padding-bottom；
        // · tl 之后的无壳兄弟（innerText 空壳 + 高度闸门 ≤ gap+12）display:none；
        // · 包着 dock 的壳只收自身 padding-bottom / min-height，绝不 display:none
        //   ——popup-mode 靠 dock 复活，祖先 display:none 连 fixed 弹窗一起埋掉。
        // 一层没有收出任何变化就停（再往上是页面主干，不该由底部清理去动）。
        // float-on 关闭（空会话原生输入框在底部）时外底带就是 dock 本尊，绝不能碰。
        if (doc.documentElement.classList.contains('zcode-float-on')) {
          var oChild = tl;
          var oAnc = tl.parentElement;
          for (var oLvl = 0; oAnc && oLvl < 3; oLvl++) {
            var oGap = window.innerHeight - tl.getBoundingClientRect().bottom;
            if (oGap <= 12 || oGap > 240) { break; }
            var oMoved = false;
            var oPb = parseFloat(getComputedStyle(oAnc).paddingBottom) || 0;
            if (oPb > 4 && oPb <= oGap + 12) {
              tailFixed.push({ el: oAnc, prop: 'paddingBottom', val: oAnc.style.paddingBottom });
              oAnc.style.paddingBottom = '0px';
              oMoved = true; changed = true; uiLog('tail-pad-out');
            }
            var oKids = oAnc.children, oAfter = false;
            for (var ok = 0; ok < oKids.length; ok++) {
              var oe = oKids[ok];
              if (oe === oChild) { oAfter = true; continue; }
              if (!oAfter || oe.tagName === 'SECTION' || (oe.id && oe.id.indexOf('zcode-') === 0)) { continue; }
              var oh = oe.offsetHeight;
              if (oh <= 4 || oh > oGap + 12) { continue; }
              if (dock && oe.contains && oe.contains(dock)) {
                // dock 壳：只收自身的 padding-bottom / min-height（高度来源是自身
                // padding/min-height 时会随之塌掉），display 碰都不碰
                var opb = parseFloat(getComputedStyle(oe).paddingBottom) || 0;
                if (opb > 4 && opb <= oGap + 12) {
                  tailFixed.push({ el: oe, prop: 'paddingBottom', val: oe.style.paddingBottom });
                  oe.style.paddingBottom = '0px';
                  oMoved = true; changed = true; uiLog('tail-dockpad-out');
                }
                var omh = parseFloat(getComputedStyle(oe).minHeight) || 0;
                if (omh > 4 && omh <= oGap + 12) {
                  tailFixed.push({ el: oe, prop: 'minHeight', val: oe.style.minHeight });
                  oe.style.minHeight = '0px';
                  oMoved = true; changed = true; uiLog('tail-dockmin-out');
                }
                continue;
              }
              var ot = '';
              try { ot = (oe.innerText === undefined ? oe.textContent : oe.innerText) || ''; } catch (eO) { ot = oe.textContent || ''; }
              if ((ot || '').replace(/\s+/g, '').length) { continue; }   // 渲染出文字=真内容，绝不动
              tailFixed.push({ el: oe, prop: 'display', val: oe.style.display });
              oe.style.display = 'none';
              oMoved = true; changed = true; uiLog('tail-el-out');
            }
            if (!oMoved) { break; }
            oChild = oAnc;
            oAnc = oAnc.parentElement;
          }
        }
      } catch (eA) {}
      var want = null;
      var all = tl.querySelectorAll('section .min-h-5');
      if (all.length) {
        var last = all[all.length - 1];
        try {
          var cr2 = tl.getBoundingClientRect();
          var sb = last.getBoundingClientRect().bottom - cr2.top + tl.scrollTop;
          if (tl.scrollHeight - sb <= 60) { want = last; }   // 不在内容末尾的不动
        } catch (eB) {}
      }
      if (want !== tailSpacerEl) {
        if (tailSpacerEl) { tailSpacerEl.style.display = ''; }
        tailSpacerEl = want || null;
        if (tailSpacerEl) { tailSpacerEl.style.display = 'none'; }
        changed = true;
      }
      if (changed) { tailNudge(); }
    }
    // ---------- 虚拟列表空洞修补（v68b；v73 升级） ----------
    // 上滚/收纳翻转后，虚拟列表（@tanstack/react-virtual）按旧区间渲染：视口底部会留一段
    // 没有任何 section 覆盖的"空洞"（真机渲染节流下可持续数秒——用户实测"滚到上面
    // 底部还是有空白占位"）。v68b 的 1px 抖动×6 次对"区间算错型"空洞无效且耗尽后永久沉默。
    // v73 四点升级：
    // ① 探测点 x 取 30%/50%/70% 三点，任一命中内容即算覆盖——页面自带的"滚动到底部"
    //   圆钮悬在底部中央（28×28@171-199,742-770），单点探测会被它挡成假空洞；
    //   自家浮层（面板/遮罩/toast，id 以 zcode- 开头）盖住的点不算数也不算空洞；
    // ② 抖动升级三档：1px×2 →（无效）resize+scroll 双派发逼虚拟列表重测视口 rect →
    //   （仍无效）±8px 真位移——区间算错型空洞只有滚动位置真变才会触发重算；
    // ③ 不再 6 次后永久沉默：持续补到 24 拍（500ms 一拍），用户一亲手滚动就清零重来；
    //   自己抖动的回声（300ms 内）不当用户输入，绝不跟人抢滚动；
    // ④ 诊断卡 holeState：covered/gapPx（视口底到最低 section 底缘的实测距离）/tries/
    //   userScrollAgo——滚到上面复现空白后发一次诊断即可定位到档位。
    var holeTries = 0;
    var holeLast = 0;
    var holeCovered = -1;      // -1=本轮未判定 1=覆盖 0=空洞 2=自家浮层挡着（不定）
    var holeGapPx = -1;        // 视口底到最低已渲染 section 底缘的距离
    var holeLastAt = 0;
    var lastUserScrollAt = 0;
    var holeSelfAt = 0;        // 自己抖动 scrollTop 的时刻（区分回声与用户滚动）
    var holeTlEl = null;
    function holeProbePoint(fx){
      var el = null;
      try { el = doc.elementFromPoint(Math.round(window.innerWidth * fx), window.innerHeight - 2); } catch (e0) {}
      for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
        if (n.tagName === 'SECTION' || (n.getAttribute && (n.getAttribute('data-testid') || '').indexOf('v4-row') === 0)) { return 1; }
        if (n.id && n.id.indexOf('zcode-') === 0) { return 2; }
      }
      return 0;
    }
    function onHoleUserScroll(){
      if (Date.now() - holeSelfAt < 300) { return; }   // 自己抖的回声，不当用户输入
      lastUserScrollAt = Date.now();
      holeTries = 0;   // 用户亲手滚动 = 虚拟列表正被真实事件驱动，计数重来
    }
    function ensureHoleScrollListen(){
      var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl || tl === holeTlEl) { return; }
      if (holeTlEl) { try { holeTlEl.removeEventListener('scroll', onHoleUserScroll, true); } catch (e0) {} }
      holeTlEl = tl;
      try { tl.addEventListener('scroll', onHoleUserScroll, { passive: true, capture: true }); } catch (e1) {}
    }
    function fixViewportHole(){
      if (composerOpen || composerFull) { holeTries = 0; return; }
      var tl = holeTlEl || root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl) { return; }
      var p1 = holeProbePoint(0.3), p2 = holeProbePoint(0.5), p3 = holeProbePoint(0.7);
      var covered = (p1 === 1 || p2 === 1 || p3 === 1);
      // 自家浮层盖住任一探测点（面板/遮罩开着）：不定态，不出手也不清零
      if (!covered && (p1 === 2 || p2 === 2 || p3 === 2)) {
        holeCovered = 2; holeLastAt = Date.now(); return;
      }
      holeCovered = covered ? 1 : 0;
      holeLastAt = Date.now();
      // 空洞高度取证：最低 section 底缘离视口底多远（无 section = -1）。
      // v76 瘦身：只在检出空洞（!covered，罕见路径）时才全量扫 section——covered 是
      // 常态，每秒白扫十几个 rect 纯为诊断数据；诊断卡改为手势时刻现算（04_theme）
      holeGapPx = -1;
      if (!covered) {
        try {
          var secs = tl.querySelectorAll('section');
          var low = -1;
          for (var si = 0; si < secs.length; si++) {
            var sb = secs[si].getBoundingClientRect().bottom;
            if (sb > low) { low = sb; }
          }
          if (low >= 0) { holeGapPx = Math.round(window.innerHeight - low); }
        } catch (eS) {}
      }
      var dist = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
      if (covered || dist <= 40) { holeTries = 0; return; }
      // v71：尾部清理刚通知过虚拟列表重算，先让窗口落定再判定——两个修复器不抢同一帧
      if (Date.now() - tailNudgeAt < 600) { return; }
      var now = Date.now();
      if (now - holeLast > 500 && holeTries < 24) {
        holeTries++; holeLast = now;
        holeSelfAt = Date.now();
        if (holeTries <= 2) {
          if (tl.scrollTop > 0) { tl.scrollTop -= 1; }
          setTimeout(function(){ try { holeSelfAt = Date.now(); tl.scrollTop += 1; } catch (e1) {} }, 30);
          uiLog('hole-nudge');
        } else if (holeTries <= 6) {
          // 升级①：双派发——resize 逼虚拟列表重测滚动容器 rect（视口高度变化型空洞）
          try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
          try { tl.dispatchEvent(new Event('scroll')); } catch (e3) {}
          uiLog('hole-reshout');
        } else {
          // 升级②：真位移 ±8px——区间算错型空洞只有滚动位置真变才触发重算，
          // 异步弹回原位，视觉上是 8px 的一下轻颤
          if (tl.scrollTop > 8) { tl.scrollTop -= 8; }
          setTimeout(function(){ try { holeSelfAt = Date.now(); tl.scrollTop += 8; } catch (e4) {} }, 60);
          uiLog('hole-kick');
        }
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
      // 新 session 时 React 重挂载，无 v4-row → 输入框可见，符合"新对话不隐藏"的预期。
      // v71（实机"底部空白/填满一秒一变"）：hasRows 加迟滞——虚拟列表换窗/重渲染会瞬时
      // 卸载全部行，单次空 query 不再直接摘 zcode-float-on（摘了 dock 立刻弹回原生高度、
      // section padding 回弹，下轮行回来又收回去，正好一秒一闪）。有行立即确认；
      // 无行需连续两轮且不在展开/弹窗/动画态，才认定真的是空会话翻回常驻输入框。
      var wasFloat = doc.documentElement.classList.contains('zcode-float-on');
      var hasRows = root.querySelector('[data-testid^="v4-row"]');
      if (hasRows) {
        rowsEverSeen = true;
        missingRowsTicks = 0;
        setFloatState(true, 'rows');
      } else {
        var dockAnimating = dock.classList.contains('zcode-composer-morph') ||
          dock.classList.contains('zcode-composer-closing');
        if (!composerOpen && !dock.classList.contains('zcode-popup-mode') && !dockAnimating) {
          missingRowsTicks++;
          if (missingRowsTicks >= ROW_MISS_LIMIT) {
            rowsEverSeen = false;
            missingRowsTicks = 0;
            setFloatState(false, 'empty-session');
          }
        }
      }
      // v62：dock 隐藏后触发虚拟列表重算——zcode 的 message 虚拟列表按"dock 在底部"预留空间，
      // dock display:none 后预留的 ~121px 不会被回收（最后一条消息不下移，底部空一块）。
      // dispatch resize 让虚拟列表（@tanstack/react-virtual 监听视口变化）重新测量并填满。
      // v71：setFloatState 只在 class 真正翻转时返回 true，故这里每秒轮询不会重复 dispatch
      if (doc.documentElement.classList.contains('zcode-float-on') !== wasFloat) {
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
      // 把当前状态落到 live 节点（重挂载后 class 会丢，这里补回来）。必须先移除两个类
      // 再只加一个：如果只 add，从全屏缩回浮动时 zcode-composer-full 会残留，CSS 里
      // full 规则排在 float 之后且特异性相同 → full 永远赢 → 合不上（v38 修）。
      // v71：closing/morph 动画进行中不动状态类、不派发高度变化——1s 轮询若在动画窗口
      // 内重写会把过渡掐断成跳变（v69 只守了 closing，这里补齐 morph）；动画落定处的
      // 提交回调（morphMinimize/closing done）都会再调 syncComposer，在稳定帧补测
      var dockAnimating = dock.classList.contains('zcode-composer-closing') ||
        dock.classList.contains('zcode-composer-morph');
      if (!dockAnimating) {
        dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
        if (composerOpen) {
          dock.classList.add(composerFull ? 'zcode-composer-full' : 'zcode-composer-float');
        }
      }
      ensureIcon(dock);   // v66：收纳图标同步（悬浮层，跟随 float-on/open/popup 状态显隐 + 手势豁免上报）
      applySafeB();   // v70：底部安全区 → --zc-safe-b（图标/悬浮/全屏 bottom 引用）
      ensureHoleScrollListen();   // v73：滚动容器监听（用户亲手滚动 → 空洞计数清零；重挂载自动续接）
      // v72：悬浮/弹窗让位垫（见 needOverlayPad 注释）——收纳态先撤垫再测尾部，
      // 悬浮/弹窗态先尾部还原、后落垫
      var padNeed = needOverlayPad(dock);
      if (padNeed === 0) { clearOverlayPad(); }
      syncTailBlank(dock);   // v68/v69：收纳态底部残留逐一测量回收，末条消息贴屏底
      if (padNeed > 0) { setOverlayPad(padNeed); }
      // v66：dock 流内高度变化（收纳 0 ⇄ 唤出悬浮高 ⇄ 常驻 121）时通知虚拟列表重算，
      // 让位跟着新高度走，旧高度不会残留成底部空带。v71：动画进行中跳过——动画中的
      // 中间高度没有意义，落定后的 syncComposer 会补上这次测量与派发
      if (!dockAnimating) {
        var dh = dock.offsetHeight;
        if (dh !== lastDockH) {
          lastDockH = dh;
          try { window.dispatchEvent(new Event('resize')); } catch (e4) {}
          try {
            var tl2 = root.querySelector('[data-testid="v4-timeline"]');
            if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
          } catch (e5) {}
        }
      }
      // v68b：收纳态视口底部空洞检测+抖动修补（上滚/翻转后虚拟列表没跟上时）
      fixViewportHole();
      // v79⑥：流式增长跟踪（阅读位置线的"正在流式"判据）+ 阅读线保洁
      trackStreamGrow();
      if (readLineEl && (!streamingNow() || !readLineEl.isConnected)) { removeReadLine(true); }
      applyKbLift();   // v79③：键盘实验模式——胶囊高度变化（多行）时重算抬升量
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
      // 收纳图标悬浮位（CSS: left:12px bottom:12px+安全区，44px）
      return { x: 12, y: window.innerHeight - 56 - safeB(), w: 44, h: 44 };
    }
    // 底部安全区 → CSS 变量（v70）：原生层写 window.__zcodeSafeB，这里落到
    // documentElement 的 --zc-safe-b，fxStyle 里图标/悬浮/全屏/弹窗的 bottom 全部引用。
    // 挂 syncComposer（1s 轮询 + 状态切换），值变化才写；页面重载后首轮即生效。
    var lastSafeB = -1;
    function applySafeB(){
      var b = safeB();
      if (b === lastSafeB) { return; }
      lastSafeB = b;
      try { doc.documentElement.style.setProperty('--zc-safe-b', b + 'px'); } catch (e) {}
    }
    // ---------- v79③：键盘跟随·实验 ----------
    // 原生 exp 模式不缩放 WebView（页面零重排），键盘起/落各报一次最终高度（__zcKb）。
    // 这里换算"需要抬升的像素"：胶囊底缘越过键盘顶沿多少就抬多少，且不超过
    // (胶囊顶缘-8px)——全屏态胶囊很高时只抬差值，不顶出屏。CSS 过渡（02_style
    // zc-kb-on 规则）让抬升本身是动画；1s 轮询里补算（多行输入胶囊变高时重算）。
    var kbH = 0, kbLift = 0;
    var kbCalls = 0, kbLastAt = 0;   // v80③：桥接埋点——下次诊断分辨"原生没报"vs"报了没生效"
    function calcKbLift(){
      if (kbH <= 0 || !composerOpen) { return 0; }
      var dk = getDock();
      var pill = dk ? dk.querySelector('.rounded-2xl') : null;
      if (!pill || !pill.offsetWidth) { return 0; }
      var r = pill.getBoundingClientRect();
      var overflow = r.bottom - (window.innerHeight - kbH);
      if (overflow <= 0) { return 0; }
      return Math.max(0, Math.min(overflow, r.top - 8));
    }
    function applyKbLift(){
      var lift = calcKbLift();
      if (lift === kbLift && (kbH > 0) === doc.documentElement.classList.contains('zc-kb-on')) { return; }
      kbLift = lift;
      try {
        doc.documentElement.style.setProperty('--zc-kb-lift', lift + 'px');
        doc.documentElement.classList.toggle('zc-kb-on', kbH > 0);
      } catch (e) {}
    }
    function setKbH(h){
      kbH = (typeof h === 'number' && h > 0 && h < window.innerHeight * 0.9) ? h : 0;
      applyKbLift();
    }
    try {
      window.__zcKb = function(h){
        kbCalls++;
        kbLastAt = Date.now();
        setKbH(h);
      };
    } catch (eK0) {}
    // v80③ 兜底：实验模式下若 WebView 未缩放但 Chromium 仍更新 visualViewport（部分
    // 版本会），高度差直接当键盘高度用——原生桥（__zcKb）正常时两者一致，无害
    try {
      if (window.visualViewport && window.visualViewport.addEventListener) {
        window.visualViewport.addEventListener('resize', function(){
          if (!kbFollowExp || !composerOpen) { return; }
          var implied = Math.round(window.innerHeight - window.visualViewport.height);
          if (implied > 60 && Math.abs(implied - kbH) > 24) {
            kbCalls += 100;   // 埋点标记：这条来自 vv 兜底（≥100 即 vv 生效过）
            setKbH(implied);
          }
        });
      }
    } catch (eVV) {}
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
      pill.style.visibility = '';
      pillChildrenFade(pill, '', 0, 0);
    }
    function morphable(pill, dock){
      return ANIM_ON && !REDUCED && !!pill && !!dock &&
        !dock.classList.contains('zcode-popup-mode') && !dock.classList.contains('zcode-popup-present');
    }
    // v78 morph 幽灵替身：真实胶囊 visibility:hidden，替身（复刻胶囊的背景/描边/阴影/
    // 圆角）用 left/top/width/height 尺寸动画飞行。旧方案 transform:scale 非均匀缩放
    // （sx≠sy）会把圆角与描边粗细压扁拉长——飞行中途那帧"丑陋椭圆"就是它；尺寸动画
    // 的几何全程正确。落位（dur+40ms）换回真身并回调 settle；morphTok 防飞行中再触发
    // 的旧回调提交状态（新飞行接管一切）。
    function morphGhost(pill, from, to, dur, radiusEnd, settle){
      var cs;
      try { cs = getComputedStyle(pill); } catch (e0) { cs = null; }
      var g = doc.createElement('div');
      g.className = 'zcode-morph-ghost';
      g.style.left = from.x + 'px';
      g.style.top = from.y + 'px';
      g.style.width = Math.max(1, from.w) + 'px';
      g.style.height = Math.max(1, from.h) + 'px';
      if (cs) {
        g.style.background = cs.backgroundColor;
        g.style.border = cs.borderTopWidth + ' ' + cs.borderTopStyle + ' ' + cs.borderTopColor;
        g.style.boxShadow = cs.boxShadow;
      }
      g.style.borderRadius = (Math.abs(from.w - from.h) < 2 ? '50%' : (radiusEnd || '22px'));
      (doc.body || doc.documentElement).appendChild(g);
      pill.style.visibility = 'hidden';
      void g.offsetWidth;   // 起点帧先生效，否则 transition 不触发
      var ease = ' cubic-bezier(0.2,0.8,0.2,1)';
      g.style.transition = 'left ' + dur + 'ms' + ease + ',top ' + dur + 'ms' + ease +
        ',width ' + dur + 'ms' + ease + ',height ' + dur + 'ms' + ease +
        ',border-radius ' + dur + 'ms ease';
      g.style.left = to.x + 'px';
      g.style.top = to.y + 'px';
      g.style.width = Math.max(1, to.w) + 'px';
      g.style.height = Math.max(1, to.h) + 'px';
      g.style.borderRadius = radiusEnd || '22px';
      var tok = ++morphTok;
      setTimeout(function(){
        try { g.parentNode.removeChild(g); } catch (e1) {}
        if (tok !== morphTok) { return; }   // 已被新飞行接管，真身可见性归新飞行管
        try { pill.style.visibility = ''; } catch (e2) {}
        settle();
      }, dur + 40);
    }
    // 唤出：真身先就位（float 态）但隐藏，替身从图标位置长大到胶囊落位；
    // 落位后才落垫+状态同步（settle 回调），垫的 padding 过渡见 02_style
    function morphReveal(dock, pill, from, after){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pillChildrenFade(pill, '0', 0, 0);   // 内容随落位淡入，避免换回真身时瞬现
      var rad = '';
      try { rad = getComputedStyle(pill).borderRadius || ''; } catch (e0) {}
      morphGhost(pill, from, to, 280, rad, function(){
        pillMorphCleanup(dock, pill);
        pillChildrenFade(pill, '1', 0.12, 0);
        if (after) { after(); }
      });
    }
    // 收纳：替身原地缩进图标落位，落点瞬间提交收纳态（期间保持开态，避免轮询中途翻转）。
    // v69：拖动反馈的 dockDragTy 偏移并入替身起点，飞行从跟手位置继续
    function morphMinimize(dock, pill){
      var to = iconHomeRect();
      var from = rectOf(pill);
      from.y += (dockDragTy || 0);
      dock.classList.add('zcode-composer-morph');
      morphGhost(pill, from, to, 260, '50%', function () {
        composerOpen = false;
        composerFull = false;
        pendingOpen = false;
        minUpDist = 0;   // 收纳态重新计数"离开底部的距离"
        lastMinAt = Date.now();   // v69：手动收纳时刻（自动恢复的冷却起点）
        dockDragTy = 0;
        persistComposerState('min');
        hideMask();
        dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
        pillMorphCleanup(dock, pill);
        syncComposer();
        holeBurst();   // v68b：收纳翻转露出 121px 新视口，快速查几拍空洞
        uiLog('composer-hide');
      });
    }
    // 浮动⇄全屏：同一元素 FLIP（替身飞行，真身落位后现身）
    function morphFlip(dock, pill, from){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pillChildrenFade(pill, '0', 0, 0);   // 内容随落位淡入，避免换回真身时瞬现
      var rad = '';
      try { rad = getComputedStyle(pill).borderRadius || ''; } catch (e0) {}
      morphGhost(pill, from, to, 260, rad, function(){
        pillMorphCleanup(dock, pill);
        pillChildrenFade(pill, '1', 0.12, 0);
      });
    }
    // v79②：等 morph/closing 动画窗口过去再执行 fn（双击直进全屏要避开飞行窗口，
    // 两段 morph 的 morphTok 会互相顶掉对方的替身清理，漏出隐藏中的真身）
    function whenMorphDone(fn, tries){
      var dk = getDock();
      if (dk && (dk.classList.contains('zcode-composer-morph') || dk.classList.contains('zcode-composer-closing'))) {
        if ((tries || 0) < 14) { setTimeout(function(){ whenMorphDone(fn, (tries || 0) + 1); }, 60); }
        return;
      }
      fn();
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
        // v79②：双击宽限——图标单击唤出后 520ms 内改"隐形但可接"（opacity:0 仍收触摸，
        // display:none 会收不到第二击），morph 替身从图标位起飞的同时图标淡出，无双影；
        // 宽限窗外维持原瞬时隐藏。窗口=双击判定 500ms + 20ms 余量
        if (composerOpen && lastIconTapAt > 0 && Date.now() - lastIconTapAt < 520 && ic) {
          ic.style.opacity = '0';
          updateGestureExclude();
          return;
        }
        // 唤出态/弹窗态藏图标：与输入框的切换由容器变换负责，这里瞬时切换
        if (ic) { ic.style.display = 'none'; ic.style.opacity = ''; }
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
      ic.style.opacity = '';   // v79②：清掉双击宽限期的隐形
      ic.style.transform = '';   // v69：清掉上次手势的跟手偏移，图标每次复出都从原位开始
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
    function showComposer(autofocus){
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
      // v72：垫上让位垫之前记住是否在底部——垫上后把内容顶到垫上方，
      // 否则开输入框的瞬间最后一条消息就沉到胶囊底下
      var tlKeep = overlayTl();
      var wasBottom = false;
      if (tlKeep) { try { wasBottom = tlKeep.scrollHeight - tlKeep.scrollTop - tlKeep.clientHeight < 160; } catch (eK) {} }
      minUpDist = 0;
      persistComposerState('open');
      dock.classList.add('zcode-composer-float');
      uiLog('composer-open fixed');
      showMask();
      // 容器变换起点：图标当前位置（v69 修正：图标 v66 起挂 body 层悬浮，dock.querySelector
      // 永远找不到、一直走标准落位分支——拖动偏移后 morph 起点会差一截；改全局取，含偏移）
      var pill0 = dock.querySelector('.rounded-2xl');
      var ic0 = doc.getElementById('zcode-composer-icon');
      var from = (ic0 && ic0.style.display !== 'none' && ic0.offsetWidth > 0) ? rectOf(ic0) : iconHomeRect();
      var pill = dock.querySelector('.rounded-2xl');
      var willMorph = morphable(pill, dock);
      // v78：飞行期间不落垫不抢同步——真身隐藏中，布局变化会穿帮；垫与状态同步挪到
      // morphReveal 落位回调，tl 的 padding 过渡（02_style）让"列表让位"成为连续滑动。
      // 保底滚动与替身飞行并行（同为缓动曲线，同向运动）；目标算在垫之前，末条恰好
      // 停在（即将到位的）胶囊上沿。非动画路径保持原即时行为。
      if (!willMorph) { syncComposer(); }
      if (wasBottom && tlKeep) {
        try { tlKeep.scrollTo({ top: tlKeep.scrollHeight, behavior: willMorph ? 'smooth' : 'auto' }); }
        catch (eK2) { try { tlKeep.scrollTop = tlKeep.scrollHeight; } catch (eK3) {} }
      }
            if (willMorph) { morphReveal(dock, pill, from, function(){ syncComposer(); }); }
      // v77：默认只弹胶囊不拉键盘。用户实测"弹出输入框+键盘同时起来远远不够平滑"——
      // 胶囊 morph、让位垫、滚动补偿、IME 逐帧 resize 四件事叠在同一瞬间；键盘改为
      // 用户亲手点输入框才起来（原生 focus 由点击触发，系统动画跟手）。
      // 参数反转：旧签名 focus!==false 就聚焦；新签名只有显式传 true 才聚焦——
      // 现无调用方传 true，留作以后"打开时自动弹键盘"设置项的钩子。
      if (autofocus === true) {
        try {
          var ta = dock.querySelector('[data-testid^="v4-composer-input"], textarea, input[type="text"]');
          if (ta) { setTimeout(function(){ try { ta.focus(); } catch (e2) {} }, 360); }
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
      lastMinAt = Date.now();   // v69：手动收纳时刻（自动恢复的冷却起点）
      dockDragTy = 0;
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

