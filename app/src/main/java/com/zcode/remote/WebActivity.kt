package com.zcode.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
        // z-index 阶梯：999 真导航 / 99995 遮罩 / 99996 logo / 99997 面板 / 99998 悬浮输入+预览 / 99999 全屏输入+提示+诊断
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
        '@keyframes zcodeLogoIn{from{opacity:0;transform:scale(0.5) translateY(-6px)}to{opacity:1;transform:scale(1) translateY(0)}}' +
        // 浮动输入：默认隐藏（html 门控 class，诊断卡片可一键恢复），悬浮态/全屏态
        // 设计原则：dock 只负责"定位 + 抬起"，本身透明无边框；页面原生 .rounded-2xl 输入胶囊
        // （自带 bg+border+圆角）作为唯一视觉框架，避免双框/臃肿。v4-composer 那层不透明块需透明化。
        // 隐藏用 display:none 不占位（visibility 会留下底部空白，v46 改回）；
        // z.ai 的 ask/确认弹窗渲染在 dock 内 → 弹窗出现时临时显示 dock（zcode-popup-mode），
        // 只露出弹窗、隐藏输入部分，弹窗消失后自动收起。
        'html.zcode-float-on [data-v4-composer-dock="true"]{display:none!important}' +
        // 弹窗模式：dock 临时显示为底部容器（不遮挡全屏），输入部分隐藏，弹窗可见可点
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-popup-mode{' +
        'display:block!important;visibility:visible!important;pointer-events:auto!important;' +
        'position:fixed!important;left:50%!important;right:auto!important;bottom:0!important;top:auto!important;' +
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
        'bottom:14px!important;top:auto!important;transform:translateX(-50%)!important;' +
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
        'html.zcode-body-popup #zcode-logo{z-index:45!important;pointer-events:none!important}' +
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
        'padding:0 0 20px!important;background:rgba(0,0,0,0.55)!important;border:none!important;' +
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
        // 不加这条全屏只是"暗遮罩+底部小输入框"，v37 就是这个坑）。32vh 约 1/3 屏高。
        'html.zcode-float-on [data-v4-composer-dock="true"].zcode-composer-full [data-testid^="v4-composer-input"]{' +
        'min-height:32vh!important;max-height:none!important;height:32vh!important;font-size:16px!important;' +
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
      // 悬浮输入开着时上移，避免和输入胶囊重叠
      var up = (doc.documentElement.classList.contains('zcode-float-on') && getDock()) ? 128 : 88;
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

    // ---------- 点外部收起：半透明全屏遮罩（导航/浮动输入共用） ----------
    function showMask(){
      if (mask && mask.parentNode) { return; }
      mask = doc.createElement('div');
      mask.id = 'zcode-mask';   // v53：body 弹窗检测让位时需要能定位到遮罩
      mask.style.cssText = 'position:fixed;inset:0;z-index:99995;background:rgba(0,0,0,0);';
      mask.addEventListener('touchstart', function(e){
        e.preventDefault();
        closePanel();
        hideNav();
        hideComposer();
      }, {passive:false});
      (isShadow ? root : doc.body || doc.documentElement).appendChild(mask);
    }
    function hideMask(){
      if (mask && mask.parentNode) { mask.parentNode.removeChild(mask); }
      mask = null;
    }

    // ---------- 真导航展开/收起 ----------
    var SHOW_CSS = NAV_SEL + ' { width: auto !important; min-width: 220px !important; max-width: 78vw !important; ' +
      'pointer-events: auto !important; z-index: 999 !important; animation: zcodeFadeIn 0.18s ease-out !important; } ' +
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
      while (el && el !== navEl && (!el.textContent || !el.textContent.trim())) { el = el.parentElement; }
      if (!el || el === navEl || !navEl.contains(el)) { return null; }
      return el;
    }
    function previewText(el){
      return entryLabel(el, 180);
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
    // 只取气泡正文：克隆节点后隐藏行外的操作按钮（复制/编辑/反馈/分叉）再读 innerText
    // （innerText 会忽略 display:none 的元素，避免"复制 编辑"混进导航条目）。
    // 克隆读取绝不改 live DOM：改 display 再恢复 = 每行强制 reflow，且读取失败会让按钮永久隐藏。
    var TURN_NOISE_SEL = '[data-testid^="v4-copy-"], [data-testid^="v4-edit-"], ' +
      '[data-testid^="v4-feedback-"], [data-testid^="v4-fork-"]';
    function entryLabel(el, maxLen){
      var clone = el.cloneNode(true);
      var btns = clone.querySelectorAll ? clone.querySelectorAll(TURN_NOISE_SEL) : [];
      for (var i = 0; i < btns.length; i++) { btns[i].style.display = 'none'; }
      var txt = (clone.innerText || '').replace(/\s+/g, ' ').trim();
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
        hideNav();
        setLogoDim(false);
        return;
      }
      if (showNav()) {
        setLogoDim(true);
        hint('已唤出对话问题导航（点外部或下拉 logo 收起）');
        return;
      }
      if (panel) { closePanel(); return; }
      var turns = collectTurns();
      if (!turns.length) {
        hint('还没有可导航的消息，先去聊几句吧');
        return;
      }
      buildPanel(turns);
      vibrate();
      hint('已唤出问题导航（点外部收起）');
    }

    // ---------- 浮动 logo（替代旧的"左缘三次滑动"唤出方式） ----------
    // 半隐藏悬浮在右上角：单击=设置页；长按后拖动=移动位置（持久化）；
    // 下拉=对话问题导航；上滑=快速跳到最后一条消息。
    function setLogoDim(dim){
      if (!logoEl) { return; }
      // 只调透明度，绝不禁用触摸：禁用后只能靠再次触摸恢复，会永久死锁（logo 再也点不动）
      logoEl.style.opacity = dim ? '0.15' : '';
    }
    function jumpBottom(){
      var rows = root.querySelectorAll('[data-testid^="v4-row"]');
      var last = null;
      for (var i = 0; i < rows.length; i++) { last = rows[i]; }
      if (!last) { hint('还没有消息'); return; }
      jumpTo(last);
    }
    function createLogo(){
      var br = bridge();
      var saved = '';
      try { if (br && br.getLogoPos) { saved = String(br.getLogoPos()); } } catch (e) {}
      var vw = window.innerWidth, vh = window.innerHeight;
      var logoTop = 14, logoRight = 8;
      if (saved && saved.length > 2) {
        var sp = saved.split(',');
        var fx = parseFloat(sp[0]), fy = parseFloat(sp[1]);
        if (!isNaN(fx) && !isNaN(fy)) {
          logoRight = Math.max(6, Math.round(fx * vw));
          logoTop = Math.max(4, Math.round(fy * vh));
        }
      } else {
        // 从未拖过：贴页面头部下沿（页头本身已避让状态栏）
        try {
          var hdr = root.querySelector('[data-testid="workspace-header"]');
          if (hdr) {
            var hr = hdr.getBoundingClientRect();
            if (hr.bottom > 20) { logoTop = hr.bottom + 6; }
          }
        } catch (e) {}
      }
      var logo = doc.createElement('div');
      logo.id = 'zcode-logo';
      logo.textContent = 'Z';
      logo.style.cssText = 'position:fixed;z-index:99996;width:40px;height:40px;border-radius:50%;' +
        'background:var(--zc-hover-strong);' +
        '-webkit-backdrop-filter:blur(18px) saturate(1.4);backdrop-filter:blur(18px) saturate(1.4);' +
        'border:1px solid var(--zc-stroke-strong);' +
        'color:var(--zc-text-2);font-size:18px;font-weight:700;font-family:system-ui,-apple-system,sans-serif;' +
        'display:flex;align-items:center;justify-content:center;' +
        'box-shadow:0 4px 18px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.16);' +
        'user-select:none;-webkit-user-select:none;' +
        anim('zcodeLogoIn', '0.35s', 'cubic-bezier(0.2,0.8,0.2,1)') +
        (ANIM_ON && !REDUCED ? 'transition:opacity 0.15s, transform 0.12s;' : '') +
        'opacity:0.38;';
      logo.style.right = logoRight + 'px';
      logo.style.top = logoTop + 'px';
      logoEl = logo;
      (isShadow ? root : doc.body || doc.documentElement).appendChild(logo);

      var t0 = 0, x0 = 0, y0 = 0, longPress = false, dragging = false, pressTimer = null, tapTimer = null;
      logo.addEventListener('touchstart', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
        t0 = Date.now(); x0 = t.clientX; y0 = t.clientY;
        longPress = false; dragging = false;
        logo.style.opacity = '1';
        logo.style.transform = 'scale(0.92)';
        logo.style.transition = 'none';   // 拖动期间禁用 transform 过渡，避免跟手滞后
        clearTimeout(pressTimer);
        pressTimer = setTimeout(function(){
          longPress = true;
          vibrate();
          hint('拖动 logo 调整位置');
        }, 400);
      }, {passive:true});
      logo.addEventListener('touchmove', function(e){
        if (e.touches.length !== 1) { return; }
        var t = e.touches[0];
""" +
"""        var dx = t.clientX - x0, dy = t.clientY - y0;
        if (!longPress) {
          // 长按未生效前不进入拖动：移动超过阈值即取消长按，交给 touchend 判滑动方向
          if (Math.abs(dx) > 10 || Math.abs(dy) > 10) { clearTimeout(pressTimer); }
          return;
        }
        dragging = true;
        e.preventDefault();
        // 拖动只写 transform（不触发布局）；结束拖动时再提交为 left/top
        logo.style.right = 'auto';
        logo.style.transform = 'translate3d(' + (t.clientX - x0) + 'px,' + (t.clientY - y0) + 'px,0) scale(0.92)';
      }, {passive:false});
      logo.addEventListener('touchend', function(e){
        clearTimeout(pressTimer);
        if (dragging) {
          dragging = false;
          var rect = logo.getBoundingClientRect();
          var fx2 = (vw - (rect.left + rect.width)) / vw;
          var fy2 = rect.top / vh;
          // 提交位置：left/top 落位（此后拖动/单击都基于固定定位）
          logo.style.left = rect.left + 'px';
          logo.style.top = rect.top + 'px';
          logo.style.right = 'auto';
          logo.style.transform = '';
          logo.style.transition = '';
          try {
            var br2 = bridge();
            if (br2 && br2.saveLogoPos) { br2.saveLogoPos(fx2, fy2); }
          } catch (e2) {}
          hint('位置已保存');
          logo.style.opacity = ''; logo.style.transform = '';
          return;
        }
        var dt = Date.now() - t0;
        var t = e.changedTouches[0];
        var dx = t.clientX - x0, dy = t.clientY - y0;
        if (dt < 350 && Math.abs(dx) < 10 && Math.abs(dy) < 10) {
          // 单击 → 浮动输入框；300ms 内第二击 → 设置页（双击进入）
          uiLog('logo-tap');
          if (tapTimer) {
            clearTimeout(tapTimer);
            tapTimer = null;
            uiLog('logo-dbltap-settings');
            try {
              var br3 = bridge();
              if (br3 && br3.openSettings) { br3.openSettings(); }
            } catch (e3) {}
          } else {
            tapTimer = setTimeout(function(){
              tapTimer = null;
              if (floatInput) { toggleComposer(); }
              else { hint('输入框常驻底部，双击 logo 进设置'); }
            }, 300);
          }
          logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
          return;
        }
        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 40) {
          if (dy > 0) {
            // 下拉 → 对话问题导航
            uiLog('logo-swipedown');
            if (!navOn) { hint('对话问题导航已关闭'); }
            else { toggleNavigator(); }
          } else {
            // 上滑 → 快速到最后一条消息
            uiLog('logo-swipeup');
            jumpBottom();
          }
        }
        logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
      }, {passive:true});
      logo.addEventListener('touchcancel', function(){
        clearTimeout(pressTimer);
        longPress = false;
        dragging = false;
        logo.style.opacity = ''; logo.style.transform = ''; logo.style.transition = '';
      }, {passive:true});
    }
    // 界面增强总开关：关闭后不注入 logo/悬浮输入/导航面板等，保持纯网页观感
    if (UI_ON) {
      createLogo();
      syncComposer();
    }

    // ---------- 输入框劫持：默认隐藏底部输入框，单击 logo 唤出悬浮框，右上角 icon 可全屏 ----------
    // 关键设计：绝不缓存 dock 节点引用。React 重渲染会换掉节点，缓存引用会指向已脱离 DOM 的旧节点
    // → 加 class、挂 expand 按钮都打到旧节点上，live 节点上什么都没有（v36 的 hasExpand=0 就这病）。
    // 这里每次需要都重新 querySelector，并在 1s 轮询里把"展开/全屏"状态同步到 live 节点。
    var COMPOSER_SEL = '[data-v4-composer-dock="true"]';
    var composerOpen = false, composerFull = false, pendingOpen = false;   // pendingOpen: dock 未就绪时的待开标记
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
      '[data-elicitation-dialog-card], [data-elicitation-dialog-body]';
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
        // 我们自己的注入层不算（诊断卡/导航/logo/遮罩）；dock 内弹窗走 popup-mode 复活，也不算
        if (el.closest('#zcode-mask, #zcode-logo, #zcode-fallback-nav, #zcode-nav-hint, #zcode-nav-preview, #zcode-diag-card')) { return false; }
        if (el.closest('[data-v4-composer-dock="true"]')) { return false; }
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') { return false; }
        var z = parseInt(cs.zIndex, 10);
        // 高于页面内容（z-20/30），低于我们的注入层（99990）——页面自己的弹窗区间
        if (!(z > 25 && z < 99990)) { return false; }
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 24) { return false; }
        if (r.right < 0 || r.left > window.innerWidth || r.bottom < 0 || r.top > window.innerHeight) { return false; }
        return true;
      } catch (e) { return false; }
    }
    function scanBodyPopups(){
      if (!UI_ON) { return; }
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
    function syncComposer(){
      if (!floatInput || !UI_ON) { return; }
      var dock = getDock();
      if (!dock) { return; }
      scanDockPopups();   // dock 内弹窗复活（不依赖打开状态）
      ensureDockSnapObs(dock);   // v55：dock 新增节点快照（弹窗结构事件捕获）
      if (composerFoundAt < 0) { composerFoundAt = Date.now() - injectAt2; }
      // 单击时 dock 还没渲染：轮询到后再自动打开（见 showComposer 的 pendingOpen）
      if (pendingOpen) { pendingOpen = false; showComposer(); return; }
      // 默认隐藏底部输入框（html 门控 class，诊断卡片可一键恢复）
      doc.documentElement.classList.add('zcode-float-on');
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
      // 把当前状态落到 live 节点（重挂载后 class 会丢，这里补回来）。
      // 必须先移除两个类再只加一个：如果只 add，从全屏缩回浮动时 zcode-composer-full
      // 会残留，CSS 里 full 规则排在 float 之后且特异性相同 → full 永远赢 → 合不上（v38 修）。
      dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
      if (composerOpen) {
        dock.classList.add(composerFull ? 'zcode-composer-full' : 'zcode-composer-float');
      }
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
    // logo 单击 = 开关：开 → 收起，关 → 唤起（v38 起 toggle，之前打开状态下点 logo 被静默忽略）
    function toggleComposer(){
      // v59：弹窗挂起（popup-mode）时 logo 单击不切换——dock 会在 popup-mode（全宽 100%）
      // 与 float（94vw/520px 收窄）两种布局间跳变，提问框跟着缩放抖动（用户实测"点按钮提问框变一下大小"）
      var dd = getDock();
      if (dd && dd.classList.contains('zcode-popup-mode')) { return; }
      if (composerOpen) { hideComposer(); }
      else { showComposer(); }
    }
    function showComposer(){
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
      dock.classList.add('zcode-composer-float');
      uiLog('composer-open fixed');
      showMask();
      syncComposer();
      // 聚焦输入框：键盘跟随弹出
      try {
        var ta = dock.querySelector('[data-testid^="v4-composer-input"], textarea, input[type="text"]');
        if (ta) { setTimeout(function(){ try { ta.focus(); } catch (e2) {} }, 150); }
      } catch (e) {}
    }
    function toggleFullComposer(){
      var dock = getDock();
      if (!dock) { return; }
      composerFull = !composerFull;
      uiLog(composerFull ? 'composer-full' : 'composer-shrink');
      syncComposer();
    }
    function hideComposer(){
      var dock = getDock();
      composerOpen = false;
      composerFull = false;
      pendingOpen = false;
      hideMask();
      if (dock) {
        if (ANIM_ON && !REDUCED) {
          // 收起动画：先加 closing（淡出下移），animationend 后移除状态类（动画事件兜底 600ms）
          dock.classList.add('zcode-composer-closing');
          var done = function(){
            dock.classList.remove('zcode-composer-float', 'zcode-composer-full', 'zcode-composer-closing');
          };
          dock.addEventListener('animationend', done, {once: true});
          setTimeout(done, 600);
        } else {
          dock.classList.remove('zcode-composer-float', 'zcode-composer-full');
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

    /** 注入广播式修正包：样式 + 巡检器 + 导航手势 + 主题回报 + 回复监听（一次注入，广播到所有同源文档）。 */
    private fun injectMobileFix() {
        val css = StringBuilder(MOBILE_CSS)
        css.append(if (settings.hideScrollbar) CSS_SCROLLBAR_HIDDEN else CSS_SCROLLBAR_SLIM)
        val js = "(" + BUNDLE_JS.trim() + ")(" +
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
            "\"floatInput\":" + s.floatingInput + "})"
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
