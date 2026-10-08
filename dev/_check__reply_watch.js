(
function(){
  if (window.__zcodeReplyWatch) { return; }
  window.__zcodeReplyWatch = true;
  var THROTTLE_MS = 8000, lastNotify = 0, seq = 0;

  function notify(text){
    var now = Date.now();
    if (now - lastNotify < THROTTLE_MS) { return; }
    lastNotify = now;
    try { window.zcodeBridge.onReply(text); } catch (e) {}
  }

  function noise(el){
    if (!el || el.nodeType !== 1) { return true; }
    if (el.id === 'zcode-fallback-nav' || el.id === 'zcode-nav-hint' || el.id === 'zcode-nav-preview') { return true; }
    if (el.closest && el.closest('#zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview')) { return true; }
    if (el.getAttribute) {
      var tid = el.getAttribute('data-testid') || '';
      if (/navigator/i.test(tid)) { return true; }
    }
    return false;
  }
  function txt(el){ return (el.innerText || '').trim(); }

  function raiseBlock(el){
    var top = el;
    for (var i = 0; i < 6; i++) {
      var p = top.parentElement;
      if (!p || noise(p)) { break; }
      if (p.getAttribute && (p.getAttribute('data-testid') || p.getAttribute('role'))) { top = p; }
      else { break; }
    }
    return top;
  }
  function send(el){
    var t = txt(el);
    if (t.length < 24) { return; }
    if (t.length > 90) { t = t.slice(0, 90) + '…'; }
    notify(t);
  }

  // 1) 新增消息块：MutationObserver 批量去抖
  var pending = [], timer = null;
  function flush(){
    timer = null;
    var adds = pending; pending = [];
    var seen = {};
    adds.forEach(function(n){
      var el = (n.nodeType === 1) ? n : (n.parentElement || null);
      if (!el || noise(el) || seen[el]) { return; }
      seen[el] = 1;
      var top = raiseBlock(el);
      if (!noise(top) && !seen[top]) { seen[top] = 1; send(top); }
    });
  }
  var obs = new MutationObserver(function(muts){
    var burst = false;
    for (var i = 0; i < muts.length; i++) {
      if (muts[i].type !== 'childList') { continue; }
      for (var j = 0; j < muts[i].addedNodes.length; j++) {
        if (muts[i].addedNodes[j].nodeType === 1) { pending.push(muts[i].addedNodes[j]); burst = true; }
      }
    }
    if (burst) {
      if (timer) { clearTimeout(timer); }
      timer = setTimeout(flush, 900);
    }
  });

  function mainRoot(){
    var els = Array.prototype.slice.call(document.querySelectorAll('*'));
    var best = null, score = 0;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.scrollHeight > el.clientHeight + 60 && el.clientWidth > 220) {
        var s = el.scrollHeight * el.clientWidth;
        if (s > score) { score = s; best = el; }
      }
    }
    return best || document.body;
  }
  var root = mainRoot();
  try { obs.observe(root, {childList:true, subtree:true}); } catch (e) {}

  // 2) 轮询文本增长（流式输出兜底）：基线之后变长才算新内容
  var lens = {};
  setInterval(function(){
    var nodes = root.querySelectorAll('*');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (noise(el) || el.children.length > 0) { continue; }
      var t = txt(el);
      if (t.length < 24) { continue; }
      if (!el.__rk) { el.__rk = ++seq; }
      var prev = lens[el.__rk] || 0;
      if (prev > 0 && t.length - prev >= 24) { send(el); }
      lens[el.__rk] = t.length;
    }
  }, 2500);
}
)(null)
