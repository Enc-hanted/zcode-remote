package com.zcode.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全屏 WebView 容器：加载 ZCode 远控页面。
 * 安全策略：z.ai 域内链接在 WebView 内打开，其余一律跳系统浏览器；
 * SSL 错误一律拒绝（远控链接 = 凭证，绝不放行不安全的连接）。
 */
class WebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val TAG = "ZCodeRemote"
        private val BG = Color.parseColor("#0A0A0A")
        private const val REPLY_CHANNEL = "reply_notify"
        private const val REPLY_BRIDGE = "zcodeBridge"
        private const val TRACE_PREFS = "zcode_remote_trace"
        private const val KEY_TRACE = "trace"

        /**
         * 远控页在窄屏下的修正样式：flex 子元素允许收缩、长代码/路径断行、
         * 竖向滚动容器禁止横向平移，消除"整页可左右滑动"的问题。
         * 只追加样式，不改页面逻辑。
         */
        private const val MOBILE_CSS = """
html, body { max-width: 100vw !important; overflow-x: hidden !important; overscroll-behavior-x: none !important; }
div, section, article, aside, main, header, footer { min-width: 0 !important; }
[class*="overflow-y-auto"], [class*="overflow-y-scroll"], [class*="overflow-auto"] { overflow-x: hidden !important; touch-action: pan-y !important; }
pre, table { touch-action: pan-x pan-y !important; }
img, video, canvas { max-width: 100% !important; height: auto !important; }
pre { max-width: 100% !important; overflow-x: auto !important; }
code, p, li, td, th, a { overflow-wrap: anywhere !important; }
table { display: block !important; max-width: 100% !important; overflow-x: auto !important; }
[class*="markdown"] pre, [class*="markdown"] code { white-space: pre-wrap !important; word-break: break-word !important; }
"""

        /** 滚动条完全隐藏（手机上点不到，直接不要，内容占满全宽） */
        private const val CSS_SCROLLBAR_HIDDEN = """
* { scrollbar-width: none !important; scrollbar-color: transparent transparent !important; }
::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none !important; }
::-webkit-scrollbar-track, ::-webkit-scrollbar-thumb, ::-webkit-scrollbar-button, ::-webkit-scrollbar-corner { display: none !important; width: 0 !important; height: 0 !important; }
"""

        /** 3px 半透明细滚动条（备用方案，不挤占内容右侧） */
        private const val CSS_SCROLLBAR_SLIM = """
* { scrollbar-width: thin !important; scrollbar-color: rgba(170,180,200,0.30) transparent !important; scrollbar-gutter: auto !important; }
::-webkit-scrollbar { width: 3px !important; height: 3px !important; }
::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent !important; }
::-webkit-scrollbar-thumb { background: rgba(170,180,200,0.30) !important; border-radius: 2px !important; border: none !important; }
::-webkit-scrollbar-button { display: none !important; }
"""

        /**
         * 广播式注入包（在顶层文档执行，并递归广播到同源 iframe 与 shadow DOM）：
         * 1) 溢出修正巡检器；2) 主题回报（页面背景色匹配 ZCode 主题 22/22/22 或 248/248/248）；
         * 3) 左缘三滑唤出对话问题导航（展开页面真导航，拖动悬停预览，点条目跳转）；
         *    真导航不存在时退化为自建面板；4) 新回复监听上报原生层。
         * 各文档用 root.__zcodeDone 防重入，同窗口多实例用 __zcodeNavLastToggle 去抖。
         * 注意：JS 超长时拆多段字符串拼接（JVM 常量池单字符串限 65535 字节），段数见下方。
         */
        private val BUNDLE_JS =
"""function(css, navOn, replyOn, floatInput, uiAnim){
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

    // ---------- 底部安全区（v70，边到边布局） ----------
    // 原生层改边到边后，系统手势条/三键导航覆盖在 WebView 底部之上；WebActivity 把导航栏
    // inset 换算成 CSS px 写进 window.__zcodeSafeB（页面每次加载后重新写入）。
    // 图标/悬浮输入/全屏输入按这个值抬升，不压手势条；桌面/旧壳未注入时恒 0，无感兼容。
    function safeB(){
      var v = window.__zcodeSafeB;
      return (typeof v === 'number' && v > 0 && v < 200) ? v : 0;
    }

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

    // ---------- 动效与输入框劫持样式（毛玻璃、去蓝、渐入动画） ----------
    var fxStyle = null;
    // anim() 生成 animation 值：开 = 指定动画；关 = none（动效开关 + 系统减弱动效双门控）
    function anim(name, dur, ease){
      return (ANIM_ON && !REDUCED) ? ('animation:' + name + ' ' + dur + (ease ? ' ' + ease : '') + ';') : '';
    }
    function ensureFxStyle(){
      if (fxStyle && fxStyle.parentNode) { return; }
      fxStyle = doc.createElement('style');
      fxStyle.id = 'zcode-fx';
      fxStyle.textContent =
        // 设计 token：注入 UI 共用一套颜色/圆角（黑灰系，无蓝调；对齐原生 colors-night.xml）
        // z-index 阶梯：999 真导航解锁 / 99995 遮罩 / 99996 原生导航解锁态 / 99997 面板 / 99998 悬浮输入+预览 / 99999 全屏输入+提示+诊断
        ':root, :host{--zc-bg:rgba(30,30,30,0.85);--zc-bg-strong:rgba(26,26,26,0.94);' +
        '--zc-bg-deep:rgba(22,22,22,0.92);--zc-hint-bg:rgba(20,20,20,0.94);' +
        '--zc-stroke:rgba(255,255,255,0.14);--zc-stroke-faint:rgba(255,255,255,0.08);--zc-stroke-strong:rgba(255,255,255,0.20);' +
        '--zc-text:#F2F2F2;--zc-text-2:#FAFAFA;--zc-text-dim:#CFCFCF;--zc-text-3:#909090;' +
        '--zc-hover:rgba(255,255,255,0.08);--zc-hover-strong:rgba(255,255,255,0.12);--zc-outline:rgba(255,255,255,0.85);' +
        '--zc-radius:12px;--zc-radius-lg:16px;--zc-radius-xl:22px;}' +
        // 浅色主题：页面检测为浅色时 JS 给 <html> 挂 zcode-ui-light，整套 token 换白底黑字（04_theme.js reportTheme 驱动）
        'html.zcode-ui-light{' +
        '--zc-bg:rgba(252,253,255,0.92);--zc-bg-strong:rgba(255,255,255,0.97);' +
        '--zc-bg-deep:rgba(255,255,255,0.96);--zc-hint-bg:rgba(255,255,255,0.97);' +
        '--zc-stroke:rgba(10,18,34,0.12);--zc-stroke-faint:rgba(10,18,34,0.06);--zc-stroke-strong:rgba(10,18,34,0.18);' +
        '--zc-text:#1B2230;--zc-text-2:#0F1522;--zc-text-dim:#3A4356;--zc-text-3:#8A93A6;' +
        '--zc-hover:rgba(10,18,34,0.06);--zc-hover-strong:rgba(10,18,34,0.10);--zc-outline:rgba(20,35,60,0.80);' +
        'color-scheme:light;}' +
        '@keyframes zcodeFadeUp{from{opacity:0;transform:translateX(-50%) translateY(16px)}to{opacity:1;transform:translateX(-50%)}}' +
        '@keyframes zcodeFadeUpNoX{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}' +
        '@keyframes zcodeFadeIn{from{opacity:0}to{opacity:1}}' +
        '@keyframes zcodeFadeOut{from{opacity:1}to{opacity:0}}' +
        '@keyframes zcodeFadeDown{from{opacity:1;transform:translateX(-50%) translateY(0)}to{opacity:0;transform:translateX(-50%) translateY(14px)}}' +
        '@keyframes zcodeFadeDownNoX{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(14px)}}' +
        '@keyframes zcodeFadePanel{from{opacity:0;transform:translateX(-50%) translateY(10px)}to{opacity:1;transform:translateX(-50%)}}' +
        '@keyframes zcodeFadePanelOut{from{opacity:1;transform:translateX(-50%) translateY(0)}to{opacity:0;transform:translateX(-50%) translateY(10px)}}' +
        '@keyframes zcodeFadeHint{from{opacity:0;transform:translateX(-50%) translateY(8px) scale(0.96)}to{opacity:1;transform:translateX(-50%)}}' +
        // 浮动输入（v65 图标化）：有消息未唤出时不再把整个 dock display:none——
        // 新版(3.14.4)页面给 composer 让位的 padding 挂在 timeline 内容列等节点上，
        // dock 一消失让位空间就失去锚点，底部留大块空白（真机实测+线上 bundle 取证）。
        // 改为：只在"未唤出且无弹窗"态收起 v4-composer 子树，由注入的 #zcode-composer-icon
        // （44px 圆钮，左下角）撑起 dock——dock 保持流内 sticky，页面/虚拟列表的预留逻辑照常工作。
        // 悬浮态/全屏态不变：dock 只负责"定位 + 抬起"，页面原生 .rounded-2xl 输入胶囊
        // （自带 bg+border+圆角）作为唯一视觉框架。z.ai 的 ask/确认弹窗渲染在 dock 内 →
        // 弹窗出现时临时显示 dock（zcode-popup-mode），只露弹窗、藏输入与图标，消失后回收纳态。
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode) [data-testid="v4-composer"]{display:none!important}' +
        // 收纳态零占位（v66）：dock 压到 0 高、content 边距清零——消息列表直接铺到屏幕底部，
        // 不再留 60px 空带；图标改悬浮（见下），页面自己的让位计算随 dock 高度归零。
        // 注意 :not(.zcode-popup-mode)：弹窗态 dock 要正常尺寸
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode){height:0!important;min-height:0!important;' +
        'padding:0!important;overflow:visible!important}' +
        'html.zcode-float-on [data-v4-composer-dock="true"]:not(.zcode-composer-float)' +
        ':not(.zcode-composer-full):not(.zcode-popup-mode) [data-v4-composer-dock-content]{padding:0!important}' +
        // 收纳图标：悬浮在左下角内容之上（fixed，不属于任何流），黑灰毛玻璃小圆钮。
        // 右滑开导航（配合原生手势豁免）；与输入框的切换走容器变换（morphReveal/morphMinimize）
        // v70：bottom 抬升底部安全区（边到边后不压系统手势条）；
        // touch-action:none——实机右滑/上滑完全没反应的元凶：默认 auto 下浏览器在 ~10px
        // slop 后就把手势流抢去滚动/ overscroll（touchcancel），跟手反馈与 48px 阈值全被掐死；
        // 44px 圆钮自身没有任何可滚动内容，关掉浏览器手势处理零代价
        '#zcode-composer-icon{position:fixed;left:12px;bottom:calc(12px + var(--zc-safe-b, 0px));z-index:99996;margin:0;' +
        'display:flex;align-items:center;justify-content:center;' +
        'width:44px;height:44px;border-radius:22px;background:var(--zc-bg-strong);' +
        '-webkit-backdrop-filter:blur(18px) saturate(1.4);backdrop-filter:blur(18px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke-strong);color:var(--zc-text-2);font-size:19px;line-height:1;' +
        'box-shadow:0 4px 18px rgba(0,0,0,0.35);cursor:pointer;user-select:none;-webkit-user-select:none;' +
        'touch-action:none;' +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.15s,transform 0.12s;' : '') +
        '}' +
        '#zcode-composer-icon:active{opacity:0.7;' + (ANIM_ON && !REDUCED ? 'transform:scale(0.92);' : '') + '}' +
        // 唤出态/全屏态/弹窗态藏图标（JS 侧同步 display，这里是样式兜底）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode #zcode-composer-icon,' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-present #zcode-composer-icon{display:none!important}' +
        // 清除底部残留占位（v61）：dock 隐藏后，消息列表 section 的 pb-5 仍留 20px 底部留白。
        // 实测 dock 自身用 display:none 已不占位，但 section 的 padding-bottom 与输入框常驻时
        // 配合是合理的（避免最后一条消息贴着输入框），dock 隐藏后这段就成了多余占位，清除。
        'html.zcode-float-on [data-testid="v4-timeline"] section{padding-bottom:0!important}' +
        // 底部渐隐遮罩摘除（v74，实机 CDP 取证）：z.ai 给时间线内容列挂了跟随滚动的 CSS mask——
        // mask-image: linear-gradient(black 0, black 566px, transparent 590px, …100%);
        // mask-position: 0px <scrollTop>px; mask-size: 100% <视口高>px
        // 可见区底部 ~120px 恒定渐隐成空白；惯性滚动后 mask-position 与 scrollTop 脱同步时
        // 透明带更大（实机照片实测 190px）——这就是"上滚后底部空白占位"的真身。
        // DOM 探针全被它骗过：hit-test/innerText 都无视 mask，probe 报"有内容"但屏幕是白的。
        // 收纳态（float-on）dock 已隐藏，这层渐隐只剩空白，直接摘掉让消息画到底边；
        // 原生 dock 态（float off）保留 z.ai 原设计（渐隐用于和输入框过渡）。
        // [style*="mask-position"] 属性选择器：z.ai 卸掉 mask 时属性串消失即不匹配，自门控。
        'html.zcode-float-on [data-testid="v4-timeline"] [style*="mask-position"],' +
        'html.zcode-float-on [data-testid="v4-timeline-scroll"] [style*="mask-position"]' +
        '{-webkit-mask-image:none!important;mask-image:none!important}' +
        // v80⑦：z.ai 原生"滚动到底部"圆钮避让系统手势条（实机诊断 dockHtml/popupSnap 取证：
        // v4-timeline-bottom 挂在 dock 内 data-v4-back-to-bottom-anchor 锚上，absolute/
        // bottom-full/mb-2 悬在 0 高 dock 上方，收纳态离屏底仅 ~8px 压小白条）。transform
        // 整体重写并复刻它自带的 -translate-x-1/2 居中：抬高 安全区+6px（原 8px 底距 +
        // 抬升量 ≥ safeB+10 的避让口径）。safeB=0（桌面/旧壳）时只抬 6px，无感
        'html.zcode-float-on [data-testid="v4-timeline-bottom"]' +
        '{transform:translate(-50%, calc(-6px - var(--zc-safe-b, 0px)))!important}' +
        // v78 让位垫过渡：胶囊唤出/收纳时 tl 的 padding-bottom 变化不再是瞬时跳变，
        // 而是短滑过渡——垫的写入时机已挪到 morph 落位之后（06_composer），配合这条
        // 过渡整个"消息列表为胶囊让位"的动作是连续的。仅收纳态生效，不干扰 z.ai
        // 原生模式的任何 padding 行为；过渡期间 scrollHeight 逐帧变化由 v75 静止闸门兜住。
        'html.zcode-float-on [data-testid="v4-timeline"],' +
        'html.zcode-float-on [data-testid="v4-timeline-scroll"]' +
        '{transition:padding-bottom 0.22s cubic-bezier(0.2,0.8,0.2,1)}' +
        // v78 上拉手柄：胶囊上内缘（右上角）低可视小横条——提示"可上拉展开半屏输入"。
        // 挂 dock（fixed=定位锚）的 ::after 伪元素：样式表注入，React 重渲染抹不掉；
        // 不碰 z.ai 的胶囊本体（不改它的 position，零回归面）。点按无感（pointer-events
        // none），拖动展开的手势本来就由胶囊整体的纵向 dockGesture 承担。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float::after{content:"";' +
        'position:absolute;top:3px;right:10px;width:26px;height:3px;border-radius:2px;' +
        'background:var(--zc-text-3);opacity:0.18;pointer-events:none}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float:active::after{opacity:0.4}' +
        // v78 morph 幽灵替身基样式（动画值由 JS 内联写入）：真实胶囊飞行期间隐藏，
        // 替身用尺寸动画飞行——scale 非均匀缩放会把圆角与描边压成椭圆（"中途丑陋
        // 椭圆帧"的来源），尺寸动画几何全程正确
        '.zcode-morph-ghost{position:fixed;z-index:99999;pointer-events:none;box-sizing:border-box}' +
        // v79⑤ 会话切换骨架屏：路由切换 → 整列表卸载重建（实机 ~1s 主线程冻结），冻结期
        // 铺一层与页面底色一致的微光条，首条 v4-row 渲染即淡出（05_nav route watch 驱动）。
        // 淡入 140ms：快速切换时骨架几乎不可见；pointer-events:none 不挡任何点击
        '#zcode-skeleton{position:fixed;inset:0;z-index:99990;pointer-events:none;opacity:0;transition:opacity 0.14s ease-out}' +
        '#zcode-skeleton.zc-sk-in{opacity:1}' +
        '#zcode-skeleton.zc-sk-out{opacity:0;transition:opacity 0.18s ease-in}' +
        '.zc-sk-bar{position:absolute;height:13px;border-radius:7px;' +
        'background:linear-gradient(90deg,rgba(255,255,255,0.04),rgba(255,255,255,0.10),rgba(255,255,255,0.04));' +
        'background-size:200% 100%;animation:zcodeSkShimmer 1.3s linear infinite}' +
        'html.zcode-ui-light .zc-sk-bar{background:linear-gradient(90deg,rgba(10,18,34,0.05),rgba(10,18,34,0.12),rgba(10,18,34,0.05));background-size:200% 100%}' +
        '@keyframes zcodeSkShimmer{from{background-position:200% 0}to{background-position:-200% 0}}' +
        // v79⑥ 流式阅读位置线：流式中上滑离开底部，在离开时刻的内容底边插一条低可视
        // 细线（"新内容都在线下方"），滚回底部/流式结束淡出。挂在内容流里（行后插节点），
        // 内容只在尾部追加，锚点稳定；行被虚拟化卸载则顺手收线
        '.zc-readline{position:relative;height:2px;margin:10px 2px;border-radius:1px;' +
        'background:linear-gradient(90deg,transparent,var(--zc-stroke-strong) 12%,var(--zc-stroke-strong) 88%,transparent);' +
        'opacity:0.7;pointer-events:none;transition:opacity 0.4s ease}' +
        '.zc-readline.zc-rl-out{opacity:0}' +
        '.zc-readline i{position:absolute;right:2px;top:-15px;font-style:normal;font-size:10px;' +
        'color:var(--zc-text-3);opacity:0.75;letter-spacing:1px}' +
        // v79⑨ AMOLED 纯黑（实验开关）：注入层 token 换纯黑系；页面侧 best-effort
        //（body/timeline 底面），z.ai 气泡/代码块等具体表面色需实机调色（HANDOFF 待办）
        'html.zc-amoled{--zc-bg:rgba(0,0,0,0.86);--zc-bg-strong:rgba(0,0,0,0.92);--zc-bg-deep:rgba(0,0,0,0.96);' +
        '--zc-hint-bg:rgba(0,0,0,0.95);--zc-stroke:rgba(255,255,255,0.10);--zc-stroke-faint:rgba(255,255,255,0.05);' +
        '--zc-stroke-strong:rgba(255,255,255,0.16)}' +
        'html.zc-amoled body,html.zc-amoled [data-testid="v4-timeline"],html.zc-amoled [data-testid="v4-timeline-scroll"]' +
        '{background-color:#000!important}' +
        // v79③ 键盘跟随·实验：WebView 不随 IME 缩放（原生 exp 模式不垫 ime padding，页面
        // 零重排、虚拟列表不再逐帧重算），键盘起/落各报一次最终高度（__zcKb），JS 换算
        // --zc-kb-lift，胶囊/全屏用 CSS 过渡贴着键盘顶沿升降。掉帧根修的实验路径。
        // 特定性注意：选择器必须比上面的 float/full 基础规则多一级（html.zcode-float-on
        // 前缀），否则同为 (0,3,2) 时基础规则的 transform 会盖掉这里的 translateY
        'html.zcode-float-on.zc-kb-on [data-v4-composer-dock="true"].zcode-composer-float{' +
        'transform:translateX(-50%) translateY(calc(0px - var(--zc-kb-lift, 0px)))!important;' +
        'transition:transform 0.26s cubic-bezier(0.2,0.8,0.2,1)!important}' +
        'html.zcode-float-on.zc-kb-on [data-v4-composer-dock="true"].zcode-composer-full{' +
        'transform:translateY(calc(0px - var(--zc-kb-lift, 0px)))!important;' +
        'transition:transform 0.26s cubic-bezier(0.2,0.8,0.2,1)!important}' +
        // 弹窗模式：dock 临时显示为底部容器（不遮挡全屏），输入部分隐藏，弹窗可见可点
        // 容器变换进行中：压掉 float/full 态的入场 animation，几何交给 JS 的 transform 过渡
        '[data-v4-composer-dock="true"].zcode-composer-morph{animation:none!important}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'display:block!important;visibility:visible!important;pointer-events:auto!important;' +
        'position:fixed!important;left:50%!important;right:auto!important;bottom:var(--zc-safe-b, 0px)!important;top:auto!important;' +
        'transform:translateX(-50%)!important;width:100%!important;max-width:760px!important;height:auto!important;' +
        'padding:0!important;margin:0!important;background:transparent!important;border:none!important;' +
        'box-shadow:none!important;z-index:99998!important;' +
        'overflow:visible!important;' +
        (ANIM_ON && !REDUCED ? 'animation:zcodeFadeIn 0.18s ease-out;' : '') + '}' +
        // 弹窗模式：只露弹窗——只藏 v4-composer 整棵子树（胶囊/输入框/expand 全在它里面）。
        // 注意：绝不能再用 .rounded-2xl 选择器藏胶囊——ask 弹窗卡片自身也带 rounded-2xl 类
        // （诊断 dockHtml 实锤），会被一起藏掉（v58 修：v57 就是这个坑导致弹窗 display:none）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode [data-testid="v4-composer"]' +
        '{display:none!important}' +
        // 弹窗复活说明（v60）：不再给弹窗节点加 !important 可见性——v46 起 dock 用 display:none 隐藏、
        // popup-mode 用 display:block 显示整棵子树，弹窗节点在打开的 dock 里自然可见。
        // 曾经的 visibility:visible!important 会压制页面自身的关闭样式（弹窗点选后关不掉，v60 修）。
        // .zcode-popup-visible 只作检测标记保留，不干预样式。
        // 注意：悬浮/全屏规则必须带 html.zcode-float-on 前缀提升特异性，否则会被上面的隐藏规则压住
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float{' +
        'display:flex!important;visibility:visible!important;pointer-events:auto!important;position:fixed!important;left:50%!important;right:auto!important;' +
        'bottom:calc(14px + var(--zc-safe-b, 0px))!important;top:auto!important;transform:translateX(-50%)!important;' +
        'width:min(94vw,520px)!important;max-width:none!important;height:auto!important;' +
        'z-index:99998!important;pointer-events:auto!important;' +
        'padding:0!important;background:transparent!important;border:none!important;box-shadow:none!important;' +
        anim('zcodeFadeUp', '0.22s', 'cubic-bezier(0.2,0.8,0.2,1)') +
        '}' +
        // 弹窗存在时（含悬浮输入打开态）不显示 expand 按钮：弹窗与输入按钮不应同时出现
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-present #zcode-composer-expand{display:none!important}' +
        // body 级弹窗让位（v53）：z.ai 的 Radix 下拉/菜单 portal 到 body 根部，z-index 仅 z-50，
        // 远低于我们的遮罩（99995）/dock（99998+）。不让位时遮罩吞掉点击、全屏暗层盖住视觉
        // ——"对话框按钮的二级弹窗被盖住"。检测到 body 弹窗（html.zcode-body-popup）时把注入层
        // 全部压到弹窗之下：z-40 高于页面内容(z-20)、低于页面弹窗(z-50)，logo 让到 45。
        'html.zcode-body-popup #zcode-mask{z-index:40!important}' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-float,' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-full,' +
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'z-index:40!important;' +
        '}' +
        // 全屏暗层会让弹窗"看得见但黑纱罩着"：让位时去掉背景，弹窗完整可见
        'html.zcode-body-popup [data-v4-composer-dock="true"].zcode-composer-full{background:transparent!important}' +
        // 收起态：淡出下移后由 JS 移除类（避免 display:none 瞬间消失，呆板）
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-float.zcode-composer-closing{' +
        'pointer-events:none!important;' + anim('zcodeFadeDown', '0.18s', 'ease-in') + '}' +
        // 中和 v4-composer 那层不透明背景块（rgb 22,22,22），否则会和原生气泡叠成双框
        'html.zcode-float-on [data-v4-composer-dock="true"] [data-testid="v4-composer"]{' +
        'background:transparent!important;' +
        '}' +
        // 原生气泡（.rounded-2xl）加一层投影抬起，其余沿用页面自带样式。
        // overflow:visible：胶囊自带 Tailwind overflow-hidden，会把 expand 按钮岔出圆角的外弧裁掉（v52 按钮挂进胶囊后必须放行）
        'html.zcode-float-on [data-v4-composer-dock="true"] [data-testid="v4-composer"] .rounded-2xl{' +
        'box-shadow:0 12px 40px rgba(0,0,0,0.5)!important;overflow:visible!important;' +
        '}' +
        // 全屏态：dock 变全屏遮罩 + 底部居中；气泡放大、加投影
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full{' +
        'display:flex!important;visibility:visible!important;pointer-events:auto!important;position:fixed!important;left:0!important;right:0!important;bottom:0!important;top:0!important;' +
        'transform:none!important;width:100%!important;max-width:none!important;height:100%!important;' +
        'z-index:99999!important;pointer-events:auto!important;align-items:flex-end!important;justify-content:center!important;' +
        'padding:0 0 calc(20px + var(--zc-safe-b, 0px))!important;background:rgba(0,0,0,0.55)!important;border:none!important;' +
        anim('zcodeFadeIn', '0.18s', 'ease-out') +
        '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full.zcode-composer-closing{' +
        'pointer-events:none!important;' + anim('zcodeFadeOut', '0.18s', 'ease-in') + '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid="v4-composer"]{' +
        'width:min(94vw,640px)!important;' +
        '}' +
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid="v4-composer"] .rounded-2xl{' +
        'width:min(94vw,640px)!important;box-shadow:0 16px 50px rgba(0,0,0,0.6)!important;' +
        '}' +
        // 全屏关键：输入区放大（页面原生 min-h-10 max-h-40 会把输入框锁死在 40px，
        // 不加这条全屏只是"暗遮罩+底部小输入框"，v37 就是这个坑）。
        // v69（实机"全屏输入框不够大"）：32vh 只占三分之一屏，提到 56vh——输入区是全屏态的主体。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid^="v4-composer-input"]{' +
        'min-height:56vh!important;max-height:none!important;height:56vh!important;font-size:16px!important;' +
        '}' +
        // expand：纯 SVG 圆弧双态按钮（Qwen demo 几何）。SVG 根 pointer-events:none → 方块不挡文字，
        // 命中区 = 弧线两侧 14px 透明粗描边（pointer-events:stroke），只有弧环可点；
        // 外弧 R+4（悬浮态）= 扩大，内弧 R−4（全屏态）= 缩小，同圆心同跨角，opacity/visibility 互斥切换。
        // 尺寸由 positionExpand 按胶囊圆角半径动态计算（JS px，跟随界面缩放），这里只兜底。
        '#zcode-composer-expand{position:absolute;top:0.25em;right:0.25em;width:2.75em;height:2.75em;font-size:13px;' +
        'color:var(--zc-text-dim);z-index:99997;pointer-events:none;}' +
        '#zcode-composer-expand:hover{color:var(--zc-text-2);}' +
        '#zcode-composer-expand svg{width:100%;height:100%;overflow:visible;}' +
        '#zcode-composer-expand .zc-hit{fill:none;stroke:transparent;stroke-width:14;pointer-events:stroke;cursor:pointer;}' +
        '#zcode-composer-expand .zc-line{fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;opacity:0.6;' +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.25s;' : '') + '}' +
        '#zcode-composer-expand:hover .zc-line{opacity:0.9;}' +
        '#zcode-composer-expand .zc-arc{' + (ANIM_ON && !REDUCED ? 'transition:opacity 0.3s,visibility 0.3s;' : '') + '}' +
        // 互斥切换：SVG 的 pointer-events:stroke 写死在 path 上，g 的 visibility:none 都不管用
        // （继承会被 path 自身值覆盖，实测隐藏环仍可命中）→ 必须直接置空隐藏环的命中环；
        // :not(.zc-full) 限定：全屏态下内弧是可见的，命中环必须保留（否则缩回收不回）
        '#zcode-composer-expand .zc-shrink{opacity:0;visibility:hidden;}' +
        '#zcode-composer-expand:not(.zc-full) .zc-shrink .zc-hit{pointer-events:none;}' +
        '#zcode-composer-expand.zc-full .zc-expand{opacity:0;visibility:hidden;}' +
        '#zcode-composer-expand.zc-full .zc-expand .zc-hit{pointer-events:none;}' +
        '#zcode-composer-expand.zc-full .zc-shrink{opacity:1;visibility:visible;}' +
        // 自建导航面板：出现淡入（panel 内联已带 FadePanel），收起淡出下移
        '[aria-label="对话问题导航"].zcode-closing{' + anim('zcodeFadePanelOut', '0.18s', 'ease-in') + '}';
      styleRoot().appendChild(fxStyle);
    }
    ensureFxStyle();
    var lastThemeKey = '', lastThemeR = -1, lastThemeG = -1, lastThemeB = -1;
    var replyTrace = [];   // 回复链路埋点（检测/通知每次尝试都记，诊断时随 JSON 上报）

    function hint(text){
      var old = doc.getElementById('zcode-nav-hint');
      if (old && old.parentNode) { old.parentNode.removeChild(old); }
      var h = doc.createElement('div');
      h.id = 'zcode-nav-hint';
      h.textContent = text;
      // 悬浮输入开着时上移，避免和输入胶囊重叠；v70：再叠加底部安全区（边到边后不压手势条）
      var up = ((doc.documentElement.classList.contains('zcode-float-on') && getDock()) ? 128 : 88) + safeB();
      h.style.cssText = 'position:fixed;left:50%;bottom:' + up + 'px;transform:translateX(-50%);' +
        'background:var(--zc-hint-bg);color:var(--zc-text);font-size:13px;line-height:1.4;padding:11px 18px;' +
        'border-radius:999px;z-index:99999;border:1px solid var(--zc-stroke);box-shadow:0 8px 26px rgba(0,0,0,0.4);' +
        'pointer-events:none;max-width:min(84vw,420px);text-align:center;overflow-wrap:break-word;' +
        'animation:zcodeFadeHint 0.18s ease-out;';
      (isShadow ? root : doc.body || doc.documentElement).appendChild(h);
      // 淡出：1600ms 后播 180ms fadeOut，animationend 移除（动画事件兜底 400ms）
      setTimeout(function(){
        if (!h.parentNode) { return; }
        if (ANIM_ON && !REDUCED) {
          h.style.animation = 'zcodeFadeOut 0.18s ease-in';
          var done = function(){ if (h.parentNode) { h.parentNode.removeChild(h); } };
          h.addEventListener('animationend', done, {once: true});
          setTimeout(done, 400);
        } else {
          h.parentNode.removeChild(h);
        }
      }, 1600);
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
        bundleVer: 57,
        // v70：实机"动画不生效/交互生硬"排查项——系统减弱动效（REDUCED）会关掉全部注入
        // 动画；safeB 是原生边到边上报的底部安全区（0 = 旧壳/桌面/未上报）
        animOn: ANIM_ON,
        reducedMotion: REDUCED,
        safeB: safeB(),
        // 脱敏：sid/hash 是现行链接凭证（3.14.4 实测仍用），remote=<id> 是新版 WebSocket 凭证、
        // token/ticket 是 /ws/remote-control/window/<token> 子系统的凭证形态（线上 bundle 取证），
        // mid 是设备稳定标识——粘贴诊断前全部打码
        url: (location.href || '').replace(/([?&](?:sid|hash|remote|token|ticket|mid)=)[^&]+/g, '$1***').slice(0, 140),
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
        ' icon=' + (function(){ var d = getDock(); return d ? (d.querySelector('#zcode-composer-icon') ? 1 : 0) : 0; })() +
        ' popups=' + (function(){ var d = getDock(); return d ? d.querySelectorAll('.zcode-popup-visible').length : 0; })();
      // v71：float 状态机健康度——实机"底部空白/填满一秒一变"排查项。
      // everRows=已见过消息 missTicks=连续缺行轮数 hasRows=当下是否有行 dockH=dock 高度
      // lastFloat=最近一次翻转的时刻与原因（rows/empty-session），翻转频繁即布局振荡实锤
      try {
        var dck = getDock();
        out.floatState = {
          on: doc.documentElement.classList.contains('zcode-float-on') ? 1 : 0,
          hasRows: root.querySelector('[data-testid^="v4-row"]') ? 1 : 0,
          everRows: rowsEverSeen ? 1 : 0,
          missTicks: missingRowsTicks,
          dockH: dck ? dck.offsetHeight : -1,
          lastFloatAt: lastFloatAt ? new Date(lastFloatAt).toTimeString().slice(0, 8) : '',
          lastFloatReason: lastFloatReason || ''
        };
      } catch (eFS) {}
      // v71：尾部清理健康度——at=最近一次实际修复时刻 phantom=当时空白量
      // nudgeAt=最近一次通知虚拟列表重算时刻（空洞看门狗据此让位 600ms）
      out.tailFix = {
        at: tailFixAt ? new Date(tailFixAt).toTimeString().slice(0, 8) : '',
        phantom: tailFixPhantom,
        nudgeAt: tailNudgeAt ? new Date(tailNudgeAt).toTimeString().slice(0, 8) : ''
      };
      // v72：底部空带取证——实机"收纳态底部仍有空白带"定位用。tlBottom < innerH
      // 即时间线容器没铺到屏底（空带在容器外，dock 槽位圈）；probe 逐行报底带里
      // 实际压着什么元素（tag>链路，null=什么都没有）；padPx=悬浮让位垫当前值
      try {
        var bbTl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
        var bbDk = getDock();
        var bbProbe = [];
        var bbIh = window.innerHeight;
        for (var bbDy = 8; bbDy <= 96; bbDy += 22) {
          var bbEl = null;
          try { bbEl = doc.elementFromPoint(Math.round(window.innerWidth / 2), bbIh - bbDy); } catch (eP0) {}
          var bbChain = [];
          for (var bbN = bbEl; bbN && bbN.nodeType === 1 && bbChain.length < 4; bbN = bbN.parentElement) {
            var bbTid = bbN.getAttribute ? (bbN.getAttribute('data-testid') || '') : '';
            bbChain.push(bbN.tagName + (bbTid ? '[' + bbTid + ']' : '') + (bbN.id ? '#' + bbN.id : ''));
          }
          bbProbe.push((bbIh - bbDy) + ':' + (bbChain.join('>') || 'null'));
        }
        out.bottomBand = {
          tlBottom: bbTl ? Math.round(bbTl.getBoundingClientRect().bottom) : -1,
          innerH: bbIh,
          tlScrollH: bbTl ? bbTl.scrollHeight : -1,
          tlClientH: bbTl ? bbTl.clientHeight : -1,
          dockH: bbDk ? Math.round(bbDk.offsetHeight) : -1,
          dockBottom: bbDk ? Math.round(bbDk.getBoundingClientRect().bottom) : -1,
          padPx: (typeof overlayPadPx === 'number' ? overlayPadPx : -1),
          probe: bbProbe
        };
      } catch (eBB) { out.bottomBand = 'err'; }
      // v73：空洞看门狗状态——实机"滚到上面底部仍有空白"取证。covered：-1未判定/
      // 1覆盖/0空洞/2自家浮层挡着；gapPx=视口底到最低 section 底缘的实测距离（空洞
      // 高度，-1=无 section）；tries=当前已尝试拍数（1-2 1px抖动/3-6 双派发/7+ ±8px
      // 真位移）；userScrollAgo=距用户上次亲手滚动的秒数
      // v76：gapPx 手势时刻现算——看门狗 covered 态不再每秒扫 section，读缓存会拿 -1
      out.holeState = (function(){
        var hg = -1;
        try {
          var hgTl = root.querySelector('[data-testid="v4-timeline"]') ||
                     root.querySelector('[data-testid="v4-timeline-scroll"]');
          if (hgTl) {
            var hSecs = hgTl.querySelectorAll('section');
            var hLow = -1;
            for (var hi = 0; hi < hSecs.length; hi++) {
              var hb = hSecs[hi].getBoundingClientRect().bottom;
              if (hb > hLow) { hLow = hb; }
            }
            if (hLow >= 0) { hg = Math.round(window.innerHeight - hLow); }
          }
        } catch (eH) {}
        return {
          covered: holeCovered, gapPx: hg, tries: holeTries,
          userScrollAgo: lastUserScrollAt ? Math.round((Date.now() - lastUserScrollAt) / 1000) : -1
        };
      })();
      // composerProbe：诊断当下重新查一次容器，确认选择器本身是否命中（与 dock=0 区分"从未找到"vs"找到后又丢了"）
      try {
        var liveDock = root.querySelector(COMPOSER_SEL);
        out.composerProbe = liveDock ? ('hit cls=' + (liveDock.className || '').slice(0, 60) +
          ' hasExpand=' + (liveDock.querySelector('#zcode-composer-expand') ? 1 : 0)) : 'miss';
      } catch (e) { out.composerProbe = 'err'; }
      // v79/v80：新交互特性状态（①发送归位 ③键盘实验 ⑤骨架 ⑥阅读线 ⑦↓钮CSS抬升 ⑨AMOLED）
      // kbCalls/kbAgo：__zcKb 桥接埋点——kbCalls=0 表示原生从没报过高度（桥断），
      // ≥100 表示 visualViewport 兜底生效过；kbAgo=距最近一次上报的秒数
      try {
        out.feats = {
          sendMin: sendMinimize ? 1 : 0, kbExp: kbFollowExp ? 1 : 0,
          kbH: kbH || 0, kbLift: kbLift || 0, kbCalls: kbCalls || 0,
          kbAgo: kbLastAt ? Math.round((Date.now() - kbLastAt) / 1000) : -1,
          amoled: amoledBlack ? 1 : 0,
          skShown: (typeof skShown === 'number' ? skShown : 0),
          readLine: readLineEl ? 1 : 0
        };
      } catch (eFT) { out.feats = 'err'; }
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
      // timelinePad（v64）：3.14.4 把 composer 让位 padding 挂在 timeline 新节点上
      // （v61 的 section 规则已打空），把滚动容器/消息层/末个内容列的 computed padding
      // 直接报出来——真机复制一次诊断即可钉死"底部空白"要清的数值
      try {
        function padOf(el){
          if (!el) { return null; }
          var cs = getComputedStyle(el);
          return [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].join(' ') +
            ' h=' + Math.round(el.getBoundingClientRect().height);
        }
        var cols = root.querySelectorAll('[data-v4-timeline-content-column]');
        out.timelinePad = {
          scroll: padOf(root.querySelector('[data-v4-timeline-scroll]') ||
                        root.querySelector('[data-testid="v4-timeline"]')),
          messageLayer: padOf(root.querySelector('[data-v4-timeline-message-layer]')),
          lastCol: padOf(cols[cols.length - 1]),
          colCount: cols.length
        };
      } catch (e) { out.timelinePad = 'err'; }
      // maskState（v74）：z.ai 底部渐隐遮罩取证——存在性 + mask-position 与 scrollTop 的
      // 同步差（脱同步=透明带比 120px 大，"上滚底部空白"的真凶）+ v74 摘除是否生效
      try {
        var mEl = root.querySelector('[data-testid="v4-timeline"] [style*="mask-position"]') ||
                  root.querySelector('[data-testid="v4-timeline-scroll"] [style*="mask-position"]');
        if (mEl) {
          var mCs = getComputedStyle(mEl);
          var mPos = (mCs.webkitMaskPosition || mCs.maskPosition || '');
          var mPosNum = parseFloat((mPos.match(/-?\d+(\.\d+)?px\s*$/)||['0'])[0]) || 0;
          var mTl = root.querySelector('[data-testid="v4-timeline-scroll"]') ||
                    root.querySelector('[data-testid="v4-timeline"]');
          out.maskState = {
            pos: mPos,
            st: mTl ? Math.round(mTl.scrollTop) : -1,
            syncDelta: mTl ? Math.round(mPosNum - mTl.scrollTop) : -999,
            img: (mCs.webkitMaskImage || mCs.maskImage || '').slice(0, 40),
            removed: /none/.test(mCs.webkitMaskImage || mCs.maskImage || '') ? 1 : 0
          };
        } else { out.maskState = { none: 1 }; }
      } catch (e) { out.maskState = 'err'; }
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
""" +
"""        var ta = doc.createElement('textarea');
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
        hint('输入框已' + (doc.documentElement.classList.contains('zcode-float-on') ? '收纳（点左下角图标唤出）' : '恢复常驻底部'));
      };
      card.appendChild(title); card.appendChild(pre); card.appendChild(copy);
      card.appendChild(toggleInput); card.appendChild(close);
      (isShadow ? root : doc.body || doc.documentElement).appendChild(card);
    }

    // ---------- 点外部收起：半透明全屏遮罩（导航/浮动输入共用） ----------
    function showMask(){
      if (mask && mask.parentNode) { return; }
      mask = doc.createElement('div');
      mask.id = 'zcode-mask';   // v53：body 弹窗检测让位时需要能定位到遮罩
      mask.style.cssText = 'position:fixed;inset:0;z-index:99995;background:rgba(0,0,0,0);';
      mask.addEventListener('touchstart', function(e){
        // v80②：双击回退——真机上第二击常落在 44px 图标之外的遮罩上（图标首击唤出后
        // 已隐形，全凭肌肉记忆点）。500ms 内 + 距首击落点 120px 以内的快速触摸，视为
        // 双击第二击直进全屏，不当作"点外部收起"
        if (lastIconTapAt > 0 && Date.now() - lastIconTapAt < 500 && composerOpen && !composerFull) {
          var t0 = (e.touches && e.touches[0]) || e;
          var dx = lastIconTapXY ? (t0.clientX - lastIconTapXY.x) : 999;
          var dy = lastIconTapXY ? (t0.clientY - lastIconTapXY.y) : 999;
          if (dx * dx + dy * dy < 120 * 120) {
            e.preventDefault();
            lastIconTapAt = 0;
            uiLog('icon-dbltap-mask');
            vibrate();
            whenMorphDone(function(){
              if (composerOpen && !composerFull) { toggleFullComposer(); }
            });
            return;
          }
        }
        e.preventDefault();
        closePanel();
        hideNav();
        hideComposer();
      }, {passive:false});
      // 桌面鼠标兜底：触摸设备上合成 click 之前 mousedown 不单独触发收起（touchstart 已处理），
      // 纯鼠标环境没有 touchstart，点外部收不回输入框
      mask.addEventListener('mousedown', function(){
        closePanel();
        hideNav();
        hideComposer();
      });
      (isShadow ? root : doc.body || doc.documentElement).appendChild(mask);
    }
    function hideMask(){
      if (mask && mask.parentNode) { mask.parentNode.removeChild(mask); }
      mask = null;
    }

    // ---------- 真导航展开/收起 ----------
    // v65（3.14.4 线上 bundle 取证）：原生导航在手机上不是不渲染，是「对话区 ≥864px 才可见」的
    // 容器查询把它藏了（@min-[864px]/conversation:visible）。解锁三件事：
    // ① z-index 抬到遮罩(99995)之上——否则看得见点不着，点击全被遮罩吃掉变成"收起"；
    // ② transform 归零——隐藏态带 -translate-x-2，不清零轨道半藏在屏幕外；
    // ③ 不再强制 min-width:220px（老版文字面板的遗留），原生轨道是 36px 细条，撑宽只会多出空白可点区。
    // v69（实机"导航出不来，什么都看不到"）：藏法是 Tailwind `hidden @min-[864px]/conversation:visible`
    // ——基础态是 display:none，v65 只解了 visibility/opacity，桌面测试时容器查询本来就激活
    // （视口 ≥864px 页面自己就显示），实机窄容器下 display:none 从未被恢复 → 全黑。
    // 这里补 display:flex!important（轨道本就是纵向细条）；若 260ms 后仍无布局宽度（藏法超出预期），
    // showNav 的校验自动切自建面板兜底，保证任何情况下都有可见产物。
    // 注意 transform/z-index 只对原生 nav 生效：自建面板靠 translateX(-50%) 居中，不能被清掉。
    var SHOW_CSS = NAV_SEL + ' { display: flex !important; flex-direction: column !important; visibility: visible !important; opacity: 1 !important; pointer-events: auto !important; } ' +
      'nav[data-testid="v4-turn-navigator"]{transform:none!important;z-index:99996!important;}' +
      NAV_SEL + ' * { visibility: visible !important; pointer-events: auto !important; } ' +
      // 注意不能写 NAV_SEL + ' [hidden]'：NAV_SEL 是逗号列表，展开成 "nav, panel [hidden]"
      // 后第一段变成裸 nav —— display:block!important 反把上面的 flex 顶掉（v69 实测踩中）
      'nav[data-testid="v4-turn-navigator"] [hidden] { display: block !important; } ' +
      '[aria-label="对话问题导航"] [hidden] { display: block !important; }';
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
      // 只在导航子树内找：手指在外面时 elementFromPoint 返回遮罩/页面节点，不能误报预览
      if (!el || !navEl || !navEl.contains(el)) { return null; }
      // 原生导航的条目是"无文字的小横条"，唯一可读的是 aria-label（"跳转到第 N 条问题"）；
      // 自建面板条目有正文。两类都接住：有 aria-label 或有正文都算命中。
      while (el && el !== navEl) {
        var al = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
        if ((el.textContent && el.textContent.trim()) || al) { return el; }
        el = el.parentElement;
      }
      return null;
    }
    function previewText(el){
      var al = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
      if (al) { return al; }
      return entryLabel(el, 180);
    }
    function onNavMove(e){
      var t = e.touches[0];
      var it = hoverItemAt(t.clientX, t.clientY);
      if (it) { showPreview(t.clientX, t.clientY, previewText(it)); }
      else { hidePreview(); }
    }
    function onNavEnd(){ hidePreview(); }
    // 原生轨道条目点击后自动收起：跳转完成（React 滚动 ~300ms）就还屏给阅读。
    // capture 监听不拦截默认行为，只做延时收起；自建面板条目本来就点完即收，无需此处理。
    function navAutoClose(){
      setTimeout(function(){
        if (navVisible) { hideNav(); }
      }, 350);
    }
    function showNav(){
      navEl = root.querySelector(NAV_SEL);
      if (!navEl) { return false; }
      ensureShowStyle();
      showMask();
      navVisible = true;
      uiLog('nav-open');
      navEl.addEventListener('touchmove', onNavMove, {passive:true});
      navEl.addEventListener('touchend', onNavEnd, {passive:true});
      navEl.addEventListener('click', navAutoClose, true);
      vibrate();
      // v69：原生轨道可见性校验——display 解锁后仍无布局宽度（display:none 之外的藏法/结构变化）
      // 就地切自建面板，用户永远能看到导航，而不是对着空气右滑。
      // 双采样：260ms 与 480ms 各测一次，都为零宽才判定（单次测量可能撞上渲染中间态）
      setTimeout(function(){
        if (!navVisible) { return; }
        setTimeout(function(){
          if (!navVisible) { return; }
          var el = root.querySelector(NAV_SEL);
          if (el && el !== navEl) { navEl = el; }
          if (navEl && navEl.offsetWidth > 0) { return; }
          uiLog('nav-invisible-fallback');
          hideNav();
          var turns = collectTurns();
          if (turns.length) {
            buildPanel(turns);
            hint('已唤出问题导航（点外部收起）');
          } else {
            hint('还没有可导航的消息，先去聊几句吧');
          }
        }, 220);
      }, 260);
      return true;
    }
    function hideNav(){
      removeShowStyle();
      hideMask();
      navVisible = false;
      if (navEl) {
        navEl.removeEventListener('touchmove', onNavMove);
        navEl.removeEventListener('touchend', onNavEnd);
        navEl.removeEventListener('click', navAutoClose, true);
      }
      hidePreview();
    }

    // ---------- 自建面板兜底（真导航不存在时） ----------
    // 只取气泡正文：克隆节点后把行外操作按钮（复制/编辑/反馈/分叉）从克隆里移除再读文本。
    // v70：display:none + innerText 的方案在实机上失效——innerText 对脱离文档的克隆不计算
    // 渲染样式，"复制 编辑"照样混进条目；改为直接 removeChild，文本读取与渲染无关、稳定干净。
    // 克隆读取绝不改 live DOM。
    var TURN_NOISE_SEL = '[data-testid^="v4-copy-"], [data-testid^="v4-edit-"], ' +
      '[data-testid^="v4-feedback-"], [data-testid^="v4-fork-"], ' +
      // v64：3.14.x 新行类型（待执行命令/Subagent 卡片/队列项/待审卡片/用户输入卡）
      // 不混进导航条目正文（线上 bundle testid 取证）
      '[data-testid^="v4-pending-command"], [data-testid^="v4-subagent-"], ' +
      '[data-testid^="v4-queue"], [data-testid^="v4-workspace-hook-pending"], ' +
      '[data-testid^="v4-user-input"]';
    function entryLabel(el, maxLen){
      var clone = el.cloneNode(true);
      var btns = clone.querySelectorAll ? clone.querySelectorAll(TURN_NOISE_SEL) : [];
      for (var i = 0; i < btns.length; i++) {
        if (btns[i].parentNode) { btns[i].parentNode.removeChild(btns[i]); }
      }
      var txt = (clone.textContent || '').replace(/\s+/g, ' ').trim();
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
        hideNav();   // v63：亮度恢复已收进 hideNav
        return;
      }
      if (panel) { closePanel(); return; }
      // v70（实机"右滑什么都看不到"，用户拍板）：自建面板为主——原生轨道的藏法是
      // 容器查询 display:none，强拉出来后位置/高度/可读性都不受控（36px 无字细条，
      // v65/v69 两轮解锁都在实机翻车）；面板显示问题原文、点按跳转，每个像素都在
      // 我们控制内。原生解锁降级为后备：行选择器失效（collectTurns=0）时仍有一线可见产物。
      var turns = collectTurns();
      if (turns.length) {
        uiLog('nav-panel-' + turns.length);
        buildPanel(turns);
        vibrate();
        hint('已唤出问题导航（点外部收起）');
        return;
      }
      if (showNav()) {
        hint('点横条跳到对应问题，点空白处收起');
        return;
      }
      hint('还没有可导航的消息，先去聊几句吧');
    }

    // ---------- v79⑤：会话切换骨架屏 ----------
    // 路由变化（history.pushState/replaceState + popstate，SPA 不触发导航事件）→ z.ai
    // 整列表卸载重建（实机取证 ~1s 主线程冻结）。冻结期画面是上一会话残影/白屏，
    // 这里铺一层与页面底色一致的骨架（几条微光条），首条 v4-row 渲染出来即淡出；
    // 1.4s 硬上限防慢网下糊屏。只在主文档挂路由钩子（shadow root 共享同一 window.history，
    // 重复包装会双触发）。uiLog/removeReadLine 等在 06 定义，函数声明提升后运行期可用。
    var skEl = null, skObs = null, skTimer = 0, skShown = 0, skLastPath = '';
    function skPageBg(){
      try {
        if (lastBot && typeof lastBot.r === 'number') { return 'rgb(' + lastBot.r + ',' + lastBot.g + ',' + lastBot.b + ')'; }
      } catch (e) {}
      return doc.documentElement.classList.contains('zcode-ui-light') ? '#FCFDFF' : '#0A0A0A';
    }
    function hideSkeleton(){
      if (!skEl) { return; }
      var el = skEl; skEl = null;
      try { if (skObs) { skObs.disconnect(); } } catch (e0) {}
      skObs = null;
      if (skTimer) { clearTimeout(skTimer); skTimer = 0; }
      if (ANIM_ON && !REDUCED) {
        el.classList.add('zc-sk-out');
        var done = function(){ if (el.parentNode) { el.parentNode.removeChild(el); } };
        el.addEventListener('transitionend', done, {once: true});
        setTimeout(done, 500);
      } else {
        try { el.parentNode.removeChild(el); } catch (e1) {}
      }
    }
    function showSkeleton(){
      if (!UI_ON || skEl) { return; }
      skShown++;
      uiLog('sk-show');
      removeReadLine(true);   // 06 的阅读线锚在旧会话的行上，切会话直接收线
      hideNav(); closePanel();   // 切会话时收起我们的浮层，骨架是唯一前景
      skEl = doc.createElement('div');
      skEl.id = 'zcode-skeleton';
      skEl.style.background = skPageBg();
      (isShadow ? root : doc.body || doc.documentElement).appendChild(skEl);
      var W = Math.min(window.innerWidth, 760);
      var offX = Math.max(10, Math.round((window.innerWidth - W) / 2));
      var vh = window.innerHeight;
      var bars = [ [0.07, 0.30], [0.13, 0.86], [0.22, 0.62], [0.32, 0.78], [0.42, 0.50], [0.55, 0.70] ];
      for (var i = 0; i < bars.length; i++) {
        var b = doc.createElement('div');
        b.className = 'zc-sk-bar';
        b.style.left = offX + 'px';
        b.style.top = Math.round(vh * bars[i][0]) + 'px';
        b.style.width = Math.round(W * bars[i][1]) + 'px';
        if (REDUCED) { b.style.animation = 'none'; }
        skEl.appendChild(b);
      }
      requestAnimationFrame(function(){
        if (skEl) { skEl.classList.add('zc-sk-in'); }
      });
      try {
        skObs = new MutationObserver(function(){
          if (skEl && root.querySelector('[data-testid^="v4-row"]')) { hideSkeleton(); }
        });
        skObs.observe(root, {childList: true, subtree: true});
      } catch (e2) {}
      skTimer = setTimeout(hideSkeleton, 1400);
    }
    function onRouteChange(){
      var p = '';
      try { p = location.pathname || ''; } catch (e) { return; }
      if (p === skLastPath) { return; }
      var first = !skLastPath;
      skLastPath = p;
      if (first) { return; }   // 注入时的初始路径不算切换
      showSkeleton();
    }
    if (!isShadow) {
      try {
        ['pushState', 'replaceState'].forEach(function(m){
          var orig = history[m];
          if (typeof orig !== 'function') { return; }
          history[m] = function(){
            var r;
            try { r = orig.apply(this, arguments); } catch (e3) { throw e3; }
            setTimeout(onRouteChange, 0);
            return r;
          };
        });
        evRoot.addEventListener('popstate', function(){ setTimeout(onRouteChange, 0); }, {passive: true});
        onRouteChange();
      } catch (eH) {}
    }

    // ---------- 三态机（v65，替代旧 logo + 胶囊）----------
    // 输入框(展开) ⇄ 左下角收纳图标(收纳)；上滑/右上角弧形钮 → 全屏输入。
    // 状态存 localStorage（记住上次）；dock 始终保持流内 sticky，图标 44px 撑起 dock，
    // 页面/虚拟列表的让位预留照常工作（v64 实证的防空白区方案不变）。
    function persistComposerState(v){
      try { localStorage.setItem('zcComposerState', v); } catch (e) {}
    }
    function storedComposerState(){
      try { return localStorage.getItem('zcComposerState') || 'min'; } catch (e) { return 'min'; }
    }
    // 页面加载后按上次状态自动恢复：'open' → 输入框直接展开（不聚焦，避免键盘突袭）
    var pendingAutoOpen = (storedComposerState() === 'open');
    // ---------- 系统手势豁免（方案A）：图标贴左下角，正落在 Android 返回手势区里，
    // 不豁免则右滑（开导航）会被系统截胡直接退出页面。API 29+ 生效，低版本静默跳过。
    // 1s 轮询会反复进来，用 key 缓存去重，矩形没变不打桥。
    var lastExclude = '';
    function updateGestureExclude(){
      try {
        var br = bridge();
        if (!br || !br.setGestureExclude) { return; }
        var d = getDock();
        var ic = doc.getElementById('zcode-composer-icon');
        var vis = !!(ic && ic.style.display !== 'none' && d &&
          doc.documentElement.classList.contains('zcode-float-on') &&
          !composerOpen && !d.classList.contains('zcode-popup-mode') &&
          !d.classList.contains('zcode-popup-present'));
        var key, r = null;
        if (vis) {
          r = ic.getBoundingClientRect();
          if (!(r.width > 0 && r.bottom > 0)) { vis = false; }
          else { key = Math.round(r.left) + ',' + Math.round(r.top) + ',' + Math.round(r.right) + ',' + Math.round(r.bottom); }
        }
        if (!vis) { key = 'off'; }
        if (key === lastExclude) { return; }
        lastExclude = key;
        if (vis) {
          // 外扩 6px：系统对手势区的判定贴得很紧，紧贴矩形边缘的起手仍可能漏出
          br.setGestureExclude(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6);
        } else {
          br.setGestureExclude(-1, -1, -1, -1);
        }
      } catch (e) {}
    }
    // ---------- 左下角收纳图标：点击/上滑 = 唤出输入框；右滑 = 对话问题导航 ----------
    // 触摸走 touch 系；桌面 Chrome 直接用鼠标时没有 touch 事件，pointer 系(mouse/pen)兜底。
    // pointer 处理器对 pointerType==='touch' 一律放行，避免移动端 touch+pointer 双触发。
    var iconTrack = null;
    var lastIconTapAt = 0;   // v79②：最近一次图标单击时刻（双击直进全屏的判据）
    var lastIconTapXY = null;   // v80②：该次单击的落点（第二击落在图标旁的遮罩上也算双击）
    function evXY(e){
      if (e.touches) { return e.touches[0]; }
      return e;
    }
    // v69（实机"按钮拖动要有对应方向的小动画，需要交互触发的确定感"）：
    // 手势进行中图标跟手平移（0.35 倍率、±16px 封顶），触发后向上滑交给容器变换接力
    // （morph 从含偏移的实时位置起变），右滑/未触发回弹归位——跟手、接力、回弹是同一动作的三段。
    function iconFeedback(ic, dx, dy){
      if (!ic || !ANIM_ON || REDUCED) { return; }
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) { return; }
      var lim = function(v){ return Math.max(-16, Math.min(16, v)); };
      ic.style.transition = 'none';
      ic.style.transform = 'translate(' + lim(dx * 0.35).toFixed(1) + 'px,' + lim(dy * 0.35).toFixed(1) + 'px)';
    }
    function iconRelease(ic){
      if (!ic) { return; }
      ic.style.transition = '';
      ic.style.transform = '';
    }
    function iconStart(e){
      if (e.touches && e.touches.length !== 1) { iconTrack = null; return; }
      var t = evXY(e);
      iconTrack = { x: t.clientX, y: t.clientY, t0: Date.now(), done: false, dir: '' };
    }
    function iconMove(e){
      if (!iconTrack || iconTrack.done) { return; }
      var t = evXY(e);
      var dx = t.clientX - iconTrack.x, dy = t.clientY - iconTrack.y;
      iconFeedback(e.currentTarget, dx, dy);
      // v70：6px 即 preventDefault——等越阈(48px)才拦太晚，浏览器在 ~10px slop 后就把
      // 手势流抢走发 touchcancel（实机"右滑完全没反应、跟手动画会卡"的元凶）；
      // 图标 CSS 已带 touch-action:none，这里是双保险（老 WebView 不认 touch-action 时兜底）
      if (Math.abs(dx) > 6 || Math.abs(dy) > 6) { e.preventDefault(); }
      if (dx > 48 && Math.abs(dy) < 40) {
        iconTrack.done = true; iconTrack.dir = 'right';
        e.preventDefault();
        uiLog('icon-swiperight');
        vibrate();
        iconRelease(e.currentTarget);   // 右滑开导航：图标回弹归位
        if (!navOn) { hint('对话问题导航已关闭'); }
        else { toggleNavigator(); }
      } else if (dy < -44 && Math.abs(dx) < 40) {
        iconTrack.done = true; iconTrack.dir = 'up';
        e.preventDefault();
        uiLog('icon-swipeup');
        showComposer();   // 不回弹：morphReveal 以图标当前位置（含偏移）为变换起点，动作接力
      }
    }
    function iconEnd(e){
      if (!iconTrack) { return; }
      var tr = iconTrack;
      iconTrack = null;
      var ic = e.currentTarget;
      if (tr.done) {
        if (tr.dir !== 'up') { iconRelease(ic); }
        return;
      }
      iconRelease(ic);
      // touchend 用 changedTouches（手指离开位置），pointerup 用事件自身坐标
      var t = e.changedTouches ? e.changedTouches[0] : e;
      // v80②：容差 10→14px——真机手指起落天然带十几像素漂移，紧容差把正常双击拍成
      // "非 tap"（mock 合成事件零漂移所以本地测不出）；双击窗口 330→500ms 同理
      if (Date.now() - tr.t0 < 400 && Math.abs(t.clientX - tr.x) < 14 && Math.abs(t.clientY - tr.y) < 14) {
        var now = Date.now();
        if (now - lastIconTapAt < 500) {
          // v79②：双击直进全屏。首击已唤出浮动胶囊（图标在宽限窗内隐形但可接——见
          // ensureIcon），第二击落点仍是图标：等 morph 飞行结束再切全屏，避免两段
          // 变换的 morphTok 互相打架（替身清理被顶掉会漏出隐藏的真身）
          uiLog('icon-dbltap-full');
          vibrate();
          lastIconTapAt = 0;
          whenMorphDone(function(){
            if (composerOpen && !composerFull) { toggleFullComposer(); }
          });
          return;
        }
        lastIconTapAt = now;
        lastIconTapXY = { x: tr.x, y: tr.y };   // v80②：遮罩回退用（第二击落图标旁也算双击）
        uiLog('icon-tap');
        showComposer();
      }
    }
    function iconPtrDown(e){
      if (e.pointerType === 'touch') { return; }
      iconStart(e);
    }
    function iconPtrMove(e){
      if (e.pointerType === 'touch') { return; }
      iconMove(e);
    }
    function iconPtrUp(e){
      if (e.pointerType === 'touch') { return; }
      iconEnd(e);
    }
    // ---------- 输入框上的滑动：下滑 = 收纳成图标，上滑 = 全屏输入 ----------
    // 事件委托挂在 evRoot 上（React 重挂 dock 也始终有效）。打字/点按的手势不归我们：
    // textarea、输入框、按钮、链接一律放行，滑动只认输入框的边框留白与工具条区域。
    var dockTrack = null;
    var dockDragTy = 0;   // v69：胶囊拖动反馈的当前纵向偏移，morph 接力时要并进起始 transform
    function dockGestureArm(tg, x, y){
      if (!composerOpen || composerFull) { return null; }
      if (!tg || !tg.closest) { return null; }
      var dk = tg.closest('[data-v4-composer-dock="true"]');
      if (!dk || dk.classList.contains('zcode-popup-mode') || dk.classList.contains('zcode-popup-present')) { return null; }
      if (tg.closest('textarea, input, select, button, a, [contenteditable], [role="button"], #zcode-composer-expand')) { return null; }
      return { x: x, y: y, t0: Date.now(), done: false, pill: dk.querySelector('.rounded-2xl') };
    }
    function dockGestureStart(e){
      if (e.touches && e.touches.length !== 1) { dockTrack = null; return; }
      var t = evXY(e);
      dockTrack = dockGestureArm(e.target, t.clientX, t.clientY);
    }
    function dockGestureMove(e){
      if (!dockTrack || dockTrack.done) { return; }
      var t = evXY(e);
      var dx = t.clientX - dockTrack.x, dy = t.clientY - dockTrack.y;
      // v69：方向拖动反馈——胶囊跟手平移（0.45 倍率、±110px 封顶），越阈正式动画从跟手位置
      // 接力（morph 读实时 rect 并保留偏移），未越阈松手回弹——"这次滑动会不会触发"有了确定答案
      // v70：进入纵向拖动就 preventDefault（不论动效开关）——手势落点已排除 textarea/按钮等
      // 交互件，这里拦默认只挡浏览器抢手势（滚动/overscroll→touchcancel），不影响页面点击
      if (Math.abs(dy) > 6 && Math.abs(dx) < 56) {
        e.preventDefault();
        if (dockTrack.pill && ANIM_ON && !REDUCED) {
          dockDragTy = Math.max(-110, Math.min(110, dy * 0.45));
          dockTrack.pill.style.transition = 'none';
          dockTrack.pill.style.transform = 'translateY(' + dockDragTy.toFixed(1) + 'px)';
        }
      }
      if (dy > 44 && Math.abs(dx) < 40) {
        dockTrack.done = true;
        e.preventDefault();
        uiLog('composer-swipedown-min');
        vibrate();
        hideComposer();
        hint('已收纳，点左下角图标唤出');
      } else if (dy < -44 && Math.abs(dx) < 40) {
        dockTrack.done = true;
        e.preventDefault();
        uiLog('composer-swipeup-full');
        vibrate();
        if (!composerFull) { toggleFullComposer(); }
      }
    }
    function dockGestureEnd(){
      if (dockTrack && !dockTrack.done && dockTrack.pill && dockDragTy) {
        // 未越阈：带过渡回弹，和触发路径在观感上明确区分
        dockTrack.pill.style.transition = 'transform 0.18s ease';
        dockTrack.pill.style.transform = '';
      }
      dockTrack = null;
      dockDragTy = 0;
    }
    // ---------- 滑到底自动恢复输入框（v67，设置项"滑到底部恢复输入框"）----------
    // 收纳态内容满屏，滚到全部消息最底端 = 读完准备输入 → 输入框自动滑出（容器变换）。
    // 防误触：流式回复的自动跟滚一直贴着底部，不能一进收纳态就触发——
    // 只有"向上离开底部 ≥220px（页面跟滚此时已停）再滚回 ≤28px"才算"读完回来了"。
    var minUpDist = 0;
    var lastMinAt = 0;   // v69：最近一次手动收纳的时刻——收纳后短期内不自动恢复（见 watchScrollRestore）
    var restoreScEl = null;
    function watchScrollRestore(){
      if (!restoreOnBottom || !floatInput || !UI_ON || composerOpen) { return; }
      if (!doc.documentElement.classList.contains('zcode-float-on')) { return; }
      // v69（实机"点输入框外关闭，此时上滑也会再唤起一次输入框"）：刚手动收纳完的第一次
      // 上滑往往只是"滚下去看看"，不是"读完想输入"。≥220px 离底 + 4s 冷却双门槛，
      // 两个都满足才算"读完回来了"，"刚关完就被弹回"的路径被掐断
      if (lastMinAt && Date.now() - lastMinAt < 4000) { return; }
      var dock = getDock();
      if (!dock || dock.classList.contains('zcode-popup-mode') || dock.classList.contains('zcode-popup-present')) { return; }
      // 容器可能被 React 重挂，断了就重找。3.14.4 实测滚动容器是 v4-timeline
      //（min-h-0 flex-1 overflow-y-auto，15000px+ 内容全在里面）；v4-timeline-scroll 是候选兼容
      if (!restoreScEl || !restoreScEl.isConnected) {
        restoreScEl = root.querySelector('[data-testid="v4-timeline-scroll"]') ||
          root.querySelector('[data-testid="v4-timeline"]');
      }
      if (!restoreScEl || !(restoreScEl.scrollHeight > restoreScEl.clientHeight + 40)) { return; }
      var dist = restoreScEl.scrollHeight - restoreScEl.clientHeight - restoreScEl.scrollTop;
      if (dist > minUpDist) { minUpDist = dist; }
      if (dist <= 28 && minUpDist >= 220) {
        minUpDist = 0;
        uiLog('restore-on-bottom');
        showComposer(false);
      }
    }
    // ---------- v79①：发送后归位（v80 修真机失效） ----------
    // 触发签名（双证据）：composer 开着 + 输入框刚有字 → 新的用户行出现 且 输入框已清空。
    // "手动删光字"不会有新用户行，不误触。z.ai 发送即清输入框（先于行挂载），所以"清空"
    // 只作证据不作触发——触发点始终是新用户行的出现。
    // v80 教训：真页 v4-composer-input 是 Lexical **contenteditable div**（诊断 dockHtml
    // 实锤），不是 textarea——旧版只认 TEXTAREA/INPUT.value，真机上 sendHadText 永假、
    // ①从不触发（mock 用 textarea 所以本地全绿）。文本读取改双形态兼容。
    // 归位延迟 420ms：让 z.ai 自己的发送动画先走一步，morph 收纳接在其后，两段不叠帧。
    var sendHadText = false, sendHadTextAt = 0;
    function composerTextOf(t){
      if (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT') { return t.value || ''; }
      return (t.textContent || '');   // contenteditable（真页形态）
    }
    function onComposerInput(e){
      var t = e.target;
      if (!t || !t.tagName || !t.closest) { return; }
      if (!t.closest('[data-v4-composer-dock="true"]')) { return; }
      if (t.tagName !== 'TEXTAREA' && t.tagName !== 'INPUT' &&
          !t.isContentEditable && !t.closest('[data-testid^="v4-composer-input"]')) { return; }
      if (composerTextOf(t).trim()) { sendHadText = true; sendHadTextAt = Date.now(); }
      else if (Date.now() - sendHadTextAt > 600) { sendHadText = false; }   // 发送瞬间的清空（600ms 内）不灭旗
    }
    var sendObs = null;
    try {
      sendObs = new MutationObserver(function(muts){
        if (!sendMinimize || !composerOpen || !sendHadText) { return; }
        if (Date.now() - sendHadTextAt > 6000) { sendHadText = false; return; }
        for (var i = 0; i < muts.length; i++) {
          if (muts[i].type !== 'childList') { continue; }
          var ns = muts[i].addedNodes;
          for (var j = 0; j < ns.length; j++) {
            var n = ns[j];
            if (n.nodeType !== 1) { continue; }
            var row = null;
            var tid = n.getAttribute ? (n.getAttribute('data-testid') || '') : '';
            if (tid.indexOf('v4-row') === 0) { row = n; }
            else if (n.querySelector) { row = n.querySelector('[data-testid^="v4-row"]'); }
            if (!row) { continue; }
            var cls = ' ' + (row.className || '') + ' ';
            if (cls.indexOf('group/user-row') < 0) { continue; }
            var dk = getDock();
            var ta = dk ? dk.querySelector('[data-testid^="v4-composer-input"], textarea') : null;
            if (ta && composerTextOf(ta).trim()) { continue; }   // 有字=不是发送
            sendHadText = false;
            uiLog('send-minimize');
            setTimeout(function(){
              try {
                var ae = doc.activeElement;
                if (ae && ae.blur) { ae.blur(); }   // 先收键盘：IME 逐帧 resize 与 morph 重叠是旧痛点
              } catch (e1) {}
              if (composerOpen) { hideComposer(); }
            }, 420);
            return;
          }
        }
      });
      sendObs.observe(root, {childList: true, subtree: true});
    } catch (eSO) {}

    // ---------- v79⑥：流式阅读位置线 ----------
    // 流式输出中用户上滑离开底部 → 在"离开那一刻的内容底边"插一条低可视细线，新内容
    // 继续在线下方生长；滚回底部或流式结束即淡出。锚在最后一个可见行后面（内容只在
    // 尾部追加，锚点位置稳定）；锚行被虚拟化卸载则顺手收线，不重建。
    var readLineEl = null, lastShSeen = -1, lastShGrowAt = 0;
    function trackStreamGrow(){
      var tl = overlayTl();
      if (!tl) { return; }
      var sh = tl.scrollHeight;
      if (lastShSeen < 0 || sh > lastShSeen + 2) { lastShGrowAt = Date.now(); }
      lastShSeen = sh;
    }
    function streamingNow(){ return (Date.now() - lastShGrowAt) < 2200; }
    function removeReadLine(now){
      if (!readLineEl) { return; }
      var el = readLineEl; readLineEl = null;
      if (ANIM_ON && !REDUCED && !now) {
        el.classList.add('zc-rl-out');
        setTimeout(function(){ if (el.parentNode) { el.parentNode.removeChild(el); } }, 450);
      } else {
        try { el.parentNode.removeChild(el); } catch (e0) {}
      }
    }
    function placeReadLine(tl){
      var vb = window.innerHeight;
      var rows = tl.querySelectorAll('[data-testid^="v4-row"]');
      var anchor = null;
      for (var i = rows.length - 1; i >= 0; i--) {
        var rb = rows[i].getBoundingClientRect().bottom;
        if (rb <= vb - 4 && rb > 0) { anchor = rows[i]; break; }
      }
      if (!anchor || !anchor.parentNode) { return; }
      var el = doc.createElement('div');
      el.className = 'zc-readline';
      var lab = doc.createElement('i');
      lab.textContent = '新内容 ↓';
      el.appendChild(lab);
      anchor.parentNode.insertBefore(el, anchor.nextSibling);
      readLineEl = el;
      uiLog('readline-place');
    }
    function onReadScroll(){
      var tl = overlayTl();
      if (!tl) { return; }
      if (readLineEl) {
        var dist = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
        if (dist <= 160 || !streamingNow() || !readLineEl.isConnected) { removeReadLine(); }
        return;
      }
      if (!streamingNow()) { return; }
      var dist2 = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
      if (dist2 > 260) { placeReadLine(tl); }
    }

    // ---------- v79⑦→v80：↓ 圆钮避让小白条 ----------
    // v79 走几何解析（无 USB 锚不到选择器），实机诊断实锤翻车原因：↓ 钮（v4-timeline-
    // bottom）挂在 dock 内部 data-v4-back-to-bottom-anchor 锚上（absolute/bottom-full，
    // 悬在 0 高 dock 上方 ~8px），lifter 把"整棵 dock 子树"排除时把它一起排掉了。
    // 现在选择器已知（testid 稳定），改为 02_style 的静态 CSS 直抬（transform 重写需
    // 复刻它自带的 -translate-x-1/2 居中），零轮询零测量，React 重渲染也盖不掉。

    function dockGesturePtrDown(e){
      if (e.pointerType === 'touch') { return; }
      dockTrack = dockGestureArm(e.target, e.clientX, e.clientY);
    }    function dockGesturePtrMove(e){
      if (e.pointerType === 'touch') { return; }
      dockGestureMove(e);
    }
    function dockGesturePtrUp(e){
      if (e.pointerType === 'touch') { return; }
      dockGestureEnd();
    }
    // 界面增强总开关：关闭后不注入图标/悬浮输入/导航面板等，保持纯网页观感
    if (UI_ON) {
      evRoot.addEventListener('touchstart', dockGestureStart, {passive:true});
      evRoot.addEventListener('touchmove', dockGestureMove, {passive:false});
      evRoot.addEventListener('touchend', dockGestureEnd, {passive:true});
      evRoot.addEventListener('touchcancel', function(){ dockTrack = null; }, {passive:true});
      evRoot.addEventListener('pointerdown', dockGesturePtrDown, {passive:true});
      evRoot.addEventListener('pointermove', dockGesturePtrMove, {passive:true});
      evRoot.addEventListener('pointerup', dockGesturePtrUp, {passive:true});
      // scroll 不冒泡但参与捕获：capture 挂根上可以收到内部滚动容器的滚动
      evRoot.addEventListener('scroll', watchScrollRestore, {passive:true, capture:true});
      evRoot.addEventListener('scroll', onReadScroll, {passive:true, capture:true});   // v79⑥：阅读位置线
      evRoot.addEventListener('input', onComposerInput, {passive:true, capture:true});   // v79①：发送检测的输入跟踪
      window.addEventListener('resize', updateGestureExclude);
      syncComposer();
    }

    // ---------- 输入框劫持：有消息时收起原输入框，左下角图标/手势唤出，右上角 icon 可全屏 ----------
    // 关键设计：绝不缓存 dock 节点引用。React 重渲染会换掉节点，缓存引用会指向已脱离 DOM 的旧节点
    // → 加 class、挂 expand 按钮都打到旧节点上，live 节点上什么都没有（v36 的 hasExpand=0 就这病）。
    // 这里每次需要都重新 querySelector，并在 1s 轮询里把"展开/全屏"状态同步到 live 节点。
    var COMPOSER_SEL = '[data-v4-composer-dock="true"]';
    var composerOpen = false, composerFull = false, pendingOpen = false;   // pendingOpen: dock 未就绪时的待开标记
    var lastDockH = -1;   // v66：dock 流内高度跟踪（收纳 0 / 唤出悬浮 / 常驻 121），变化时通知列表重算
    var restoreOnBottom = true;   // v67 设置项"滑到底部恢复输入框"（原生 applySettings 热更）
    // v79 会话页新开关（原生层注入 window.__zcSettings，applySettings 热更；mock 预览页同形）
    // v80：⑧长按菜单用户淘汰已整套删除（longPressMenu 键随之退役，旧存档多出的键无害）
    var ZSET = (window.__zcSettings = window.__zcSettings || {});
    var sendMinimize = (ZSET.sendMinimize !== false);      // ① 发送后归位（默认开）
    var kbFollowExp = (ZSET.kbFollowExp === true);          // ③ 键盘跟随·实验（默认关）
    var amoledBlack = (ZSET.amoledBlack === true);          // ⑨ 纯黑 AMOLED（默认关）
    function applyAmoled(){
      try { doc.documentElement.classList.toggle('zc-amoled', amoledBlack); } catch (e) {}
    }
    applyAmoled();
    // v71：虚拟列表换窗时可能短暂卸载全部 v4-row；不能把一次空 query 当成"新空会话"。
    // 首次见到消息后，连续两轮（约 2 秒）都无行才允许摘 zcode-float-on，避免 dock
    // 在 0 高/原生高度之间来回跳，进而触发 resize→虚拟列表重算→再次卸载的反馈回路。
    var rowsEverSeen = false;
    var missingRowsTicks = 0;
    var ROW_MISS_LIMIT = 2;
    var lastFloatState = null;
    var lastFloatAt = 0;
    var lastFloatReason = '';
    var composerFoundAt = -1, injectAt2 = Date.now();   // 渲染时序埋点（dock 首次找到耗时，诊断用）
    var uiTrace = [];   // UI 手势链路埋点（诊断用）
    function uiLog(act){
      var d = new Date();
      var hh = ('0' + d.getHours()).slice(-2), mm = ('0' + d.getMinutes()).slice(-2), ss = ('0' + d.getSeconds()).slice(-2);
      uiTrace.push({ t: hh + ':' + mm + ':' + ss, act: act });
      if (uiTrace.length > 6) { uiTrace.shift(); }
    }
    function getDock(){
      var d = root.querySelector(COMPOSER_SEL);
      return (d && d.parentNode) ? d : null;   // 必须仍在 DOM 中才算数
    }
    function setFloatState(on, reason){
      var de = doc.documentElement;
      if (!de) { return false; }
      var prev = de.classList.contains('zcode-float-on');
      if (prev === on) { return false; }
      if (on) { de.classList.add('zcode-float-on'); }
      else { de.classList.remove('zcode-float-on'); }
      lastFloatState = on;
      lastFloatAt = Date.now();
      lastFloatReason = reason || '';
      uiLog('float-' + (on ? 'on-' : 'off-') + (reason || 'state'));
      return true;
    }
    // 圆弧图标（Qwen demo 几何）：一个 SVG 内两条弧——外弧 R+gap（悬浮态=扩大）、内弧 R−gap（全屏态=缩小），
    // 同圆心同跨角：46° 短弧跨在右上角 45° 对角线上（68°→22°，从水平轴起算），弱视觉贴合圆角。
    // 命中区：透明粗描边 zc-hit（stroke-width 14，pointer-events:stroke），只有弧环可点，文字区不被方块遮挡。
    // 状态互斥由 CSS class（zc-full）切换 opacity/visibility，见 fxStyle。
    var ARC_GAP = 4;
    function arcIconSvg(radius){
      var half = radius + ARC_GAP + 9;   // 中心到盒边：弧线外径 + 命中环半宽(7) + 2px 余量
      var size = Math.max(40, Math.ceil(half * 2));
      function arcPath(R){
        var a1 = 68 * Math.PI / 180, a2 = 22 * Math.PI / 180;
        var x1 = half + R * Math.cos(a1), y1 = half - R * Math.sin(a1);
        var x2 = half + R * Math.cos(a2), y2 = half - R * Math.sin(a2);
        return 'M ' + x1.toFixed(1) + ' ' + y1.toFixed(1) + ' A ' + R + ' ' + R + ' 0 0 1 ' + x2.toFixed(1) + ' ' + y2.toFixed(1);
      }
      function group(cls, R){
        var d = arcPath(R);
        return '<g class="zc-arc ' + cls + '"><path class="zc-hit" d="' + d + '"/><path class="zc-line" d="' + d + '"/></g>';
      }
      return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" fill="none">' +
        group('zc-expand', radius + ARC_GAP) +
        group('zc-shrink', Math.max(radius - ARC_GAP, 4)) +
        '</svg>';
    }
    // ---------- dock 内弹窗复活（z.ai 的 ask/确认弹窗渲染在输入框容器里） ----------
    // dock 平时 display:none（不占位）。检测到弹窗 → 加 zcode-popup-mode 临时显示 dock
    // （只露弹窗、隐藏输入，v46），弹窗消失自动收起。悬浮输入打开时不做（弹窗本来就可见）。
    var lastPopupSeen = 0;
    var POPUP_HINT_SEL = '[role="dialog"], [role="alertdialog"], [data-testid*="ask" i], [data-testid*="confirm" i], ' +
      '[data-testid*="dialog" i], [data-testid*="modal" i], [data-testid*="popup" i], [data-testid*="request" i], [aria-modal="true"], ' +
      // v57：z.ai 的 ask/确认弹窗实测标记是 data-elicitation-dialog-card（诊断 dockHtml 实锤），
      // 且是 position:relative——只靠 role/testid/aria-modal 和 fixed 兜底永远扫不到它
      '[data-elicitation-dialog-card], [data-elicitation-dialog-body], ' +
      // v61：权限申请框（bash/写文件等工具执行授权）——与 ask 同属 elicitation 族，
      // 若 testid 不含上面的子串关键词则漏判，预防性补齐常见权限标记
      '[data-testid*="permission" i], [data-testid*="approve" i], [data-testid*="allow" i], ' +
      '[data-testid*="authorize" i], [data-testid*="can-use" i], [data-testid*="consent" i], ' +
      // v64：3.14.x 新增的待审/用户输入卡片——hook-pending 系列不含上面任何关键词，
      // 不补会静默漏检（桌面端等你授权时手机端无感知）；user-input 系列含 dialog 但显式补上更稳
      '[data-testid*="hook-pending" i], [data-testid*="user-input" i]';
    function findPopup(dock){
      try {
        var hit = dock.querySelectorAll(POPUP_HINT_SEL);
        for (var i = 0; i < hit.length; i++) {
          var el = hit[i];
          if (el.id && el.id.indexOf('zcode-') === 0) { continue; }
          // v60：只认"开着"的弹窗——页面关闭弹窗后节点可能残留（data-state=closed 或
          // display:none / visibility:hidden / opacity:0），残留节点不能继续触发 popup-mode，
          // 否则弹窗关不掉、dock 不收起（"选了没反应"的元凶之一）。
          // 注意：不能查 rect——composer 关闭时 dock 整体 display:none，子孙 rect 全为 0
          try {
            var st2 = el.getAttribute('data-state');
            if (st2 === 'closed') { continue; }
            var cs2 = getComputedStyle(el);
            if (cs2.display === 'none' || cs2.visibility === 'hidden' || parseFloat(cs2.opacity) === 0) { continue; }
          } catch (e2) {}
          return el;
        }
      } catch (e) {}
      // 兜底：无特征 testid 的弹窗 —— fixed 定位节点，或超过 60px 的 absolute 节点
      try {
        var all = dock.querySelectorAll('*');
        for (var j = 0; j < all.length && j < 300; j++) {
          var e2 = all[j];
          if (e2.closest('.rounded-2xl, [data-testid^="v4-composer-input"], #zcode-composer-expand')) { continue; }
          if (e2.id && e2.id.indexOf('zcode-') === 0) { continue; }
          var pos = '';
          try { pos = getComputedStyle(e2).position; } catch (e3) {}
          if (pos === 'fixed' || (pos === 'absolute' && (e2.offsetHeight || 0) > 60)) { return e2; }
        }
      } catch (e) {}
      return null;
    }
    function scanDockPopups(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      var pop = findPopup(dock);
      if (pop) {
        pop.classList.add('zcode-popup-visible');
        dock.classList.add('zcode-popup-present');   // 弹窗存在标记：隐藏 expand 按钮（悬浮态也生效）
        if (!composerOpen && !dock.classList.contains('zcode-popup-mode')) {
          dock.classList.add('zcode-popup-mode');
          // 弹窗出现时轻提示（防抖，避免流式渲染期间反复震）
          if (Date.now() - lastPopupSeen > 2000) {
            lastPopupSeen = Date.now();
            vibrate();
            hint('有请求需要确认');
          }
        }
      } else {
        dock.classList.remove('zcode-popup-mode', 'zcode-popup-present');
      }
    }
    // ---------- dock 新增节点快照（v55） ----------
    // ask 弹窗渲染瞬间被记录（结构链 + 定位/可见性），弹窗卸载后诊断卡仍能读到——
    // 用于确认弹窗实际挂在 dock 的哪个位置（弹窗已消失时 findPopup 只能给出 null）。
    var dockSnap = [];
    var dockSnapObs = null;
    function snapDockNode(el, dock){
      var chain = [];
      var anc = el;
      while (anc && anc !== dock) {
        var tag = anc.tagName || '';
        var tid = anc.getAttribute && anc.getAttribute('data-testid');
        if (tid) { tag += '[' + tid.slice(0, 30) + ']'; }
        if (anc.className && typeof anc.className === 'string' && anc.className) { tag += '.' + anc.className.split(' ')[0]; }
        chain.unshift(tag.slice(0, 44));
        anc = anc.parentNode;
      }
      var r = el.getBoundingClientRect();
      var cs = getComputedStyle(el);
      dockSnap.push({
        t: new Date().toTimeString().slice(0, 8),
        tag: el.tagName,
        tid: (el.getAttribute && el.getAttribute('data-testid')) || '',
        role: (el.getAttribute && el.getAttribute('role')) || '',
        cls: ((el.className || '').toString() || '').slice(0, 40),
        chain: chain.join('>').slice(0, 200),
        pos: cs.position + '/z' + cs.zIndex,
        disp: cs.display + '/v' + cs.visibility,
        rect: Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.x) + ',' + Math.round(r.y)
      });
      if (dockSnap.length > 12) { dockSnap.shift(); }
    }
    function ensureDockSnapObs(dock){
      if (dockSnapObs || !dock) { return; }
      try {
        dockSnapObs = new MutationObserver(function(muts){
          // v55b：不设 composerOpen 门控——弹窗可能是在打开输入框时才渲染的，
          // 门控会正好跳过采样窗口（首轮 popupSnap 只捕到滚到底按钮就是这个坑）
          for (var i = 0; i < muts.length; i++) {
            var added = muts[i].addedNodes;
            for (var j = 0; j < added.length; j++) {
              var n = added[j];
              if (n.nodeType !== 1) { continue; }
              if (n.id && n.id.indexOf('zcode-') === 0) { continue; }
              var MARK = '[data-testid],[role],[aria-modal],[data-state]';
              var hasMark = n.getAttribute && (n.getAttribute('data-testid') || n.getAttribute('role') || n.getAttribute('aria-modal') || n.getAttribute('data-state'));
              if (hasMark) { snapDockNode(n, dock); }
              else if (n.querySelector && n.querySelector(MARK)) {
                // React 一次性插入整个子树：根节点无标记但内部有——快照根，链上能看出挂点
                snapDockNode(n, dock);
              }
            }
          }
        });
        dockSnapObs.observe(dock, { childList: true, subtree: true });
      } catch (e) {}
    }
    // ---------- body 级弹窗检测（v53） ----------
    // z.ai 的 Radix 下拉/菜单/选择器 portal 到 body 根部（z-50），被我们的遮罩（99995）与
    // dock（99998+）盖住/吞点击。检测到即给 html 加 zcode-body-popup，CSS 把注入层压到弹窗之下。
    // 只扫特征选择器（不扫全树），400ms 采样足以跟上菜单开合。
    var PAGE_POPUP_SEL = '[role="menu"], [role="listbox"], [role="dialog"], [role="tooltip"], ' +
      '[data-radix-popper-content-wrapper], [data-radix-menu-content], [data-radix-select-content], [data-state="open"]';
    function isPagePopup(el){
      if (!el || el.nodeType !== 1) { return false; }
      if (el.id && el.id.indexOf('zcode-') === 0) { return false; }
      try {
        // 我们自己的注入层不算（诊断卡/导航/遮罩）；dock 内弹窗走 popup-mode 复活，也不算
        if (el.closest('#zcode-mask, #zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return false; }
        if (el.closest('[data-v4-composer-dock="true"]')) { return false; }
        var cs = getComputedStyle(el);
""" +
"""        if (cs.position !== 'fixed' && cs.position !== 'absolute') { return false; }
        var z = parseInt(cs.zIndex, 10);
        // 高于页面内容（z-20/30），低于我们的注入层（99990）——页面自己的弹窗区间
        if (!(z > 25 && z < 99990)) { return false; }
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 24) { return false; }
        if (r.right < 0 || r.left > window.innerWidth || r.bottom < 0 || r.top > window.innerHeight) { return false; }
        return true;
      } catch (e) { return false; }
    }
    var bodyPopupObs = null;   // v76：body 弹窗监听事件化（见 scanBodyPopups）
    function scanBodyPopups(){
      if (!UI_ON) { return; }
      // v76 瘦身：Radix 弹窗 portal 挂/卸在 body 直接子层——childList 观察器即时触发
      // 扫描（比 400ms 轮询延迟更低）；400ms 轮询降级为 2s 安全网，兜非 portal 场景与
      // 观察器异常。观察器只看 childList，与 class 写入无反馈环。
      if (!bodyPopupObs && doc.body) {
        try {
          bodyPopupObs = new MutationObserver(function(){
            scanBodyPopups();
            setTimeout(scanBodyPopups, 120);   // portal 挂载后内容/几何晚一拍到位
            setTimeout(scanBodyPopups, 400);
          });
          bodyPopupObs.observe(doc.body, { childList: true });
        } catch (e0) { bodyPopupObs = null; }
      }
      var found = false;
      try {
        var all = doc.querySelectorAll(PAGE_POPUP_SEL);
        for (var i = 0; i < all.length; i++) {
          if (isPagePopup(all[i])) { found = true; break; }
        }
      } catch (e) {}
      var html = doc.documentElement;
      if (!html) { return; }   // shadow root 没有 documentElement
      if (found) { html.classList.add('zcode-body-popup'); }
      else { html.classList.remove('zcode-body-popup'); }
    }
    // 每次调用都基于 live dock：确保 expand 按钮在、当前 open/full 状态 class 正确。
    // 这是幂等的，可被轮询反复调用以跟随 React 重挂载。
    // ---------- 底部残留清理（v68/v69/v70） ----------
    // 收纳态的底部空白来源不止一处，逐一测量回收，唤出态全部原样恢复：
    // A) 容器级残留（v70 升级为祖先链）：实机 0.0.4 空白带依旧——v64 取证就写明 3.14.4
    //    把让位 padding 挂在 data-v4-timeline-content-column 等「末节的祖先」节点上，
    //    v69 只收 timeline 自身 padding / 末节 margin / 末节后空兄弟，够不着祖先链。
    //    现改为：末节 margin-bottom → 沿 lastSec 的祖先链逐层上溯到滚动容器（含），
    //    收每层 padding-bottom + 每层在路径节点之后的无正文空壳兄弟。
    //    口径不变：phantom = scrollHeight − 末节实际底边，>12px 才动手；
    //    每笔回收落 uiLog（tail-pad/tail-margin/tail-el），诊断卡可查到底收掉了什么。
    // B) 末节内部的 min-h-5 垫片（v68）：隐藏后 flex 尾随 gap 一并消失，共回收 40px。
    // 虚拟列表随时增删 turn：每次轮询重查、按引用恢复旧节点（已卸载则无害）；
    // 并校验垫片确实位于内容末尾（末条是用户消息时不动）；有变更才 nudge 虚拟列表重算。
    var tailSpacerEl = null;
    var tailFixed = [];   // v69：A 类回收记录 [{el, prop, val}]，唤出态恢复
    // v71：去重与互斥状态——签名未变整轮跳过；nudge 时刻供空洞看门狗让位
    var tailSig = null;
    var tailFixAt = 0;
    var tailFixPhantom = 0;
    var tailNudgeAt = 0;
    var tailShLast = -1;   // v75：上拍内容高度（静止闸门，见 syncTailBlank）
    function tailRestoreAll(){
      if (tailSpacerEl) { tailSpacerEl.style.display = ''; tailSpacerEl = null; }
      for (var i = 0; i < tailFixed.length; i++) {
        try { tailFixed[i].el.style[tailFixed[i].prop] = tailFixed[i].val; } catch (eF) {}
      }
      tailFixed = [];
      tailSig = null;   // v71：修复被恢复 = 状态回退，作废签名让下轮重新评估（否则签名锁死、残留永不重收）
    }
    function tailNudge(){
      tailNudgeAt = Date.now();
      try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
      try {
        var tl2 = root.querySelector('[data-testid="v4-timeline"]');
        if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
      } catch (e3) {}
    }
    // ---------- 悬浮输入/弹窗让位垫（v72） ----------
    // dock 悬浮(fixed)/弹窗期脱离文档流，消息列表不知道底部被盖住——最后一条消息
    // 滚不出输入框上方（实机"关掉键盘后输入框挡住文本结尾"）。悬浮/弹窗态给滚动
    // 容器垫 paddingBottom，收纳态撤掉。只认自己写的 inline 值，与尾清的配合固定：
    // · 收纳态在 syncTailBlank 之前撤垫（防止垫被算进 phantom 乱收页面节点）
    // · 悬浮态在 syncTailBlank 还原之后落垫（防止还原把垫盖掉）
    var overlayPadPx = 0;
    function overlayTl(){
      return root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
    }
    function needOverlayPad(dock){
      if (!dock) { return 0; }
      if (composerOpen && !composerFull) {
        // 悬浮胶囊：胶囊实高 + 底部偏移14 + 安全区 + 呼吸位；胶囊内 textarea 变高
        // 时 offsetHeight 跟着变，1s 轮询自适应
        var dh = dock.offsetHeight;
        if (dh > 8 && dh < window.innerHeight * 0.6) { return dh + 14 + safeB() + 10; }
        return 0;
      }
      if (!composerOpen && (dock.classList.contains('zcode-popup-mode') || dock.classList.contains('zcode-popup-present'))) {
        // 弹窗态 dock 同样 fixed 铺在底部
        var ph = dock.offsetHeight;
        if (ph > 8 && ph < window.innerHeight * 0.6) { return ph + safeB() + 8; }
      }
      return 0;
    }
    function setOverlayPad(want){
      // v78：morph 飞行中不落垫（真身隐藏中，垫的变化会穿帮）——落位回调的
      // syncComposer 会补上；1s 轮询在飞行窗口撞进来也由此挡住
      var dk = getDock();
      if (dk && dk.classList.contains('zcode-composer-morph')) { return; }
      var t = overlayTl();
      if (!t || want <= 0) { return; }
      var vs = want + 'px';
      if (overlayPadPx === want && t.style.paddingBottom === vs) { return; }
      overlayPadPx = want;
      try { t.style.paddingBottom = vs; } catch (e0) { return; }
      tailNudge();
    }
    function clearOverlayPad(){
      if (overlayPadPx <= 0) { return; }
      overlayPadPx = 0;
      var t = overlayTl();
      if (t) { try { t.style.paddingBottom = ''; } catch (e1) {} }
      tailSig = null;   // 撤垫后页面自己的 padding 会露回来，让尾清重新评估
      tailNudge();
    }
    function syncTailBlank(dock){
      var minimized = dock && !dock.classList.contains('zcode-composer-float') &&
        !dock.classList.contains('zcode-composer-full') &&
        !dock.classList.contains('zcode-popup-mode');
      if (!minimized) {
        tailSig = null;   // v71：状态翻转后作废签名，回收纳态时重新评估
        // v72：悬浮/全屏/弹窗态不还原外层壳——dock 是 fixed，槽位用不上；还原会
        // 和悬浮让位垫叠成双重预留（胶囊下面一段死区，实机截图 B 的形态）。
        // 只在退回原生常驻态（float-on 关闭，页面输入框回到底部）时全部还原。
        if (!doc.documentElement.classList.contains('zcode-float-on') && (tailSpacerEl || tailFixed.length)) {
          tailRestoreAll(); tailNudge();
        }
        return;
      }
      var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl) { return; }
      // v71：缺行待确认/动画进行中跳过——虚拟列表正在换窗，此刻测量不可信，
      // 收了也会被重挂载弹回（"每秒收一遍-弹一遍"脉动的另一半）
      if (missingRowsTicks > 0 || dock.classList.contains('zcode-composer-morph') ||
          dock.classList.contains('zcode-composer-closing')) { return; }
      // v75 静止闸门（回复中每秒上下闪动的根修，实机变异日志抓的现行）：
      // 流式输出期尾清把空占位条 display:none（内容 −40px）→ React 流式重渲染整段
      // 抹掉我们写的内联样式（+40px 回来）→ 下一秒轮询再收……攻防战让钉底页面每秒
      // ±40px 上下跳。占位条在流式期间留着无害（内容一直在长，尾部本来就看不精确），
      // 一律不动手；scrollHeight 与上一拍相同（连续两拍稳定 = 流式停、滚动停稳）才清理。
      // 自身改动造成的高度变化同样只多等一拍，幂等收敛；撤垫（tailSig=null）的重评估
      // 也顺延一拍，代价可忽略。
      var shNow = tl.scrollHeight;
      if (shNow !== tailShLast) { tailShLast = shNow; return; }
      var changed = false;
      try {
        var secs = tl.querySelectorAll('section');
        if (secs.length) {
          var cr = tl.getBoundingClientRect();
          var lastSec = secs[secs.length - 1];
          var phantom = tl.scrollHeight - (lastSec.getBoundingClientRect().bottom - cr.top + tl.scrollTop);
          // v71：签名去重——末节与 phantom 都没变 = 上一轮修复仍在位（回收本身幂等），
          // 整轮跳过，不再测量/改写/通知；垫片状态随签名未变也必然稳定，一并跳过
          var sig = (((lastSec.getAttribute('data-testid') || lastSec.className || '')) + '').slice(0, 60) +
            '|' + Math.round(phantom) + '|' + tl.scrollHeight;
          if (sig === tailSig) { return; }
          tailSig = sig;
          // 上限闸门：>240px 的"空白"多半不是垫片/容器机制，而是虚拟列表只渲染了顶部区间
          // （未渲染区没有节点可回收）——那是 v68b 空洞看门狗的管辖，乱收会炸掉虚拟化
          if (phantom > 12 && phantom < 240) {
            tailFixAt = Date.now();
            tailFixPhantom = Math.round(phantom);
            var mb = parseFloat(getComputedStyle(lastSec).marginBottom) || 0;
            if (mb > 4 && mb < phantom) {
              tailFixed.push({ el: lastSec, prop: 'marginBottom', val: lastSec.style.marginBottom });
              lastSec.style.marginBottom = '0px';
              phantom -= mb; changed = true; uiLog('tail-margin');
            }
            // v70：祖先链上溯（含滚动容器本身）——每层收 padding-bottom，以及该层在
            // 路径节点之后的无正文空壳兄弟（测高残留/占位条）；SECTION（真消息）与
            // zcode 自家节点、有正文的节点绝不动。
            // v72：空壳判断 textContent → innerText——textContent 不看渲染，display:none
            // 的 dock 子树文字也算"有正文"，包着隐形 dock 的占位壳永远漏判；innerText
            // 按渲染取文，隐形即空壳
            var child = lastSec;
            var anc = lastSec.parentElement;
            while (anc && phantom > 12) {
              var pbc = parseFloat(getComputedStyle(anc).paddingBottom) || 0;
              if (pbc > 4 && pbc <= phantom + 12) {
                tailFixed.push({ el: anc, prop: 'paddingBottom', val: anc.style.paddingBottom });
                anc.style.paddingBottom = '0px';
                phantom -= pbc; changed = true; uiLog('tail-pad');
              }
              var kids = anc.children, after = false;
              for (var k = 0; k < kids.length; k++) {
                var ke = kids[k];
                if (ke === child) { after = true; continue; }
                if (!after || ke.tagName === 'SECTION' || (ke.id && ke.id.indexOf('zcode-') === 0)) { continue; }
                if (dock && ke.contains && ke.contains(dock)) { continue; }   // v72：dock 壳交给外底带逻辑
                var kt = '';
                try { kt = (ke.innerText === undefined ? ke.textContent : ke.innerText) || ''; } catch (eI) { kt = ke.textContent || ''; }
                if ((kt || '').replace(/\s+/g, '').length) { continue; }   // 渲染出文字=真内容，绝不动
                var kh = ke.offsetHeight;
                if (kh > 4 && kh <= phantom + 12) {
                  tailFixed.push({ el: ke, prop: 'display', val: ke.style.display });
                  ke.style.display = 'none';
                  phantom -= kh; changed = true; uiLog('tail-el');
                }
              }
              if (anc === tl) { break; }
              child = anc;
              anc = anc.parentElement;
            }
          }
        }
        // ---------- 容器外底带（v72） ----------
        // 实机"收纳态底部仍有 ~70px 空带"的另一半：phantom 只量得到 tl 内部，tl 视口
        // 底缘到屏底的占位（dock 槽位圈的壳）在容器外。收纳态（float-on 且未唤出，
        // 图标/胶囊全 fixed 不占流）直接量 outerGap = 屏底 − tl 底缘，>12px 才动外层，
        // 从 tl 父层起最多上探三层：
        // · 每层收 padding-bottom；
        // · tl 之后的无壳兄弟（innerText 空壳 + 高度闸门 ≤ gap+12）display:none；
        // · 包着 dock 的壳只收自身 padding-bottom / min-height，绝不 display:none
        //   ——popup-mode 靠 dock 复活，祖先 display:none 连 fixed 弹窗一起埋掉。
        // 一层没有收出任何变化就停（再往上是页面主干，不该由底部清理去动）。
        // float-on 关闭（空会话原生输入框在底部）时外底带就是 dock 本尊，绝不能碰。
        if (doc.documentElement.classList.contains('zcode-float-on')) {
          var oChild = tl;
          var oAnc = tl.parentElement;
          for (var oLvl = 0; oAnc && oLvl < 3; oLvl++) {
            var oGap = window.innerHeight - tl.getBoundingClientRect().bottom;
            if (oGap <= 12 || oGap > 240) { break; }
            var oMoved = false;
            var oPb = parseFloat(getComputedStyle(oAnc).paddingBottom) || 0;
            if (oPb > 4 && oPb <= oGap + 12) {
              tailFixed.push({ el: oAnc, prop: 'paddingBottom', val: oAnc.style.paddingBottom });
              oAnc.style.paddingBottom = '0px';
              oMoved = true; changed = true; uiLog('tail-pad-out');
            }
            var oKids = oAnc.children, oAfter = false;
            for (var ok = 0; ok < oKids.length; ok++) {
              var oe = oKids[ok];
              if (oe === oChild) { oAfter = true; continue; }
              if (!oAfter || oe.tagName === 'SECTION' || (oe.id && oe.id.indexOf('zcode-') === 0)) { continue; }
              var oh = oe.offsetHeight;
              if (oh <= 4 || oh > oGap + 12) { continue; }
              if (dock && oe.contains && oe.contains(dock)) {
                // dock 壳：只收自身的 padding-bottom / min-height（高度来源是自身
                // padding/min-height 时会随之塌掉），display 碰都不碰
                var opb = parseFloat(getComputedStyle(oe).paddingBottom) || 0;
                if (opb > 4 && opb <= oGap + 12) {
                  tailFixed.push({ el: oe, prop: 'paddingBottom', val: oe.style.paddingBottom });
                  oe.style.paddingBottom = '0px';
                  oMoved = true; changed = true; uiLog('tail-dockpad-out');
                }
                var omh = parseFloat(getComputedStyle(oe).minHeight) || 0;
                if (omh > 4 && omh <= oGap + 12) {
                  tailFixed.push({ el: oe, prop: 'minHeight', val: oe.style.minHeight });
                  oe.style.minHeight = '0px';
                  oMoved = true; changed = true; uiLog('tail-dockmin-out');
                }
                continue;
              }
              var ot = '';
              try { ot = (oe.innerText === undefined ? oe.textContent : oe.innerText) || ''; } catch (eO) { ot = oe.textContent || ''; }
              if ((ot || '').replace(/\s+/g, '').length) { continue; }   // 渲染出文字=真内容，绝不动
              tailFixed.push({ el: oe, prop: 'display', val: oe.style.display });
              oe.style.display = 'none';
              oMoved = true; changed = true; uiLog('tail-el-out');
            }
            if (!oMoved) { break; }
            oChild = oAnc;
            oAnc = oAnc.parentElement;
          }
        }
      } catch (eA) {}
      var want = null;
      var all = tl.querySelectorAll('section .min-h-5');
      if (all.length) {
        var last = all[all.length - 1];
        try {
          var cr2 = tl.getBoundingClientRect();
          var sb = last.getBoundingClientRect().bottom - cr2.top + tl.scrollTop;
          if (tl.scrollHeight - sb <= 60) { want = last; }   // 不在内容末尾的不动
        } catch (eB) {}
      }
      if (want !== tailSpacerEl) {
        if (tailSpacerEl) { tailSpacerEl.style.display = ''; }
        tailSpacerEl = want || null;
        if (tailSpacerEl) { tailSpacerEl.style.display = 'none'; }
        changed = true;
      }
      if (changed) { tailNudge(); }
    }
    // ---------- 虚拟列表空洞修补（v68b；v73 升级） ----------
    // 上滚/收纳翻转后，虚拟列表（@tanstack/react-virtual）按旧区间渲染：视口底部会留一段
    // 没有任何 section 覆盖的"空洞"（真机渲染节流下可持续数秒——用户实测"滚到上面
    // 底部还是有空白占位"）。v68b 的 1px 抖动×6 次对"区间算错型"空洞无效且耗尽后永久沉默。
    // v73 四点升级：
    // ① 探测点 x 取 30%/50%/70% 三点，任一命中内容即算覆盖——页面自带的"滚动到底部"
    //   圆钮悬在底部中央（28×28@171-199,742-770），单点探测会被它挡成假空洞；
    //   自家浮层（面板/遮罩/toast，id 以 zcode- 开头）盖住的点不算数也不算空洞；
    // ② 抖动升级三档：1px×2 →（无效）resize+scroll 双派发逼虚拟列表重测视口 rect →
    //   （仍无效）±8px 真位移——区间算错型空洞只有滚动位置真变才会触发重算；
    // ③ 不再 6 次后永久沉默：持续补到 24 拍（500ms 一拍），用户一亲手滚动就清零重来；
    //   自己抖动的回声（300ms 内）不当用户输入，绝不跟人抢滚动；
    // ④ 诊断卡 holeState：covered/gapPx（视口底到最低 section 底缘的实测距离）/tries/
    //   userScrollAgo——滚到上面复现空白后发一次诊断即可定位到档位。
    var holeTries = 0;
    var holeLast = 0;
    var holeCovered = -1;      // -1=本轮未判定 1=覆盖 0=空洞 2=自家浮层挡着（不定）
    var holeGapPx = -1;        // 视口底到最低已渲染 section 底缘的距离
    var holeLastAt = 0;
    var lastUserScrollAt = 0;
    var holeSelfAt = 0;        // 自己抖动 scrollTop 的时刻（区分回声与用户滚动）
    var holeTlEl = null;
    function holeProbePoint(fx){
      var el = null;
      try { el = doc.elementFromPoint(Math.round(window.innerWidth * fx), window.innerHeight - 2); } catch (e0) {}
      for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
        if (n.tagName === 'SECTION' || (n.getAttribute && (n.getAttribute('data-testid') || '').indexOf('v4-row') === 0)) { return 1; }
        if (n.id && n.id.indexOf('zcode-') === 0) { return 2; }
      }
      return 0;
    }
    function onHoleUserScroll(){
      if (Date.now() - holeSelfAt < 300) { return; }   // 自己抖的回声，不当用户输入
      lastUserScrollAt = Date.now();
      holeTries = 0;   // 用户亲手滚动 = 虚拟列表正被真实事件驱动，计数重来
    }
    function ensureHoleScrollListen(){
      var tl = root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl || tl === holeTlEl) { return; }
      if (holeTlEl) { try { holeTlEl.removeEventListener('scroll', onHoleUserScroll, true); } catch (e0) {} }
      holeTlEl = tl;
      try { tl.addEventListener('scroll', onHoleUserScroll, { passive: true, capture: true }); } catch (e1) {}
    }
    function fixViewportHole(){
      if (composerOpen || composerFull) { holeTries = 0; return; }
      var tl = holeTlEl || root.querySelector('[data-testid="v4-timeline"]') || root.querySelector('[data-testid="v4-timeline-scroll"]');
      if (!tl) { return; }
      var p1 = holeProbePoint(0.3), p2 = holeProbePoint(0.5), p3 = holeProbePoint(0.7);
      var covered = (p1 === 1 || p2 === 1 || p3 === 1);
      // 自家浮层盖住任一探测点（面板/遮罩开着）：不定态，不出手也不清零
      if (!covered && (p1 === 2 || p2 === 2 || p3 === 2)) {
        holeCovered = 2; holeLastAt = Date.now(); return;
      }
      holeCovered = covered ? 1 : 0;
      holeLastAt = Date.now();
      // 空洞高度取证：最低 section 底缘离视口底多远（无 section = -1）。
      // v76 瘦身：只在检出空洞（!covered，罕见路径）时才全量扫 section——covered 是
      // 常态，每秒白扫十几个 rect 纯为诊断数据；诊断卡改为手势时刻现算（04_theme）
      holeGapPx = -1;
      if (!covered) {
        try {
          var secs = tl.querySelectorAll('section');
          var low = -1;
          for (var si = 0; si < secs.length; si++) {
            var sb = secs[si].getBoundingClientRect().bottom;
            if (sb > low) { low = sb; }
          }
          if (low >= 0) { holeGapPx = Math.round(window.innerHeight - low); }
        } catch (eS) {}
      }
      var dist = tl.scrollHeight - tl.clientHeight - tl.scrollTop;
      if (covered || dist <= 40) { holeTries = 0; return; }
      // v71：尾部清理刚通知过虚拟列表重算，先让窗口落定再判定——两个修复器不抢同一帧
      if (Date.now() - tailNudgeAt < 600) { return; }
      var now = Date.now();
      if (now - holeLast > 500 && holeTries < 24) {
        holeTries++; holeLast = now;
        holeSelfAt = Date.now();
        if (holeTries <= 2) {
          if (tl.scrollTop > 0) { tl.scrollTop -= 1; }
          setTimeout(function(){ try { holeSelfAt = Date.now(); tl.scrollTop += 1; } catch (e1) {} }, 30);
          uiLog('hole-nudge');
        } else if (holeTries <= 6) {
          // 升级①：双派发——resize 逼虚拟列表重测滚动容器 rect（视口高度变化型空洞）
          try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
          try { tl.dispatchEvent(new Event('scroll')); } catch (e3) {}
          uiLog('hole-reshout');
        } else {
          // 升级②：真位移 ±8px——区间算错型空洞只有滚动位置真变才触发重算，
          // 异步弹回原位，视觉上是 8px 的一下轻颤
          if (tl.scrollTop > 8) { tl.scrollTop -= 8; }
          setTimeout(function(){ try { holeSelfAt = Date.now(); tl.scrollTop += 8; } catch (e4) {} }, 60);
          uiLog('hole-kick');
        }
      }
    }
    function holeBurst(){
      for (var i = 1; i <= 3; i++) { setTimeout(fixViewportHole, i * 400); }
    }
    function syncComposer(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      scanDockPopups();   // dock 内弹窗复活（不依赖打开状态）
      ensureDockSnapObs(dock);   // v55：dock 新增节点快照（弹窗结构事件捕获）
      if (composerFoundAt < 0) { composerFoundAt = Date.now() - injectAt2; }
      // 单击时 dock 还没渲染：轮询到后再自动打开（见 showComposer 的 pendingOpen）
      if (pendingOpen) { pendingOpen = false; showComposer(); return; }
      // 空对话（无消息）保留原输入框常驻，有了消息才收纳成左下角图标
      // 新 session 时 React 重挂载，无 v4-row → 输入框可见，符合"新对话不隐藏"的预期。
      // v71（实机"底部空白/填满一秒一变"）：hasRows 加迟滞——虚拟列表换窗/重渲染会瞬时
      // 卸载全部行，单次空 query 不再直接摘 zcode-float-on（摘了 dock 立刻弹回原生高度、
      // section padding 回弹，下轮行回来又收回去，正好一秒一闪）。有行立即确认；
      // 无行需连续两轮且不在展开/弹窗/动画态，才认定真的是空会话翻回常驻输入框。
      var wasFloat = doc.documentElement.classList.contains('zcode-float-on');
      var hasRows = root.querySelector('[data-testid^="v4-row"]');
      if (hasRows) {
        rowsEverSeen = true;
        missingRowsTicks = 0;
        setFloatState(true, 'rows');
      } else {
        var dockAnimating = dock.classList.contains('zcode-composer-morph') ||
          dock.classList.contains('zcode-composer-closing');
        if (!composerOpen && !dock.classList.contains('zcode-popup-mode') && !dockAnimating) {
          missingRowsTicks++;
          if (missingRowsTicks >= ROW_MISS_LIMIT) {
            rowsEverSeen = false;
            missingRowsTicks = 0;
            setFloatState(false, 'empty-session');
          }
        }
      }
      // v62：dock 隐藏后触发虚拟列表重算——zcode 的 message 虚拟列表按"dock 在底部"预留空间，
      // dock display:none 后预留的 ~121px 不会被回收（最后一条消息不下移，底部空一块）。
      // dispatch resize 让虚拟列表（@tanstack/react-virtual 监听视口变化）重新测量并填满。
      // v71：setFloatState 只在 class 真正翻转时返回 true，故这里每秒轮询不会重复 dispatch
      if (doc.documentElement.classList.contains('zcode-float-on') !== wasFloat) {
        try { window.dispatchEvent(new Event('resize')); } catch (e2) {}
        try {
          var tl = root.querySelector('[data-testid="v4-timeline"]');
          if (tl) { tl.dispatchEvent(new Event('scroll')); }
        } catch (e3) {}
      }
      // v65：记住上次的展开/收纳状态——上次没收起来就自动展开（不聚焦，键盘不突袭）
      if (pendingAutoOpen) {
        pendingAutoOpen = false;
        if (!composerOpen && hasRows) { showComposer(false); return; }
      }
      // 弧线图标随胶囊圆角半径动态生成；半径变了才重建（两条弧共存，状态用 class 切，无 innerHTML churn）
      var pill = dock.querySelector('.rounded-2xl');
      var radius = 16;
      if (pill) { try { radius = parseFloat(getComputedStyle(pill).borderTopRightRadius) || 16; } catch (e) {} }
      // 按钮挂进胶囊内部（.rounded-2xl 自身是 relative）：与胶囊同体移动，动画/重排期间零滞后。
      // 位置只由半径算，不再 getBoundingClientRect 测量（v52：v42 的"切换后定位"仍有窗口期滞后——
      // 打开瞬间读到的是动画起始位，要等 1s 轮询才纠正，表现为"滞后一瞬间才跟过去"）。
      var exp = pill ? pill.querySelector('#zcode-composer-expand') : null;
      if (pill && !exp) {
        exp = doc.createElement('div');
        exp.id = 'zcode-composer-expand';
        exp.setAttribute('role', 'button');
        exp.setAttribute('aria-label', composerFull ? '收起输入框' : '展开全屏输入框');
        exp._r = radius;
        exp.innerHTML = arcIconSvg(radius);
        // 关键：用 pointerup 触发，不要 onclick。移动端 touchstart preventDefault 会吞掉合成 click。
        // 根盒 pointer-events:none，事件来自弧线命中环（zc-hit）冒泡到这里。
        exp.addEventListener('touchstart', function(e){ e.stopPropagation(); }, {passive:true});
        exp.addEventListener('pointerup', function(e){ e.stopPropagation(); toggleFullComposer(); });
        // 防御：Tailwind relative 类若被 React 重挂载丢掉，inline 补上，保证 absolute 锚定胶囊
        try { pill.style.position = 'relative'; } catch (e) {}
        pill.appendChild(exp);
        positionExpand(exp, radius);
      } else if (exp && exp._r !== radius) {
        exp._r = radius;
        exp.innerHTML = arcIconSvg(radius);
        positionExpand(exp, radius);
      }
      // 状态互斥：zc-full 时隐藏外弧（扩大）、显示内弧（缩小）
      if (exp) {
        exp.classList.toggle('zc-full', composerFull);
        exp.setAttribute('aria-label', composerFull ? '收起输入框' : '展开全屏输入框');
      }
      // 把当前状态落到 live 节点（重挂载后 class 会丢，这里补回来）。必须先移除两个类
      // 再只加一个：如果只 add，从全屏缩回浮动时 zcode-composer-full 会残留，CSS 里
      // full 规则排在 float 之后且特异性相同 → full 永远赢 → 合不上（v38 修）。
      // v71：closing/morph 动画进行中不动状态类、不派发高度变化——1s 轮询若在动画窗口
      // 内重写会把过渡掐断成跳变（v69 只守了 closing，这里补齐 morph）；动画落定处的
      // 提交回调（morphMinimize/closing done）都会再调 syncComposer，在稳定帧补测
      var dockAnimating = dock.classList.contains('zcode-composer-closing') ||
        dock.classList.contains('zcode-composer-morph');
      if (!dockAnimating) {
        dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
        if (composerOpen) {
          dock.classList.add(composerFull ? 'zcode-composer-full' : 'zcode-composer-float');
        }
      }
      ensureIcon(dock);   // v66：收纳图标同步（悬浮层，跟随 float-on/open/popup 状态显隐 + 手势豁免上报）
      applySafeB();   // v70：底部安全区 → --zc-safe-b（图标/悬浮/全屏 bottom 引用）
      ensureHoleScrollListen();   // v73：滚动容器监听（用户亲手滚动 → 空洞计数清零；重挂载自动续接）
      // v72：悬浮/弹窗让位垫（见 needOverlayPad 注释）——收纳态先撤垫再测尾部，
      // 悬浮/弹窗态先尾部还原、后落垫
      var padNeed = needOverlayPad(dock);
      if (padNeed === 0) { clearOverlayPad(); }
      syncTailBlank(dock);   // v68/v69：收纳态底部残留逐一测量回收，末条消息贴屏底
      if (padNeed > 0) { setOverlayPad(padNeed); }
      // v66：dock 流内高度变化（收纳 0 ⇄ 唤出悬浮高 ⇄ 常驻 121）时通知虚拟列表重算，
      // 让位跟着新高度走，旧高度不会残留成底部空带。v71：动画进行中跳过——动画中的
      // 中间高度没有意义，落定后的 syncComposer 会补上这次测量与派发
      if (!dockAnimating) {
        var dh = dock.offsetHeight;
        if (dh !== lastDockH) {
          lastDockH = dh;
          try { window.dispatchEvent(new Event('resize')); } catch (e4) {}
          try {
            var tl2 = root.querySelector('[data-testid="v4-timeline"]');
            if (tl2) { tl2.dispatchEvent(new Event('scroll')); }
          } catch (e5) {}
        }
      }
      // v68b：收纳态视口底部空洞检测+抖动修补（上滚/翻转后虚拟列表没跟上时）
      fixViewportHole();
      // v79⑥：流式增长跟踪（阅读位置线的"正在流式"判据）+ 阅读线保洁
      trackStreamGrow();
      if (readLineEl && (!streamingNow() || !readLineEl.isConnected)) { removeReadLine(true); }
      applyKbLift();   // v79③：键盘实验模式——胶囊高度变化（多行）时重算抬升量
      // 按钮是胶囊的子节点，位置不随状态变化（只依赖半径），无需再定位
    }
    // 按钮中心对准胶囊右上角圆角圆心。按钮是胶囊的 absolute 子节点，所以只按半径算偏移，
    // 不读任何 rect —— 胶囊怎么动（打开/全屏/React 重挂）按钮就怎么跟，零测量零滞后（v52）
    function positionExpand(exp, radius){
      try {
        // 按钮盒 ≥ 弧线外径 + 命中环外延（radius+gap+9，与 arcIconSvg 的 half 一致），保证整条命中环都在盒内
        var size = Math.max(40, Math.ceil((radius + ARC_GAP + 9) * 2));
        exp.style.width = size + 'px';
        exp.style.height = size + 'px';
        // 圆心对齐：中心 = (胶囊右缘−radius, 胶囊上缘+radius) → 相对胶囊的 top/right 偏移 = radius − size/2
        var off = (radius - size / 2).toFixed(1);
        exp.style.top = off + 'px';
        exp.style.right = off + 'px';
      } catch (e2) {}
    }
    // ---------- 容器变换（container transform）----------
    // 图标⇄输入框、浮动⇄全屏都是"同一个元素连续变形"，不是各自出场/退场：
    // 收纳 = 输入胶囊原地缩进图标落位（transform-origin 钉在图标中心，圆角随行变圆、内容快速淡出），
    // 落点瞬间才提交收纳态，图标无入场动画、像素无缝衔接；唤出反向播放（先缩放在图标处再弹出）。
    // 全屏⇄浮动用 FLIP（旧 rect → 新 rect），内容淡入淡出掩盖拉伸。只动 transform/opacity，不触发布局。
    var morphTok = 0;
    function rectOf(el){
      var r = el.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height };
    }
    function iconHomeRect(){
      // 收纳图标悬浮位（CSS: left:12px bottom:12px+安全区，44px）
      return { x: 12, y: window.innerHeight - 56 - safeB(), w: 44, h: 44 };
    }
    // 底部安全区 → CSS 变量（v70）：原生层写 window.__zcodeSafeB，这里落到
    // documentElement 的 --zc-safe-b，fxStyle 里图标/悬浮/全屏/弹窗的 bottom 全部引用。
    // 挂 syncComposer（1s 轮询 + 状态切换），值变化才写；页面重载后首轮即生效。
    var lastSafeB = -1;
    function applySafeB(){
      var b = safeB();
      if (b === lastSafeB) { return; }
      lastSafeB = b;
      try { doc.documentElement.style.setProperty('--zc-safe-b', b + 'px'); } catch (e) {}
    }
    // ---------- v79③：键盘跟随·实验 ----------
    // 原生 exp 模式不缩放 WebView（页面零重排），键盘起/落各报一次最终高度（__zcKb）。
    // 这里换算"需要抬升的像素"：胶囊底缘越过键盘顶沿多少就抬多少，且不超过
    // (胶囊顶缘-8px)——全屏态胶囊很高时只抬差值，不顶出屏。CSS 过渡（02_style
    // zc-kb-on 规则）让抬升本身是动画；1s 轮询里补算（多行输入胶囊变高时重算）。
    var kbH = 0, kbLift = 0;
    var kbCalls = 0, kbLastAt = 0;   // v80③：桥接埋点——下次诊断分辨"原生没报"vs"报了没生效"
    function calcKbLift(){
      if (kbH <= 0 || !composerOpen) { return 0; }
      var dk = getDock();
      var pill = dk ? dk.querySelector('.rounded-2xl') : null;
      if (!pill || !pill.offsetWidth) { return 0; }
      var r = pill.getBoundingClientRect();
      var overflow = r.bottom - (window.innerHeight - kbH);
      if (overflow <= 0) { return 0; }
      return Math.max(0, Math.min(overflow, r.top - 8));
    }
    function applyKbLift(){
      var lift = calcKbLift();
      if (lift === kbLift && (kbH > 0) === doc.documentElement.classList.contains('zc-kb-on')) { return; }
      kbLift = lift;
      try {
        doc.documentElement.style.setProperty('--zc-kb-lift', lift + 'px');
        doc.documentElement.classList.toggle('zc-kb-on', kbH > 0);
      } catch (e) {}
    }
    function setKbH(h){
      kbH = (typeof h === 'number' && h > 0 && h < window.innerHeight * 0.9) ? h : 0;
      applyKbLift();
    }
    try {
      window.__zcKb = function(h){
        kbCalls++;
        kbLastAt = Date.now();
        setKbH(h);
      };
    } catch (eK0) {}
    // v80③ 兜底：实验模式下若 WebView 未缩放但 Chromium 仍更新 visualViewport（部分
    // 版本会），高度差直接当键盘高度用——原生桥（__zcKb）正常时两者一致，无害
    try {
      if (window.visualViewport && window.visualViewport.addEventListener) {
        window.visualViewport.addEventListener('resize', function(){
          if (!kbFollowExp || !composerOpen) { return; }
          var implied = Math.round(window.innerHeight - window.visualViewport.height);
          if (implied > 60 && Math.abs(implied - kbH) > 24) {
            kbCalls += 100;   // 埋点标记：这条来自 vv 兜底（≥100 即 vv 生效过）
            setKbH(implied);
          }
        });
      }
    } catch (eVV) {}
    function pillChildrenFade(pill, op, dur, delay){
      var kids = pill.children;
      for (var i = 0; i < kids.length; i++) {
        if (!kids[i].style) { continue; }
        kids[i].style.transition = dur ? ('opacity ' + dur + 's ease ' + (delay || 0) + 's') : '';
        kids[i].style.opacity = op;
      }
    }
    function pillMorphCleanup(dock, pill){
      dock.classList.remove('zcode-composer-morph');
      pill.style.transformOrigin = '';
      pill.style.transition = '';
      pill.style.transform = '';
      pill.style.borderRadius = '';
      pill.style.visibility = '';
      pillChildrenFade(pill, '', 0, 0);
    }
    function morphable(pill, dock){
      return ANIM_ON && !REDUCED && !!pill && !!dock &&
        !dock.classList.contains('zcode-popup-mode') && !dock.classList.contains('zcode-popup-present');
    }
    // v78 morph 幽灵替身：真实胶囊 visibility:hidden，替身（复刻胶囊的背景/描边/阴影/
    // 圆角）用 left/top/width/height 尺寸动画飞行。旧方案 transform:scale 非均匀缩放
    // （sx≠sy）会把圆角与描边粗细压扁拉长——飞行中途那帧"丑陋椭圆"就是它；尺寸动画
    // 的几何全程正确。落位（dur+40ms）换回真身并回调 settle；morphTok 防飞行中再触发
    // 的旧回调提交状态（新飞行接管一切）。
    function morphGhost(pill, from, to, dur, radiusEnd, settle){
      var cs;
      try { cs = getComputedStyle(pill); } catch (e0) { cs = null; }
      var g = doc.createElement('div');
      g.className = 'zcode-morph-ghost';
      g.style.left = from.x + 'px';
      g.style.top = from.y + 'px';
      g.style.width = Math.max(1, from.w) + 'px';
      g.style.height = Math.max(1, from.h) + 'px';
      if (cs) {
        g.style.background = cs.backgroundColor;
        g.style.border = cs.borderTopWidth + ' ' + cs.borderTopStyle + ' ' + cs.borderTopColor;
        g.style.boxShadow = cs.boxShadow;
      }
      g.style.borderRadius = (Math.abs(from.w - from.h) < 2 ? '50%' : (radiusEnd || '22px'));
      (doc.body || doc.documentElement).appendChild(g);
      pill.style.visibility = 'hidden';
      void g.offsetWidth;   // 起点帧先生效，否则 transition 不触发
      var ease = ' cubic-bezier(0.2,0.8,0.2,1)';
      g.style.transition = 'left ' + dur + 'ms' + ease + ',top ' + dur + 'ms' + ease +
        ',width ' + dur + 'ms' + ease + ',height ' + dur + 'ms' + ease +
        ',border-radius ' + dur + 'ms ease';
      g.style.left = to.x + 'px';
      g.style.top = to.y + 'px';
      g.style.width = Math.max(1, to.w) + 'px';
      g.style.height = Math.max(1, to.h) + 'px';
      g.style.borderRadius = radiusEnd || '22px';
      var tok = ++morphTok;
      setTimeout(function(){
        try { g.parentNode.removeChild(g); } catch (e1) {}
        if (tok !== morphTok) { return; }   // 已被新飞行接管，真身可见性归新飞行管
        try { pill.style.visibility = ''; } catch (e2) {}
        settle();
      }, dur + 40);
    }
    // 唤出：真身先就位（float 态）但隐藏，替身从图标位置长大到胶囊落位；
    // 落位后才落垫+状态同步（settle 回调），垫的 padding 过渡见 02_style
    function morphReveal(dock, pill, from, after){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pillChildrenFade(pill, '0', 0, 0);   // 内容随落位淡入，避免换回真身时瞬现
      var rad = '';
      try { rad = getComputedStyle(pill).borderRadius || ''; } catch (e0) {}
      morphGhost(pill, from, to, 280, rad, function(){
        pillMorphCleanup(dock, pill);
        pillChildrenFade(pill, '1', 0.12, 0);
        if (after) { after(); }
      });
    }
    // 收纳：替身原地缩进图标落位，落点瞬间提交收纳态（期间保持开态，避免轮询中途翻转）。
    // v69：拖动反馈的 dockDragTy 偏移并入替身起点，飞行从跟手位置继续
    function morphMinimize(dock, pill){
      var to = iconHomeRect();
      var from = rectOf(pill);
      from.y += (dockDragTy || 0);
      dock.classList.add('zcode-composer-morph');
      morphGhost(pill, from, to, 260, '50%', function () {
        composerOpen = false;
        composerFull = false;
        pendingOpen = false;
        minUpDist = 0;   // 收纳态重新计数"离开底部的距离"
        lastMinAt = Date.now();   // v69：手动收纳时刻（自动恢复的冷却起点）
        dockDragTy = 0;
        persistComposerState('min');
        hideMask();
        dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
        pillMorphCleanup(dock, pill);
        syncComposer();
        holeBurst();   // v68b：收纳翻转露出 121px 新视口，快速查几拍空洞
        uiLog('composer-hide');
      });
    }
    // 浮动⇄全屏：同一元素 FLIP（替身飞行，真身落位后现身）
    function morphFlip(dock, pill, from){
      var to = rectOf(pill);
      if (!(to.w > 0)) { return; }
      dock.classList.add('zcode-composer-morph');
      pillChildrenFade(pill, '0', 0, 0);   // 内容随落位淡入，避免换回真身时瞬现
      var rad = '';
      try { rad = getComputedStyle(pill).borderRadius || ''; } catch (e0) {}
      morphGhost(pill, from, to, 260, rad, function(){
        pillMorphCleanup(dock, pill);
        pillChildrenFade(pill, '1', 0.12, 0);
      });
    }
    // v79②：等 morph/closing 动画窗口过去再执行 fn（双击直进全屏要避开飞行窗口，
    // 两段 morph 的 morphTok 会互相顶掉对方的替身清理，漏出隐藏中的真身）
    function whenMorphDone(fn, tries){
      var dk = getDock();
      if (dk && (dk.classList.contains('zcode-composer-morph') || dk.classList.contains('zcode-composer-closing'))) {
        if ((tries || 0) < 14) { setTimeout(function(){ whenMorphDone(fn, (tries || 0) + 1); }, 60); }
        return;
      }
      fn();
    }
    // ---------- 左下角收纳图标（v66 悬浮化）：有消息未唤出时的唯一底栏元素 ----------
    // 挂在 body 层 fixed 悬浮（不占流内空间，消息列表直接铺到屏幕底部）；
    // dock 收纳态高度为 0（见 fxStyle 零占位规则），页面让位计算随之归零。
    // 点击/上滑 = 唤出输入框，右滑 = 对话问题导航（手势处理在文件头部，这里只建节点）。
    function ensureIcon(dock){
      var floatOn = doc.documentElement.classList.contains('zcode-float-on');
      var ic = doc.getElementById('zcode-composer-icon');
      if (!floatOn) {
        // 常驻态（空对话等）：页面输入框本来就在，图标没有存在意义
        if (ic && ic.parentNode) { ic.parentNode.removeChild(ic); }
        updateGestureExclude();
        return;
      }
      if (composerOpen || dock.classList.contains('zcode-popup-mode') ||
          dock.classList.contains('zcode-popup-present')) {
        // v79②：双击宽限——图标单击唤出后 520ms 内改"隐形但可接"（opacity:0 仍收触摸，
        // display:none 会收不到第二击），morph 替身从图标位起飞的同时图标淡出，无双影；
        // 宽限窗外维持原瞬时隐藏。窗口=双击判定 500ms + 20ms 余量
        if (composerOpen && lastIconTapAt > 0 && Date.now() - lastIconTapAt < 520 && ic) {
          ic.style.opacity = '0';
          updateGestureExclude();
          return;
        }
        // 唤出态/弹窗态藏图标：与输入框的切换由容器变换负责，这里瞬时切换
        if (ic) { ic.style.display = 'none'; ic.style.opacity = ''; }
        updateGestureExclude();
        return;
      }
      if (!ic) {
        ic = doc.createElement('div');
        ic.id = 'zcode-composer-icon';
        ic.setAttribute('role', 'button');
        ic.setAttribute('aria-label', '展开输入框');
        ic.textContent = '✎';
        ic.addEventListener('touchstart', iconStart, {passive:true});
        ic.addEventListener('touchmove', iconMove, {passive:false});
        ic.addEventListener('touchend', iconEnd, {passive:false});
        ic.addEventListener('touchcancel', function(){ iconTrack = null; }, {passive:true});
        ic.addEventListener('pointerdown', iconPtrDown, {passive:true});
        ic.addEventListener('pointermove', iconPtrMove, {passive:true});
        ic.addEventListener('pointerup', iconPtrUp, {passive:false});
        ic.addEventListener('pointercancel', function(){ iconTrack = null; }, {passive:true});
        // fixed 定位必须挂在最外层，避免 dock 祖先的 transform 把它锚进局部坐标系
        (isShadow ? root : doc.body || doc.documentElement).appendChild(ic);
      }
      ic.style.display = 'flex';
      ic.style.opacity = '';   // v79②：清掉双击宽限期的隐形
      ic.style.transform = '';   // v69：清掉上次手势的跟手偏移，图标每次复出都从原位开始
      updateGestureExclude();
    }
    // 开关入口：开 → 收纳，关 → 唤出（保留旧名，预览页/诊断仍在用）
    function toggleComposer(){
      // v59：弹窗挂起（popup-mode）时不切换——dock 会在 popup-mode（全宽 100%）
      // 与 float（94vw/520px 收窄）两种布局间跳变，提问框跟着缩放抖动（用户实测"点按钮提问框变一下大小"）
      var dd = getDock();
      if (dd && dd.classList.contains('zcode-popup-mode')) { return; }
      if (composerOpen) { hideComposer(); }
      else { showComposer(); }
    }
    function showComposer(autofocus){
      var dock = getDock();
      if (!dock) {
        // dock 还在渲染中：标记待开，1s 轮询到位后自动弹出（不给用户静默无反馈）
        pendingOpen = true;
        hint('输入框还没就绪，稍候自动弹出');
        return;
      }
      pendingOpen = false;
      if (composerOpen) { return; }
      composerOpen = true;
      // v72：垫上让位垫之前记住是否在底部——垫上后把内容顶到垫上方，
      // 否则开输入框的瞬间最后一条消息就沉到胶囊底下
      var tlKeep = overlayTl();
      var wasBottom = false;
      if (tlKeep) { try { wasBottom = tlKeep.scrollHeight - tlKeep.scrollTop - tlKeep.clientHeight < 160; } catch (eK) {} }
      minUpDist = 0;
      persistComposerState('open');
      dock.classList.add('zcode-composer-float');
      uiLog('composer-open fixed');
      showMask();
      // 容器变换起点：图标当前位置（v69 修正：图标 v66 起挂 body 层悬浮，dock.querySelector
      // 永远找不到、一直走标准落位分支——拖动偏移后 morph 起点会差一截；改全局取，含偏移）
      var pill0 = dock.querySelector('.rounded-2xl');
      var ic0 = doc.getElementById('zcode-composer-icon');
      var from = (ic0 && ic0.style.display !== 'none' && ic0.offsetWidth > 0) ? rectOf(ic0) : iconHomeRect();
      var pill = dock.querySelector('.rounded-2xl');
      var willMorph = morphable(pill, dock);
      // v78：飞行期间不落垫不抢同步——真身隐藏中，布局变化会穿帮；垫与状态同步挪到
      // morphReveal 落位回调，tl 的 padding 过渡（02_style）让"列表让位"成为连续滑动。
      // 保底滚动与替身飞行并行（同为缓动曲线，同向运动）；目标算在垫之前，末条恰好
      // 停在（即将到位的）胶囊上沿。非动画路径保持原即时行为。
      if (!willMorph) { syncComposer(); }
      if (wasBottom && tlKeep) {
        try { tlKeep.scrollTo({ top: tlKeep.scrollHeight, behavior: willMorph ? 'smooth' : 'auto' }); }
        catch (eK2) { try { tlKeep.scrollTop = tlKeep.scrollHeight; } catch (eK3) {} }
      }
            if (willMorph) { morphReveal(dock, pill, from, function(){ syncComposer(); }); }
      // v77：默认只弹胶囊不拉键盘。用户实测"弹出输入框+键盘同时起来远远不够平滑"——
      // 胶囊 morph、让位垫、滚动补偿、IME 逐帧 resize 四件事叠在同一瞬间；键盘改为
      // 用户亲手点输入框才起来（原生 focus 由点击触发，系统动画跟手）。
      // 参数反转：旧签名 focus!==false 就聚焦；新签名只有显式传 true 才聚焦——
      // 现无调用方传 true，留作以后"打开时自动弹键盘"设置项的钩子。
      if (autofocus === true) {
        try {
          var ta = dock.querySelector('[data-testid^="v4-composer-input"], textarea, input[type="text"]');
          if (ta) { setTimeout(function(){ try { ta.focus(); } catch (e2) {} }, 360); }
        } catch (e) {}
      }
    }
    function toggleFullComposer(){
      var dock = getDock();
      if (!dock) { return; }
      var pill = dock.querySelector('.rounded-2xl');
      var from = morphable(pill, dock) ? rectOf(pill) : null;
      composerFull = !composerFull;
      uiLog(composerFull ? 'composer-full' : 'composer-shrink');
      syncComposer();
      if (from && pill && pill.offsetWidth > 0) { morphFlip(dock, pill, from); }
    }
    function hideComposer(){
      var dock = getDock();
      var pill = dock ? dock.querySelector('.rounded-2xl') : null;
      // 容器变换：胶囊原地缩进图标，落点瞬间才提交收纳态（全屏态先经收起钮回浮动，不走这条）
      if (dock && morphable(pill, dock) && !dock.classList.contains('zcode-composer-full')) {
        morphMinimize(dock, pill);
        return;
      }
      composerOpen = false;
      composerFull = false;
      pendingOpen = false;
      minUpDist = 0;
      lastMinAt = Date.now();   // v69：手动收纳时刻（自动恢复的冷却起点）
      dockDragTy = 0;
      persistComposerState('min');
      hideMask();
      if (dock) {
        if (ANIM_ON && !REDUCED) {
          // 收起动画：先加 closing（淡出下移），animationend 后移除状态类（动画事件兜底 600ms）
          dock.classList.add('zcode-composer-closing');
          var done = function(){
            dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
            syncComposer();   // 立刻恢复收纳图标，不等 1s 轮询
          };
          dock.addEventListener('animationend', done, {once: true});
          setTimeout(done, 600);
        } else {
          dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
          syncComposer();   // 同上
        }
      }
      uiLog('composer-hide');
    }

    // ---------- 兜底手势：左缘上滑×3 + 右缘下滑×3（组合手势，诊断入口，不在任何 UI 文案里暴露） ----------
    var track = null, swipes = [], lastTrigger = 0;
    var EDGE = 44, SWIPE_MIN = 46, SWIPE_MAX_MS = 450, WINDOW_MS = 3500, COOLDOWN = 2000;
    function onStart(e){
      var t = e.touches[0];
      var vw = window.innerWidth;
      var side = t.clientX < EDGE ? 'L' : (t.clientX > vw - EDGE ? 'R' : null);
      if (!side) { track = null; return; }
      track = { side: side, x0: t.clientX, y0: t.clientY, t0: Date.now() };
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
      var side = track.side;
      track = null;
      var dir = dy < 0 ? 'up' : 'down';
      // 左侧只认上滑、右侧只认下滑，其余方向直接忽略
      if ((side === 'L' && dir !== 'up') || (side === 'R' && dir !== 'down')) { return; }
      if (Math.abs(dy) < SWIPE_MIN || dt > SWIPE_MAX_MS) { return; }
      var now = Date.now();
      swipes.push({ t: now, side: side, dir: dir });
      swipes = swipes.filter(function(s){ return now - s.t <= WINDOW_MS; });
      var lu = 0, rd = 0;
      for (var i = 0; i < swipes.length; i++) {
        if (swipes[i].side === 'L' && swipes[i].dir === 'up') { lu++; }
        else if (swipes[i].side === 'R' && swipes[i].dir === 'down') { rd++; }
      }
      if (lu < 3 || rd < 3) { return; }
      swipes = [];
      if (now - lastTrigger < COOLDOWN) { return; }
      lastTrigger = now;
      vibrate();
      uiLog('combo-swipe-diag');
      showDiagCard();
    }
    evRoot.addEventListener('touchstart', onStart, {passive:true});
    evRoot.addEventListener('touchmove', onMove, {passive:true});
    evRoot.addEventListener('touchend', onEnd, {passive:true});

    evRoot.addEventListener('touchmove', dragMove, {passive:false});
    evRoot.addEventListener('touchend', dragEnd, {passive:true});

    // ---------- 浮动输入：渲染时序 + 重挂载兜底 ----------
    // syncComposer 每次都重新查 live dock、补 expand 按钮、把 open/full 状态落到当前节点。
    // 必须持续轮询（不能"找到就停"）：React 重渲染会换掉 dock 节点，缓存引用会失效，
    // 持续轮询才能让状态始终跟着 live 节点走（v36 的 hasExpand=0、open=1 但无效果就是这个坑）。
    // UI 总开关关闭时不注入（纯网页）；applySettings 重新打开时再启轮询（composerTimer）。
    var composerTimer = null;
    var bodyPopupTimer = null;   // v53：body 弹窗检测（Radix 菜单让位）
    if (floatInput && UI_ON) {
      composerTimer = setInterval(syncComposer, 1000);
      bodyPopupTimer = setInterval(scanBodyPopups, 2000);   // v76: 即时性交给 bodyPopupObs，此处仅安全网;
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
    function removeIcon(){
      // 关闭浮动输入时连悬浮图标一起摘掉（轮询已停，没人替我们清理），并解除系统手势豁免
      var ic = doc.getElementById('zcode-composer-icon');
      if (ic && ic.parentNode) { ic.parentNode.removeChild(ic); }
      try {
        var br = bridge();
        if (br && br.setGestureExclude) { br.setGestureExclude(-1, -1, -1, -1); }
      } catch (e) {}
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
      // 浮动输入：关闭时移除图标并恢复常驻底部输入框；开启时重启轮询（图标由 syncComposer 重建）
      if (nFloat !== floatInput) {
        floatInput = nFloat;
        UI_ON = nFloat;
        if (UI_ON) {
          ensureFxStyle();
          if (!composerTimer) { composerTimer = setInterval(syncComposer, 1000); }
          if (!bodyPopupTimer) { bodyPopupTimer = setInterval(scanBodyPopups, 2000); }   // v76: 即时性交给 bodyPopupObs，此处仅安全网
        } else {
          removeIcon();
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
      // 滑到底自动恢复输入框
      if (o.restoreOnBottom !== undefined) { restoreOnBottom = (o.restoreOnBottom !== false); }
      // v79 新开关（同步进 window.__zcSettings，页面重载后的初始值也走它）
      // v80：⑧长按菜单已删除，longPressMenu 键退役（旧存档多出的键无害）
      if (o.sendMinimize !== undefined) { sendMinimize = (o.sendMinimize !== false); ZSET.sendMinimize = sendMinimize; }
      if (o.kbFollowExp !== undefined) { kbFollowExp = (o.kbFollowExp === true); ZSET.kbFollowExp = kbFollowExp; }
      if (o.amoledBlack !== undefined) { amoledBlack = (o.amoledBlack === true); ZSET.amoledBlack = amoledBlack; applyAmoled(); }
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
""" +
"""      var n = parseInt(rid, 10);
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
      if (el.id === 'zcode-fallback-nav' || el.id === 'zcode-nav-hint' || el.id === 'zcode-nav-preview' || el.id === 'zcode-diag-card') { return true; }
      if (el.closest && el.closest('#zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return true; }
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
"""
}

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var settings: SettingsStore
    private lateinit var replyDedupe: ReplyDedupe
    private var currentUrl: String = ""
    private var appVisible = false
    private var notifSeq = 0
    private var pausedForSettings = false
    /** 上次注入页面的会话页设置快照（设置页返回时对比，变化即热更新，null = 还没注入过） */
    private var applied: PageSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        // 边到边（v70，实机"消息列表底部有空带"的原生侧根因）：旧版根布局
        // fitsSystemWindows=true 把系统导航栏整段垫在 WebView 下面（窗口底色空带，
        // JS 怎么修都够不着）。改为：内容铺到物理屏底，顶部状态栏垫高保留；
        // 底部 inset 不占 padding，换算成 CSS px 上报 JS（window.__zcodeSafeB），
        // 注入层的图标/悬浮输入/全屏输入按它抬升、不压手势条。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            // 透明系统栏在 29+ 默认被强加半透明对比遮罩，关掉才是真透明
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // v72（实机"输入法完全遮住输入框"）：setDecorFitsSystemWindows(false) 之后
        // manifest 的 adjustResize 不再自动缩放窗口（v70 注释的假设是错的）——键盘期
        // 由本监听把 IME inset 垫进 content 底部，WebView 随之缩到键盘上方；键盘收起
        // 回到 0（底部安全区仍走 safeB 桥）。键盘期 safeB 归零：WebView 底缘已落在
        // 键盘顶上，没有再要避让的系统条，图标/胶囊不用二次抬升。
        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // v79③ 键盘跟随·实验：开关开启时不把 IME 垫进 content（WebView 不缩放，页面
            // 零重排、虚拟列表不再逐帧重算），只把键盘最终高度报给 JS（__zcKb），胶囊由
            // CSS 过渡贴键盘顶沿升降。实验关闭时维持 v72 行为（本监听垫高）
            val kbExp = ::settings.isInitialized && settings.kbFollowExp
            v.setPadding(bars.left, bars.top, bars.right, if (kbExp) 0 else ime)
            if (kbExp) reportKb(ime)
            reportSafeB(if (ime > 0) 0 else bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // 键盘起落动画期同步垫高：不加这段 padding 要等动画结束的最终分发才跳变，
        // 页面会先被键盘盖一瞬再弹起（v79 实验模式同样跳过 ime 垫高，键盘期页面不缩放）
        ViewCompat.setWindowInsetsAnimationCallback(
            contentView,
            object : WindowInsetsAnimationCompat.Callback(
                // 项目的 androidx.core 版本没把这组常量挂到 Compat 上，借平台常量
                //（static final int 编译期内联，<30 的设备不会加载该类）
                android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    running: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val imeNow = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val kbExpNow = ::settings.isInitialized && settings.kbFollowExp
                    if (kbExpNow) {
                        // v80③：实验模式在动画期也报高度——实机"键盘盖住输入框"排查：
                        // 仅靠 onApply 报终值若哪一环没送到（分发时机/旧值），JS 全程
                        // 不知道键盘来了。动画期按 ≥8px 步进补报，CSS 过渡自动平滑追
                        reportKb(imeNow)
                    } else {
                        contentView.setPadding(
                            contentView.paddingLeft, contentView.paddingTop,
                            contentView.paddingRight, imeNow,
                        )
                    }
                    return insets
                }
            },
        )
        // debug 构建允许 WebView 远程调试：电脑 Chrome 打开 chrome://inspect 可直接
        // F12 式检查注入页面 DOM（定位弹窗/布局问题必备；release 构建自动关闭）
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        settings = SettingsStore(this)
        replyDedupe = ReplyDedupe(this)
        // 初始：透明系统栏 + 按页面主题切图标明暗（不写死颜色，等 JS 边缘采样回报）
        val initialDark = settings.pageThemeDark ?: SystemBars.isDarkSystem(this)
        SystemBars.initTransparent(this, initialDark)

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (currentUrl.isEmpty()) {
            finish()
            return
        }

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener { finish() }

        setupWebView()
        setupBackHandling()

        if (savedInstanceState != null) {
            // restoreState 经常只还回空白页，失败时按原链接重载（链接即凭证，重载即重连）
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) webView.loadUrl(currentUrl)
        } else {
            webView.loadUrl(currentUrl)
        }
    }

    private fun setupWebView() {
        webView.setBackgroundColor(BG)
        // 原生滚动条也用覆盖式渐隐，不挤压网页布局
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.isScrollbarFadingEnabled = true

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true // 远控页用了 sessionStorage，必须开
        s.databaseEnabled = true
        // 远控页每次全新加载：覆盖安装/更新后不会用到 WebView 残留缓存（页面结构变了旧缓存会出怪问题）
        s.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.textZoom = settings.textZoom
        s.setSupportMultipleWindows(false)
        s.setGeolocationEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)

        // 页面 JS 通过该桥上报主题与"新回复"，原生层决定系统栏配色与通知
        webView.addJavascriptInterface(AppBridge(currentUrl), REPLY_BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                val host = uri.host.orEmpty()
                val isHttp = uri.scheme == "https" || uri.scheme == "http"
                val isZai = host == "z.ai" || host.endsWith(".z.ai")
                if (isHttp && isZai) return false
                if (!isHttp) {
                    // 非 http/https scheme（tel:/mailto:/intent: 等）一律拦截：
                    // 不无确认拉起外部应用，避免页面里藏着的链接直接跳系统
                    toast(R.string.link_blocked)
                    return true
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: ActivityNotFoundException) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                errorView.visibility = View.GONE
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                injectMobileFix()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    errorText.text = getString(
                        R.string.load_error,
                        error.description?.toString().orEmpty(),
                    )
                    errorView.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel()
                errorText.text = getString(R.string.ssl_error)
                errorView.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }
        }
    }

    /** 底部安全区（系统导航栏高度，CSS px）→ JS。注入层据此抬升图标/悬浮输入/全屏输入。
     *  只在值变化时写；页面加载时机早于/晚于注入都可能，所以 injectMobileFix 的注入串里
     *  也会带上当前值（页面重载后 window 属性被清空，必须随 bundle 重新写入）。 */
    private var lastSafeB = 0

    private fun reportSafeB(bottomPx: Int) {
        val css = (bottomPx / resources.displayMetrics.density).toInt()
        if (css == lastSafeB) return
        lastSafeB = css
        if (::webView.isInitialized && webView.url != null) {
            webView.evaluateJavascript("window.__zcodeSafeB=$css;void 0", null)
        }
    }

    /** v79③ 键盘跟随·实验：IME 高度（CSS px）→ JS（__zcKb）。onApply 报动画终值，
     *  v80 起 onProgress 动画期按步进补报（终值为主、中间值为保险——任一环送达即可）。
     *  升降动画仍由页面侧 CSS 过渡完成。只在值跨 ≥8px 步进或归零时桥，不做逐帧。 */
    private var lastKbH = -1

    private fun reportKb(bottomPx: Int) {
        if (!::settings.isInitialized || !::webView.isInitialized || webView.url == null) return
        val css = (bottomPx / resources.displayMetrics.density).toInt()
        if (css == lastKbH) return
        if (lastKbH >= 0 && css > 0 && kotlin.math.abs(css - lastKbH) < 8) return   // 动画小步不桥
        lastKbH = css
        webView.evaluateJavascript("window.__zcKb&&window.__zcKb($css);void 0", null)
    }

    /** 注入广播式修正包：样式 + 巡检器 + 导航手势 + 主题回报 + 回复监听（一次注入，广播到所有同源文档）。 */
    private fun injectMobileFix() {
        val css = StringBuilder(MOBILE_CSS)
        css.append(if (settings.hideScrollbar) CSS_SCROLLBAR_HIDDEN else CSS_SCROLLBAR_SLIM)
        // v70：先写 safeB 再执行 bundle；BODY 内首轮 syncComposer 会立即落 CSS 变量，
        // 不等 1s 轮询，避免真机首帧图标/输入框先贴到系统手势条再跳一下。
        // v79：会话页新开关走 window.__zcSettings（bundle 读它做初始值，applySettings 热更）
        // v80：⑧长按菜单已删除（longPressMenu 键退役，旧页面存档多出的键无害）
        val js = "window.__zcodeSafeB=" + lastSafeB + ";" +
            "window.__zcSettings={" +
            "\"sendMinimize\":" + settings.sendMinimize + "," +
            "\"kbFollowExp\":" + settings.kbFollowExp + "," +
            "\"amoledBlack\":" + settings.amoledBlack + "};" +
            "(" + BUNDLE_JS.trim() + ")(" +
            JSONObject.quote(css.toString()) + ", " +
            settings.turnNavigator + ", " +
            settings.notifyReply + ", " +
            settings.floatingInput + ", " +
            "true);'__zcode_ok'"   // uiAnim 固定开启（设置项已移除，v43）
        // 哨兵回调：注入失败（语法/运行异常）不再静默，记日志供诊断
        webView.evaluateJavascript(js) { result ->
            if (result == null || result != "\"__zcode_ok\"") {
                Log.w(TAG, "injectMobileFix: bundle did not run cleanly (result=$result)")
            }
        }
        applied = settings.pageSnapshot()
    }

    /** 设置即时生效：设置页返回时对比上次注入的快照，有变化就通过 __zcodeAPI.applySettings 热更新页面。 */
    private fun applySettingsIfChanged() {
        if (!::webView.isInitialized || webView.url == null) return
        val s = settings.pageSnapshot()
        if (applied == s) return
        applied = s
        val js = "window.__zcodeAPI && window.__zcodeAPI.applySettings({" +
            "\"scrollbar\":" + s.hideScrollbar + "," +
            "\"turnNav\":" + s.turnNavigator + "," +
            "\"notifyReply\":" + s.notifyReply + "," +
            "\"floatInput\":" + s.floatingInput + "," +
            "\"restoreOnBottom\":" + s.restoreOnBottom + "," +
            "\"sendMinimize\":" + s.sendMinimize + "," +
            "\"kbFollowExp\":" + s.kbFollowExp + "," +
            "\"amoledBlack\":" + s.amoledBlack + "})"
        webView.evaluateJavascript(js, null)
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：保活任务取消，会话由真实界面接管；同时清掉保活通知里的回复预览
        KeepAliveService.stop(this)
        KeepAliveService.clearReplyPreview(this)
    }

    override fun onResume() {
        super.onResume()
        appVisible = true
        pausedForSettings = false
        // 从设置页返回：对比设置快照，变化则热更新页面（不重进会话）
        applySettingsIfChanged()
    }

    override fun onPause() {
        super.onPause()
        appVisible = false
        // 必须在仍处前台的 onPause 阶段启动前台服务（Android 12+ 禁止后台启动 FGS）；
        // 从会话内打开设置页不算"切出"，不启动保活，避免通知栏闪烁
        if (!pausedForSettings && !isFinishing && settings.keepAliveMinutes > 0) {
            KeepAliveService.start(this, settings.keepAliveMinutes, currentUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        KeepAliveService.stop(this)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    /** JS → 原生桥：页面上报主题（同步入口页）与就近取色（顶/底栏各自变色）、新回复。 */
    inner class AppBridge(private val url: String) {
        @JavascriptInterface
        fun onReply(text: String) {
            runOnUiThread {
                // 埋点：JS 检测到回复（诊断链路用）
                recordReplyTrace("onReply", text, "appVisible=$appVisible")
                // 去重：7 天窗口内出现过的内容不再重复通知
                if (replyDedupe.markSeen(text)) {
                    recordReplyTrace("dedup", text, "skipped")
                    return@runOnUiThread
                }
                // 统一走标准横幅通知：前台弹横幅，后台进通知栏
                showReplyNotification(url, text)
                recordReplyTrace("notified", text, "posted")
            }
        }

        @JavascriptInterface
        fun onProgress(text: String) {
            // 中间进度：只滚动更新保活通知预览，不弹横幅（最终横幅由 onReply 负责）
            KeepAliveService.updateReplyPreview(this@WebActivity, text)
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread {
                // 会话内直接打开设置页；返回时任务栈还在，会话不重载
                pausedForSettings = true
                try {
                    startActivity(Intent(this@WebActivity, SettingsActivity::class.java))
                } catch (e: Exception) {
                    pausedForSettings = false
                }
            }
        }

        @JavascriptInterface
        fun saveLogoPos(fx: Float, fy: Float) {
            runOnUiThread {
                settings.logoPosSet = true
                settings.logoX = fx
                settings.logoY = fy
            }
        }

        @JavascriptInterface
        fun getLogoPos(): String {
            // 未拖过返回空串，JS 端按页面头部自动定位
            return if (settings.logoPosSet) {
                settings.logoX.toString() + "," + settings.logoY.toString()
            } else {
                ""
            }
        }

        @JavascriptInterface
        fun getTrace(): String {
            return getSharedPreferences(TRACE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TRACE, "[]") ?: "[]"
        }

        @JavascriptInterface
        fun onTheme(r: Int, g: Int, b: Int, dark: Boolean) {
            // 只记住页面深浅主题，供入口页/设置页跟随；系统栏颜色由 onBars 就近取色管
            runOnUiThread { settings.pageThemeDark = dark }
        }

        @JavascriptInterface
        fun onBars(
            tR: Int, tG: Int, tB: Int, tDark: Boolean,
            bR: Int, bG: Int, bB: Int, bDark: Boolean,
        ) {
            runOnUiThread {
                SystemBars.applyBars(this@WebActivity, tR, tG, tB, tDark, bR, bG, bB, bDark)
            }
        }

        /**
         * 系统手势豁免（Android 10+）：注入层的收纳图标贴左下角，右滑是"打开问题导航"的
         * 手势——不豁免会被系统返回手势截胡直接退出页面。JS 传 CSS 像素矩形（WebView 本地
         * 坐标即 CSS 视口坐标，密度换算在原生做）；负值 = 清除豁免。低版本静默忽略。
         */
        @JavascriptInterface
        fun setGestureExclude(left: Float, top: Float, right: Float, bottom: Float) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT < 29) return@runOnUiThread
                if (!::webView.isInitialized) return@runOnUiThread
                if (left < 0 || top < 0 || right <= 0 || bottom <= 0) {
                    webView.systemGestureExclusionRects = emptyList()
                } else {
                    val d = resources.displayMetrics.density
                    webView.systemGestureExclusionRects = listOf(
                        Rect(
                            (left * d).toInt(),
                            (top * d).toInt(),
                            (right * d).toInt() + 1,
                            (bottom * d).toInt() + 1,
                        ),
                    )
                }
            }
        }
    }

    private fun showReplyNotification(url: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    REPLY_CHANNEL,
                    getString(R.string.reply_notif_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, REPLY_CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(getString(R.string.reply_notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(2000 + (notifSeq++ % 20), n)
        // 保活通知同步带上最近回复预览（锁屏可见），服务未运行时仅存档
        KeepAliveService.updateReplyPreview(this, text)
    }

    /** 回复链路原生侧埋点（最近 6 条，持久化以支持 Activity 重建后读取，随诊断 JSON 上报）。 */
    private fun recordReplyTrace(ev: String, text: String, note: String) {
        val prefs = getSharedPreferences(TRACE_PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_TRACE, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("ev", ev)
                .put("txt", text.take(40))
                .put("note", note),
        )
        while (arr.length() > 6) arr.remove(0)
        prefs.edit().putString(KEY_TRACE, arr.toString()).apply()
    }
}
