// ===== 04_theme.js (305-619) =====
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
      // 注入 UI 配色跟随页面主题：浅色页给 <html> 挂 zcode-ui-light（02_style.js 的 token 覆盖生效）
      var de = doc.documentElement;
      if (de) { de.classList.toggle('zcode-ui-light', !t.dark); }
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
      for (var i = 0; el && el.nodeType === 1 && i < 25; i++) {
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
      var xs = [0.25, 0.5, 0.75];
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
      for (var i = 0; el && el.nodeType === 1 && i < 25; i++) {
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
    setInterval(reportBars, 3000);

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
        var t = entryLabel(row, 600);   // 干净正文（排除复制/编辑等按钮文字）
        if (t.length >= 4 && t.length <= 600) { out.push(row); }
      }
      return out;
    }

    // ---------- 诊断信息（复制发给我） ----------
    function diagInfo(){
      var de = doc.documentElement;
      var out = {
        bundleVer: 35,
        url: (location.href || '').replace(/([?&](?:sid|hash)=)[^&]+/g, '$1***').slice(0, 140),
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
      // 回复链路埋点（JS 检测 + 原生通知两段，随诊断一起上报）
      out.replyTrace = replyTrace;
      var nt = '';
      try {
        var br = bridge();
        if (br && br.getTrace) { nt = String(br.getTrace()); }
      } catch (e) {}
      if (nt) {
        try { out.nativeTrace = JSON.parse(nt); } catch (e2) { out.nativeTrace = nt; }
      }
      // 检测器内部状态（下一轮排查用）
      try {
        out.detector = 'baseline=' + Object.keys(rowBaseline).length +
          ' watchers=' + Object.keys(watchers).length +
          ' done=' + Object.keys(doneRows).length +
          ' settled=' + Object.keys(settledRows).length +
          ' anchor=' + anchor +
          ' runActive=' + runActive() +
          ' pendingReply=' + (pendingReply ? 1 : 0) +
          ' fixPending=' + (pending ? 1 : 0);
      } catch (e) {}
      out.uiTrace = uiTrace;
      out.jsErrors = (window.__zcodeErrors || []).slice(-10);
      out.composer = 'floatInput=' + floatInput + ' dock=' + (getDock() ? 1 : 0) +
        ' open=' + (composerOpen ? 1 : 0) + ' full=' + (composerFull ? 1 : 0) +
        ' foundAt=' + composerFoundAt +
        ' popups=' + (function(){ var d = getDock(); return d ? d.querySelectorAll('.zcode-popup-visible').length : 0; })();
      // composerProbe：诊断当下重新查一次容器，确认选择器本身是否命中（与 dock=0 区分"从未找到"vs"找到后又丢了"）
      try {
        var liveDock = root.querySelector(COMPOSER_SEL);
        out.composerProbe = liveDock ? ('hit cls=' + (liveDock.className || '').slice(0, 60) +
          ' hasExpand=' + (liveDock.querySelector('#zcode-composer-expand') ? 1 : 0)) : 'miss';
      } catch (e) { out.composerProbe = 'err'; }
      out.bars = 'top=' + (lastTop ? lastTop.r + ',' + lastTop.g + ',' + lastTop.b : 'null') +
        ' bot=' + (lastBot ? lastBot.r + ',' + lastBot.g + ',' + lastBot.b : 'null');
      // 弹窗复活诊断（v54）：dock 内所有疑似弹窗节点的位置链——含 findPopup 跳过的 pill 内部节点，
      // 用于确认 ask/确认弹窗实际渲染在哪、popup-mode 为什么没生效
      try {
        var dd = getDock();
        if (dd) {
          var diag = [];
          var dPop = dd.querySelectorAll(POPUP_HINT_SEL);
          for (var pz = 0; pz < dPop.length && pz < 20; pz++) {
            var pe = dPop[pz];
            var chain = [];
            var anc = pe;
            while (anc && anc !== dd) {
              var tag = anc.tagName || '';
              var tid = anc.getAttribute && anc.getAttribute('data-testid');
              if (tid) { tag += '[' + tid.slice(0, 30) + ']'; }
              if (anc.className && typeof anc.className === 'string' && anc.className) { tag += '.' + anc.className.split(' ')[0]; }
              chain.unshift(tag.slice(0, 44));
              anc = anc.parentNode;
            }
            var pr2 = pe.getBoundingClientRect();
            var pcs = getComputedStyle(pe);
            diag.push({
              tid: (pe.getAttribute && pe.getAttribute('data-testid')) || '',
              cls: ((pe.className || '').toString() || '').slice(0, 40),
              chain: chain.join('>').slice(0, 180),
              pos: pcs.position + '/z' + pcs.zIndex,
              disp: pcs.display + '/v' + pcs.visibility,
              rect: Math.round(pr2.width) + 'x' + Math.round(pr2.height) + '@' + Math.round(pr2.x) + ',' + Math.round(pr2.y),
              popVis: pe.classList && pe.classList.contains('zcode-popup-visible') ? 1 : 0
            });
          }
          out.popupDiag = {
            dockCls: (dd.className || '').toString().slice(0, 80),
            mode: dd.classList.contains('zcode-popup-mode') ? 1 : 0,
            present: dd.classList.contains('zcode-popup-present') ? 1 : 0,
            hints: diag,
            findPopup: (function(){
              try {
                var fp = findPopup(dd);
                return fp ? ((fp.getAttribute && fp.getAttribute('data-testid')) || fp.tagName) : null;
              } catch (e) { return 'err'; }
            })()
          };
        } else {
          out.popupDiag = 'no-dock';
        }
      } catch (e) { out.popupDiag = 'err'; }
      out.popupSnap = dockSnap;   // v55：dock 新增节点快照（弹窗卸载后也能从缓存读到结构）
      // dockHtml：dock 完整 HTML（截断），弹窗挂载期间复制诊断即可直接看结构——
      // 等效 F12 Elements 面板（v56）
      try {
        var dh = getDock();
        if (dh) { out.dockHtml = dh.innerHTML.slice(0, 3000); }
        else { out.dockHtml = 'no-dock'; }
      } catch (e) { out.dockHtml = 'err'; }
      return out;
    }
    function showDiagCard(){
      var info = diagInfo();
      var text = JSON.stringify(info, null, 1);
      var card = doc.createElement('div');
      card.id = 'zcode-diag-card';
      card.style.cssText = 'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:99999;' +
        'width:min(340px,90vw);max-height:72vh;overflow-y:auto;background:var(--zc-bg-deep);' +
        '-webkit-backdrop-filter:blur(24px) saturate(1.4);backdrop-filter:blur(24px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke);border-radius:var(--zc-radius-lg);padding:14px;color:var(--zc-text-dim);' +
        'font-size:12px;line-height:1.55;box-shadow:0 12px 40px rgba(0,0,0,0.6);' +
        'animation:zcodeFadeIn 0.18s ease-out;';
      var title = doc.createElement('div');
      title.textContent = '页面结构诊断';
      title.style.cssText = 'font-size:13px;font-weight:600;color:var(--zc-text-2);margin-bottom:8px;';
      var pre = doc.createElement('div');
      pre.style.cssText = 'white-space:pre-wrap;word-break:break-all;';
      pre.textContent = text;
      var copy = doc.createElement('div');
      copy.textContent = '复制诊断';
      copy.style.cssText = 'margin-top:10px;text-align:center;color:var(--zc-text-2);background:rgba(255,255,255,0.10);' +
        'border:1px solid var(--zc-stroke-soft);padding:10px;border-radius:var(--zc-radius);font-size:13px;font-weight:600;cursor:pointer;';
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
      close.style.cssText = 'margin-top:6px;text-align:center;color:var(--zc-text-3);padding:10px;' +
        'border-radius:var(--zc-radius);font-size:13px;cursor:pointer;';
      close.onclick = function(){ if (card.parentNode) { card.parentNode.removeChild(card); } };
      // 逃生通道：浮动输入异常时一键恢复底部输入框
      var toggleInput = doc.createElement('div');
      toggleInput.textContent = '输入框：' + (doc.documentElement.classList.contains('zcode-float-on') ? '隐藏' : '显示');
      toggleInput.style.cssText = 'margin-top:6px;text-align:center;color:var(--zc-text-3);padding:10px;' +
        'border-radius:var(--zc-radius);font-size:13px;cursor:pointer;';
      toggleInput.onclick = function(){
        doc.documentElement.classList.toggle('zcode-float-on');
        toggleInput.textContent = '输入框：' + (doc.documentElement.classList.contains('zcode-float-on') ? '隐藏' : '显示');
        hint('输入框已' + (doc.documentElement.classList.contains('zcode-float-on') ? '隐藏（点 logo 唤出）' : '恢复常驻底部'));
      };
      card.appendChild(title); card.appendChild(pre); card.appendChild(copy);
      card.appendChild(toggleInput); card.appendChild(close);
      (isShadow ? root : doc.body || doc.documentElement).appendChild(card);
    }

