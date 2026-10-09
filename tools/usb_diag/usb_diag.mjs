#!/usr/bin/env node
// usb_diag —— ZCodeRemote 实机 USB 取证通道（Chrome DevTools 协议直连 WebView）
//
// 前置：手机开 USB 调试并连电脑；装的是 debug APK（WebView 调试已开）；Node >= 22
//（用原生 fetch/WebSocket，零依赖）。adb 自动找：ADB_PATH 环境变量 → PATH →
// 常见安装位置。只读子命令不动页面；fling 会操纵页面（结束恢复原位）。
//
// 用法（在仓库根目录）：
//   node tools/usb_diag/usb_diag.mjs status              # 设备/app pid/socket/端口转发/页面目标 一条龙
//   node tools/usb_diag/usb_diag.mjs eval <expr|@file.js> # 在真实页面执行 JS（输出自动脱敏）
//   node tools/usb_diag/usb_diag.mjs webshot <out.png>    # WebView 合成层截图
//   node tools/usb_diag/usb_diag.mjs screen <out.png>     # 物理屏截图（adb screencap，视觉真相）
//   node tools/usb_diag/usb_diag.mjs scan <img.png>       # 像素扫描量化底部空白带（Windows/PowerShell）
//   node tools/usb_diag/usb_diag.mjs blank                # screen+scan 组合，一屏读出空白带
//   node tools/usb_diag/usb_diag.mjs frames [ms]          # 帧采样：scrollTop/scrollHeight 振荡检测
//                                          #   （曾用它抓到 1Hz ±40px 方波 = 尾清与 React 的内联样式攻防战）
//   node tools/usb_diag/usb_diag.mjs mut [ms]             # 变异监听：谁在抢 style/class（攻防双方点名，
//                                          #   按 t%1000 相位聚合；曾用它抓到 React 每秒抹掉 display:none）
//   node tools/usb_diag/usb_diag.mjs mask                 # z.ai 底部渐隐 mask 状态（pos 与 scrollTop 同步差）
//   node tools/usb_diag/usb_diag.mjs log [ms]             # 收集页面 console 报错/异常 N 毫秒
//   node tools/usb_diag/usb_diag.mjs fling                # ⚠️ 操纵页面：真实触摸惯性滚动 + 采样 + 回位
//
// 排障：status 说 no device → 手机上授权 USB 调试；说 app 未运行 → 先打开 App 到会话页
//（WebView 得活着）；connect timeout → 重跑（转发偶发失效，脚本会重建）。

import { execFileSync } from 'node:child_process';
import { existsSync, writeFileSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const PORT = process.env.USB_DIAG_PORT || '9222';
const PKG = 'com.zcode.remote';

// ---------- adb 定位 ----------
function findAdb() {
  if (process.env.ADB_PATH && existsSync(process.env.ADB_PATH)) return process.env.ADB_PATH;
  const candidates = [
    'adb',
    join(process.env.LOCALAPPDATA || '', 'Temp', 'platform-tools', 'adb.exe'),
    join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    process.env.ANDROID_HOME ? join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe') : '',
  ].filter(Boolean);
  for (const c of candidates) {
    try { execFileSync(c, ['version'], { stdio: 'pipe' }); return c; } catch {}
  }
  return null;
}

function forwardReady(adbPath) {
  const dev = adb(adbPath, ['devices']).split('\n').slice(1).filter(l => l.trim() && !l.includes('*'));
  if (!dev.some(l => /\bdevice\b/.test(l))) {
    console.log('no device（手机连了吗？USB 调试授权了吗？）'); process.exit(1);
  }
  const pid = adb(adbPath, ['shell', `pidof ${PKG}`]).trim();
  if (!pid) { console.log(`app ${PKG} 未运行——先在手机上打开 App 到会话页`); process.exit(1); }
  let sock = '';
  try {
    sock = adb(adbPath, ['shell', 'cat /proc/net/unix']).split('\n')
      .map(l => (l.match(/@?(webview_devtools_remote_\d+)/) || [])[1])
      .find(Boolean) || '';
  } catch {}
  if (!sock) { console.log('no devtools socket——debug 包才会开 WebView 调试'); process.exit(1); }
  adb(adbPath, ['forward', `tcp:${PORT}`, `localabstract:${sock}`]);
  return { pid, sock };
}
function adb(adbPath, args) {
  return execFileSync(adbPath, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
}

// ---------- CDP 通道 ----------
async function cdpConnect() {
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const page = targets.find(t => t.type === 'page');
  if (!page) { console.log('no page target（App 里的 WebView 页面还开着吗？）'); process.exit(1); }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const events = [];
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id); pending.delete(m.id);
      m.error ? p.rej(new Error(m.error.message)) : p.res(m.result);
    } else if (m.method) { events.push(m); }
  };
  await new Promise((res, rej) => {
    ws.onopen = res;
    ws.onerror = () => rej(new Error('ws connect fail'));
    setTimeout(() => rej(new Error('connect timeout')), 5000);
  });
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id;
    pending.set(i, { res, rej });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const evaluate = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      return { __exc: (r.exceptionDetails.exception?.description || r.exceptionDetails.text || '').slice(0, 2000) };
    }
    return r.result.value ?? r.result.description;
  };
  return { ws, events, send, eval: evaluate };
}

const MASK_RE = /[?&](sid|hash|remote|token|ticket|mid)=[^&\s"']+/gi;
function mask(s) { return String(s).replace(MASK_RE, '$1=***').replace(/https?:\/\/[^\s"']{60,}/g, 'URL***'); }
const show = v => console.log(mask(typeof v === 'string' ? v : JSON.stringify(v, null, 1)).slice(0, 20000));

// ---------- 页面探针（无侵入：只读/只监听，重复调用幂等） ----------
const TL_SEL = `document.querySelector('[data-testid="v4-timeline"]')||document.querySelector('[data-testid="v4-timeline-scroll"]')`;

const PROBE_INSTALL = `(() => {
  if (window.__usbDiag) return 'probe already on';
  const tl = ${TL_SEL};
  if (!tl) return 'no tl';
  const t0 = performance.now();
  const D = window.__usbDiag = { frames: [], scrolls: [], muts: [] };
  tl.addEventListener('scroll', () => {
    D.scrolls.push({ t: Math.round(performance.now() - t0), st: Math.round(tl.scrollTop * 10) / 10 });
    if (D.scrolls.length > 400) D.scrolls.shift();
  }, true);
  const mo = new MutationObserver(muts => {
    const now = Math.round(performance.now() - t0);
    for (const m of muts) {
      if (m.type === 'attributes') {
        D.muts.push({ t: now, k: 'a:' + m.attributeName,
          tgt: m.target.tagName + '|' + ('' + (m.target.className || '')).slice(0, 40) +
               '|h=' + Math.round(m.target.getBoundingClientRect().height),
          val: ((m.target.getAttribute('style') || '') + ' ' + (m.target.getAttribute('class') || '')).slice(0, 70) });
      } else if (m.type === 'childList') {
        let near = false;
        for (let n = m.target; n && n.nodeType === 1; n = n.parentElement) {
          if (n === tl.parentElement || n === tl) { near = true; break; }
        }
        if (near) D.muts.push({ t: now, k: 'cl', tgt: m.target.tagName + '|' + ('' + (m.target.className || '')).slice(0, 40),
          add: m.addedNodes.length, rm: m.removedNodes.length });
      }
      if (D.muts.length > 400) D.muts.shift();
    }
  });
  mo.observe(tl.parentElement || tl, { subtree: true, attributes: true, attributeFilter: ['style', 'class'], childList: true });
  let n = 0;
  const fr = () => {
    D.frames.push({ t: Math.round(performance.now() - t0), st: Math.round(tl.scrollTop), sh: tl.scrollHeight, ch: tl.clientHeight });
    if (++n < 900) requestAnimationFrame(fr);
  };
  requestAnimationFrame(fr);
  return 'probe on';
})()`;

const FRAMES_REPORT = `(() => {
  const D = window.__usbDiag; if (!D) return 'no probe';
  const ch = D.frames.slice(-450);
  if (!ch.length) return 'no frames';
  const changes = []; let last = null;
  for (const f of ch) {
    if (last === null || Math.abs(f.st - last) >= 1) { changes.push({ dt: f.t - ch[0].t, st: f.st, sh: f.sh }); last = f.st; }
  }
  const sts = ch.map(f => f.st);
  const mean = sts.reduce((a, b) => a + b, 0) / sts.length;
  const sd = Math.sqrt(sts.reduce((a, b) => a + (b - mean) ** 2, 0) / sts.length);
  let periodHint = '';
  const cTs = changes.slice(1).map(c => c.dt);
  if (cTs.length >= 3) {
    const gaps = cTs.slice(1).map((t, i) => t - cTs[i]);
    const med = gaps.slice().sort((a, b) => a - b)[Math.floor(gaps.length / 2)];
    if (med >= 300 && med <= 3000) periodHint = '主周期 ~' + med + 'ms（≈' + (med / 1000).toFixed(1) + 's 一跳 → 查 1s 轮询/攻防战）';
  }
  const st0 = ch[0].st, stLast = ch[ch.length - 1].st;
  return { nFrames: ch.length, sdSt: Math.round(sd * 10) / 10,
    settled: Math.abs(st0 - stLast) < 2 && sd < 2, periodHint, changes: changes.slice(0, 50) };
})()`;

const MUT_REPORT = `(() => {
  const D = window.__usbDiag; if (!D) return 'no probe';
  const by = {};
  for (const m of D.muts) {
    const k = m.k + '|' + (m.tgt || '').slice(0, 46);
    if (!by[k]) by[k] = { n: 0, phase: [], sample: m };
    by[k].n++;
    if (by[k].phase.length < 6) by[k].phase.push(m.t % 1000);
  }
  const top = Object.entries(by).sort((a, b) => b[1].n - a[1].n).slice(0, 12)
    .map(([k, v]) => ({ sig: k.slice(0, 80), n: v.n, phaseMs: v.phase,
      sample: ('val' in v.sample ? v.sample.val : 'add' + v.sample.add + '/rm' + v.sample.rm).slice(0, 70) }));
  const hint = top.some(t => t.phaseMs.length >= 3 && t.phaseMs.every(p => p > 850 || p < 150))
    ? '相位集中在整秒附近 → 1s 轮询参与的攻防战' : '';
  return { total: D.muts.length, top, hint };
})()`;

const MASK_STATE = `(() => {
  const d = document;
  const el = d.querySelector('[data-testid="v4-timeline"] [style*="mask-position"]') ||
             d.querySelector('[data-testid="v4-timeline-scroll"] [style*="mask-position"]');
  const tl = ${TL_SEL};
  if (!el) return { mask: 'none（无遮罩——v74 摘除生效或 z.ai 未挂）' };
  const cs = getComputedStyle(el);
  const pos = cs.webkitMaskPosition || cs.maskPosition || '';
  const y = parseFloat((pos.match(/(-?\\d+(\\.\\d+)?)px\\s*$/) || ['0'])[0]) || 0;
  const st = tl ? tl.scrollTop : -1;
  return { pos, scrollTop: Math.round(st), syncDelta: Math.round(y - st),
    img: (cs.webkitMaskImage || cs.maskImage || '').slice(0, 60),
    removed: /none/.test(cs.webkitMaskImage || cs.maskImage || ''),
    note: 'syncDelta!=0 = mask 脱同步（底部透明带比 120px 大的元凶）' };
})()`;

// ---------- 子命令 ----------
async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  if (!cmd) {
    console.log('子命令：status eval webshot screen scan blank frames mut mask log fling（详见文件头注释）');
    return;
  }
  const adbPath = findAdb();
  if (!adbPath) { console.log('找不到 adb——设 ADB_PATH 或装 platform-tools（README 有说明）'); process.exit(1); }

  const needsPage = !['screen', 'scan'].includes(cmd);
  let ctx = null, c = null;
  if (needsPage) { ctx = forwardReady(adbPath); c = await cdpConnect(); }

  switch (cmd) {
    case 'status': {
      await c.send('Runtime.enable');
      const info = await c.eval(`({url: location.href.split('?')[0], vw: innerWidth, vh: innerHeight,
        tl: !!${TL_SEL}, rows: document.querySelectorAll('[data-testid^="v4-row"]').length,
        floatOn: document.documentElement.className.indexOf('zcode-float-on') >= 0})`);
      show({ pid: ctx.pid, sock: ctx.sock, port: PORT, page: info });
      break;
    }
    case 'eval': {
      const arg = rest[0];
      if (!arg) { console.log('用法：eval "<expr>" 或 eval @file.js'); process.exit(1); }
      const expr = arg.startsWith('@') ? readFileSync(arg.slice(1), 'utf8') : arg;
      show(await c.eval(expr));
      break;
    }
    case 'webshot': {
      const out = rest[0] || 'web.png';
      const r = await c.send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(out, Buffer.from(r.data, 'base64'));
      console.log('saved', out, '（WebView 合成层——可能与物理屏不同帧，别单信它）');
      break;
    }
    case 'screen': {
      const out = rest[0] || 'screen.png';
      const buf = execFileSync(adbPath, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 });
      writeFileSync(out, buf);
      console.log('saved', out, buf.length, 'bytes（物理屏真相）');
      break;
    }
    case 'scan': {
      const img = rest[0];
      if (!img || !existsSync(img)) { console.log('用法：scan <img.png>'); process.exit(1); }
      execFileSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', resolve(import.meta.dirname, 'scan_blank.ps1')],
        { stdio: 'inherit', env: { ...process.env, USB_DIAG_IMG: resolve(img) } });
      break;
    }
    case 'blank': {
      const out = rest[0] || 'screen.png';
      const buf = execFileSync(adbPath, ['exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 });
      writeFileSync(out, buf);
      console.log('saved', out);
      execFileSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', resolve(import.meta.dirname, 'scan_blank.ps1')],
        { stdio: 'inherit', env: { ...process.env, USB_DIAG_IMG: resolve(out) } });
      break;
    }
    case 'frames': {
      const ms = Math.min(+rest[0] || 6000, 15000);
      console.log(await c.eval(PROBE_INSTALL));
      console.log(`sampling ${ms}ms (use the phone normally / let it stream)...`);
      await new Promise(r => setTimeout(r, ms));
      show(await c.eval(FRAMES_REPORT));
      break;
    }
    case 'mut': {
      const ms = Math.min(+rest[0] || 5000, 15000);
      console.log(await c.eval(PROBE_INSTALL));
      console.log(`watching mutations for ${ms}ms...`);
      await new Promise(r => setTimeout(r, ms));
      show(await c.eval(MUT_REPORT));
      break;
    }
    case 'mask': show(await c.eval(MASK_STATE)); break;
    case 'log': {
      const ms = Math.min(+rest[0] || 5000, 20000);
      await c.send('Runtime.enable'); await c.send('Log.enable');
      console.log(`collecting console/errors for ${ms}ms (use the phone normally)...`);
      await new Promise(r => setTimeout(r, ms));
      const ls = c.events
        .filter(e => e.method === 'Runtime.consoleAPICalled' || e.method === 'Log.entryAdded' || e.method === 'Runtime.exceptionThrown')
        .map(e => {
          if (e.method === 'Runtime.consoleAPICalled') {
            return `[${e.params.type}] ` + (e.params.args || []).map(a => a.value ?? a.description ?? a.type).join(' ').slice(0, 200);
          }
          if (e.method === 'Log.entryAdded') {
            return `[${e.params.entry.level}/${e.params.entry.source}] ` + (e.params.entry.text || '').slice(0, 200);
          }
          return '[EXC] ' + ((e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text || '')).slice(0, 300);
        }).slice(-60);
      console.log(ls.length ? mask(ls.join('\n')) : '（无输出）');
      break;
    }
    case 'fling': {
      console.log('WARNING: manipulating the page (fling up, sample 3.5s, restore). Do not touch the phone.');
      show(await c.eval(`(() => { const tl = ${TL_SEL}; if (!tl) return 'no tl';
        window.__flingSt0 = tl.scrollTop; return 'st0=' + Math.round(tl.scrollTop); })()`));
      const pts = []; for (let i = 0; i <= 6; i++) pts.push({ x: 185, y: 380 + i * 80 });
      const touch = async (type, p) => c.send('Input.dispatchTouchEvent', { type, touchPoints: p || [] });
      await touch('touchStart', [pts[0]]); await new Promise(r => setTimeout(r, 14));
      for (let i = 1; i < pts.length; i++) { await touch('touchMove', [pts[i]]); await new Promise(r => setTimeout(r, 14)); }
      await touch('touchEnd', []);
      console.log(await c.eval(PROBE_INSTALL));
      await new Promise(r => setTimeout(r, 3500));
      show(await c.eval(FRAMES_REPORT));
      show(await c.eval(`(() => { const tl = ${TL_SEL};
        if (tl && window.__flingSt0 !== undefined) { tl.scrollTop = window.__flingSt0; return 'restored ' + Math.round(window.__flingSt0); }
        return 'skip'; })()`));
      break;
    }
    default: console.log('未知子命令:', cmd);
  }
  if (c) c.ws.close();
  process.exit(0);
}

main().catch(e => { console.error('ERR:', e.message); process.exit(1); });
