// ===== 01_core.js (1-66) =====
function(css, navOn, replyOn, floatInput, uiAnim){
  var BODY = function(css, navOn, replyOn, floatInput, uiAnim){
    var root = window.__zcodeRoot || document;
    var doc = root.ownerDocument || document;
    var isShadow = !!root.host;
    var evRoot = isShadow ? root : doc;
    if (root.__zcodeDone) { return; }
    root.__zcodeDone = true;
    // 浮动输入开关即"增强 UI"总开关：关闭时不创建 logo、不劫持输入框（输入框常驻底部 = 纯网页），
    // 后台能力（主题/取色/溢出巡检/回复监听/诊断三滑）始终保留。
    var UI_ON = (floatInput !== false);
    var ANIM_ON = (uiAnim !== false);
    // 系统"减弱动态效果"门控：开启时注入 UI 全部无动画
    var REDUCED = false;
    try { REDUCED = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) {}

    var NAV_SEL = 'nav[data-testid="v4-turn-navigator"], [aria-label="对话问题导航"]';

    // ---------- 保持后台连接：对页面谎报"始终可见" ----------
    // 聊天页通常会在隐藏（visibilitychange）时主动断开 WebSocket，
    // 导致后台收不到消息、回复通知无从谈起。注入后页面以为一直可见，连接不断。
    // 只跟随"新回复通知"开关：通知关闭时恢复正常可见性（省电；后台可能断连，属预期）。
    // 注意：只拦 visibilitychange，绝不拦 blur——z.ai 的授权/确认弹窗点选后靠
    // document blur 处理收起，拦掉会导致"点选已生效但弹窗不消失"（v43 修）。
    var lieOn = false;
    function stopVis(e){ e.stopImmediatePropagation(); }
    function installVisLie(){
      if (lieOn) { return; }
      lieOn = true;
      try {
        Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
        Object.defineProperty(document, 'hasFocus', { value: function(){ return true; }, configurable: true });
        document.addEventListener('visibilitychange', stopVis, true);
        document.addEventListener('webkitvisibilitychange', stopVis, true);
      } catch (e) {}
    }
    function uninstallVisLie(){
      if (!lieOn) { return; }
      lieOn = false;
      try {
        // 删除实例上的自有属性，恢复原型上的原生 getter（谎报前页面若自定义过则一并还原）
        delete document.hidden;
        delete document.visibilityState;
        delete document.hasFocus;
        document.removeEventListener('visibilitychange', stopVis, true);
        document.removeEventListener('webkitvisibilitychange', stopVis, true);
      } catch (e) {}
    }
    if (replyOn) { installVisLie(); }

    // 全局错误兜底：注入后页面任何 JS 错误都进诊断链路（__zcodeErrors），不再完全静默
    try {
      window.__zcodeErrors = window.__zcodeErrors || [];
      window.addEventListener('error', function(ev){
        var arr = window.__zcodeErrors;
        if (arr.length < 20) { arr.push({ t: Date.now(), msg: String(ev && ev.message || 'err').slice(0, 200) }); }
      });
      window.addEventListener('unhandledrejection', function(ev){
        var arr = window.__zcodeErrors;
        if (arr.length < 20) { arr.push({ t: Date.now(), msg: 'unhandledrejection: ' + String(ev && ev.reason || '').slice(0, 160) }); }
      });
    } catch (e) {}
    var navEl = null, navVisible = false, showStyle = null, panel = null, mask = null, preview = null, drag = null;
    var logoEl = null;   // 浮动 logo（手势入口：单击输入框 / 双击设置 / 长按拖动 / 下拉导航 / 上滑跳底）

