// 协议测试：用 Node vm 模拟 [页面 MAIN] ⇄ [中继 ISOLATED] ⇄ [后台页] ⇄ [原生(Kotlin 的 JS 替身)]，
// 页面里跑的是真正的 mafari_gm_runtime.js + shim；中继 / 后台页也是真文件。
// 不覆盖：真实 GeckoView、contentScripts 注入时序、webRequest 改头、Kotlin 本身（见 docs/GECKO_GM.md 的真机清单）。
//   node run.js                      中继先加载
//   RELAY_ORDER=late node run.js     脚本先执行、中继后加载（握手靠 ready 补发）
const vm = require('vm'), fs = require('fs'), path = require('path'), assert = require('assert');
// 共用资源在 main，Gecko 专属资源（内置拓展 / shim）在 gecko 变体目录
const A_DIRS = ['../../app/src/main/assets', '../../app/src/gecko/assets'].map((d) => path.join(__dirname, d));
const rd = (p) => {
  const dir = A_DIRS.find((d) => fs.existsSync(path.join(d, p)));
  if (!dir) throw new Error('asset not found: ' + p);
  return fs.readFileSync(path.join(dir, p), 'utf8');
};
const LATE = process.env.RELAY_ORDER === 'late';
const clone = (m) => JSON.parse(JSON.stringify(m));
const tick = (n = 1) => new Promise((r) => setTimeout(r, 20 * n));
const eq = (a, b, m) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b), m);

// ---------- 假的 browser ----------
const TOK = { s1: 'tok-s1', s2: 'tok-s2' };       // 原生为每个脚本生成的能力令牌
const bgMsgListeners = [], bgConnectListeners = [];
const registered = [];                              // contentScripts.register 当前有效的注册
const nativeLog = [], rejected = [], prefs = {};
let bgNativePort = null;

function makePair(sender, name) {
  const a = { l: [], d: [] }, b = { l: [], d: [] };
  const mk = (me, other, extra) => Object.assign({
    name, onMessage: { addListener: (f) => me.l.push(f) }, onDisconnect: { addListener: (f) => me.d.push(f) },
    postMessage: (m) => { const c = clone(m); setImmediate(() => other.l.forEach((f) => f(c))); },
  }, extra);
  return [mk(a, b, {}), mk(b, a, { sender })];
}
function makeNativePort(onFromJs, name) {
  const toJs = [];
  const side = { post: (m) => setImmediate(() => toJs.forEach((f) => f(clone(m)))) };
  const port = { name, onMessage: { addListener: (f) => toJs.push(f) }, onDisconnect: { addListener() {} },
    postMessage: (m) => setImmediate(() => onFromJs(clone(m), side)) };
  return { port, side };
}
// = GeckoGm.handle：令牌不对一律丢弃
function nativeOnSession(m, side) {
  if (TOK[m.sid] !== m.tok) { rejected.push(m); return; }
  nativeLog.push(m);
  switch (m.t) {
    case 'set': prefs[m.sid + '::' + m.k] = m.v; broadcast(m.sid, m.k, m.v, m.src); break;
    case 'getValue': side.post({ t: 'valRes', id: m.sid, k: m.k, raw: prefs[m.sid + '::' + m.k] === undefined ? null : prefs[m.sid + '::' + m.k] }); break;
    case 'notify': side.post({ t: 'cb', fn: '__mafariNtfCallback_' + m.cbId, args: ['click'] }); break;
    case 'download': if (m.cbId) { const cb = { t: 'cb', fn: '__mafariDlCallback_' + m.cbId, args: [true] }; side.post(cb); side.post(cb); } break; // 故意回两次
    case 'menu': global.__menu = { list: m.list, side }; break;
    case 'ran': (global.__ran = global.__ran || new Set()).add(m.id); break;
  }
}
// = GeckoGm.broadcast：大值不带内容
function broadcast(id, k, raw, src) {
  const o = { t: 'val', id, k, src };
  if (raw !== null && raw.length > 64 * 1024) o.big = true; else o.raw = raw;
  bgNativePort.post(o);
}

function makeBrowser(url) {
  const sender = { id: 'ext', url, tab: { id: 1, url, incognito: false } };
  return {
    runtime: {
      id: 'ext',
      sendMessage: (m) => {
        let out;
        for (const l of bgMsgListeners) { const r = l(clone(m), sender); if (r !== undefined) out = r; }
        return Promise.resolve(out);
      },
      onMessage: { addListener: (f) => bgMsgListeners.push(f) },
      onConnect: { addListener: (f) => bgConnectListeners.push(f) },
      connect: ({ name }) => { const [mine, bgSide] = makePair(sender, name); bgConnectListeners.forEach((f) => f(bgSide)); return mine; },
      connectNative: (name) => {
        if (name === 'mafariGmBg') {
          const { port, side } = makeNativePort((m) => {
            if (m.t === 'hello') side.post({ t: 'sync', rev: 1, scripts: global.__payload });
            if (m.t === 'synced') global.__synced = m;
            if (m.t === 'error') (global.__bgErrors = global.__bgErrors || []).push(m);
          }, name);
          bgNativePort = side;
          global.__bgSide = side;
          return port;
        }
        return makeNativePort((m, side) => nativeOnSession(m, side), name).port;
      },
    },
    contentScripts: { register: async (o) => { registered.push(o); return { unregister: async () => { const i = registered.indexOf(o); if (i >= 0) registered.splice(i, 1); } }; } },
    webRequest: { onBeforeSendHeaders: { addListener() {} } },
    cookies: { getAll: async () => [{ name: 'sid', value: 'abc', domain: 'example.com', hostOnly: true, path: '/', secure: true, httpOnly: true, session: true }], set: async () => {}, remove: async () => {} },
  };
}

// ---------- 页面（MAIN）----------
function pageCtx(href, now) {
  const et = new EventTarget();
  let G = null; // 上下文内部看到的 window（vm 里 window !== 沙箱对象），ev.source 必须是它
  const ctx = {
    addEventListener: (t, f, o) => et.addEventListener(t, f, o),
    removeEventListener: (t, f, o) => et.removeEventListener(t, f, o),
    postMessage: (data, origin, transfer) => {
      const ev = new Event('message');
      Object.defineProperties(ev, { data: { value: data }, source: { value: G }, ports: { value: transfer || [] } });
      setImmediate(() => et.dispatchEvent(ev));
    },
    MessageChannel, console, setTimeout, clearTimeout, setInterval, clearInterval, URL, TextEncoder, TextDecoder, btoa, atob, Promise, Blob,
    Uint8Array, ArrayBuffer, JSON, Object, Math, MutationObserver: class { observe() {} disconnect() {} },
    Date: now ? class extends Date { static now() { return now(); } } : Date,
    document: Object.assign(new EventTarget(), { documentElement: {}, cookie: 'a=1' }),
    location: new URL(href), navigator: { userAgent: 'UA', language: 'en-US' }, crypto: { randomUUID: () => require('crypto').randomUUID() },
    MouseEvent: class {}, CustomEvent, fetch: undefined,
  };
  ctx.window = ctx; ctx.self = ctx; ctx.top = ctx;
  const c = vm.createContext(ctx);
  G = vm.runInContext('window', c);
  return c;
}
const loadRelay = (page) => vm.runInNewContext(rd('mafari-gm-ext/relay.js'), { window: vm.runInContext('window', page), browser: makeBrowser(page.location.href), setTimeout, clearTimeout, JSON });

// ---------- 加载后台页 ----------
vm.runInNewContext(rd('mafari-gm-ext/background.js'), {
  browser: makeBrowser('moz-extension://ext/background.html'), console, setTimeout, clearTimeout, atob, btoa, Uint8Array, URL,
  Headers: class { constructor() { this.m = {}; } set(k, v) { this.m[k] = v; } forEach() {} },
  AbortController, unescape, encodeURIComponent, navigator: { userAgent: 'UA' },
  fetch: async (url) => ({ status: 200, statusText: 'OK', url, headers: new Map([['content-type', 'text/plain']]), arrayBuffer: async () => new TextEncoder().encode('hello from ' + url).buffer }),
});

// ---------- 原生侧：拼源码（= GeckoGm.buildSource）----------
const runtime = rd('mafari_gm_runtime.js');
const shimTpl = rd('mafari_gm_gecko_shim.js');
const INLINE_MAX = 64 * 1024;
function snapshot(id, all) { // = GeckoGm.valuesJson：超阈值的值不内联，键名进 big
  const ents = Object.entries(all);
  return { values: Object.fromEntries(ents.filter(([, v]) => v.length <= INLINE_MAX)), big: ents.filter(([, v]) => v.length > INLINE_MAX).map(([k]) => k) };
}
function shimFor(id, all) {
  const s = snapshot(id, all), at = shimTpl.lastIndexOf('__MAFARI_SNAPSHOT__');
  return shimTpl.slice(0, at) + JSON.stringify({ id, tok: TOK[id], values: s.values, big: s.big }) + shimTpl.slice(at + '__MAFARI_SNAPSHOT__'.length);
}
function build(id, code, all, match) {
  const META = JSON.stringify({ id, name: 't-' + id, namespace: '', version: '1', description: '', author: '', homepage: '', icon: '', runAt: 'document-start', noframes: false,
    match: match || [], include: [], exclude: [], excludeMatch: [], userMatch: [], grant: [], require: [], resources: {}, meta: {}, metaBlock: '' });
  const rt = runtime.replace('__MAFARI_META__', () => META).replace('__MAFARI_FN__', 'function').replace('__MAFARI_CODE__', () => code);
  return `(function(__mafariBridge){\n${rt}\n})(${shimFor(id, all)});`;
}
const entry = (id, code, extra) => Object.assign({ id, sig: 'sig-' + id, tok: TOK[id], code, matches: ['*://*/*'], excludeMatches: [], noframes: false, connect: [] }, extra);

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
    window.__gm = { set: GM_setValue, get: GM_getValue, GM: GM };
    GM_cookie.list({ url: 'https://example.com/' }, function (c) { window.__log.push(['cookies', c.length, c[0] && c[0].httpOnly]); });
    GM.getValue('bigA').then(function (v) { window.__log.push(['bigA', v && v.length]); });
    window.__log.push(['bigSync1', GM_getValue('bigB', 'dflt')]);
  `;
  const BIG_A = 'a'.repeat(100 * 1024), BIG_B = 'b'.repeat(70 * 1024);
  const initial = { counter: '7', name: '"mafari"', bigA: JSON.stringify(BIG_A), bigB: JSON.stringify(BIG_B) };
  Object.entries(initial).forEach(([k, v]) => { prefs['s1::' + k] = v; });
  // s2：匹配范围里没有本页，运行时会直接 return——验证「没运行的脚本不握手」
  global.__payload = [
    entry('s1', build('s1', userCode, initial)),
    entry('s2', build('s2', `GM_setValue('x', 1);`, {}, ['https://other.example/*'])),
  ];

  await tick(2);
  // —— 后台：中继 + 脚本都注册了；中继先于脚本；matches 是并集 ——
  const relays = registered.filter((r) => r.js[0].file === 'relay.js'), scripts = registered.filter((r) => r.js[0].code);
  assert.strictEqual(relays.length, 1, 'relay registered once');
  assert.strictEqual(scripts.length, 2, 'both scripts registered');
  assert.ok(registered.indexOf(relays[0]) < registered.indexOf(scripts[0]), 'relay registered before scripts');
  eq(relays[0].matches, ['*://*/*']);
  assert.strictEqual(global.__synced.count, 2); assert.strictEqual(global.__synced.failed, 0);
  const r = scripts.find((x) => x.js[0].code.includes('"id":"s1"'));
  assert.strictEqual(r.world, 'MAIN'); assert.strictEqual(r.runAt, 'document_start');

  // —— 页面 A、B：注入脚本 ——
  const A = pageCtx('https://example.com/'), B = pageCtx('https://example.com/other');
  const spyA = []; // 页面自己的 message 监听器：不得看到握手端口
  if (!LATE) { loadRelay(A); loadRelay(B); }
  A.addEventListener('message', (ev) => spyA.push(ev), true);
  const hiCount = { n: 0 };
  const origPost = A.postMessage; A.postMessage = (d, o, t) => { if (d && d.mafariGm === 'hi') hiCount.n++; return origPost(d, o, t); };
  const s1code = r.js[0].code, s2code = scripts.find((x) => x !== r).js[0].code;
  vm.runInContext(s1code, A); vm.runInContext(s1code, B);
  // 注意：shim 在 IIFE 里抓的是注入时的 postMessage，所以 hiCount 用下面的 spy 数握手
  vm.runInContext(s2code, A);
  if (LATE) { await tick(); loadRelay(A); loadRelay(B); }
  await tick(10);

  const find = (log, k) => log.find((x) => x[0] === k);
  const logA = A.__log;
  eq(find(logA, 'init'), ['init', 7, 'mafari'], 'snapshot read is synchronous');
  eq(find(logA, 'readback'), ['readback', 42], 'read-your-own-write');
  assert.strictEqual(prefs['s1::counter'], '42', 'native received GM_setValue (relay stamped sid/tok)');
  eq(find(logA, 'xhr'), ['xhr', 200, 'hello from https://example.com/api'], 'xhr via background');
  assert.ok(find(logA, 'ntf-click'), 'notification callback delivered');
  assert.ok(find(logA, 'dl-ok'), 'download callback delivered');
  eq(find(logA, 'cookies'), ['cookies', 1, true], 'HttpOnly cookies via cookies API');
  assert.ok(global.__ran && global.__ran.has('s1'), 'native knows the script ran');
  assert.ok(global.__menu && JSON.parse(global.__menu.list)[0].title === 'Hello', 'menu list reported to native');
  assert.ok(!nativeLog.some((m) => m.sid === 's2'), 's2 never ran on this page: no traffic from it');
  assert.strictEqual(rejected.length, 0, 'every message reaching native carried a valid token');
  // 中继先于脚本加载（正常顺序，后台页保证先注册先执行）时，中继在捕获阶段截停 hi，页面监听器看不到端口。
  // 中继晚到属于降级路径：先发出的那个 hi 没人截，页面若已挂监听器就能看到——已知残余风险，见 docs/GECKO_GM.md。
  if (!LATE) assert.ok(!spyA.some((ev) => ev.data && ev.data.mafariGm === 'hi'), 'page listeners never saw the handshake port');

  // —— 大值：GM.getValue 异步拿到；同步 GM_getValue 首次缺失（返回默认值）并预取，之后就有 ——
  eq(find(logA, 'bigA'), ['bigA', 100 * 1024], 'GM.getValue resolves a value that was not inlined');
  eq(find(logA, 'bigSync1'), ['bigSync1', 'dflt'], 'sync read of a non-inlined key misses first');
  assert.strictEqual(A.__gm.get('bigB').length, 70 * 1024, 'sync read hits after the prefetch');
  assert.ok(!s1code.includes('a'.repeat(1000)) && !s1code.includes('b'.repeat(1000)), 'big values are not inlined into the registered source');

  // —— 菜单执行 ——
  const cid = JSON.parse(global.__menu.list)[0].id;
  global.__menu.side.post({ t: 'menuRun', cid, n: 1 });
  await tick(4);
  assert.ok(find(B.__log, 'menu-run') || find(A.__log, 'menu-run'), 'menu command executed (delivered to the frame whose port reported the menu)');

  // —— 值广播：B 写入 → 原生 → 后台 → 绑定了该脚本的中继 → A（只发给已绑定的端口，不写 storage）——
  B.__gm.set('shared', 'from-B');
  await new Promise((r) => setTimeout(r, 1300));
  eq(find(A.__log, 'change'), ['change', 'shared', null, 'from-B', true], 'remote value change reaches the other tab');
  eq(find(B.__log, 'change'), ['change', 'shared', null, 'from-B', false], 'local change not echoed as remote');
  eq(A.__gm.get('shared'), 'from-B');
  B.__gm.set('seq', 1); B.__gm.set('seq', 2);
  await tick(6);
  eq(B.__gm.get('seq'), 2, 'own echo is ignored (no stale overwrite)');

  // —— 大值广播不带内容：B 写 100KB，A 之后读到新值（经 getValue 拉取）——
  const newBig = JSON.stringify('c'.repeat(100 * 1024));
  B.__gm.set('bigA', 'c'.repeat(100 * 1024)); // 运行时会 JSON.stringify
  await tick(10);
  const bigRes = await A.__gm.GM.getValue('bigA');
  assert.strictEqual(bigRes.length, 100 * 1024); assert.strictEqual(bigRes[0], 'c', 'big remote update visible via GM.getValue');
  void newBig;

  // —— 敌意页面：伪造端口 ——
  const evilInbox = [];
  const evil = new MessageChannel();
  evil.port1.onmessage = (e) => evilInbox.push(e.data);
  A.postMessage({ mafariGm: 'hi' }, '*', [evil.port2]);
  await tick();
  // 1) 不 bind 直接发请求
  evil.port1.postMessage(JSON.stringify({ t: 'set', sid: 's1', tok: TOK.s1, k: 'evil', v: '1' }));
  await tick();
  // 2) 另一个端口：错令牌 bind，然后发请求
  const evil2 = new MessageChannel(), inbox2 = [];
  evil2.port1.onmessage = (e) => inbox2.push(e.data);
  A.postMessage({ mafariGm: 'hi' }, '*', [evil2.port2]);
  await tick();
  evil2.port1.postMessage(JSON.stringify({ t: 'bind', sid: 's1', tok: 'guess' }));
  evil2.port1.postMessage(JSON.stringify({ t: 'set', sid: 's1', tok: 'guess', k: 'evil', v: '1' }));
  evil2.port1.postMessage(JSON.stringify({ t: 'xhr', sid: 's1', tok: 'guess', reqId: 'x:1', opts: JSON.stringify({ url: 'https://example.com/' }) }));
  await tick(3);
  B.__gm.set('shared', 'secret-update');           // 之后的广播，伪造端口一概收不到
  await tick(8);
  eq(evilInbox, ['ack'], 'forged port (never bound) receives nothing but the ack');
  eq(inbox2, ['ack'], 'forged port with a wrong token receives nothing');
  assert.ok(!nativeLog.some((m) => m.k === 'evil'), 'forged ports cannot reach native');
  assert.ok(!('s1::evil' in prefs));

  // —— 直连 shim：限流 / 一次性回调 / 握手上限 ——
  let t = 1_000_000;
  const C = pageCtx('https://example.com/c', () => t);
  loadRelay(C);
  const bridge = vm.runInContext(shimFor('s1', {}), C);
  // 一次性回调：原生故意回了两次，只应调用一次
  let dlCalls = 0;
  C['__mafariDlCallback_once1'] = () => { dlCalls++; };
  bridge.downloadAsync('https://example.com/x', 'x', 'once1');
  await tick(4);
  assert.strictEqual(dlCalls, 1, 'one-shot callback fires once');
  assert.strictEqual(C['__mafariDlCallback_once1'], undefined, 'callback removed before invocation');
  // 页面不能借 cb 调用任意函数
  let evilCalls = 0; C.evilFn = () => { evilCalls++; };
  const evilCb = { t: 'cb', fn: 'evilFn', args: [] };
  global.__menu.side.post(evilCb); await tick(2);
  assert.strictEqual(evilCalls, 0);
  // xhr 表上限：不 poll 就堆，第 257 个直接得到错误；61s 后结果 TTL 淘汰，又可用
  for (let i = 0; i < 256; i++) bridge.xhrStart('s1:' + i, JSON.stringify({ url: 'https://example.com/' + i }), '');
  bridge.xhrStart('s1:over', JSON.stringify({ url: 'https://example.com/' }), '');
  eq(JSON.parse(bridge.xhrPoll('s1:over')).error, 'too many pending requests', 'pending cap enforced');
  await tick(10);
  t += 61_000;
  bridge.xhrStart('s1:fresh', JSON.stringify({ url: 'https://example.com/fresh' }), '');
  await tick(5);
  const fresh = bridge.xhrPoll('s1:fresh');
  assert.ok(fresh && JSON.parse(fresh).state !== 'error' || fresh && JSON.parse(fresh).error !== 'too many pending requests', 'stale results evicted, new request accepted');
  assert.strictEqual(bridge.xhrPoll('s1:3'), null, 'unpolled result older than TTL was evicted');
  // 握手上限：页面狂刷 ready，最多 3 次 hi
  const D2 = pageCtx('https://example.com/d'); let his = 0;
  const op = D2.postMessage; D2.postMessage = (d, o, tr) => { if (d && d.mafariGm === 'hi') his++; return op(d, o, tr); };
  const b2 = vm.runInContext(shimFor('s1', {}), D2);
  b2.getValue('s1', 'x'); // 惰性握手：此刻才发 hi（没有中继，不会 ack）
  for (let i = 0; i < 20; i++) { D2.postMessage({ mafariGm: 'ready' }, '*'); await tick(); }
  assert.ok(his >= 1 && his <= 3, 'handshake attempts capped at 3, got ' + his);
  // 惰性：没人用桥就不握手
  const E = pageCtx('https://example.com/e'); let ehi = 0;
  const oe = E.postMessage; E.postMessage = (d, o, tr) => { if (d && d.mafariGm === 'hi') ehi++; return oe(d, o, tr); };
  vm.runInContext(shimFor('s1', {}), E);
  await tick(2); assert.strictEqual(ehi, 0, 'no handshake until the bridge is actually used');

  // —— 后台：增量同步（sig 没变的脚本不再带 code）；缺 code 且未注册 → 失败而不是崩 ——
  const before = registered.length;
  global.__bgSide.post({ t: 'sync', rev: 2, scripts: global.__payload.map((e) => { const o = Object.assign({}, e); delete o.code; return o; }) });
  await tick(3);
  assert.strictEqual(global.__synced.rev, 2); assert.strictEqual(global.__synced.failed, 0, 'slim sync keeps registrations');
  assert.strictEqual(registered.length, before, 'no re-registration for unchanged sigs');
  global.__bgSide.post({ t: 'sync', rev: 3, scripts: [Object.assign({}, global.__payload[0], { id: 'sNew', code: undefined })] });
  await tick(3);
  assert.strictEqual(global.__synced.failed, 1, 'missing code for an unknown script is a failure, not a crash');
  // 脚本全部移除：脚本与中继都注销（没有脚本的页面零中继成本）
  global.__bgSide.post({ t: 'sync', rev: 4, scripts: [] });
  await tick(3);
  assert.strictEqual(registered.length, 0, 'no scripts → no relay either');

  console.log('ALL PASS' + (LATE ? ' (late relay)' : ''));
  process.exit(0);
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
