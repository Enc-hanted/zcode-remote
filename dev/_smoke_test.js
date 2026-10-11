// bundle 运行时冒烟测试：最小 DOM mock，跑通注入 + applySettings 各开关组合
// 用法: node dev/_smoke_test.js
const fs = require('fs');
const path = require('path');

function makeEl(tag) {
  const el = {
    tagName: String(tag).toUpperCase(),
    id: '', className: '', textContent: '', innerText: '',
    style: {}, dataset: {}, children: [],
    parentNode: null, parentElement: null,
    _listeners: {},
    shadowRoot: null,
    setAttribute(k, v) { this[k === 'class' ? 'className' : k] = v; },
    getAttribute(k) { return this[k === 'class' ? 'className' : k] ?? null; },
    addEventListener(ev, fn) { (this._listeners[ev] = this._listeners[ev] || []).push(fn); },
    removeEventListener() {},
    appendChild(c) { c.parentNode = this; c.parentElement = this; this.children.push(c); return c; },
    removeChild(c) { this.children = this.children.filter(x => x !== c); c.parentNode = null; c.parentElement = null; return c; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    cloneNode(deep) { const c = makeEl(this.tagName); c.className = this.className; c.textContent = this.textContent; return c; },
    getBoundingClientRect() { return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 }; },
    closest() { return null; },
    focus() {},
    classList: { _s: new Set(),
      add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, contains(c) { return this._s.has(c); }, toggle(c) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); } },
    get scrollWidth() { return 0; }, get clientWidth() { return 0; }, get scrollLeft() { return 0; }, set scrollLeft(v) {},
    get offsetWidth() { return 0; }, get offsetHeight() { return 0; },
  };
  return el;
}

const body = makeEl('body');
const head = makeEl('head');
const html = makeEl('html');
body.parentElement = html; html.children.push(body); html.children.push(head);
const docEl = html;

const document = {
  body, head, documentElement: html,
  hidden: false, visibilityState: 'visible',
  createElement: (t) => makeEl(t),
  getElementById: () => null,
  querySelector: () => null,
  querySelectorAll: () => [],
  elementFromPoint: () => null,
  addEventListener() {}, removeEventListener() {},
};

let errors = [];
const origError = console.error;
global.__zcodeSmokeErrors = [];

// 捕获 bundle 内未捕获异常
process.on('uncaughtException', (e) => { global.__zcodeSmokeErrors.push(e.message); });

global.window = {
  innerWidth: 390, innerHeight: 844,
  addEventListener() {}, removeEventListener() {},
  localStorage: { getItem: () => null, setItem() {} },
  matchMedia: () => ({ matches: false }),
  requestAnimationFrame: (fn) => setTimeout(fn, 0),
  cancelAnimationFrame: (id) => clearTimeout(id),
  __zcodeRoot: undefined,
  __zcodeErrors: [],
};
global.document = document;
global.navigator = { vibrate() {}, userAgent: 'test' };
global.MutationObserver = class { constructor(cb) { this.cb = cb; } observe() {} disconnect() {} };
global.Element = function Element() {};
global.Element.prototype = { attachShadow() { return makeEl('shadow'); }, querySelector: () => null, querySelectorAll: () => [] };
global.setInterval = setInterval; global.clearInterval = clearInterval;
global.setTimeout = setTimeout; global.clearTimeout = clearTimeout;
global.requestAnimationFrame = window.requestAnimationFrame;
global.cancelAnimationFrame = window.cancelAnimationFrame;
global.JSON = JSON; global.Date = Date; global.Math = Math; global.parseInt = parseInt;
global.String = String; global.Object = Object; global.Array = Array;
global.location = { href: 'https://zcode.z.ai/remote/test' };
global.parseInt = parseInt; global.parseFloat = parseFloat; global.isNaN = isNaN;

const bundle = fs.readFileSync(path.join(__dirname, '..', '_bundle.js'), 'utf8');

function run(flags) {
  try {
    const fn = eval('(' + bundle + ')');
    fn('', flags.navOn, flags.replyOn, flags.floatInput, flags.uiAnim);
  } catch (e) {
    global.__zcodeSmokeErrors.push('inject: ' + e.message);
  }
}

// 场景 0（一期）：agentStateOn=false 注入 → 09_agent 整体不启动（不注册轮询定时器）。
// 用 setInterval 计数差验证：两次注入之间唯一新增注册者应是 09（BODY 有 __zcodeDone
// 防重入不再注册；08 的 30s 兜底每次注入都会注册，故只断言"有新增"）。
// 注意在同一个 __zcSettings 对象上改键——BODY 首次执行时 06_composer 的 ZSET 绑定
// 了它，之后 applySettings 写回的也是它；换对象会让热更断言读到野引用
let siCount = 0;
const realSetInterval = global.setInterval;
global.setInterval = (...a) => { siCount++; return realSetInterval(...a); };
window.__zcSettings = { agentStateOn: false };
run({ navOn: true, replyOn: true, floatInput: true, uiAnim: true });
const siAfterOff = siCount;
if (window.__zcodeAgentOn !== undefined) global.__zcodeSmokeErrors.push('09_agent started despite agentStateOn=false');
window.__zcSettings.agentStateOn = true;   // 09 启动判定/每拍读 window.__zcSettings（动态查找）
run({ navOn: true, replyOn: true, floatInput: true, uiAnim: true });
if (siCount <= siAfterOff) global.__zcodeSmokeErrors.push('09_agent poll interval not registered');
if (window.__zcodeAgentOn !== true) global.__zcodeSmokeErrors.push('09_agent did not start (agentStateOn default)');
global.setInterval = realSetInterval;

// 场景 1: 全开
run({ navOn: true, replyOn: true, floatInput: true, uiAnim: true });
if (!window.__zcodeAPI) global.__zcodeSmokeErrors.push('api missing after inject');

// 场景 2: applySettings 全切换
window.__zcodeAPI.applySettings({ scrollbar: false, turnNav: false, notifyReply: false, floatInput: false, uiAnim: false });
window.__zcodeAPI.applySettings({ scrollbar: true, turnNav: true, notifyReply: true, floatInput: true, uiAnim: true });
window.__zcodeAPI.applySettings({});   // 空对象必须无害
// v82：三个后补开关的热更分支纳入基线（此前组合键仅覆盖 v76 前的五键）——
// hapticOn（P0① 触感）、sendMinimize/amoledBlack（v79 ①⑨）。ZSET 跨场景存活，
// 试完切回默认值，不污染后续场景的注入初始态
window.__zcodeAPI.applySettings({ hapticOn: false });
window.__zcodeAPI.applySettings({ sendMinimize: false });
window.__zcodeAPI.applySettings({ amoledBlack: true });
window.__zcodeAPI.applySettings({ hapticOn: true, sendMinimize: true, amoledBlack: false });
// 一期：状态感知轴三键热更（agentStateOn/statusStripOn/errNotifyOn）——09_agent 每拍
// 自读 ZSET.agentStateOn，写回即热更（关=空拍静默+光点移除）；statusStrip 的呈现消费
// 在原生侧；errNotify 键先落地无消费。试完切回默认值，不污染后续场景
window.__zcodeAPI.applySettings({ agentStateOn: false, statusStripOn: false, errNotifyOn: false });
if (window.__zcSettings.agentStateOn !== false || window.__zcSettings.statusStripOn !== false ||
    window.__zcSettings.errNotifyOn !== false) {
  global.__zcodeSmokeErrors.push('agent-state keys not written back to ZSET');
}
window.__zcodeAPI.applySettings({ agentStateOn: true, statusStripOn: true, errNotifyOn: true });
window.__zcodeAPI.applySettings({ agentStateOn: false });
window.__zcodeAPI.applySettings({ agentStateOn: true });

// 场景 3: 回复通知关闭注入 → API 仍在
run({ navOn: true, replyOn: false, floatInput: true, uiAnim: true });
if (!window.__zcodeAPI) global.__zcodeSmokeErrors.push('api missing when replyOn=false');
window.__zcodeAPI.applySettings({ notifyReply: true, floatInput: false, uiAnim: false });
window.__zcodeAPI.applySettings({ notifyReply: false, floatInput: true, uiAnim: true });

// 场景 4: 浮动输入关闭注入
run({ navOn: false, replyOn: true, floatInput: false, uiAnim: false });
window.__zcodeAPI.applySettings({ floatInput: true, turnNav: true, uiAnim: true, notifyReply: true, scrollbar: true });

// 场景 5: 全部关闭
run({ navOn: false, replyOn: false, floatInput: false, uiAnim: false });
if (!window.__zcodeAPI) global.__zcodeSmokeErrors.push('api missing when all off');

const errs = global.__zcodeSmokeErrors;
if (errs.length) {
  console.error('SMOKE FAILED:');
  errs.forEach(e => console.error('  -', e));
  process.exit(1);
}
console.log('SMOKE OK: inject x6 + applySettings x14 passed');
process.exit(0);   // bundle 常驻 interval 会让进程不退出，显式结束
