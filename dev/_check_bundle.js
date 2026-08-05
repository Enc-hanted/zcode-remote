(
function(css, navOn, replyOn){
  var BODY = function(css, navOn, replyOn){
    var root = window.__zcodeRoot || document;
    var doc = root.ownerDocument || document;
    var isShadow = !!root.host;
    var evRoot = isShadow ? root : doc;
    if (root.__zcodeDone) { return; }
    root.__zcodeDone = true;

    var NAV_SEL = 'nav[data-testid="v4-turn-navigator"], [aria-label="对话问题导航"]';

    // ---------- 保持后台连接：对页面谎报"始终可见" ----------
    // 聊天页通常会在隐藏（visibilitychange）时主动断开 WebSocket，
    // 导致后台收不到消息、回复通知无从谈起。注入后页面以为一直可见，连接不断。
    try {
      Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
      Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
      Object.defineProperty(document, 'hasFocus', { value: function(){ return true; }, configurable: true });
      document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
      document.addEventListener('webkitvisibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
      document.addEventListener('blur', function(e){ e.stopImmediatePropagation(); }, true);
    } catch (e) {}
    var EDGE = 44, SWIPE_MIN = 46, SWIPE_MAX_MS = 450, WINDOW_MS = 1800, NEEDED = 3, COOLDOWN = 1300;
    var track = null, swipes = [], lastTrigger = 0;
    var navEl = null, navVisible = false, showStyle = null, panel = null, mask = null, preview = null, drag = null;
    var lastThemeKey = '', lastThemeR = -1, lastThemeG = -1, lastThemeB = -1;

    function hint(text){
      var old = doc.getElementById('zcode-nav-hint');
      if (old && old.parentNode) { old.parentNode.removeChild(old); }
      var h = doc.createElement('div');
      h.id = 'zcode-nav-hint';
      h.textContent = text;
      h.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);' +
        'background:rgba(21,27,40,0.94);color:#E8ECF4;font-size:13px;line-height:1;' +
        'padding:10px 16px;border-radius:999px;z-index:99999;pointer-events:none;' +
        'border:1px solid rgba(91,163,255,0.45);box-shadow:0 4px 18px rgba(0,0,0,0.45);';
      (isShadow ? root : doc.body || doc.documentElement).appendChild(h);
      setTimeout(function(){ if (h.parentNode) { h.parentNode.removeChild(h); } }, 1600);
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

    // ---------- 修正样式 ----------
    function ensureStyle(){
      if (root.querySelector && root.querySelector('#zcode-mobile-fix')) { return; }
      var st = doc.createElement('style');
      st.id = 'zcode-mobile-fix';
      st.textContent = css;
      styleRoot().appendChild(st);
    }

    // ---------- 溢出巡检（root 化） ----------
    function fix(){
      ensureStyle();
      var de = doc.documentElement;
      if (de && de.scrollLeft) { de.scrollLeft = 0; }
      if (root !== doc && root.scrollLeft) { root.scrollLeft = 0; }
      if (doc.body && doc.body.scrollLeft) { doc.body.scrollLeft = 0; }
      var vw = (de && de.clientWidth) || 0;
      var els = root.querySelectorAll('*');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) { continue; }
        if (el.scrollWidth > el.clientWidth + 1) {
          var oy = getComputedStyle(el).overflowY;
          if (oy === 'auto' || oy === 'scroll') {
            el.style.overflowX = 'hidden';
            el.style.touchAction = 'pan-y';
            if (el.scrollLeft) { el.scrollLeft = 0; }
          } else if (el.tagName === 'PRE' || el.tagName === 'TABLE') {
            el.style.overflowX = 'auto';
          }
        }
        if ((r.left < -1 || r.right > vw + 1) && !el.closest('pre,table')) {
          el.style.marginLeft = '0px';
          el.style.marginRight = '0px';
          el.style.maxWidth = '100%';
        }
      }
    }
    var pending = false;
    function schedule(){
      if (pending) { return; }
      pending = true;
      setTimeout(function(){ pending = false; fix(); }, 400);
    }
    ensureStyle();
    fix();
    try {
      new MutationObserver(schedule).observe(root, {childList:true, subtree:true, attributes:true, attributeFilter:['class','style']});
    } catch (e) {}
    setInterval(fix, 3000);
    evRoot.addEventListener('scroll', function(){
      var de = doc.documentElement;
      if (de && de.scrollLeft) { de.scrollLeft = 0; }
      if (doc.body && doc.body.scrollLeft) { doc.body.scrollLeft = 0; }
    }, true);

    // ---------- 主题检测（html 类名 / data-theme / localStorage / 背景亮度） ----------
    function detectTheme(){
      var de = doc.documentElement;
      var dataTheme = de ? (de.getAttribute('data-theme') || '') : '';
      var cls = de ? (' ' + (de.className || '') + ' ') : ' ';
      // 兼容 theme-zai-light / dark 这类连字符类名
      var darkHint = (/(^|[\s-])(dark|theme-dark|zcode-dark)(\s|$)/i.test(cls) || /dark/i.test(dataTheme));
      var lightHint = (/(^|[\s-])(light|theme-light|zcode-light)(\s|$)/i.test(cls) || /light/i.test(dataTheme));
      var lsDark = null;
      try {
        var keys = ['theme', 'zcode-theme', 'color-theme', 'appearance'];
        for (var i = 0; i < keys.length; i++) {
          var v = window.localStorage.getItem(keys[i]);
          if (!v) { continue; }
          if (/dark|black/i.test(v)) { lsDark = true; break; }
          if (/light|white/i.test(v)) { lsDark = false; break; }
        }
      } catch (e) {}
      var r = 0, g = 0, b = 0, lumDark = null, gotColor = false;
      var el = doc.body;
      var bg = el ? getComputedStyle(el).backgroundColor : '';
      if (!bg || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') {
        bg = de ? getComputedStyle(de).backgroundColor : '';
      }
      var m = /rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/.exec(bg || '');
      if (m && (m[4] === undefined || parseFloat(m[4]) > 0.05)) {
        r = +m[1]; g = +m[2]; b = +m[3];
        lumDark = (0.299 * r + 0.587 * g + 0.114 * b) < 128;
        gotColor = true;
      }
      var dark = darkHint ? true : (lightHint ? false : (lsDark !== null ? lsDark : lumDark));
      if (dark === null) { return null; }
      if (!gotColor) { r = dark ? 22 : 248; g = r; b = r; }
      return { r: r, g: g, b: b, dark: dark };
    }
    function reportTheme(){
      var t = detectTheme();
      if (!t) { return; }
      var key = t.dark ? 'd' : 'l';
      if (key === lastThemeKey && t.r === lastThemeR && t.g === lastThemeG && t.b === lastThemeB) { return; }
      lastThemeKey = key; lastThemeR = t.r; lastThemeG = t.g; lastThemeB = t.b;
      var br = bridge();
      if (!br || !br.onTheme) { return; }
      try { br.onTheme(t.r, t.g, t.b, t.dark); } catch (e) {}
    }
    setInterval(reportTheme, 2000);

    // ---------- 顶栏/底栏就近取色：多点采样 + 透明/渐变上溯，采不到就不动 ----------
    function sampleColor(x, y){
      var el = doc.elementFromPoint(x, y);
      for (var i = 0; el && el.nodeType === 1 && i < 50; i++) {
        if (el.id && /^zcode-/.test(el.id)) { el = el.parentElement; continue; }
        var cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.08) { el = el.parentElement; continue; }
        var bg = cs.backgroundColor || '';
        var m = /rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/.exec(bg);
        if (m) {
          var a = m[4] === undefined ? 1 : parseFloat(m[4]);
          if (a > 0.05) {
            return { r: +m[1], g: +m[2], b: +m[3], dark: (0.299 * m[1] + 0.587 * m[2] + 0.114 * m[3]) < 128 };
          }
        }
        // 背景色透明但背景图是渐变：取停靠点颜色（近纯色取平均，明显渐变取起点）
        var gi = cs.backgroundImage || '';
        if (gi && gi.indexOf('gradient') >= 0) {
          var g = parseGradientColor(gi);
          if (g) { return g; }
        }
        el = el.parentElement;
      }
      return null;
    }
    function parseGradientColor(bg){
      var re = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/g;
      var colors = [], mm;
      while ((mm = re.exec(bg)) !== null) {
        var a = mm[4] === undefined ? 1 : parseFloat(mm[4]);
        if (a > 0.05) { colors.push({ r: +mm[1], g: +mm[2], b: +mm[3] }); }
      }
      if (!colors.length) { return null; }
      var avg = { r: 0, g: 0, b: 0 };
      for (var i = 0; i < colors.length; i++) {
        avg.r += colors[i].r; avg.g += colors[i].g; avg.b += colors[i].b;
      }
      avg.r = Math.round(avg.r / colors.length);
      avg.g = Math.round(avg.g / colors.length);
      avg.b = Math.round(avg.b / colors.length);
      var spread = 0;
      for (var j = 0; j < colors.length; j++) {
        spread += Math.abs(colors[j].r - avg.r) + Math.abs(colors[j].g - avg.g) + Math.abs(colors[j].b - avg.b);
      }
      if (colors.length > 1 && spread / colors.length > 60) { return colors[0]; }
      return { r: avg.r, g: avg.g, b: avg.b, dark: (0.299 * avg.r + 0.587 * avg.g + 0.114 * avg.b) < 128 };
    }
    function colorDiff(a, b){
      return Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b);
    }
    function sampleArea(ys){
      var xs = [0.15, 0.35, 0.5, 0.65, 0.85];
      var got = [];
      for (var i = 0; i < ys.length; i++) {
        for (var j = 0; j < xs.length; j++) {
          var c = sampleColor(Math.floor(window.innerWidth * xs[j]), ys[i]);
          if (c) { got.push(c); }
        }
      }
      if (!got.length) { return null; }
      var best = got[0], bestN = 1;
      for (var a = 0; a < got.length; a++) {
        var n = 0;
        for (var b = 0; b < got.length; b++) {
          if (colorDiff(got[a], got[b]) <= 40) { n++; }
        }
        if (n > bestN) { best = got[a]; bestN = n; }
      }
      return best;
    }
    // 顶部区域采不到时：从页面的头部容器向上爬（浅色主题的白色往往挂在很深的祖先上）
    function headerColor(){
      var el = root.querySelector('[data-testid="workspace-header"], [data-testid="v4-session-pane"]');
      for (var i = 0; el && el.nodeType === 1 && i < 50; i++) {
        if (el.id && /^zcode-/.test(el.id)) { el = el.parentElement; continue; }
        var cs = getComputedStyle(el);
        var bg = cs.backgroundColor || '';
        var m = /rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/.exec(bg);
        if (m && (m[4] === undefined || parseFloat(m[4]) > 0.05)) {
          return { r: +m[1], g: +m[2], b: +m[3], dark: (0.299 * m[1] + 0.587 * m[2] + 0.114 * m[3]) < 128 };
        }
        var gi = cs.backgroundImage || '';
        if (gi && gi.indexOf('gradient') >= 0) {
          var g = parseGradientColor(gi);
          if (g) { return g; }
        }
        el = el.parentElement;
      }
      return null;
    }
    var lastTop = null, lastBot = null;
    function reportBars(){
      var top = sampleArea([50, 100]) || headerColor();
      var bot = sampleArea([Math.max(50, window.innerHeight - 50), Math.max(50, window.innerHeight - 100)]);
      var changedT = top && (!lastTop || lastTop.dark !== top.dark || colorDiff(lastTop, top) > 4);
      var changedB = bot && (!lastBot || lastBot.dark !== bot.dark || colorDiff(lastBot, bot) > 4);
      if (!changedT && !changedB) { return; }
      if (top) { lastTop = top; }
      if (bot) { lastBot = bot; }
      var br = bridge();
      if (!br || !br.onBars) { return; }
      try {
        br.onBars(
          top ? top.r : -1, top ? top.g : 0, top ? top.b : 0, top ? top.dark : false,
          bot ? bot.r : -1, bot ? bot.g : 0, bot ? bot.b : 0, bot ? bot.dark : false
        );
      } catch (e) {}
    }
    setTimeout(reportBars, 400);
    setInterval(reportBars, 1000);

    // ---------- 消息定位候选：只列"我的消息"行（class = group/user-row，真机结构验证） ----------
    function collectTurns(){
      var rows = root.querySelectorAll('[data-testid^="v4-row"]');
      var out = [];
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var pt = row.parentElement && row.parentElement.getAttribute
          ? (row.parentElement.getAttribute('data-testid') || '') : '';
        if (pt.indexOf('v4-row') === 0) { continue; } // 嵌套行（附件等）跳过
        var cls = ' ' + (row.className || '') + ' ';
        if (cls.indexOf('group/user-row') < 0) { continue; } // 只留我发的消息
        var t = (row.innerText || '').trim();
        if (t.length >= 4 && t.length <= 600) { out.push(row); }
      }
      return out;
    }

    // ---------- 诊断信息（复制发给我） ----------
    function diagInfo(){
      var de = doc.documentElement;
      var out = {
        bundleVer: 12,
        url: (location.href || '').slice(0, 140),
        isShadow: isShadow,
        viewport: window.innerWidth + 'x' + window.innerHeight,
        bodyBg: '',
        htmlClass: de ? (de.className || '') : '',
        hasNav: root.querySelectorAll(NAV_SEL).length,
        theme: detectTheme() || null,
        turnCandidates: collectTurns().length
      };
      var bodyEl = doc.body;
      var bg = bodyEl ? getComputedStyle(bodyEl).backgroundColor : '';
      if (!bg || bg === 'rgba(0, 0, 0, 0)') { bg = de ? getComputedStyle(de).backgroundColor : ''; }
      out.bodyBg = bg;
      var fs = root.querySelectorAll('iframe, frame');
      var frames = [];
      for (var i = 0; i < fs.length; i++) {
        var f = fs[i];
        var fr = { src: (f.src || '').slice(0, 140), bundle: false };
        try { fr.bundle = !!(f.contentWindow && f.contentWindow.document && f.contentWindow.document.__zcodeDone); } catch (e) {}
        frames.push(fr);
      }
      out.iframes = frames;
      var shadows = 0;
      var all2 = root.querySelectorAll('*');
      for (var j = 0; j < all2.length; j++) { if (all2[j].shadowRoot) { shadows++; } }
      out.shadowRoots = shadows;
      var rowStats = { total: 0, assistant: 0, user: 0 };
      var rowEls = root.querySelectorAll('[data-testid^="v4-row"]');
      for (var rr = 0; rr < rowEls.length; rr++) {
        var cls = ' ' + (rowEls[rr].className || '') + ' ';
        if (cls.indexOf('group/assistant-row') >= 0) { rowStats.assistant++; }
        else if (cls.indexOf('group/user-row') >= 0) { rowStats.user++; }
        rowStats.total++;
      }
      out.rows = 'total=' + rowStats.total + ' assistant=' + rowStats.assistant + ' user=' + rowStats.user;
      var counts = {};
      var all3 = root.querySelectorAll('[data-testid]');
      for (var k = 0; k < all3.length && k < 10000; k++) {
        var tid = all3[k].getAttribute('data-testid') || '';
        var pre = tid.replace(/[0-9]+$/g, '#').split('-').slice(0, 3).join('-');
        counts[pre] = (counts[pre] || 0) + 1;
      }
      out.testidPrefixes = Object.keys(counts)
        .sort(function(a, b){ return counts[b] - counts[a]; })
        .slice(0, 30)
        .map(function(k){ return k + '=' + counts[k]; })
        .join(' ');
      return out;
    }
    function showDiagCard(){
      var info = diagInfo();
      var text = JSON.stringify(info, null, 1);
      var card = doc.createElement('div');
      card.id = 'zcode-diag-card';
      card.style.cssText = 'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:99999;' +
        'width:min(340px,90vw);max-height:72vh;overflow-y:auto;background:rgba(15,19,29,0.98);' +
        'border:1px solid rgba(91,163,255,0.40);border-radius:14px;padding:14px;color:#E8ECF4;' +
        'font-size:11px;line-height:1.55;box-shadow:0 12px 40px rgba(0,0,0,0.6);';
      var title = doc.createElement('div');
      title.textContent = '找不到导航 · 页面结构诊断';
      title.style.cssText = 'font-size:13px;font-weight:600;color:#5BA3FF;margin-bottom:8px;';
      var pre = doc.createElement('div');
      pre.style.cssText = 'white-space:pre-wrap;word-break:break-all;';
      pre.textContent = text;
      var copy = doc.createElement('div');
      copy.textContent = '复制诊断';
      copy.style.cssText = 'margin-top:10px;text-align:center;color:#0B0E14;background:#5BA3FF;' +
        'padding:9px;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;';
      copy.onclick = function(){
        var ta = doc.createElement('textarea');
        ta.value = text;
        ta.style.cssText = 'position:fixed;left:-9999px;top:0;';
        (doc.body || doc.documentElement).appendChild(ta);
        ta.select();
        try { doc.execCommand('copy'); } catch (e) {}
        (doc.body || doc.documentElement).removeChild(ta);
        hint('已复制，粘贴到会话里发给我');
      };
      var close = doc.createElement('div');
      close.textContent = '关闭';
      close.style.cssText = 'margin-top:6px;text-align:center;color:#8B93A7;padding:9px;' +
        'border-radius:8px;font-size:13px;cursor:pointer;';
      close.onclick = function(){ if (card.parentNode) { card.parentNode.removeChild(card); } };
      card.appendChild(title); card.appendChild(pre); card.appendChild(copy); card.appendChild(close);
      (isShadow ? root : doc.body || doc.documentElement).appendChild(card);
    }

    if (!navOn) { return; }

    // ---------- 点外部收起：半透明全屏遮罩 ----------
    function showMask(){
      if (mask && mask.parentNode) { return; }
      mask = doc.createElement('div');
      mask.style.cssText = 'position:fixed;inset:0;z-index:99996;background:rgba(0,0,0,0);';
      mask.addEventListener('touchstart', function(e){
        e.preventDefault();
        closePanel();
        hideNav();
      }, {passive:false});
      (isShadow ? root : doc.body || doc.documentElement).appendChild(mask);
    }
    function hideMask(){
      if (mask && mask.parentNode) { mask.parentNode.removeChild(mask); }
      mask = null;
    }

    // ---------- 真导航展开/收起 ----------
    var SHOW_CSS = NAV_SEL + ' { width: auto !important; min-width: 220px !important; max-width: 78vw !important; ' +
      'pointer-events: auto !important; z-index: 999 !important; } ' +
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
        preview.style.cssText = 'position:fixed;z-index:99999;pointer-events:none;' +
          'max-width:min(280px,80vw);background:rgba(11,14,20,0.92);border:1px solid rgba(91,163,255,0.40);' +
          'border-radius:10px;padding:8px 10px;color:#E8ECF4;font-size:12px;line-height:1.5;' +
          'box-shadow:0 6px 20px rgba(0,0,0,0.5);';
        (isShadow ? root : doc.body || doc.documentElement).appendChild(preview);
      }
      preview.textContent = text;
      var l = Math.min(x + 14, window.innerWidth - preview.offsetWidth - 8);
      var t = Math.min(y + 14, window.innerHeight - preview.offsetHeight - 8);
      preview.style.left = Math.max(8, l) + 'px';
      preview.style.top = Math.max(8, t) + 'px';
    }
    function hidePreview(){
      if (preview && preview.parentNode) { preview.parentNode.removeChild(preview); }
      preview = null;
    }
    function hoverItemAt(x, y){
      var el = doc.elementFromPoint(x, y);
      while (el && el !== navEl && (!el.innerText || !el.innerText.trim())) { el = el.parentElement; }
      if (!el || el === navEl || !navEl.contains(el)) { return null; }
      return el;
    }
    function previewText(el){
      var t = (el.innerText || '').trim();
      if (t.length > 180) { t = t.slice(0, 180) + '…'; }
      return t;
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
    function entryLabel(el, maxLen){
      var txt = (el.innerText || '').replace(/\s+/g, ' ').trim();
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
      el.style.outline = '2px solid #5BA3FF';
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
        'width:min(340px,92vw);max-height:38vh;display:flex;flex-direction:column;' +
        'background:linear-gradient(180deg, rgba(21,27,40,0.55), rgba(13,17,26,0.72));' +
        '-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);' +
        'border:1px solid rgba(255,255,255,0.10);border-radius:18px;z-index:99998;' +
        'box-shadow:0 14px 44px rgba(0,0,0,0.55);overflow:hidden;';
      var head = doc.createElement('div');
      head.style.cssText = 'display:flex;align-items:center;gap:8px;padding:12px 14px;' +
        'cursor:grab;user-select:none;-webkit-user-select:none;touch-action:none;' +
        'border-bottom:1px solid rgba(255,255,255,0.08);';
      var grip = doc.createElement('div');
      grip.textContent = '≡';
      grip.style.cssText = 'color:#8B93A7;font-size:16px;';
      var title = doc.createElement('div');
      title.textContent = '对话问题导航';
      title.style.cssText = 'flex:1;color:#E8ECF4;font-size:14px;font-weight:600;';
      var close = doc.createElement('div');
      close.textContent = '收起';
      close.style.cssText = 'color:#8B93A7;font-size:13px;padding:4px 10px;cursor:pointer;border-radius:8px;';
      close.addEventListener('touchstart', function(e){ e.stopPropagation(); }, {passive:true});
      close.onclick = closePanel;
      head.appendChild(grip); head.appendChild(title); head.appendChild(close);
      var list = doc.createElement('div');
      list.style.cssText = 'overflow-y:auto;padding:6px 0;max-height:30vh;';
      turns.forEach(function(el, i){
        var item = doc.createElement('div');
        item.setAttribute('data-idx', i);
        item.textContent = (i + 1) + '. ' + entryLabel(el, 38);
        item.style.cssText = 'color:#C7CFDD;font-size:13px;line-height:1.45;padding:9px 14px;' +
          'border-bottom:1px solid rgba(255,255,255,0.06);cursor:pointer;';
        item.onclick = function(){
          jumpTo(el);
          closePanel();
        };
        list.appendChild(item);
      });
      var foot = doc.createElement('div');
      foot.style.cssText = 'display:flex;justify-content:space-between;align-items:center;padding:4px 14px 10px;';
      var diag = doc.createElement('div');
      diag.textContent = '诊断';
      diag.style.cssText = 'color:#8B93A7;font-size:11px;padding:4px 8px;cursor:pointer;border-radius:6px;';
      diag.onclick = function(){ closePanel(); showDiagCard(); };
      foot.appendChild(diag);
      panel.appendChild(head); panel.appendChild(list); panel.appendChild(foot);
      (isShadow ? root : doc.body || doc.documentElement).appendChild(panel);
      head.addEventListener('touchstart', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
        var r = panel.getBoundingClientRect();
        drag = {
          startX: t.clientX, startY: t.clientY,
          baseX: r.left, baseY: r.top,
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
        var nx = Math.max(4, Math.min(window.innerWidth - p.offsetWidth - 4, drag.baseX + dx));
        var ny = Math.max(4, Math.min(window.innerHeight - p.offsetHeight - 4, drag.baseY + dy));
        p.style.left = nx + 'px';
        p.style.top = ny + 'px';
        p.style.transform = 'none';
        clearHl();
        var it = itemAt(t.clientX, t.clientY);
        if (it) {
          var idx = parseInt(it.getAttribute('data-idx'), 10);
          var full = (drag.turns[idx].innerText || '').trim();
          if (full.length > 160) { full = full.slice(0, 160) + '…'; }
          showPreview(t.clientX, t.clientY, full);
          it.style.background = 'rgba(91,163,255,0.18)';
        } else {
          hidePreview();
        }
      }
      e.preventDefault();
    }
    function dragEnd(){
      if (!drag) { return; }
      drag = null;
      hidePreview();
      clearHl();
    }
    function closePanel(){
      if (panel && panel.parentNode) { panel.parentNode.removeChild(panel); }
      panel = null;
      hideMask();
      hidePreview();
    }
    function toggleNavigator(){
      var now = Date.now();
      if (window.__zcodeNavLastToggle && now - window.__zcodeNavLastToggle < 700) { return; }
      window.__zcodeNavLastToggle = now;
      if (navVisible) { hideNav(); return; }
      if (showNav()) {
        hint('已唤出对话问题导航（点外部或左缘三滑收起）');
        return;
      }
      if (panel) { closePanel(); return; }
      var turns = collectTurns();
      if (!turns.length) {
        showDiagCard();
        return;
      }
      buildPanel(turns);
      vibrate();
      hint('已唤出问题导航（点外部收起）');
    }

    // ---------- 手势：左缘 1.8 秒内快速滑 3 次 ----------
    function onStart(e){
      var t = e.touches[0];
      if (t.clientX > EDGE) { track = null; return; }
      track = { x0: t.clientX, y0: t.clientY, t0: Date.now() };
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
      track = null;
      if (Math.abs(dy) < SWIPE_MIN || dt > SWIPE_MAX_MS) { return; }
      var now = Date.now();
      swipes.push(now);
      swipes = swipes.filter(function(ts){ return now - ts <= WINDOW_MS; });
      if (swipes.length < NEEDED) { return; }
      swipes = [];
      if (now - lastTrigger < COOLDOWN) { return; }
      lastTrigger = now;
      toggleNavigator();
    }
    evRoot.addEventListener('touchstart', onStart, {passive:true});
    evRoot.addEventListener('touchmove', onMove, {passive:true});
    evRoot.addEventListener('touchend', onEnd, {passive:true});
    evRoot.addEventListener('touchmove', dragMove, {passive:false});
    evRoot.addEventListener('touchend', dragEnd, {passive:true});

    // ---------- 新回复监听 ----------
    if (!replyOn) { return; }
    var THROTTLE_MS = 25000, lastNotify = 0;
    function notify(text){
      var now = Date.now();
      if (now - lastNotify < THROTTLE_MS) { return; }
      lastNotify = now;
      var br = bridge();
      if (!br || !br.onReply) { return; }
      try { br.onReply(text); } catch (e) {}
    }
    function noise(el){
      if (!el || el.nodeType !== 1) { return true; }
      if (el.id === 'zcode-fallback-nav' || el.id === 'zcode-nav-hint' || el.id === 'zcode-nav-preview' || el.id === 'zcode-diag-card') { return true; }
      if (el.closest && el.closest('#zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return true; }
      if (el.getAttribute) {
        var tid = el.getAttribute('data-testid') || '';
        if (/navigator/i.test(tid)) { return true; }
      }
      return false;
    }
    function txt(el){ return (el.innerText || '').trim(); }
    function send(el){
      var t = txt(el);
      // 取"最终那句回复"：行内最后一段非空文字
      var lines = t.split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
      if (lines.length > 1) { t = lines[lines.length - 1]; }
      if (t.length > 90) { t = t.slice(0, 90) + '…'; }
      notify(t);
    }
    // ===== 数据定稿版（真机结构验证）：只报"最终回复" =====
    // 行 = v4-row-<id>（id 稳定）；class 区分身份：group/user-row 我发的、group/assistant-row 助手发的；
    // 思考/工具是独立行且带 chat-reasoning/chat-tool/tool-summary 标记；
    // 反馈按钮 v4-feedback-like-<id> 在行外、按行 id 关联，出现 = 该行消息写完。
    var pending2 = [];
    // 注入时基线：历史行（重连补渲染）永不提醒
    var rowBaseline = {};
    (function(){
      var existing = root.querySelectorAll('[data-testid^="v4-row"]');
      for (var i = 0; i < existing.length; i++) {
        var tid = existing[i].getAttribute('data-testid') || '';
        if (tid) { rowBaseline[tid] = true; }
      }
    })();
    function rowIdOf(el){
      var tid = el.getAttribute ? (el.getAttribute('data-testid') || '') : '';
      return tid.replace(/^v4-row-/, '');
    }
    function isAnswerRow(row){
      var cls = ' ' + (row.className || '') + ' ';
      if (cls.indexOf('group/assistant-row') < 0) { return false; }
      if (row.querySelector('[data-testid^="chat-reasoning"], [data-testid^="chat-tool"], [data-testid^="tool-summary"]')) { return false; }
      return true;
    }
    // 完成监视：反馈按钮出现即报；否则文本稳定 2 轮（约 3 秒）兜底
    var watchers = {};      // rowId -> { row, lastLen, stable, timer }
    var doneRows = {};      // rowId -> true（已报过，防重）
    function watchRow(row){
      var rid = rowIdOf(row);
      if (!rid || watchers[rid] || doneRows[rid]) { return; }
      var w = { row: row, lastLen: -1, stable: 0, timer: null };
      watchers[rid] = w;
      function tick(){
        if (doneRows[rid]) { delete watchers[rid]; return; }
        if (!w.row.parentNode) { delete watchers[rid]; return; }
        var t = txt(w.row);
        if (t.length >= 2) {
          if (t.length === w.lastLen) {
            w.stable++;
            if (w.stable >= 2) {
              delete watchers[rid];
              doneRows[rid] = true;
              send(w.row);
              return;
            }
          } else {
            w.stable = 0;
            w.lastLen = t.length;
          }
        } else {
          w.stable = 0;
          w.lastLen = t.length;
        }
        w.timer = setTimeout(tick, 1500);
      }
      tick();
    }
    function onFeedback(rid){
      if (doneRows[rid]) { return; }
      doneRows[rid] = true;
      var w = watchers[rid];
      if (w) {
        delete watchers[rid];
        send(w.row);
      } else {
        var row = root.querySelector('[data-testid="v4-row-' + rid + '"]');
        if (row && isAnswerRow(row)) { send(row); }
      }
    }
    function flush(){
      var adds = pending2; pending2 = [];
      adds.forEach(function(n){
        var el = (n.nodeType === 1) ? n : (n.parentElement || null);
        if (!el || noise(el)) { return; }
        // 1) 新消息行
        var row = null;
        var tid0 = el.getAttribute ? (el.getAttribute('data-testid') || '') : '';
        if (tid0.indexOf('v4-row') === 0) { row = el; }
        else if (el.querySelector) {
          var r0 = el.querySelector('[data-testid^="v4-row"]');
          if (r0) { row = r0; }
        }
        if (row) {
          var rid = rowIdOf(row);
          if (rid && !rowBaseline['v4-row-' + rid] && isAnswerRow(row)) {
            rowBaseline['v4-row-' + rid] = true;
            watchRow(row);
          }
          return;
        }
        // 2) 反馈按钮（行外，按行 id 关联）→ 该行消息已写完
        var fb = null;
        var t1 = el.getAttribute ? (el.getAttribute('data-testid') || '') : '';
        if (t1.indexOf('v4-feedback') === 0) { fb = el; }
        else if (el.querySelector) {
          var f0 = el.querySelector('[data-testid^="v4-feedback"]');
          if (f0) { fb = f0; }
        }
        if (fb) {
          var m = /v4-feedback-(?:like|dislike)-(\d+)/.exec(fb.getAttribute('data-testid') || '');
          if (m) { onFeedback(m[1]); }
        }
      });
    }
    try {
      var obs = new MutationObserver(function(muts){
        for (var i = 0; i < muts.length; i++) {
          if (muts[i].type !== 'childList') { continue; }
          for (var j = 0; j < muts[i].addedNodes.length; j++) {
            if (muts[i].addedNodes[j].nodeType === 1) { pending2.push(muts[i].addedNodes[j]); }
          }
        }
        // 直接冲刷：不依赖 setTimeout（后台节流会把去抖定时器冻结到回前台才跑）
        if (pending2.length) { flush(); }
      });
      obs.observe(root, {childList:true, subtree:true});
    } catch (e) {}
  };

  // ---------- 顶层执行 ----------
  BODY(css, navOn, replyOn);

  // ---------- 广播到同源 iframe / shadow DOM（定期补扫迟加载的框架） ----------
  function walk(r){
    var frames = r.querySelectorAll('iframe, frame');
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      try {
        if (f.contentWindow && f.contentWindow.eval) {
          f.contentWindow.eval('(' + BODY.toString() + ')(' + JSON.stringify(css) + ',' + navOn + ',' + replyOn + ')');
          walk(f.contentDocument);
        }
      } catch (e) {}
    }
    var hosts = r.querySelectorAll('*');
    for (var j = 0; j < hosts.length; j++) {
      var sr = hosts[j].shadowRoot;
      if (sr) {
        var prev = window.__zcodeRoot;
        window.__zcodeRoot = sr;
        try { BODY(css, navOn, replyOn); } catch (e) {}
        window.__zcodeRoot = prev;
        walk(sr);
      }
    }
  }
  walk(document);
  setInterval(function(){ walk(document); }, 5000);
}
)(null,true,true)
