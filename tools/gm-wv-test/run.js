/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// WebView 隔离世界通道的协议测试：用 Node vm 模拟
//   [页面 MAIN：真 mafari_gm_runtime.js + mafari_gm_shim.js] ⇄ [中继 ISOLATED：真 mafari_gm_relay_wv.js]
//   ⇄ [原生 GmChannel 的 JS 替身：addWebMessageListener 的 postMessage / JavaScriptReplyProxy]
// 不覆盖：真实 WebView（跨世界执行顺序、origin 规则、ReplyProxy 生命周期）、Kotlin 本身——见 docs/WEBVIEW_GM_CHANNEL.md 的真机清单。
//   node run.js                      中继先加载
//   RELAY_ORDER=late node run.js     脚本先执行、中继后加载（握手靠 ready 补发）
const vm = require('vm'), fs = require('fs'), path = require('path'), assert = require('assert');
const ASSETS = path.join(__dirname, '../../app/src/main/assets');
const rd = (p) => fs.readFileSync(path.join(ASSETS, p), 'utf8');
const LATE = process.env.RELAY_ORDER === 'late';
const tick = (n = 1) => new Promise((r) => setTimeout(r, 20 * n));
const eq = (a, b, m) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b), m);

// ---------- 原生侧（= GmChannel 的 JS 替身）----------
const SCRIPTS = { // 原生为每个脚本生成的能力令牌 + @match（用 origin 级匹配做校验）
  s1: { tok: 'tok-s1', matches: ['*://*/*'] },
  s2: { tok: 'tok-s2', matches: ['https://other.example/*'] },
  s3: { tok: 'tok-s3', matches: ['*://*/*'] },
};
const prefs = {}, files = {}, nativeLog = [], rejected = [], frames = [];
let fileSeq = 0;
function originMatches(origin, patterns) {
  const u = new URL(origin);
  return patterns.some((p) => {
    const m = /^(\*|https?):\/\/([^/]+)\//.exec(p); if (!m) return false;
    const schemeOk = m[1] === '*' ? /^https?:$/.test(u.protocol) : u.protocol === m[1] + ':';
    const hostOk = m[2] === '*' || m[2] === u.host || (m[2].startsWith('*.') && u.host.endsWith(m[2].slice(1)));
    return schemeOk && hostOk;
  });
}
function nativeOnPost(str, ctx) { // ctx = { origin, isMainFrame, reply }（浏览器给的，页面伪造不了）
  let m; try { m = JSON.parse(str); } catch (e) { rejected.push(['bad json']); return; }
  const sc = SCRIPTS[m.sid];
  const ok = !!sc && sc.tok === m.tok && originMatches(ctx.origin, sc.matches);
  const say = (o) => ctx.reply.postMessage(JSON.stringify(o));
  if (m.t === 'bind') {
    if (ok) { let f = frames.find((x) => x.reply === ctx.reply); if (!f) frames.push(f = { reply: ctx.reply, sids: new Set() }); f.sids.add(m.sid); }
    else rejected.push(m);
    say({ t: 'bound', pid: m.pid, ok });
    return;
  }
  if (!ok) { rejected.push(m); return; }
  nativeLog.push(m);
  switch (m.t) {
    case 'set': {
      prefs[m.sid + '::' + m.k] = m.v;
      const o = { t: 'val', id: m.sid, k: m.k, src: m.src };
      if (m.v.length > 64 * 1024) o.big = true; else o.raw = m.v;
      frames.forEach((f) => { if (f.sids.has(m.sid)) f.reply.postMessage(JSON.stringify(o)); }); // 广播（含发起方，shim 靠 src 去回声）
      break;
    }
    case 'getValue': { const v = prefs[m.sid + '::' + m.k]; say({ t: 'valRes', pid: m.pid, id: m.sid, k: m.k, raw: v === undefined ? null : v }); break; }
    case 'xhr':
      setTimeout(() => {
        const url = JSON.parse(m.opts).url;
        say({ t: 'xhrRes', pid: m.pid, reqId: m.reqId, json: JSON.stringify({ state: 'load', status: 200, statusText: 'OK', finalUrl: url, headers: 'content-type: text/plain\r\n', body: Buffer.from('hello from ' + url).toString('base64') }) });
      }, 5);
      break;
    case 'cookieList': say({ t: 'cookieRes', pid: m.pid, cbId: m.cbId, arr: [{ name: 'sid', value: 'abc', domain: 'example.com', path: '/', httpOnly: true, secure: true, session: true }] }); break;
    case 'notify': say({ t: 'cb', pid: m.pid, fn: '__mafariNtfCallback_' + m.cbId, args: ['click'] }); break;
    case 'download': if (m.cbId) { const cb = { t: 'cb', pid: m.pid, fn: '__mafariDlCallback_' + m.cbId, args: [true] }; say(cb); say(cb); } break; // 故意回两次
    case 'menu': global.__menu = { list: m.list, reply: ctx.reply, isMain: ctx.isMainFrame }; break;
    case 'ran': (global.__ran = global.__ran || new Set()).add(m.id); break;
    case 'stream': {
      const res = (r) => say({ t: 'streamRes', pid: m.pid, cbId: m.cbId, res: r });
      if (m.op === 'open') { const id = 'f' + (++fileSeq); files[id] = { name: m.name || 'file', chunks: [], closed: false }; res(JSON.stringify({ id, name: files[id].name })); }
      else if (m.op === 'write') { const f = files[m.id]; if (!f || f.closed) res('unknown stream'); else { f.chunks.push(Buffer.from(m.b64, 'base64')); res(''); } }
      else if (m.op === 'close') { const f = files[m.id]; if (!f) res('unknown stream'); else { f.closed = true; res(''); } }
      else if (m.op === 'abort') delete files[m.id];
      break;
    }
  }
}

// ---------- 页面（MAIN）+ 中继（ISOLATED）----------
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
/** = addWebMessageListener + addJavaScriptOnEvent：mafariGmRelay 只放进中继的沙箱（页面世界里没有） */
function loadRelay(page) {
  const nat = { onmessage: null, postMessage: (s) => setImmediate(() => nativeOnPost(s, { origin: page.location.origin, isMainFrame: true, reply })) };
  const reply = { postMessage: (s) => setImmediate(() => nat.onmessage && nat.onmessage({ data: s })) };
  vm.runInNewContext(rd('mafari_gm_relay_wv.js'), { window: vm.runInContext('window', page), mafariGmRelay: nat, setTimeout, clearTimeout });
  return nat;
}

// ---------- 原生侧拼源码（= GmChannel / UserscriptEngine.buildSource）----------
const runtime = rd('mafari_gm_runtime.js');
const shimTpl = rd('mafari_gm_shim.js');
const INLINE_MAX = 64 * 1024;
function shimFor(id, all) {
  const ents = Object.entries(all), at = shimTpl.lastIndexOf('__MAFARI_SNAPSHOT__');
  const snap = { id, tok: SCRIPTS[id].tok, values: Object.fromEntries(ents.filter(([, v]) => v.length <= INLINE_MAX)), big: ents.filter(([, v]) => v.length > INLINE_MAX).map(([k]) => k), caps: ['stream'] };
  return shimTpl.slice(0, at) + JSON.stringify(snap) + shimTpl.slice(at + '__MAFARI_SNAPSHOT__'.length);
}
function build(id, code, all, match) {
  const META = JSON.stringify({ id, name: 't-' + id, namespace: '', version: '1', description: '', author: '', homepage: '', icon: '', runAt: 'document-start', noframes: false,
    match: match || [], include: [], exclude: [], excludeMatch: [], userMatch: [], grant: [], require: [], resources: {}, meta: {}, metaBlock: '' });
  const rt = runtime.replace('__MAFARI_META__', () => META).replace('__MAFARI_FN__', 'function').replace('__MAFARI_CODE__', () => code);
  return `(function(__mafariBridge){\n${rt}\n})(${shimFor(id, all)});`;
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
    window.__gm = { set: GM_setValue, get: GM_getValue, GM: GM };
    GM_cookie.list({ url: 'https://example.com/' }, function (c) { window.__log.push(['cookies', c.length, c[0] && c[0].httpOnly]); });
    GM.getValue('bigA').then(function (v) { window.__log.push(['bigA', v && v.length]); });
    window.__log.push(['bigSync1', GM_getValue('bigB', 'dflt')]);
  `;
  const BIG_A = 'a'.repeat(100 * 1024), BIG_B = 'b'.repeat(70 * 1024);
  const initial = { counter: '7', name: '"mafari"', bigA: JSON.stringify(BIG_A), bigB: JSON.stringify(BIG_B) };
  Object.entries(initial).forEach(([k, v]) => { prefs['s1::' + k] = v; });
  const s1code = build('s1', userCode, initial);
  const s2code = build('s2', `GM_setValue('x', 1);`, {}, ['https://other.example/*']); // 匹配范围里没有本页：运行时直接 return
  const s3code = build('s3', `window.__s3 = { v: GM_getValue('mine', 'none') }; GM_setValue('mine', 's3-private');`, {});

  const A = pageCtx('https://example.com/'), B = pageCtx('https://example.com/other');
  const spyA = []; // 页面自己的 message 监听器：不得看到握手端口
  let natA, natB;
  if (!LATE) { natA = loadRelay(A); natB = loadRelay(B); }
  A.addEventListener('message', (ev) => spyA.push(ev), true);
  vm.runInContext(s1code, A); vm.runInContext(s1code, B);
  vm.runInContext(s2code, A);
  vm.runInContext(s3code, A);
  if (LATE) { await tick(); natA = loadRelay(A); natB = loadRelay(B); }
  await tick(10);

  const find = (log, k) => log.find((x) => x[0] === k);
  const logA = A.__log;
  // —— 基本能力 ——
  eq(find(logA, 'init'), ['init', 7, 'mafari'], 'snapshot read is synchronous');
  eq(find(logA, 'readback'), ['readback', 42], 'read-your-own-write');
  assert.strictEqual(prefs['s1::counter'], '42', 'native received GM_setValue (relay stamped sid/tok)');
  eq(find(logA, 'xhr'), ['xhr', 200, 'hello from https://example.com/api'], 'xhr result pushed through the channel');
  assert.ok(find(logA, 'ntf-click'), 'notification callback delivered');
  assert.ok(find(logA, 'dl-ok'), 'download callback delivered');
  eq(find(logA, 'cookies'), ['cookies', 1, true], 'HttpOnly cookies via native');
  assert.ok(global.__ran && global.__ran.has('s1'), 'native knows the script ran');
  assert.ok(global.__menu && JSON.parse(global.__menu.list)[0].title === 'Hello' && global.__menu.isMain, 'menu list reported from the main frame');
  assert.ok(!nativeLog.some((m) => m.sid === 's2'), 's2 never ran on this page: no traffic from it');
  assert.strictEqual(rejected.length, 0, 'every message reaching native passed token + origin check');
  // 值按脚本隔离：s3 读不到 s1 的值，写的值也不进 s1
  assert.strictEqual(A.__s3.v, 'none'); assert.strictEqual(prefs['s3::mine'], '"s3-private"'); assert.ok(!('s1::mine' in prefs));
  // 隔离世界：原生通道对象只在中继的沙箱里，页面世界看不到；握手端口页面监听器也看不到
  assert.strictEqual(vm.runInContext('typeof mafariGmRelay', A), 'undefined', 'native channel object is invisible to the page world');
  assert.strictEqual(vm.runInContext('typeof window.mafariGmRelay', A), 'undefined');
  if (!LATE) assert.ok(!spyA.some((ev) => ev.data && ev.data.mafariGm === 'hi'), 'page listeners never saw the handshake port');

  // —— 大值 ——
  eq(find(logA, 'bigA'), ['bigA', 100 * 1024], 'GM.getValue resolves a value that was not inlined');
  eq(find(logA, 'bigSync1'), ['bigSync1', 'dflt'], 'sync read of a non-inlined key misses first');
  assert.strictEqual(A.__gm.get('bigB').length, 70 * 1024, 'sync read hits after the prefetch');
  assert.ok(!s1code.includes('a'.repeat(1000)), 'big values are not inlined into the injected source');

  // —— 菜单执行：无 pid / 无 id → 本框架所有已绑定端口 ——
  const cid = JSON.parse(global.__menu.list)[0].id;
  global.__menu.reply.postMessage(JSON.stringify({ t: 'menuRun', cid, n: 1 }));
  await tick(4);
  assert.ok(find(B.__log, 'menu-run') || find(A.__log, 'menu-run'), 'menu command executed (delivered to the frame whose relay reported the menu)');

  // —— 值广播：B 写 → 原生 → 绑定了该脚本的所有框架（只发给同脚本）——
  B.__gm.set('shared', 'from-B');
  await tick(6);
  eq(find(A.__log, 'change'), ['change', 'shared', null, 'from-B', true], 'remote value change reaches the other frame');
  eq(find(B.__log, 'change'), ['change', 'shared', null, 'from-B', false], 'local change not echoed as remote');
  eq(A.__gm.get('shared'), 'from-B');
  assert.strictEqual(A.__s3.v, 'none', 's3 is not affected by s1 broadcasts');
  B.__gm.set('seq', 1); B.__gm.set('seq', 2);
  await tick(6);
  eq(B.__gm.get('seq'), 2, 'own echo is ignored (no stale overwrite)');
  B.__gm.set('bigA', 'c'.repeat(100 * 1024)); // 大值广播不带内容，A 之后经 getValue 拉
  await tick(10);
  const bigRes = await A.__gm.GM.getValue('bigA');
  assert.strictEqual(bigRes.length, 100 * 1024); assert.strictEqual(bigRes[0], 'c', 'big remote update visible via GM.getValue');

  // —— 异步文件流（caps: stream）——
  const h = await A.__gm.GM.openFileStream('x.bin', 'application/octet-stream');
  await h.write(new Blob(['hello'])); await h.write(new Blob([' world'])); await h.close();
  assert.strictEqual(Buffer.concat(files.f1.chunks).toString(), 'hello world', 'file stream written through the channel');
  assert.ok(files.f1.closed);
  const h2 = await A.__gm.GM.openFileStream('y.bin', ''); await h2.abort();
  await tick(2); assert.ok(!files.f2, 'abort removed the stream');
  await assert.rejects(h.write(new Blob(['late'])), /stream closed/, 'write after close rejected');

  // —— 敌意页面 / 伪造端口 ——
  const mkRaw = async (page) => {
    const ch = new MessageChannel(), inbox = [];
    ch.port1.onmessage = (e) => inbox.push(e.data);
    page.postMessage({ mafariGm: 'hi' }, '*', [ch.port2]);
    await tick();
    return { p: ch.port1, inbox, send: (o) => ch.port1.postMessage(JSON.stringify(o)) };
  };
  const evil1 = await mkRaw(A);                                        // 1) 不 bind 直接发请求
  evil1.send({ t: 'set', sid: 's1', tok: SCRIPTS.s1.tok, k: 'evil', v: '1' });
  const evil2 = await mkRaw(A);                                        // 2) 错令牌 bind
  evil2.send({ t: 'bind', sid: 's1', tok: 'guess' });
  evil2.send({ t: 'set', sid: 's1', tok: 'guess', k: 'evil', v: '1' });
  const evil3 = await mkRaw(A);                                        // 3) 令牌对但 origin 不在该脚本范围（偷到 s2 的令牌）
  evil3.send({ t: 'bind', sid: 's2', tok: SCRIPTS.s2.tok });
  evil3.send({ t: 'xhr', sid: 's2', tok: SCRIPTS.s2.tok, reqId: 'x:1', opts: JSON.stringify({ url: 'https://example.com/' }) });
  await tick(4);
  B.__gm.set('shared', 'secret-update'); await tick(6);
  eq(evil1.inbox, ['ack'], 'never-bound port receives nothing but the ack');
  eq(evil2.inbox, ['ack'], 'wrong-token port receives nothing');
  eq(evil3.inbox, ['ack'], 'right token from a foreign origin is refused by the native origin check');
  assert.ok(!nativeLog.some((m) => m.k === 'evil') && !('s1::evil' in prefs), 'forged ports cannot reach native');
  assert.ok(!nativeLog.some((m) => m.reqId === 'x:1'), 'foreign-origin xhr never executed');
  assert.ok(rejected.some((m) => m.t === 'bind' && m.sid === 's2'), 'origin-mismatched bind was rejected by native');

  // —— 合法端口：中继强制覆盖 sid/tok/pid；下行按 pid 精确投递；未知类型丢弃 ——
  const good1 = await mkRaw(A), good2 = await mkRaw(A);
  good1.send({ t: 'bind', sid: 's1', tok: SCRIPTS.s1.tok }); good2.send({ t: 'bind', sid: 's1', tok: SCRIPTS.s1.tok });
  await tick(3);
  good1.send({ t: 'set', sid: 's2', tok: SCRIPTS.s2.tok, pid: 999, k: 'zz', v: '"1"', src: 'raw' }); // 想冒充 s2 / 别的 pid
  good1.send({ t: 'evilType', sid: 's1', k: 'evil2' });
  good1.send({ t: 'bind', sid: 's2', tok: SCRIPTS.s2.tok });                                          // 绑定后再 bind：不是允许的类型，丢弃
  good1.send({ t: 'download', url: 'https://example.com/z', name: 'z', cbId: 'rawcb' });
  await tick(5);
  assert.strictEqual(prefs['s1::zz'], '"1"', 'relay stamped the bound sid, ignoring the forged one'); assert.ok(!('s2::zz' in prefs));
  assert.ok(!nativeLog.some((m) => m.t === 'evilType'), 'non-whitelisted message types are dropped by the relay');
  const cbs = (inbox) => inbox.filter((d) => d !== 'ack' && JSON.parse(d).t === 'cb');
  assert.ok(cbs(good1.inbox).length >= 1, 'callback routed to the requesting port by pid');
  eq(cbs(good2.inbox), [], 'another bound port in the same frame does not get it');
  assert.ok(!good1.inbox.concat(good2.inbox).some((d) => d !== 'ack' && 'pid' in JSON.parse(d)), 'pid is stripped before reaching the script side');

  // —— 一次性回调 / 不能借 cb 调任意函数 / 握手上限 / 惰性 ——
  let t = 1_000_000;
  const C = pageCtx('https://example.com/c', () => t);
  loadRelay(C);
  const bridge = vm.runInContext(shimFor('s1', {}), C);
  let dlCalls = 0; C['__mafariDlCallback_once1'] = () => { dlCalls++; };
  bridge.downloadAsync('https://example.com/x', 'x', 'once1');
  await tick(4);
  assert.strictEqual(dlCalls, 1, 'one-shot callback fires once'); assert.strictEqual(C['__mafariDlCallback_once1'], undefined);
  let evilCalls = 0; C.evilFn = () => { evilCalls++; };
  global.__menu.reply.postMessage(JSON.stringify({ t: 'cb', fn: 'evilFn', args: [] })); await tick(2);
  assert.strictEqual(evilCalls, 0, 'page cannot use cb to call arbitrary functions');
  const D2 = pageCtx('https://example.com/d'); let his = 0;
  const op = D2.postMessage; D2.postMessage = (d, o, tr) => { if (d && d.mafariGm === 'hi') his++; return op(d, o, tr); };
  const b2 = vm.runInContext(shimFor('s1', {}), D2);
  b2.getValue('s1', 'x');
  for (let i = 0; i < 20; i++) { D2.postMessage({ mafariGm: 'ready' }, '*'); await tick(); }
  assert.ok(his >= 1 && his <= 3, 'handshake attempts capped at 3, got ' + his);
  const E = pageCtx('https://example.com/e'); let ehi = 0;
  const oe = E.postMessage; E.postMessage = (d, o, tr) => { if (d && d.mafariGm === 'hi') ehi++; return oe(d, o, tr); };
  vm.runInContext(shimFor('s1', {}), E);
  await tick(2); assert.strictEqual(ehi, 0, 'no handshake until the bridge is actually used');
  // 中继重复注入：只有一份在工作（只有一个 ack）
  const F = pageCtx('https://example.com/f'); loadRelay(F); loadRelay(F);
  const rawF = await mkRaw(F); eq(rawF.inbox, ['ack'], 'a second injection of the relay is a no-op');
  // 没有 mafariGmRelay 对象（监听器没装上 / origin 不在规则内）：中继什么都不做，不报错
  const G = pageCtx('https://example.com/g');
  vm.runInNewContext(rd('mafari_gm_relay_wv.js'), { window: vm.runInContext('window', G), setTimeout, clearTimeout });
  const rawG = await mkRaw(G); eq(rawG.inbox, [], 'no native channel → relay stays inert');

  // —— 回归：同步桥（addJavascriptInterface 的旧桥，旧 WebView 上保留的降级路径）的 openFileStream 行为不变 ——
  const SB = pageCtx('https://example.com/sync');
  const sw = []; let failOpen = false, failWrite = false;
  SB.__syncBridge = {
    getValue: () => null, setValue() {}, deleteValue() {}, listValues: () => '[]', toast() {},
    streamOpen: (n) => failOpen ? JSON.stringify({ error: 'no space' }) : JSON.stringify({ id: 'S1', name: n }),
    streamWrite: (id, b) => { if (failWrite) return 'disk full'; sw.push(Buffer.from(b, 'base64').toString()); return ''; },
    streamClose: () => '', streamAbort() {},
  };
  const META = JSON.stringify({ id: 'sync1', name: 't', namespace: '', version: '1', description: '', author: '', homepage: '', icon: '', runAt: 'document-start', noframes: false,
    match: [], include: [], exclude: [], excludeMatch: [], userMatch: [], grant: [], require: [], resources: {}, meta: {}, metaBlock: '' });
  const rtSync = runtime.replace('__MAFARI_META__', () => META).replace('__MAFARI_FN__', 'function').replace('__MAFARI_CODE__', () => 'window.__GM = GM;');
  vm.runInContext(`(function(__mafariBridge){\n${rtSync}\n})(window.__syncBridge);`, SB);
  const sh = await SB.__GM.openFileStream('s.bin', ''); await sh.write(new Blob(['ab'])); await sh.write(new Blob(['cd'])); await sh.close();
  eq(sw, ['ab', 'cd'], 'sync bridge: stream written');
  failWrite = true;
  const sh2 = await SB.__GM.openFileStream('t.bin', '');
  await assert.rejects(sh2.write(new Blob(['x'])), /disk full/, 'sync bridge: write error propagates');
  failOpen = true;
  await assert.rejects(SB.__GM.openFileStream('u.bin', ''), /no space/, 'sync bridge: open error propagates');

  console.log('ALL PASS' + (LATE ? ' (late relay)' : ''));
  process.exit(0);
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
