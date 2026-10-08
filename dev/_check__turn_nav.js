(
function(){
  if (window.__zcodeTurnNav) { return; }
  window.__zcodeTurnNav = true;

  var EDGE = 44, SWIPE_MIN = 46, SWIPE_MAX_MS = 450, WINDOW_MS = 1800, NEEDED = 3, COOLDOWN = 1200;
  var track = null, swipes = [], lastTrigger = 0;
  var panel = null, navVisible = false, preview = null;
  var drag = null;

  function hint(text){
    var old = document.getElementById('zcode-nav-hint');
    if (old && old.parentNode) { old.parentNode.removeChild(old); }
    var h = document.createElement('div');
    h.id = 'zcode-nav-hint';
    h.textContent = text;
    h.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);' +
      'background:rgba(21,27,40,0.94);color:#E8ECF4;font-size:13px;line-height:1;' +
      'padding:10px 16px;border-radius:999px;z-index:99999;pointer-events:none;' +
      'border:1px solid rgba(91,163,255,0.45);box-shadow:0 4px 18px rgba(0,0,0,0.45);';
    (document.body || document.documentElement).appendChild(h);
    setTimeout(function(){ if (h.parentNode) { h.parentNode.removeChild(h); } }, 1600);
  }

  function collectTurns(){
    var all = Array.prototype.slice.call(document.querySelectorAll('[data-testid*="turn" i]'));
    var cands = all.filter(function(el){
      return !/navigator/i.test(el.getAttribute('data-testid') || '');
    });
    var outer = cands.filter(function(el){
      return !cands.some(function(o){ return o !== el && o.contains(el); });
    });
    return outer.filter(function(el){
      return (el.innerText || '').trim().length > 0;
    });
  }

  function entryLabel(el, maxLen){
    var txt = (el.innerText || '').replace(/\s+/g, ' ').trim();
    if (txt.length > maxLen) { txt = txt.slice(0, maxLen) + '…'; }
    return txt;
  }

  function showPreview(x, y, text){
    if (!preview) {
      preview = document.createElement('div');
      preview.id = 'zcode-nav-preview';
      preview.style.cssText = 'position:fixed;z-index:99999;pointer-events:none;' +
        'max-width:min(280px,80vw);background:rgba(11,14,20,0.92);border:1px solid rgba(91,163,255,0.40);' +
        'border-radius:10px;padding:8px 10px;color:#E8ECF4;font-size:12px;line-height:1.5;' +
        'box-shadow:0 6px 20px rgba(0,0,0,0.5);';
      document.body.appendChild(preview);
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

  function buildPanel(turns){
    panel = document.createElement('div');
    panel.setAttribute('aria-label', '对话问题导航');
    panel.style.cssText = 'position:fixed;left:50%;top:14vh;transform:translateX(-50%);' +
      'width:min(340px,92vw);max-height:38vh;display:flex;flex-direction:column;' +
      'background:linear-gradient(180deg, rgba(21,27,40,0.78), rgba(13,17,26,0.92));' +
      '-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);' +
      'border:1px solid rgba(91,163,255,0.30);border-radius:16px;z-index:99998;' +
      'box-shadow:0 12px 40px rgba(0,0,0,0.5);overflow:hidden;';

    var head = document.createElement('div');
    head.style.cssText = 'display:flex;align-items:center;gap:8px;padding:12px 14px;' +
      'cursor:grab;user-select:none;-webkit-user-select:none;touch-action:none;' +
      'border-bottom:1px solid rgba(255,255,255,0.08);';
    var grip = document.createElement('div');
    grip.textContent = '≡';
    grip.style.cssText = 'color:#8B93A7;font-size:16px;';
    var title = document.createElement('div');
    title.textContent = '对话问题导航 · 按住拖动预览';
    title.style.cssText = 'flex:1;color:#E8ECF4;font-size:14px;font-weight:600;';
    var close = document.createElement('div');
    close.textContent = '收起';
    close.style.cssText = 'color:#8B93A7;font-size:13px;padding:4px 10px;cursor:pointer;border-radius:8px;';
    close.addEventListener('touchstart', function(e){ e.stopPropagation(); }, {passive:true});
    close.onclick = closePanel;
    head.appendChild(grip); head.appendChild(title); head.appendChild(close);

    var list = document.createElement('div');
    list.style.cssText = 'overflow-y:auto;padding:6px 0;max-height:31vh;';
    turns.forEach(function(el, i){
      var item = document.createElement('div');
      item.setAttribute('data-idx', i);
      item.textContent = (i + 1) + '. ' + entryLabel(el, 38);
      item.style.cssText = 'color:#C7CFDD;font-size:13px;line-height:1.45;padding:9px 14px;' +
        'border-bottom:1px solid rgba(255,255,255,0.06);cursor:pointer;';
      item.onclick = function(){
        el.scrollIntoView({behavior:'smooth', block:'start'});
        closePanel();
      };
      list.appendChild(item);
    });
    panel.appendChild(head); panel.appendChild(list);
    document.body.appendChild(panel);

    // 按住头部开始拖动
    head.addEventListener('touchstart', function(e){
      if (e.touches.length !== 1) return;
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

  function itemAt(x, y){
    var els = panel.querySelectorAll('[data-idx]');
    for (var i = 0; i < els.length; i++) {
      var r = els[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) { return els[i]; }
    }
    return null;
  }
  function clearHl(){
    if (!panel) return;
    var els = panel.querySelectorAll('[data-idx]');
    for (var i = 0; i < els.length; i++) { els[i].style.background = ''; }
  }

  // 拖动中：移动面板 + 悬停条目未松手显示预览
  function dragMove(e){
    if (!drag) return;
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
  function dragEnd(e){
    if (!drag) return;
    drag = null;
    hidePreview();
    clearHl();
  }

  function closePanel(){
    if (panel && panel.parentNode) { panel.parentNode.removeChild(panel); }
    panel = null;
    hidePreview();
  }

  function toggleNavigator(){
    var el = document.querySelector('[data-testid="v4-turn-navigator"], [aria-label="对话问题导航"]');
    if (el) {
      if (navVisible) {
        el.style.setProperty('display', 'none', 'important');
        el.style.setProperty('visibility', 'hidden', 'important');
      } else {
        el.style.setProperty('display', 'flex', 'important');
        el.style.setProperty('visibility', 'visible', 'important');
        el.style.setProperty('opacity', '1', 'important');
        el.style.setProperty('pointer-events', 'auto', 'important');
      }
      navVisible = !navVisible;
      if (navigator.vibrate) { navigator.vibrate(15); }
      return;
    }
    if (panel) { closePanel(); return; }
    var turns = collectTurns();
    if (!turns.length) { hint('没找到可导航的会话内容'); return; }
    buildPanel(turns);
    if (navigator.vibrate) { navigator.vibrate(15); }
    hint('已唤出问题导航（左缘三滑收起）');
  }

  function onStart(e){
    var t = e.touches[0];
    if (t.clientX > EDGE) { track = null; return; }
    track = { x0: t.clientX, y0: t.clientY, t0: Date.now() };
  }
  function onMove(e){
    if (!track) return;
    var t = e.touches[0];
    if (Math.abs(t.clientX - track.x0) > Math.abs(t.clientY - track.y0) + 24) { track = null; }
  }
  function onEnd(e){
    if (!track) return;
    var t = e.changedTouches[0];
    var dy = t.clientY - track.y0;
    var dt = Date.now() - track.t0;
    track = null;
    if (Math.abs(dy) < SWIPE_MIN || dt > SWIPE_MAX_MS) return;
    var now = Date.now();
    swipes.push(now);
    swipes = swipes.filter(function(ts){ return now - ts <= WINDOW_MS; });
    if (swipes.length < NEEDED) return;
    swipes = [];
    if (now - lastTrigger < COOLDOWN) return;
    lastTrigger = now;
    toggleNavigator();
  }

  document.addEventListener('touchstart', onStart, {passive:true});
  document.addEventListener('touchmove', onMove, {passive:true});
  document.addEventListener('touchend', onEnd, {passive:true});
  document.addEventListener('touchmove', dragMove, {passive:false});
  document.addEventListener('touchend', dragEnd, {passive:true});
}
)(null)
