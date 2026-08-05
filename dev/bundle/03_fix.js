// ===== 03_fix.js (229-304) =====
    // ---------- 修正样式 ----------
    function ensureStyle(){
      if (root.querySelector && root.querySelector('#zcode-mobile-fix')) { return; }
      var st = doc.createElement('style');
      st.id = 'zcode-mobile-fix';
      st.textContent = css;
      styleRoot().appendChild(st);
    }

    // ---------- 溢出巡检（root 化） ----------
    // 性能：不做 3s 全树常驻扫描（长对话下每轮 querySelectorAll('*') + 逐元素
    // getBoundingClientRect/getComputedStyle 是最大的布局抖动源）。改为：
    // 1) MutationObserver 400ms 合并触发；2) 注入后 1/3/6s settle 补扫（流式渲染早期结构不全）；
    // 3) 30s 低频兜底。已修正元素打 data-zc-fixed 标记，后续扫描跳过（避免重复 getComputedStyle）；
    // 流式新增的节点没有标记，会被正常扫描。
    function fix(){
      ensureStyle();
      var de = doc.documentElement;
      if (de && de.scrollLeft) { de.scrollLeft = 0; }
      if (root !== doc && root.scrollLeft) { root.scrollLeft = 0; }
      if (doc.body && doc.body.scrollLeft) { doc.body.scrollLeft = 0; }
      var vw = (de && de.clientWidth) || 0;
      var els = root.querySelectorAll('*');
      var count = 0;
      for (var i = 0; i < els.length; i++) {
        if (++count > 3000) { break; }   // 单轮上限：超大文档分多次扫，避免一次卡死主线程
        var el = els[i];
        if (el.id && el.id.indexOf('zcode-') === 0) { continue; }   // 注入节点不动
        if (el.dataset && el.dataset.zcFixed) { continue; }          // 已修过
        // fixed 元素（弹窗/portal/注入浮层）不修：居中弹窗动画中 rect 会瞬时超界，
        // 误加 maxWidth/margin 会破坏其布局与关闭动画（v43 修）
        if (getComputedStyle(el).position === 'fixed') { continue; }
        var r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) { continue; }
        var fixed = false;
        if (el.scrollWidth > el.clientWidth + 1) {
          var oy = getComputedStyle(el).overflowY;
          if (oy === 'auto' || oy === 'scroll') {
            el.style.overflowX = 'hidden';
            el.style.touchAction = 'pan-y';
            if (el.scrollLeft) { el.scrollLeft = 0; }
            fixed = true;
          } else if (el.tagName === 'PRE' || el.tagName === 'TABLE') {
            el.style.overflowX = 'auto';
            fixed = true;
          }
        }
        if ((r.left < -1 || r.right > vw + 1) && !el.closest('pre,table')) {
          el.style.marginLeft = '0px';
          el.style.marginRight = '0px';
          el.style.maxWidth = '100%';
          fixed = true;
        }
        if (fixed && el.dataset) { el.dataset.zcFixed = '1'; }
      }
    }
    var pending = false;   // fix 去抖（勿与回复监听的 pendingReply 混用：历史坑，同名变量互相踩）
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
    // settle 补扫：1/3/6s 各扫一次（标记机制让重复扫很便宜）；30s 低频兜底迟加载
    [1, 3, 6].forEach(function(sec){ setTimeout(fix, sec * 1000); });
    setInterval(fix, 30000);
    evRoot.addEventListener('scroll', function(){
      var de = doc.documentElement;
      if (de && de.scrollLeft) { de.scrollLeft = 0; }
      if (doc.body && doc.body.scrollLeft) { doc.body.scrollLeft = 0; }
    }, true);

