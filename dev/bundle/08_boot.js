// ===== 08_boot.js (1708-1785) =====
  // ---------- 顶层执行 ----------
  // BODY 包 try/catch：注入失败不静默（错误进 __zcodeErrors，诊断卡可查）
  try {
    BODY(css, navOn, replyOn, floatInput, uiAnim);
  } catch (e) {
    try {
      var _errs = window.__zcodeErrors || (window.__zcodeErrors = []);
      if (_errs.length < 20) { _errs.push({ t: Date.now(), src: 'boot', msg: String(e && e.message || e).slice(0, 200) }); }
    } catch (e2) {}
  }

  // ---------- 广播到同源 iframe / shadow DOM ----------
  // 事件驱动：MutationObserver 捕获新增 iframe、attachShadow 补丁记录新 shadow root，出现即广播；
  // 30s 低频全扫兜底迟加载。不再每 5s 全文档扫描（querySelectorAll('*') 是大文档的遍历大头）。
  var knownFrames = {}, knownShadows = {};
  function broadcastFrame(f){
    try {
      if (knownFrames[f]) { return; }
      knownFrames[f] = true;
      if (f.contentWindow && f.contentWindow.eval) {
        if (!(f.contentDocument && f.contentDocument.__zcodeDone)) {
          f.contentWindow.eval('(' + BODY.toString() + ')(' + JSON.stringify(css) + ',' + navOn + ',' + replyOn + ',' + floatInput + ',' + uiAnim + ')');
        }
        // 递归一层：帧内再嵌套的 iframe/shadow 也覆盖到
        var fd = f.contentDocument;
        if (fd) {
          var fs2 = fd.querySelectorAll('iframe, frame');
          for (var i = 0; i < fs2.length; i++) { broadcastFrame(fs2[i]); }
          var all2 = fd.querySelectorAll('*');
          for (var j = 0; j < all2.length; j++) { if (all2[j].shadowRoot) { queueShadow(all2[j].shadowRoot); } }
        }
      }
    } catch (e) {}
  }
  function queueShadow(sr){
    setTimeout(function(){
      if (knownShadows[sr]) { return; }
      knownShadows[sr] = true;
      var prev = window.__zcodeRoot;
      window.__zcodeRoot = sr;
      try { BODY(css, navOn, replyOn, floatInput, uiAnim); } catch (e) {}
      window.__zcodeRoot = prev;
    }, 0);
  }
  try {
    new MutationObserver(function(muts){
      for (var i = 0; i < muts.length; i++) {
        var ns = muts[i].addedNodes;
        for (var j = 0; j < ns.length; j++) {
          var el = ns[j];
          if (el.nodeType !== 1) { continue; }
          if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') { broadcastFrame(el); }
          else if (el.querySelectorAll) {
            var fs3 = el.querySelectorAll('iframe, frame');
            for (var k = 0; k < fs3.length; k++) { broadcastFrame(fs3[k]); }
          }
        }
      }
    }).observe(document, {childList: true, subtree: true});
  } catch (e) {}
  try {
    var _origAttach = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function(init){
      var sr = _origAttach.call(this, init);
      queueShadow(sr);
      return sr;
    };
  } catch (e) {}
  // 30s 兜底：attachShadow 补丁/observer 失效场景仍有覆盖
  setInterval(function(){
    try {
      var fs4 = document.querySelectorAll('iframe, frame');
      for (var i = 0; i < fs4.length; i++) { broadcastFrame(fs4[i]); }
      var all4 = document.querySelectorAll('*');
      for (var j = 0; j < all4.length; j++) { if (all4[j].shadowRoot) { queueShadow(all4[j].shadowRoot); } }
    } catch (e) {}
  }, 30000);
}
