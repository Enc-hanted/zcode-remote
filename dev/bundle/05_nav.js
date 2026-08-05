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
      (isShadow ? root : doc.body || doc.documentElement).appendChild(mask);
    }
    function hideMask(){
      if (mask && mask.parentNode) { mask.parentNode.removeChild(mask); }
      mask = null;
    }

    // ---------- 真导航展开/收起 ----------
    var SHOW_CSS = NAV_SEL + ' { width: auto !important; min-width: 220px !important; max-width: 78vw !important; ' +
      'pointer-events: auto !important; z-index: 999 !important; animation: zcodeFadeIn 0.18s ease-out !important; } ' +
      NAV_SEL + ' * { visibility: visible !important; opacity: 1 !important; pointer-events: auto !important; } ' +
      NAV_SEL + ' [hidden] { display: block !important; }';
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
      while (el && el !== navEl && (!el.textContent || !el.textContent.trim())) { el = el.parentElement; }
      if (!el || el === navEl || !navEl.contains(el)) { return null; }
      return el;
    }
    function previewText(el){
      return entryLabel(el, 180);
    }
    function onNavMove(e){
      var t = e.touches[0];
      var it = hoverItemAt(t.clientX, t.clientY);
      if (it) { showPreview(t.clientX, t.clientY, previewText(it)); }
      else { hidePreview(); }
    }
    function onNavEnd(){ hidePreview(); }
    function showNav(){
      navEl = root.querySelector(NAV_SEL);
      if (!navEl) { return false; }
      ensureShowStyle();
      showMask();
      navVisible = true;
      navEl.addEventListener('touchmove', onNavMove, {passive:true});
      navEl.addEventListener('touchend', onNavEnd, {passive:true});
      vibrate();
      return true;
    }
    function hideNav(){
      removeShowStyle();
      hideMask();
      navVisible = false;
      if (navEl) {
        navEl.removeEventListener('touchmove', onNavMove);
        navEl.removeEventListener('touchend', onNavEnd);
      }
      hidePreview();
    }

    // ---------- 自建面板兜底（真导航不存在时） ----------
    // 只取气泡正文：克隆节点后隐藏行外的操作按钮（复制/编辑/反馈/分叉）再读 innerText
    // （innerText 会忽略 display:none 的元素，避免"复制 编辑"混进导航条目）。
    // 克隆读取绝不改 live DOM：改 display 再恢复 = 每行强制 reflow，且读取失败会让按钮永久隐藏。
    var TURN_NOISE_SEL = '[data-testid^="v4-copy-"], [data-testid^="v4-edit-"], ' +
      '[data-testid^="v4-feedback-"], [data-testid^="v4-fork-"]';
    function entryLabel(el, maxLen){
      var clone = el.cloneNode(true);
      var btns = clone.querySelectorAll ? clone.querySelectorAll(TURN_NOISE_SEL) : [];
      for (var i = 0; i < btns.length; i++) { btns[i].style.display = 'none'; }
      var txt = (clone.innerText || '').replace(/\s+/g, ' ').trim();
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
        hideNav();
        setLogoDim(false);
        return;
      }
      if (showNav()) {
        setLogoDim(true);
        hint('已唤出对话问题导航（点外部或下拉 logo 收起）');
        return;
      }
      if (panel) { closePanel(); return; }
      var turns = collectTurns();
      if (!turns.length) {
        hint('还没有可导航的消息，先去聊几句吧');
        return;
      }
      buildPanel(turns);
      vibrate();
      hint('已唤出问题导航（点外部收起）');
    }

