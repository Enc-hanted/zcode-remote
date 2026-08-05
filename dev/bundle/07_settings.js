// ===== 07_settings.js (1372-1707) =====
    // ---------- 浮动输入：渲染时序 + 重挂载兜底 ----------
    // syncComposer 每次都重新查 live dock、补 expand 按钮、把 open/full 状态落到当前节点。
    // 必须持续轮询（不能"找到就停"）：React 重渲染会换掉 dock 节点，缓存引用会失效，
    // 持续轮询才能让状态始终跟着 live 节点走（v36 的 hasExpand=0、open=1 但无效果就是这个坑）。
    // UI 总开关关闭时不注入（纯网页）；applySettings 重新打开时再启轮询（composerTimer）。
    var composerTimer = null;
    var bodyPopupTimer = null;   // v53：body 弹窗检测（Radix 菜单让位）
    if (floatInput && UI_ON) {
      composerTimer = setInterval(syncComposer, 1000);
      bodyPopupTimer = setInterval(scanBodyPopups, 400);
    }

    // ---------- 设置即时生效 API（原生层从设置页返回时调用） ----------
    // 各开关就地切换：动效重建样式、浮动输入增删 logo、导航删注入样式、
    // 回复监听启停（含可见性伪装）、滚动条热切。原生层只在设置变化时调用，全部幂等。
    function setScrollbar(hide){
      var st = doc.getElementById('zcode-scrollbar-live');
      if (st && st.parentNode) { st.parentNode.removeChild(st); }
      if (hide === null) { return; }
      st = doc.createElement('style');
      st.id = 'zcode-scrollbar-live';
      // 与原生注入的 CSS_SCROLLBAR_HIDDEN/SLIM 同规则、后置覆盖，实现不重进会话的热切换
      st.textContent = hide ?
        '*{scrollbar-width:none!important;scrollbar-color:transparent transparent!important}' +
        '::-webkit-scrollbar{width:0!important;height:0!important;display:none!important}' +
        '::-webkit-scrollbar-track,::-webkit-scrollbar-thumb,::-webkit-scrollbar-button,::-webkit-scrollbar-corner{display:none!important;width:0!important;height:0!important}' :
        '*{scrollbar-width:thin!important;scrollbar-color:rgba(170,180,200,0.30) transparent!important;scrollbar-gutter:auto!important}' +
        '::-webkit-scrollbar{width:3px!important;height:3px!important}' +
        '::-webkit-scrollbar-track,::-webkit-scrollbar-corner{background:transparent!important}' +
        '::-webkit-scrollbar-thumb{background:rgba(170,180,200,0.30)!important;border-radius:2px!important;border:none!important}' +
        '::-webkit-scrollbar-button{display:none!important}';
      styleRoot().appendChild(st);
    }
    function removeLogo(){
      if (logoEl && logoEl.parentNode) { logoEl.parentNode.removeChild(logoEl); }
      logoEl = null;
    }
    function applySettings(o){
      if (!o) { return; }
      var nFloat = (o.floatInput !== false);
      var nAnim = (o.uiAnim !== false);
      var nNav = (o.turnNav !== false);
      var nReply = (o.notifyReply !== false);
      // 动效：重建 fxStyle（anim() 读 ANIM_ON，重建后全部按新值）
      if (nAnim !== ANIM_ON) {
        ANIM_ON = nAnim;
        if (fxStyle && fxStyle.parentNode) { fxStyle.parentNode.removeChild(fxStyle); }
        fxStyle = null;
        ensureFxStyle();
      }
      // 浮动输入：关闭时移除 logo 并恢复常驻底部输入框；开启时重建 logo + 重启轮询
      if (nFloat !== floatInput) {
        floatInput = nFloat;
        UI_ON = nFloat;
        if (UI_ON) {
          ensureFxStyle();
          if (!logoEl || !logoEl.parentNode) { createLogo(); }
          if (!composerTimer) { composerTimer = setInterval(syncComposer, 1000); }
          if (!bodyPopupTimer) { bodyPopupTimer = setInterval(scanBodyPopups, 400); }
        } else {
          removeLogo();
          if (composerOpen) { hideComposer(); }
          doc.documentElement.classList.remove('zcode-float-on');
          if (composerTimer) { clearInterval(composerTimer); composerTimer = null; }
          if (bodyPopupTimer) { clearInterval(bodyPopupTimer); bodyPopupTimer = null; }
        }
      }
      // 导航开关：关闭时收起已展开的导航
      if (nNav !== navOn) {
        navOn = nNav;
        if (!navOn) { hideNav(); closePanel(); }
      }
      // 回复监听 + 可见性伪装（通知开关的运行时门控）
      if (nReply !== replyActive) {
        replyActive = nReply;
        if (replyActive) { installVisLie(); bootstrapReply(); }
        else { uninstallVisLie(); }
      }
      // 滚动条热切（原生已注入原版，这里只在变化时加覆盖样式）
      if (o.scrollbar !== undefined) { setScrollbar(o.scrollbar); }
    }
    try {
      window.__zcodeAPI = { applySettings: applySettings, version: 1 };
    } catch (e) {}

    // ---------- 新回复监听 ----------
    // 运行时开关 replyActive 跟随"新回复通知"设置（即时生效）；bootstrapReply 只执行一次。
    // 注入时通知关闭则完全不装监听（省资源），之后在设置里打开时再启动。
    var replyActive = (replyOn === true);
    var replyBooted = false;
    var THROTTLE_MS = 25000, lastNotify = 0;
    // 锚点法：注入时记下"我的最后一条消息"的行 id；之后出现的行 id ≤ 锚点 = 历史懒加载，只进基线；
    // 注入后 5 秒内的行视为页面初始渲染，同样只进基线（并顺带刷新锚点）。
    // 行 id 服务端稳定递增（诊断验证 1019→1216 跨重载成立），比固定时间窗精确，能压住 10 秒后才渲染的历史批次。
    var ANCHOR_MS = 5000, injectAt = Date.now(), anchor = 0;
    function updateAnchor(rid){
      var n = parseInt(rid, 10);
      if (!isNaN(n) && n > anchor) { anchor = n; }
    }
    function isHistory(rid){
      var n = parseInt(rid, 10);
      return !isNaN(n) && n <= anchor;
    }
    function traceAdd(act, rid, txt){
      var d = new Date();
      var hh = ('0' + d.getHours()).slice(-2);
      var mm = ('0' + d.getMinutes()).slice(-2);
      var ss = ('0' + d.getSeconds()).slice(-2);
      replyTrace.push({ t: hh + ':' + mm + ':' + ss, act: act, rid: rid || '', txt: (txt || '').slice(0, 40) });
      if (replyTrace.length > 8) { replyTrace.shift(); }
    }
    function notify(text){
      var now = Date.now();
      if (now - lastNotify < THROTTLE_MS) { traceAdd('throttle', '', text); return; }
      lastNotify = now;
      var br = bridge();
      if (!br || !br.onReply) { traceAdd('nobridge', '', text); return; }
      try { br.onReply(text); traceAdd('notify', '', text); } catch (e) { traceAdd('err', '', text); }
    }
    function noise(el){
      if (!el || el.nodeType !== 1) { return true; }
      if (el.id === 'zcode-fallback-nav' || el.id === 'zcode-nav-hint' || el.id === 'zcode-nav-preview' || el.id === 'zcode-diag-card' || el.id === 'zcode-logo') { return true; }
      if (el.closest && el.closest('#zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card, #zcode-logo')) { return true; }
      if (el.getAttribute) {
        var tid = el.getAttribute('data-testid') || '';
        if (/navigator|composer/i.test(tid)) { return true; }
      }
      return false;
    }
    function txt(el){ return (el.innerText || '').trim(); }
    // 收集制：单条回复完成先滚动更新保活预览，整轮回复结束（连续多轮无"进行中的行"）才弹最终横幅。
    // 依据：答案行出反馈按钮 = 该行写完；v4-stop/加载行在 = 本轮还在生成。
    // 这样"一个任务 = 多步消息"只会收到一条最终通知，中间步骤只在保活通知里滚动。
    var pendingReply = null, finalFired = false, inactiveStreak = 0;
    var settledRows = {};   // 已结束的行（基线/历史/出过反馈）→ runActive 跳过，避免每轮重复 querySelector
    function runActive(){
      if (root.querySelector('[data-testid^="v4-stop"], [data-testid^="chat-loading"]')) { return true; }
      var rows = root.querySelectorAll('[data-testid^="v4-row"]');
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        var rid = rowIdOf(r);
        if (!rid || doneRows[rid] || settledRows[rid] || !isAnswerRow(r)) { continue; }
        if (!root.querySelector('[data-testid="v4-feedback-like-' + rid + '"], [data-testid="v4-feedback-dislike-' + rid + '"]')) { return true; }
      }
      return false;
    }
    function send(el){
      if (!replyActive) { return; }
      var t = txt(el);
      traceAdd('send', rowIdOf(el), t);
      // 取"最终那句回复"：行内最后一段非空文字
      var lines = t.split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
      if (lines.length > 1) { t = lines[lines.length - 1]; }
      if (t.length > 90) { t = t.slice(0, 90) + '…'; }
      // 先收进"本轮回复"并滚动更新保活预览；结束判定交给 checkRunEnd
      pendingReply = t;
      finalFired = false;
      inactiveStreak = 0;
      var br = bridge();
      if (br && br.onProgress) { try { br.onProgress(t); } catch (e) {} }
    }
    function checkRunEnd(){
      if (!pendingReply || finalFired) { return; }
      if (runActive()) { inactiveStreak = 0; return; }
      inactiveStreak++;
      if (inactiveStreak >= 3) {   // 连续 3 轮（约 6 秒）无进行中的行 → 本轮回复结束
        finalFired = true;
        var t = pendingReply; pendingReply = null;
        notify(t);
      }
    }
    // ===== 数据定稿版（真机结构验证）：只报"最终回复" =====
    // 行 = v4-row-<id>（id 稳定）；class 区分身份：group/user-row 我发的、group/assistant-row 助手发的；
    // 思考/工具是独立行且带 chat-reasoning/chat-tool/tool-summary 标记；
    // 反馈按钮 v4-feedback-like-<id> 在行外、按行 id 关联，出现 = 该行消息写完。
    var pending2 = [];
    // 注入时基线：已存在的行永不提醒；用户行同时用于刷新锚点
    var rowBaseline = {};
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
      if (!replyActive) { return; }
      var rid = rowIdOf(row);
      if (!rid || watchers[rid] || doneRows[rid]) { return; }
      traceAdd('watch', rid, '');
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
              settledRows[rid] = true;
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
      traceAdd('fb', rid, '');
      doneRows[rid] = true;
      settledRows[rid] = true;
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
      if (!replyActive) { return; }
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
          if (rid && !rowBaseline['v4-row-' + rid]) {
            rowBaseline['v4-row-' + rid] = true;
            var cls = ' ' + (row.className || '') + ' ';
            if (cls.indexOf('group/user-row') >= 0) { updateAnchor(rid); return; }
            if (!isAnswerRow(row)) { return; }
            if ((Date.now() - injectAt) < ANCHOR_MS || isHistory(rid)) {
              traceAdd('hist', rid, '');
              settledRows[rid] = true;   // 历史/初始渲染行已结束，不再参与 runActive 判定
              return;
            }
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
    // 一次性启动：基线 + observer + 轮询兜底（applySettings 重新打开通知时也会走到这里）
    function bootstrapReply(){
      if (replyBooted) { return; }
      replyBooted = true;
      (function(){
        var existing = root.querySelectorAll('[data-testid^="v4-row"]');
        for (var i = 0; i < existing.length; i++) {
          var el = existing[i];
          var tid = el.getAttribute('data-testid') || '';
          if (!tid) { continue; }
          rowBaseline[tid] = true;
          var cls = ' ' + (el.className || '') + ' ';
          if (cls.indexOf('group/user-row') >= 0) { updateAnchor(rowIdOf(el)); }
          else if (isAnswerRow(el)) { settledRows[rowIdOf(el)] = true; }   // 已完成的回答行直接记为结束
        }
      })();
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
      // 轮询兜底：observer 在后台冻结/漏报时，任何新出现的行 2 秒内都会被捞到
      // （也顺带覆盖"先插行后上 class"的时序，class 就位后下一轮即命中）
      setInterval(function(){
        if (!replyActive) { return; }
        try {
          var rows = root.querySelectorAll('[data-testid^="v4-row"]');
          for (var i = 0; i < rows.length; i++) {
            var r = rows[i];
            var rid = rowIdOf(r);
            if (!rid || rowBaseline['v4-row-' + rid] || watchers[rid] || doneRows[rid]) { continue; }
            rowBaseline['v4-row-' + rid] = true;
            var cls = ' ' + (r.className || '') + ' ';
            if (cls.indexOf('group/user-row') >= 0) { updateAnchor(rid); continue; }
            if (!isAnswerRow(r)) { continue; }
            if ((Date.now() - injectAt) < ANCHOR_MS || isHistory(rid)) {
              traceAdd('hist', rid, '');
              settledRows[rid] = true;
              continue;
            }
            watchRow(r);
          }
        } catch (e) {}
        // 本轮回复结束判定（连续 6 秒无进行中的行才弹最终横幅）
        checkRunEnd();
      }, 2000);
    }
    if (replyActive) { bootstrapReply(); }
  };

