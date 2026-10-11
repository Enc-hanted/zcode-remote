// ===== 09_agent.js (顶层执行区·收尾段；文件末的 "}" 闭合最外层包装函数——01_core.js 开、本文件闭) =====
  // ---------- 一期「状态感知轴」：09_agent 只读状态机（→ br.onAgentState + 悬浮光点） ----------
  // 独立 1s 轻轮询，不进 syncComposer 轮询中心；__zcSettings.agentStateOn !== false 门控，
  // 注入时关闭则整个 IIFE 不启动（缺件健全）。热更关闭（applySettings 写回 ZSET，见 07）
  // 后每拍自读该键：不再判定/上报/显示光点——轮询保留一个空拍的布尔检查，换取 07→09 零
  // 跨闭包调用（同 zcHaptic 每拍读 hapticOn 的松耦合做法）。
  // 判定自建（评审 4：runActive/checkRunEnd 是 07 的闭包函数，不可引），选择器沿用已实机
  // 验证的 v4-* 族，出处（dev/bundle 内实际行号）：
  //   generating : [data-testid^="v4-stop"], [data-testid^="chat-loading"]（07_settings.js:158-159，runActive 首行）
  //   thinking   : 末条 assistant 行含 [data-testid^="chat-reasoning"]（07_settings.js:208，isAnswerRow 内；函数头 205）
  //   done       : generating/thinking → 非活跃的下降沿；行完成证据 = 反馈按钮
  //                v4-feedback-(like|dislike)-<rid>（07_settings.js:165，行外按 rid 关联）
  //   waitConfirm: dock 内确认弹窗命中（选择器复制自 06_composer.js:456-467 的 POPUP_HINT_SEL
  //                常量——字符串可复制，闭包不可引；dock 选择器复制自 :370 COMPOSER_SEL）
  //   error      : 本期不产出——错误行选择器未经实机取证（evolution-plan §7-#1），宁漏报不误报；
  //                取证通过后在此补 error 分支（错误态判定 + onAgentState('error', snippet)）
  //   idle/unknown：有消息行=空闲，无行=unknown（页面未就绪/选择器漂移 → 停 unknown 不误报）
  // 只报变化沿；桥判空照 07_settings.js:134-141 notify() 先例，try-catch 静默——老 APK 无
  // onAgentState 桥方法时照常跑光点、不上报。
  // 局限（如实注明）：本段在顶层文档执行、用 document 查询，不被 08 的 BODY 广播带进
  // shadow DOM/iframe——若页面把消息行放进 shadow root，本状态机退化为 unknown（漏报不误报，
  // 与 error 同等保守取向）；实机核对若发现该场景，再评估并入 BODY 广播。
  // 光点自绘而非挂 #zcode-composer-icon（06_composer.js:1282-1297 ensureIcon 创建）：waitConfirm
  // 恰是最需要被看见的状态，而 ensureIcon 在弹窗态/唤出态会把 icon display:none（06_composer.js:1275-1280），
  // 挂在 icon 里的光点在该态不可见；自绘 fixed 光点（pointer-events:none，不挡任何点击）不依赖
  // icon 生命周期。与原生状态条同开时以原生条为唯一真源（同源同色，光点只是页面内就近呈现）。
  (function () {
    var ZS0 = window.__zcSettings;
    if (ZS0 && ZS0.agentStateOn === false) { return; }
    if (window.__zcodeAgentOn) { return; }   // 同窗口重复注入防重入（BODY 的 __zcodeDone 同款语义）
    window.__zcodeAgentOn = true;

    var doc = document;
    // 选择器常量（复制，出处见文件头注释；禁止闭包引用兄弟模块）
    var RUN_SEL = '[data-testid^="v4-stop"], [data-testid^="chat-loading"]';
    var ROW_SEL = '[data-testid^="v4-row"]';
    var REASON_SEL = '[data-testid^="chat-reasoning"]';
    var DOCK_SEL = '[data-v4-composer-dock="true"]';
    // 确认弹窗族（06_composer.js:456-467 POPUP_HINT_SEL 全量复制，含 v57/v61/v64 三轮扩展）
    var POPUP_SEL = '[role="dialog"], [role="alertdialog"], [data-testid*="ask" i], [data-testid*="confirm" i], ' +
      '[data-testid*="dialog" i], [data-testid*="modal" i], [data-testid*="popup" i], [data-testid*="request" i], [aria-modal="true"], ' +
      '[data-elicitation-dialog-card], [data-elicitation-dialog-body], ' +
      '[data-testid*="permission" i], [data-testid*="approve" i], [data-testid*="allow" i], ' +
      '[data-testid*="authorize" i], [data-testid*="can-use" i], [data-testid*="consent" i], ' +
      '[data-testid*="hook-pending" i], [data-testid*="user-input" i]';

    // 系统"减弱动态效果"（注入层作用域内 matchMedia 可用；uiAnim 固定开启无需另查 ANIM_ON）
    var REDUCED = false;
    try { REDUCED = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (eR) {}
    var last = 'unknown';
    var wasActive = false;   // 上拍是否 generating|thinking（done 下降沿的"沿"来源）
    var dotHideT = 0;

    function rowIdOf(el){
      // 07_settings.js:201-204 rowIdOf 同款等价重写
      var tid = el.getAttribute ? (el.getAttribute('data-testid') || '') : '';
      return tid.replace(/^v4-row-/, '');
    }
    function isAssistant(row){
      return (' ' + (row.className || '') + ' ').indexOf('group/assistant-row') >= 0;
    }
    function lastAssistantRow(){
      try {
        var rows = doc.querySelectorAll(ROW_SEL);
        for (var i = rows.length - 1; i >= 0; i--) {
          if (rows[i].parentNode && isAssistant(rows[i])) { return rows[i]; }
        }
      } catch (e) {}
      return null;
    }
    // "开着"的确认弹窗：可见性检查复制自 06_composer.js:474-483 findPopup（v60 教训——关闭的
    // 弹窗节点残留 data-state=closed/display:none/visibility:hidden/opacity:0，不能算命中）。
    // 不做 06 的 fixed/absolute 几何兜底扫描（那是完整防御），此处宁漏报不误报。
    function openPopup(dock){
      try {
        var hit = dock.querySelectorAll(POPUP_SEL);
        for (var i = 0; i < hit.length; i++) {
          var el = hit[i];
          if (el.id && el.id.indexOf('zcode-') === 0) { continue; }   // 自家注入层不算
          if ((el.getAttribute('data-state') || '') === 'closed') { continue; }
          var cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) { continue; }
          return el;
        }
      } catch (e) {}
      return null;
    }
    function snippetOf(el){
      // 最后文本片段：行/弹窗内最后一段非空文字（07_settings.js:169-175 send() 同款取法），≤60 字
      var t = '';
      try { t = ((el.innerText === undefined ? el.textContent : el.innerText) || '').trim(); } catch (eT) { t = ''; }
      var lines = t.split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
      var s = lines.length ? lines[lines.length - 1] : t;
      if (s.length > 60) { s = s.slice(0, 60) + '…'; }
      return s;
    }
    function classify(){
      // 优先级：waitConfirm（用户须行动）> generating > thinking > done 沿 > idle/unknown
      var dock = doc.querySelector(DOCK_SEL);
      var pop = dock ? openPopup(dock) : null;
      if (pop) { return { s: 'waitConfirm', snip: snippetOf(pop) }; }
      if (doc.querySelector(RUN_SEL)) {
        var ar = lastAssistantRow();
        return { s: 'generating', snip: ar ? snippetOf(ar) : '' };
      }
      var row = lastAssistantRow();
      if (row && row.querySelector(REASON_SEL)) { return { s: 'thinking', snip: snippetOf(row) }; }
      if (wasActive && row) {
        // 下降沿 + 行完成证据（反馈按钮行外按 rid 关联，07_settings.js:164-165 同款）；
        // 查不到证据（行已虚拟化卸载/选择器漂移）→ idle，宁漏报不误报
        var rid = rowIdOf(row);
        if (rid && doc.querySelector('[data-testid="v4-feedback-like-' + rid + '"], [data-testid="v4-feedback-dislike-' + rid + '"]')) {
          return { s: 'done', snip: snippetOf(row) };
        }
      }
      return { s: doc.querySelector(ROW_SEL) ? 'idle' : 'unknown', snip: '' };
    }
    // ---------- 悬浮状态光点（自绘；呼吸 keyframes 与 --zc-live/--zc-warn token 在 02_style） ----------
    // 位置：收纳图标（左下 12,12+safeB，44px）右上角外——图标自身的手势区之外，不干扰其命中
    function dotEl(){
      var d = doc.getElementById('zcode-agent-dot');
      if (!d) {
        d = doc.createElement('div');
        d.id = 'zcode-agent-dot';
        d.style.cssText = 'position:fixed;left:53px;bottom:calc(53px + var(--zc-safe-b, 0px));' +
          'width:6px;height:6px;border-radius:3px;z-index:99997;pointer-events:none;';
        (doc.body || doc.documentElement).appendChild(d);
      }
      return d;
    }
    function dotOff(){
      if (dotHideT) { clearTimeout(dotHideT); dotHideT = 0; }
      var d = doc.getElementById('zcode-agent-dot');
      if (d && d.parentNode) { try { d.parentNode.removeChild(d); } catch (e0) {} }
    }
    function renderDot(s){
      if (s !== 'thinking' && s !== 'generating' && s !== 'waitConfirm' && s !== 'done') { dotOff(); return; }
      var d = dotEl();
      if (dotHideT) { clearTimeout(dotHideT); dotHideT = 0; }
      d.style.animation = 'none';
      d.style.transition = '';
      d.style.opacity = '1';
      if (s === 'waitConfirm') {
        d.style.background = 'var(--zc-warn)';   // amber 常亮
        return;
      }
      d.style.background = 'var(--zc-live)';
      if (s === 'done') {
        // 绿常亮 2s 渐隐后移除（done 是瞬时沿）；减弱动效下渐隐是动画 → 直接移除
        if (REDUCED) { dotOff(); return; }
        d.style.transition = 'opacity 2s ease-out';
        void d.offsetWidth;   // 起点帧先生效，否则过渡不触发（同 06 morphGhost 的做法）
        d.style.opacity = '0';
        dotHideT = setTimeout(dotOff, 2100);
        return;
      }
      // thinking|generating：呼吸绿 1.2s（状态灯呼吸 = 全 App 唯一 >300ms 动画例外）；
      // prefers-reduced-motion 时降级半透明常亮（不播动画）
      if (REDUCED) { d.style.opacity = '0.55'; return; }
      d.style.animation = 'zcodeDotBreath 1.2s ease-in-out infinite';
    }
    function tick(){
      var zs = window.__zcSettings;
      if (zs && zs.agentStateOn === false) { dotOff(); return; }   // 热更关：空拍静默
      var r;
      try { r = classify(); } catch (eC) { r = { s: 'unknown', snip: '' }; }
      if (r.s !== last) {
        last = r.s;   // 先记账再打桥：桥抛异常不至于每拍重报
        var br = window.zcodeBridge;
        if (br && br.onAgentState) { try { br.onAgentState(r.s, r.snip || ''); } catch (eB) {} }
      }
      try { renderDot(r.s); } catch (eD) {}
      wasActive = (r.s === 'generating' || r.s === 'thinking');
    }
    tick();   // 首拍立即建立基线（unknown 起步，无沿不报）
    setInterval(tick, 1000);
  })();
}
