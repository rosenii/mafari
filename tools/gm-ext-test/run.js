// 协议冒烟测试：用 Node vm 模拟 [页面 MAIN] ⇄ [中继 ISOLATED] ⇄ [后台页] ⇄ [原生(Kotlin 的 JS 替身)]，
// 页面里跑的是真正的 mafari_gm_runtime.js + shim。不覆盖：真实 GeckoView、contentScripts 注入时序、webRequest 改头。
const vm = require('vm'), fs = require('fs'), path = require('path'), assert = require('assert');
const A = path.join(__dirname, '../../app/src/main/assets');
const rd = (p) => fs.readFileSync(path.join(A, p), 'utf8');

// ---------- 假浏览器 ----------
const tick = () => new Promise((r) => setTimeout(r, 20));
const bgListeners = [];          // runtime.onMessage
const storageListeners = [];     // storage.onChanged（所有中继共享）
const registered = [];           // contentScripts.register 的调用
let bgPortHandler = null;        // 后台 ← 原生
const nativeLog = [];            // 原生收到的会话消息
const prefs = {};                // 原生的 SharedPreferences 替身
let sessionPorts = [];           // 原生持有的会话端口（对应各框架的中继）
let bgNativePort = null;         // 原生持有的后台端口

function makePort(onFromJs, label) {
  const toJs = [];
  const port = {
    name: label,
    onMessage: { addListener: (f) => toJs.push(f) },
    onDisconnect: { addListener() {} },
    postMessage: (m) => setImmediate(() => onFromJs(JSON.parse(JSON.stringify(m)), nativeSide)),
  };
  const nativeSide = { post: (m) => setImmediate(() => toJs.forEach((f) => f(JSON.parse(JSON.stringify(m))))) };
  return { port, nativeSide };
}

function nativeOnSession(m, side) {            // = GeckoGm.handle
  nativeLog.push(m);
  switch (m.t) {
    case 'set': prefs[m.id + '::' + m.k] = m.v; broadcast(m.id, m.k, m.v, m.src); break;
    case 'notify': side.post({ t: 'cb', fn: '__mafariNtfCallback_' + m.cbId, args: ['click'] }); break;
    case 'download': if (m.cbId) side.post({ t: 'cb', fn: '__mafariDlCallback_' + m.cbId, args: [true] }); break;
    case 'menu': global.__menu = { list: m.list, side }; break;
    case 'ran': (global.__ran = global.__ran || new Set()).add(m.id); break;
  }
}
function broadcast(id, k, raw, src) { bgNativePort.post({ t: 'val', id, k, raw, src }); } // = GeckoGm.broadcast

function makeBrowser(isBackground) {
  const b = {
    runtime: {
      sendMessage: (m) => {
        let out;
        for (const l of bgListeners) { const r = l(JSON.parse(JSON.stringify(m)), { tab: { id: 1, incognito: false } }); if (r !== undefined) out = r; }
        return Promise.resolve(out);
      },
      onMessage: { addListener: (f) => bgListeners.push(f) },
      connectNative: (name) => {
        if (name === 'mafariGmBg') {
          const { port, nativeSide } = makePort((m) => {
            if (m.t === 'hello') nativeSide.post({ t: 'sync', rev: 1, scripts: global.__payload });
            if (m.t === 'synced') global.__synced = m;
          }, name);
          bgNativePort = nativeSide;
          return port;
        }
        const { port, nativeSide } = makePort((m, side) => nativeOnSession(m, side), name);
        sessionPorts.push(nativeSide);
        return port;
      },
    },
    storage: {
      local: { set: async (o) => { for (const k of Object.keys(o)) storageListeners.forEach((f) => f({ [k]: { newValue: o[k] } }, 'local')); } },
      onChanged: { addListener: (f) => storageListeners.push(f) },
    },
    contentScripts: { register: async (o) => { registered.push(o); return { unregister: async () => { registered.splice(registered.indexOf(o), 1); } }; } },
    webRequest: { onBeforeSendHeaders: { addListener() {} } },
    cookies: { getAll: async () => [{ name: 'sid', value: 'abc', domain: 'example.com', hostOnly: true, path: '/', secure: true, httpOnly: true, session: true }], set: async () => {}, remove: async () => {} },
  };
  return b;
}

// ---------- 页面（MAIN）：共享一个 document ----------
const document = new EventTarget();
document.documentElement = {}; document.cookie = 'a=1';
function pageCtx(href) {
  const ctx = {
    document, CustomEvent, console, setTimeout, clearTimeout, setInterval, clearInterval, URL, TextEncoder, TextDecoder,
    btoa, atob, Promise, Blob, Uint8Array, ArrayBuffer, JSON, Object, Math, Date, MutationObserver: class { observe() {} disconnect() {} },
    location: new URL(href), navigator: { userAgent: 'UA', language: 'en-US' }, crypto: { randomUUID: () => require('crypto').randomUUID() },
    MouseEvent: class {}, fetch: undefined,
  };
  ctx.location.origin = ctx.location.origin;
  ctx.window = ctx; ctx.self = ctx; ctx.top = ctx;
  return vm.createContext(ctx);
}
// ---------- 加载扩展 ----------
vm.runInNewContext(rd('mafari-gm-ext/background.js'), {
  browser: makeBrowser(true), console, setTimeout, clearTimeout, atob, btoa, Uint8Array, URL, Headers: class { constructor() { this.m = {}; } set(k, v) { this.m[k] = v; } forEach(f) {} },
  AbortController, fetch: async (url) => ({ status: 200, statusText: 'OK', url, headers: new Map([['content-type', 'text/plain']]), arrayBuffer: async () => new TextEncoder().encode('hello from ' + url).buffer }),
});
const loadRelay = () => vm.runInNewContext(rd('mafari-gm-ext/relay.js'), { browser: makeBrowser(false), document, CustomEvent, JSON });
const LATE = process.env.RELAY_ORDER === 'late';
if (!LATE) loadRelay();

// ---------- 原生侧：拼源码（= GeckoGm.buildSource）----------
const runtime = rd('mafari_gm_runtime.js');
function build(id, code, values) {
  const META = JSON.stringify({ id, name: 't-' + id, namespace: '', version: '1', description: '', author: '', homepage: '', icon: '', runAt: 'document-start', noframes: false,
    match: [], include: [], exclude: [], excludeMatch: [], userMatch: [], grant: ['GM_getValue', 'GM_setValue', 'GM_xmlhttpRequest', 'GM_notification', 'GM_registerMenuCommand', 'GM_cookie', 'GM_addValueChangeListener', 'GM_download'], require: [], resources: {}, meta: {}, metaBlock: '' });
  let rt = runtime.replace('__MAFARI_META__', () => META).replace('__MAFARI_FN__', 'function').replace('__MAFARI_CODE__', () => code);
  const tpl = rd('mafari_gm_gecko_shim.js'), at = tpl.lastIndexOf('__MAFARI_SNAPSHOT__');
  const shim = tpl.slice(0, at) + JSON.stringify({ id, values }) + tpl.slice(at + '__MAFARI_SNAPSHOT__'.length);
  return `(function(__mafariBridge){\n${rt}\n})(${shim});`;
}

(async () => {
  const userCode = `
    window.__log = [];
    window.__log.push(['init', GM_getValue('counter', 0), GM_getValue('name')]);
    GM_setValue('counter', 42);
    window.__log.push(['readback', GM_getValue('counter')]);
    GM_addValueChangeListener('shared', function (n, o, v, remote) { window.__log.push(['change', n, o, v, remote]); });
    GM_registerMenuCommand('Hello', function () { window.__log.push(['menu-run']); });
    GM_xmlhttpRequest({ url: 'https://example.com/api', method: 'GET', onload: function (r) { window.__log.push(['xhr', r.status, r.responseText]); } });
    GM_notification({ title: 'T', text: 'X', onclick: function () { window.__log.push(['ntf-click']); } });
    GM_download({ url: 'https://example.com/f.bin', name: 'f.bin', onload: function () { window.__log.push(['dl-ok']); } });
    window.__gm = { set: GM_setValue, get: GM_getValue };
    GM_cookie.list({ url: 'https://example.com/' }, function (c) { window.__log.push(['cookies', c.length, c[0] && c[0].httpOnly]); });
  `;
  global.__payload = [{ id: 's1', sig: 'x', code: build('s1', userCode, { counter: '7', name: '"mafari"' }), matches: ['*://*/*'], excludeMatches: [], noframes: false }];

  // 后台启动后向原生 hello → 收到 sync → 注册
  await tick(); await tick();
  assert.strictEqual(registered.length, 1, 'contentScripts.register called once');
  const r = registered[0];
  assert.strictEqual(r.world, 'MAIN'); assert.strictEqual(r.runAt, 'document_start'); assert.ok(r.js[0].code.includes('mafari_gm_runtime') || r.js[0].code.includes('Mafari userscript runtime'));
  assert.strictEqual(global.__synced.count, 1, 'background acked synced');

  // 模拟 Gecko 把注册的代码注入页面 A、页面 B（另一个标签）
  const A = pageCtx('https://example.com/'), B = pageCtx('https://example.com/other');
  vm.runInContext(r.js[0].code, A);
  vm.runInContext(r.js[0].code, B);
  if (LATE) { await tick(); loadRelay(); } // 脚本先执行、中继后就绪：早期消息必须不丢
  for (let i = 0; i < 8; i++) await tick();

  const logA = A.__log;
  const eq = (a, b, m) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b), m);
  const find = (log, k) => log.find((x) => x[0] === k);
  eq(find(logA, 'init'), ['init', 7, 'mafari'], 'snapshot read is synchronous');
  eq(find(logA, 'readback'), ['readback', 42], 'read-your-own-write');
  assert.strictEqual(prefs['s1::counter'], '42', 'native received GM_setValue');
  eq(find(logA, 'xhr'), ['xhr', 200, 'hello from https://example.com/api'], 'xhr via background');
  assert.ok(find(logA, 'ntf-click'), 'notification callback delivered');
  assert.ok(find(logA, 'dl-ok'), 'download callback delivered');
  eq(find(logA, 'cookies'), ['cookies', 1, true], 'HttpOnly cookies via cookies API');
  assert.ok(global.__ran && global.__ran.has('s1'), 'native knows the script ran');
  assert.ok(global.__menu && JSON.parse(global.__menu.list)[0].title === 'Hello', 'menu list reported to native');

  // 菜单执行：原生 → 端口 → 中继 → shim → __mafariMenuRun（A、B 的 shim 都会收到，各自只在自己的 window 上执行一次）
  const cid = JSON.parse(global.__menu.list)[0].id;
  global.__menu.side.post({ t: 'menuRun', cid, n: 1 });
  for (let i = 0; i < 4; i++) await tick();
  assert.ok(find(A.__log, 'menu-run'), 'menu command executed');

  // 值广播：B 写入 → 原生 → 后台 bus → 中继 → A 的快照更新（运行时 1s 轮询触发监听，remote=true）
  B.__gm.set('shared', 'from-B');
  await new Promise((r) => setTimeout(r, 1300));
  const ch = find(A.__log, 'change');
  eq(ch, ['change', 'shared', null, 'from-B', true], 'remote value change reaches the other tab');
  eq(find(B.__log, 'change'), ['change', 'shared', null, 'from-B', false], 'local change not echoed as remote');
  eq(A.__gm.get('shared'), 'from-B');
  // 乱序回声：B 连写两次，B 最终读到的必须是第二次的值
  B.__gm.set('seq', 1); B.__gm.set('seq', 2);
  for (let i = 0; i < 6; i++) await tick();
  eq(B.__gm.get('seq'), 2, 'own echo is ignored (no stale overwrite)');
  console.log('ALL PASS');
  process.exit(0);
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
