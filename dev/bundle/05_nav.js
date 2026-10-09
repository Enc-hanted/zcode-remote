// ===== 05_nav.js (620-951) =====
    // ---------- 点外部收起：半透明全屏遮罩（导航/浮动输入共用） ----------
    function showMask(){
      if (mask && mask.parentNode) { return; }
      mask = doc.createElement('div');
      mask.id = 'zcode-mask';   // v53：body 弹窗检测让位时需要能定位到遮罩
      mask.style.cssText = 'position:fixed;inset:0;z-index:99995;background:rgba(0,0,0,0);';
      mask.addEventListener('touchstart', function(e){
        e.preventDefault();
        closePanel();
        hideNav();
        hideComposer();
      }, {passive:false});
      // 桌面鼠标兜底：触摸设备上合成 click 之前 mousedown 不单独触发收起（touchstart 已处理），
      // 纯鼠标环境没有 touchstart，点外部收不回输入框
      mask.addEventListener('mousedown', function(){
        closePanel();
        hideNav();
        hideComposer();
      });
      (isShadow ? root : doc.body || doc.documentElement).appendChild(mask);
    }
    function hideMask(){
      if (mask && mask.parentNode) { mask.parentNode.removeChild(mask); }
      mask = null;
    }

    // ---------- 真导航展开/收起 ----------
    // v65（3.14.4 线上 bundle 取证）：原生导航在手机上不是不渲染，是「对话区 ≥864px 才可见」的
    // 容器查询把它藏了（@min-[864px]/conversation:visible）。解锁三件事：
    // ① z-index 抬到遮罩(99995)之上——否则看得见点不着，点击全被遮罩吃掉变成"收起"；
    // ② transform 归零——隐藏态带 -translate-x-2，不清零轨道半藏在屏幕外；
    // ③ 不再强制 min-width:220px（老版文字面板的遗留），原生轨道是 36px 细条，撑宽只会多出空白可点区。
    // v69（实机"导航出不来，什么都看不到"）：藏法是 Tailwind `hidden @min-[864px]/conversation:visible`
    // ——基础态是 display:none，v65 只解了 visibility/opacity，桌面测试时容器查询本来就激活
    // （视口 ≥864px 页面自己就显示），实机窄容器下 display:none 从未被恢复 → 全黑。
    // 这里补 display:flex!important（轨道本就是纵向细条）；若 260ms 后仍无布局宽度（藏法超出预期），
    // showNav 的校验自动切自建面板兜底，保证任何情况下都有可见产物。
    // 注意 transform/z-index 只对原生 nav 生效：自建面板靠 translateX(-50%) 居中，不能被清掉。
    var SHOW_CSS = NAV_SEL + ' { display: flex !important; flex-direction: column !important; visibility: visible !important; opacity: 1 !important; pointer-events: auto !important; } ' +
      'nav[data-testid="v4-turn-navigator"]{transform:none!important;z-index:99996!important;}' +
      NAV_SEL + ' * { visibility: visible !important; pointer-events: auto !important; } ' +
      // 注意不能写 NAV_SEL + ' [hidden]'：NAV_SEL 是逗号列表，展开成 "nav, panel [hidden]"
      // 后第一段变成裸 nav —— display:block!important 反把上面的 flex 顶掉（v69 实测踩中）
      'nav[data-testid="v4-turn-navigator"] [hidden] { display: block !important; } ' +
      '[aria-label="对话问题导航"] [hidden] { display: block !important; }';
    function ensureShowStyle(){
      if (showStyle && showStyle.parentNode) { return; }
      showStyle = doc.createElement('style');
      showStyle.id = 'zcode-nav-show';
      showStyle.textContent = SHOW_CSS;
      styleRoot().appendChild(showStyle);
    }
    function removeShowStyle(){
      if (showStyle && showStyle.parentNode) { showStyle.parentNode.removeChild(showStyle); }
      showStyle = null;
    }
    function showPreview(x, y, text){
      if (!preview) {
        preview = doc.createElement('div');
        preview.id = 'zcode-nav-preview';
        // 跟随手指的小浮层：纯色半透明即可，不做毛玻璃（移动 GPU 上叠太多 blur 卡）
        preview.style.cssText = 'position:fixed;z-index:99998;pointer-events:none;' +
          'max-width:min(280px,80vw);background:var(--zc-bg-strong);border:1px solid var(--zc-stroke);' +
          'border-radius:var(--zc-radius);padding:9px 12px;color:var(--zc-text);font-size:12px;line-height:1.5;' +
          'box-shadow:0 8px 26px rgba(0,0,0,0.45);';
        (isShadow ? root : doc.body || doc.documentElement).appendChild(preview);
      }
      preview.textContent = text;
      preview._px = x; preview._py = y;
      // rAF 批处理：touchmove 高频调用只记位置，一帧内只做一次读-写（避免每帧强制布局）
      if (!preview._raf) {
        preview._raf = requestAnimationFrame(function(){
          preview._raf = 0;
          if (!preview.parentNode) { return; }
          var l = Math.min(preview._px + 14, window.innerWidth - preview.offsetWidth - 8);
          var t = Math.min(preview._py + 14, window.innerHeight - preview.offsetHeight - 8);
          preview.style.left = Math.max(8, l) + 'px';
          preview.style.top = Math.max(8, t) + 'px';
        });
      }
    }
    function hidePreview(){
      if (preview && preview._raf) { cancelAnimationFrame(preview._raf); preview._raf = 0; }
      if (preview && preview.parentNode) { preview.parentNode.removeChild(preview); }
      preview = null;
    }
    function hoverItemAt(x, y){
      var el = doc.elementFromPoint(x, y);
      // 只在导航子树内找：手指在外面时 elementFromPoint 返回遮罩/页面节点，不能误报预览
      if (!el || !navEl || !navEl.contains(el)) { return null; }
      // 原生导航的条目是"无文字的小横条"，唯一可读的是 aria-label（"跳转到第 N 条问题"）；
      // 自建面板条目有正文。两类都接住：有 aria-label 或有正文都算命中。
      while (el && el !== navEl) {
        var al = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
        if ((el.textContent && el.textContent.trim()) || al) { return el; }
        el = el.parentElement;
      }
      return null;
    }
    function previewText(el){
      var al = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
      if (al) { return al; }
      return entryLabel(el, 180);
    }
    function onNavMove(e){
      var t = e.touches[0];
      var it = hoverItemAt(t.clientX, t.clientY);
      if (it) { showPreview(t.clientX, t.clientY, previewText(it)); }
      else { hidePreview(); }
    }
    function onNavEnd(){ hidePreview(); }
    // 原生轨道条目点击后自动收起：跳转完成（React 滚动 ~300ms）就还屏给阅读。
    // capture 监听不拦截默认行为，只做延时收起；自建面板条目本来就点完即收，无需此处理。
    function navAutoClose(){
      setTimeout(function(){
        if (navVisible) { hideNav(); }
      }, 350);
    }
    function showNav(){
      navEl = root.querySelector(NAV_SEL);
      if (!navEl) { return false; }
      ensureShowStyle();
      showMask();
      navVisible = true;
      uiLog('nav-open');
      navEl.addEventListener('touchmove', onNavMove, {passive:true});
      navEl.addEventListener('touchend', onNavEnd, {passive:true});
      navEl.addEventListener('click', navAutoClose, true);
      vibrate();
      // v69：原生轨道可见性校验——display 解锁后仍无布局宽度（display:none 之外的藏法/结构变化）
      // 就地切自建面板，用户永远能看到导航，而不是对着空气右滑。
      // 双采样：260ms 与 480ms 各测一次，都为零宽才判定（单次测量可能撞上渲染中间态）
      setTimeout(function(){
        if (!navVisible) { return; }
        setTimeout(function(){
          if (!navVisible) { return; }
          var el = root.querySelector(NAV_SEL);
          if (el && el !== navEl) { navEl = el; }
          if (navEl && navEl.offsetWidth > 0) { return; }
          uiLog('nav-invisible-fallback');
          hideNav();
          var turns = collectTurns();
          if (turns.length) {
            buildPanel(turns);
            hint('已唤出问题导航（点外部收起）');
          } else {
            hint('还没有可导航的消息，先去聊几句吧');
          }
        }, 220);
      }, 260);
      return true;
    }
    function hideNav(){
      removeShowStyle();
      hideMask();
      navVisible = false;
      if (navEl) {
        navEl.removeEventListener('touchmove', onNavMove);
        navEl.removeEventListener('touchend', onNavEnd);
        navEl.removeEventListener('click', navAutoClose, true);
      }
      hidePreview();
    }

    // ---------- 自建面板兜底（真导航不存在时） ----------
    // 只取气泡正文：克隆节点后把行外操作按钮（复制/编辑/反馈/分叉）从克隆里移除再读文本。
    // v70：display:none + innerText 的方案在实机上失效——innerText 对脱离文档的克隆不计算
    // 渲染样式，"复制 编辑"照样混进条目；改为直接 removeChild，文本读取与渲染无关、稳定干净。
    // 克隆读取绝不改 live DOM。
    var TURN_NOISE_SEL = '[data-testid^="v4-copy-"], [data-testid^="v4-edit-"], ' +
      '[data-testid^="v4-feedback-"], [data-testid^="v4-fork-"], ' +
      // v64：3.14.x 新行类型（待执行命令/Subagent 卡片/队列项/待审卡片/用户输入卡）
      // 不混进导航条目正文（线上 bundle testid 取证）
      '[data-testid^="v4-pending-command"], [data-testid^="v4-subagent-"], ' +
      '[data-testid^="v4-queue"], [data-testid^="v4-workspace-hook-pending"], ' +
      '[data-testid^="v4-user-input"]';
    function entryLabel(el, maxLen){
      var clone = el.cloneNode(true);
      var btns = clone.querySelectorAll ? clone.querySelectorAll(TURN_NOISE_SEL) : [];
      for (var i = 0; i < btns.length; i++) {
        if (btns[i].parentNode) { btns[i].parentNode.removeChild(btns[i]); }
      }
      var txt = (clone.textContent || '').replace(/\s+/g, ' ').trim();
      if (txt.length > maxLen) { txt = txt.slice(0, maxLen) + '…'; }
      return txt;
    }
    // 虚拟化列表分步瞄准：每次滚到目标当前偏移，页面补渲染后重新瞄准，直到进视口
    function jumpTo(el, tries){
      var cont = el;
      var depth = 0;
      while (cont && depth < 12 && !(cont.scrollHeight > cont.clientHeight + 50)) {
        cont = cont.parentElement;
        depth++;
      }
      if (!cont) {
        try { el.scrollIntoView({behavior:'smooth', block:'start'}); } catch (e) {}
        flash(el);
        return;
      }
      var r = el.getBoundingClientRect();
      var cr = cont.getBoundingClientRect();
      var rel = r.top - cr.top + cont.scrollTop;
      cont.scrollTop = Math.max(0, rel - 30);
      var n = tries || 0;
      if (n < 14) {
        setTimeout(function(){
          var r2 = el.getBoundingClientRect();
          if ((r2.width === 0 && r2.height === 0) || r2.top < -10 || r2.bottom > window.innerHeight + 10) {
            jumpTo(el, n + 1);
          } else {
            flash(el);
          }
        }, 280);
      } else {
        flash(el);
      }
    }
    function flash(el){
      var oldO = el.style.outline;
      el.style.outline = '2px solid var(--zc-outline)';
      setTimeout(function(){ el.style.outline = oldO; }, 1200);
    }
    function itemAt(x, y){
      var els = panel.querySelectorAll('[data-idx]');
      for (var i = 0; i < els.length; i++) {
        var r = els[i].getBoundingClientRect();
        if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) { return els[i]; }
      }
      return null;
    }
    function clearHl(){
      if (!panel) { return; }
      var els = panel.querySelectorAll('[data-idx]');
      for (var i = 0; i < els.length; i++) { els[i].style.background = ''; }
    }
    function buildPanel(turns){
      showMask();
      panel = doc.createElement('div');
      panel.setAttribute('aria-label', '对话问题导航');
      panel.style.cssText = 'position:fixed;left:50%;top:14vh;transform:translateX(-50%);' +
        'width:min(340px,92vw);max-height:40vh;display:flex;flex-direction:column;' +
        'background:var(--zc-bg);' +
        '-webkit-backdrop-filter:blur(26px) saturate(1.5);backdrop-filter:blur(26px) saturate(1.5);' +
        'border:1px solid var(--zc-stroke);border-radius:var(--zc-radius-xl);z-index:99997;' +
        'box-shadow:0 18px 52px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.07);overflow:hidden;' +
        anim('zcodeFadePanel', '0.22s', 'cubic-bezier(0.2,0.8,0.2,1)');
      var head = doc.createElement('div');
      head.style.cssText = 'display:flex;align-items:center;gap:10px;padding:13px 16px;' +
        'cursor:grab;user-select:none;-webkit-user-select:none;touch-action:none;' +
        'border-bottom:1px solid var(--zc-stroke-faint);';
      var grip = doc.createElement('div');
      grip.textContent = '≡';
      grip.style.cssText = 'color:var(--zc-text-dim);font-size:18px;font-weight:600;';
      var title = doc.createElement('div');
      title.textContent = '对话问题导航';
      title.style.cssText = 'flex:1;color:var(--zc-text-2);font-size:14px;font-weight:600;letter-spacing:0.3px;';
      var close = doc.createElement('div');
      close.textContent = '收起';
      close.setAttribute('role', 'button');
      close.setAttribute('aria-label', '收起导航面板');
      close.style.cssText = 'color:var(--zc-text-3);font-size:13px;padding:10px 16px;cursor:pointer;border-radius:999px;' +
        'background:var(--zc-hover);transition:background 0.15s;';
      close.addEventListener('touchstart', function(e){ e.stopPropagation(); }, {passive:true});
      close.onclick = closePanel;
      head.appendChild(grip); head.appendChild(title); head.appendChild(close);
      var list = doc.createElement('div');
      list.style.cssText = 'overflow-y:auto;padding:8px 0;max-height:32vh;';
      var labels = [];   // 条目正文预取（拖动悬停预览用，避免拖动时逐条读 innerText）
      turns.forEach(function(el, i){
        var item = doc.createElement('div');
        item.setAttribute('data-idx', i);
        item.setAttribute('role', 'button');
        // 无编号：列表不是全量加载，编号会误导；纯文本条目 + 左对齐
        var label = entryLabel(el, 160);
        labels.push(label);
        var disp = label.length > 44 ? label.slice(0, 44) + '…' : label;
        item.style.cssText = 'color:var(--zc-text-dim);font-size:13px;line-height:1.5;padding:10px 14px;margin:1px 10px;' +
          'border-radius:var(--zc-radius);cursor:pointer;';
        var txt = doc.createElement('div');
        txt.textContent = disp;
        txt.style.cssText = 'overflow:hidden;';
        item.appendChild(txt);
        item.addEventListener('touchstart', function(){
          item.style.background = 'var(--zc-hover)';
        }, {passive:true});
        item.addEventListener('touchend', function(){
          item.style.background = '';
        }, {passive:true});
        item.onclick = function(){
          jumpTo(el);
          closePanel();
        };
        list.appendChild(item);
      });
      var foot = doc.createElement('div');
      foot.style.cssText = 'display:flex;justify-content:space-between;align-items:center;padding:6px 16px 10px;';
      var count = doc.createElement('div');
      count.textContent = '共 ' + turns.length + ' 条';
      count.style.cssText = 'color:var(--zc-text-3);font-size:12px;';
      foot.appendChild(count);
      panel.appendChild(head); panel.appendChild(list); panel.appendChild(foot);
      panel._labels = labels;
      (isShadow ? root : doc.body || doc.documentElement).appendChild(panel);
      head.addEventListener('touchstart', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
        var r = panel.getBoundingClientRect();
        drag = {
          startX: t.clientX, startY: t.clientY,
          baseX: r.left, baseY: r.top,
          w: panel.offsetWidth, h: panel.offsetHeight,
          panel: panel, turns: turns, moved: false
        };
        e.preventDefault();
      }, {passive:false});
    }
    function dragMove(e){
      if (!drag) { return; }
      var t = e.touches[0];
      var dx = t.clientX - drag.startX, dy = t.clientY - drag.startY;
      if (Math.abs(dx) > 8 || Math.abs(dy) > 8) { drag.moved = true; }
      if (drag.moved) {
        var p = drag.panel;
        // 拖动只写 transform（不触发布局）；结束拖拽时再提交为 left/top
        var tx = Math.max(4 - drag.baseX, Math.min(window.innerWidth - drag.w - 4 - drag.baseX, dx));
        var ty = Math.max(4 - drag.baseY, Math.min(window.innerHeight - drag.h - 4 - drag.baseY, dy));
        drag.tx = tx; drag.ty = ty;
        p.style.transform = 'translateX(-50%) translate3d(' + tx + 'px,' + ty + 'px,0)';
        clearHl();
        var it = itemAt(t.clientX, t.clientY);
        if (it) {
          var idx = parseInt(it.getAttribute('data-idx'), 10);
          var full = (p._labels[idx] || '');
          if (full.length > 160) { full = full.slice(0, 160) + '…'; }
          showPreview(t.clientX, t.clientY, full);
          it.style.background = 'var(--zc-hover-strong)';
        } else {
          hidePreview();
        }
      }
      e.preventDefault();
    }
    function dragEnd(){
      if (!drag) { return; }
      var p = drag.panel;
      if (drag.moved) {
        // 提交位置：left/top 落位并清掉 transform（收起动画的 keyframe 依赖无 transform）
        p.style.left = (drag.baseX + (drag.tx || 0)) + 'px';
        p.style.top = (drag.baseY + (drag.ty || 0)) + 'px';
        p.style.transform = 'none';
      }
      drag = null;
      hidePreview();
      clearHl();
    }
    function closePanel(){
      if (panel && panel.parentNode) {
        if (ANIM_ON && !REDUCED) {
          // 收起动画：淡出下移，animationend 后移除（动画事件兜底 600ms）
          var p = panel;
          p.classList.add('zcode-closing');
          var done = function(){
            if (p.parentNode) { p.parentNode.removeChild(p); }
            if (panel === p) { panel = null; }
          };
          p.addEventListener('animationend', done, {once: true});
          setTimeout(done, 600);
        } else {
          panel.parentNode.removeChild(panel);
          panel = null;
        }
      } else {
        panel = null;
      }
      hideMask();
      hidePreview();
    }
    function toggleNavigator(){
      var now = Date.now();
      if (window.__zcodeNavLastToggle && now - window.__zcodeNavLastToggle < 700) { return; }
      window.__zcodeNavLastToggle = now;
      if (navVisible) {
        hideNav();   // v63：亮度恢复已收进 hideNav
        return;
      }
      if (panel) { closePanel(); return; }
      // v70（实机"右滑什么都看不到"，用户拍板）：自建面板为主——原生轨道的藏法是
      // 容器查询 display:none，强拉出来后位置/高度/可读性都不受控（36px 无字细条，
      // v65/v69 两轮解锁都在实机翻车）；面板显示问题原文、点按跳转，每个像素都在
      // 我们控制内。原生解锁降级为后备：行选择器失效（collectTurns=0）时仍有一线可见产物。
      var turns = collectTurns();
      if (turns.length) {
        uiLog('nav-panel-' + turns.length);
        buildPanel(turns);
        vibrate();
        hint('已唤出问题导航（点外部收起）');
        return;
      }
      if (showNav()) {
        hint('点横条跳到对应问题，点空白处收起');
        return;
      }
      hint('还没有可导航的消息，先去聊几句吧');
    }

    // ---------- v79⑤：会话切换骨架屏 ----------
    // 路由变化（history.pushState/replaceState + popstate，SPA 不触发导航事件）→ z.ai
    // 整列表卸载重建（实机取证 ~1s 主线程冻结）。冻结期画面是上一会话残影/白屏，
    // 这里铺一层与页面底色一致的骨架（几条微光条），首条 v4-row 渲染出来即淡出；
    // 1.4s 硬上限防慢网下糊屏。只在主文档挂路由钩子（shadow root 共享同一 window.history，
    // 重复包装会双触发）。uiLog/removeReadLine 等在 06 定义，函数声明提升后运行期可用。
    var skEl = null, skObs = null, skTimer = 0, skShown = 0, skLastPath = '';
    function skPageBg(){
      try {
        if (lastBot && typeof lastBot.r === 'number') { return 'rgb(' + lastBot.r + ',' + lastBot.g + ',' + lastBot.b + ')'; }
      } catch (e) {}
      return doc.documentElement.classList.contains('zcode-ui-light') ? '#FCFDFF' : '#0A0A0A';
    }
    function hideSkeleton(){
      if (!skEl) { return; }
      var el = skEl; skEl = null;
      try { if (skObs) { skObs.disconnect(); } } catch (e0) {}
      skObs = null;
      if (skTimer) { clearTimeout(skTimer); skTimer = 0; }
      if (ANIM_ON && !REDUCED) {
        el.classList.add('zc-sk-out');
        var done = function(){ if (el.parentNode) { el.parentNode.removeChild(el); } };
        el.addEventListener('transitionend', done, {once: true});
        setTimeout(done, 500);
      } else {
        try { el.parentNode.removeChild(el); } catch (e1) {}
      }
    }
    function showSkeleton(){
      if (!UI_ON || skEl) { return; }
      skShown++;
      uiLog('sk-show');
      removeReadLine(true);   // 06 的阅读线锚在旧会话的行上，切会话直接收线
      hideNav(); closePanel(); hideMsgSheet();   // 切会话时收起我们的浮层，骨架是唯一前景
      skEl = doc.createElement('div');
      skEl.id = 'zcode-skeleton';
      skEl.style.background = skPageBg();
      (isShadow ? root : doc.body || doc.documentElement).appendChild(skEl);
      var W = Math.min(window.innerWidth, 760);
      var offX = Math.max(10, Math.round((window.innerWidth - W) / 2));
      var vh = window.innerHeight;
      var bars = [ [0.07, 0.30], [0.13, 0.86], [0.22, 0.62], [0.32, 0.78], [0.42, 0.50], [0.55, 0.70] ];
      for (var i = 0; i < bars.length; i++) {
        var b = doc.createElement('div');
        b.className = 'zc-sk-bar';
        b.style.left = offX + 'px';
        b.style.top = Math.round(vh * bars[i][0]) + 'px';
        b.style.width = Math.round(W * bars[i][1]) + 'px';
        if (REDUCED) { b.style.animation = 'none'; }
        skEl.appendChild(b);
      }
      requestAnimationFrame(function(){
        if (skEl) { skEl.classList.add('zc-sk-in'); }
      });
      try {
        skObs = new MutationObserver(function(){
          if (skEl && root.querySelector('[data-testid^="v4-row"]')) { hideSkeleton(); }
        });
        skObs.observe(root, {childList: true, subtree: true});
      } catch (e2) {}
      skTimer = setTimeout(hideSkeleton, 1400);
    }
    function onRouteChange(){
      var p = '';
      try { p = location.pathname || ''; } catch (e) { return; }
      if (p === skLastPath) { return; }
      var first = !skLastPath;
      skLastPath = p;
      if (first) { return; }   // 注入时的初始路径不算切换
      showSkeleton();
    }
    if (!isShadow) {
      try {
        ['pushState', 'replaceState'].forEach(function(m){
          var orig = history[m];
          if (typeof orig !== 'function') { return; }
          history[m] = function(){
            var r;
            try { r = orig.apply(this, arguments); } catch (e3) { throw e3; }
            setTimeout(onRouteChange, 0);
            return r;
          };
        });
        evRoot.addEventListener('popstate', function(){ setTimeout(onRouteChange, 0); }, {passive: true});
        onRouteChange();
      } catch (eH) {}
    }

