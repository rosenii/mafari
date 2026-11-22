// 在 Node 里跑 app/src/main/assets/mafari_ext_shim.js：用假的 window / document / 桥模拟 WebView 环境。
// 用法：node tools/ext-shim-test/run.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.resolve(__dirname, '..', '..');
const shim = fs.readFileSync(path.join(root, 'app/src/main/assets/mafari_ext_shim.js'), 'utf8');
const cases = JSON.parse(fs.readFileSync(path.join(root, 'app/src/test/resources/ext/match_cases.json'), 'utf8'));

const BRIDGE = '__mext_test';
const ALL = { s: ['http', 'https', 'ws', 'wss', 'ftp', 'file', 'data'], h: null, sub: false, p: null, r: null };

/** 与 ExtensionInjection.assemble 保持同样的拼法 */
function assemble(cfg, entriesSrc) {
  return '(' + shim + ')(' + JSON.stringify(cfg) + ',' + entriesSrc + ');';
}

function makeEnv(opts) {
  opts = opts || {};
  const posted = [];
  const bridge = { postMessage: (s) => posted.push(JSON.parse(s)), onmessage: null };
  const docListeners = {}, winListeners = {};
  const timers = [];
  const appended = [];
  const doc = {
    readyState: opts.readyState || 'loading',
    head: opts.noHead ? null : { appendChild: (n) => appended.push(n) },
    documentElement: opts.noHead ? null : {},
    createElement: () => ({ textContent: '' }),
    addEventListener: (n, f) => { (docListeners[n] = docListeners[n] || []).push(f); },
  };
  const sandbox = {
    document: doc,
    console: { error: () => {} },
    URL, Promise, JSON, RegExp, Error, Object, Array,
    setTimeout: (f, ms) => { timers.push({ f, ms }); return timers.length; },
    navigator: { language: 'zh-CN' },
  };
  const clock = { t: 1000 };
  sandbox.Date = { now: () => clock.t };
  sandbox.window = sandbox;
  sandbox.top = opts.sub ? {} : sandbox;
  sandbox.location = { href: opts.href || 'https://a.com/' };
  sandbox.addEventListener = (n, f) => { (winListeners[n] = winListeners[n] || []).push(f); };
  sandbox[BRIDGE] = bridge;
  vm.createContext(sandbox);
  return {
    sandbox, bridge, posted, doc, timers, appended, docListeners, winListeners, clock,
    run(src) { vm.runInContext(src, sandbox); },
    native(obj) { bridge.onmessage({ data: typeof obj === 'string' ? obj : JSON.stringify(obj) }); },
    fireDoc(n) { (docListeners[n] || []).splice(0).forEach((f) => f()); },
    fireWin(n) { (winListeners[n] || []).splice(0).forEach((f) => f()); },
    runTimers() { timers.splice(0).forEach((t) => t.f()); },
  };
}

function cfg(extra) {
  return Object.assign({
    extId: 'demo@x.org', bridge: BRIDGE, origin: 'https://abc.ext.mafari.internal',
    manifest: { manifest_version: 3, name: 'Demo', version: '1' },
  }, extra || {});
}
function entry(js, o) {
  o = o || {};
  const m = JSON.stringify(o.m || [ALL]);
  const x = JSON.stringify(o.x || []);
  return '[{"m":' + m + ',"x":' + x + ',"all":' + !!o.all + ',"at":"' + (o.at || 'document_start') + '","css":' +
    JSON.stringify(o.css || []) + ',"js":[' + js.map((s) => 'function(browser,chrome){\n' + s + '\n}').join(',') + ']}]';
}

const tests = [];
function test(name, fn) { tests.push([name, fn]); }
const tick = () => new Promise((r) => setImmediate(r));

test('bridge global is deleted from the page after capture', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([''])));
  assert.strictEqual(env.sandbox[BRIDGE], undefined);
});

test('mainWorld: runs without a bridge, never talks to native, gets the page\'s own chrome/browser', () => {
  const env = makeEnv();
  delete env.sandbox[BRIDGE];
  env.sandbox.chrome = { pageOwn: 1 };
  env.run(assemble({ mainWorld: true }, entry(['window.seen = typeof browser + \':\' + (chrome && chrome.pageOwn)'])));
  assert.strictEqual(env.sandbox.seen, 'undefined:1');
  assert.strictEqual(env.posted.length, 0);
  assert.strictEqual(env.sandbox.browser, undefined);
});

test('mainWorld: match / exclude / run_at / css still apply', () => {
  let env = makeEnv({ href: 'https://b.com/' });
  const only = { s: ['https'], h: 'a.com', sub: false, p: null, r: null };
  delete env.sandbox[BRIDGE];
  env.run(assemble({ mainWorld: true }, entry(['window.hit = 1'], { m: [only] })));
  assert.strictEqual(env.sandbox.hit, undefined);

  env = makeEnv();
  delete env.sandbox[BRIDGE];
  env.run(assemble({ mainWorld: true }, entry(['window.hit = 1'], { at: 'document_end', css: ['a{}'] })));
  assert.strictEqual(env.sandbox.hit, undefined);
  env.fireDoc('DOMContentLoaded');
  assert.strictEqual(env.sandbox.hit, 1);
  assert.strictEqual(env.appended.length, 1);
});

test('isolatedWorld: browser/chrome become globals of that world (so scripting.executeScript code can use them)', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ isolatedWorld: true, tabId: 7 }), entry([''])));
  assert.strictEqual(typeof env.sandbox.chrome.runtime.sendMessage, 'function');
  assert.strictEqual(typeof env.sandbox.browser.storage.local.get, 'function');
  assert.strictEqual(env.sandbox[BRIDGE], undefined);
});

test('legacy page-world mode still keeps browser/chrome out of the page globals', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ tabId: 7 }), entry([''])));
  assert.strictEqual(env.sandbox.chrome, undefined);
  assert.strictEqual(env.sandbox.browser, undefined);
});

test('does nothing when the bridge is missing', () => {
  const env = makeEnv();
  delete env.sandbox[BRIDGE];
  env.run(assemble(cfg(), entry(['globalThis.ran = 1'])));
  assert.strictEqual(env.sandbox.ran, undefined);
});

test('match descriptors agree with the shared fixture', () => {
  for (const c of cases) {
    for (const [url, want] of Object.entries(c.urls)) {
      const env = makeEnv({ href: url });
      env.run(assemble(cfg(), entry(['window.hit = 1'], { m: [c.desc] })));
      assert.strictEqual(env.sandbox.hit === 1, want, c.pattern + ' vs ' + url);
    }
  }
});

test('exclude matches win; sub frames skipped unless all_frames', () => {
  let env = makeEnv({ href: 'https://a.com/private' });
  const excl = { s: ['https'], h: 'a.com', sub: false, p: null, r: '^\\/private$' };
  env.run(assemble(cfg(), entry(['window.hit = 1'], { x: [excl] })));
  assert.strictEqual(env.sandbox.hit, undefined);

  env = makeEnv({ sub: true });
  env.run(assemble(cfg(), entry(['window.hit = 1'])));
  assert.strictEqual(env.sandbox.hit, undefined);
  env = makeEnv({ sub: true });
  env.run(assemble(cfg(), entry(['window.hit = 1'], { all: true })));
  assert.strictEqual(env.sandbox.hit, 1);
});

test('run_at scheduling', () => {
  const start = makeEnv();
  start.run(assemble(cfg(), entry(['window.hit = 1'], { at: 'document_start' })));
  assert.strictEqual(start.sandbox.hit, 1);

  const end = makeEnv();
  end.run(assemble(cfg(), entry(['window.hit = 1'], { at: 'document_end' })));
  assert.strictEqual(end.sandbox.hit, undefined);
  end.fireDoc('DOMContentLoaded');
  assert.strictEqual(end.sandbox.hit, 1);

  const endLoaded = makeEnv({ readyState: 'interactive' });
  endLoaded.run(assemble(cfg(), entry(['window.hit = 1'], { at: 'document_end' })));
  assert.strictEqual(endLoaded.sandbox.hit, 1);

  const idle = makeEnv();
  idle.run(assemble(cfg(), entry(['window.n = (window.n || 0) + 1'], { at: 'document_idle' })));
  assert.strictEqual(idle.sandbox.n, undefined);
  idle.fireDoc('DOMContentLoaded');
  assert.strictEqual(idle.sandbox.n, undefined);
  idle.runTimers(); // 200ms 定时器先到
  idle.fireWin('load'); // load 再到：不会重复执行
  assert.strictEqual(idle.sandbox.n, 1);
});

test('run_at with native document-end hook (isolated world)', () => {
  const HOOK = '__mend_test';
  const nc = () => cfg({ isolatedWorld: true, nativeEnd: HOOK });

  // document_end：不再监听 DOMContentLoaded，等宿主的 document-end 脚本调钩子
  const end = makeEnv();
  end.run(assemble(nc(), entry(['window.hit = (window.hit || 0) + 1'], { at: 'document_end' })));
  assert.strictEqual(typeof end.sandbox[HOOK], 'function');
  assert.strictEqual((end.docListeners['DOMContentLoaded'] || []).length, 0);
  assert.strictEqual(end.sandbox.hit, undefined);
  end.sandbox[HOOK]();
  assert.strictEqual(end.sandbox.hit, 1);
  assert.strictEqual(end.sandbox[HOOK], undefined); // 触发后钩子被摘掉
  end.fireWin('load'); // load 兜底不会重复执行
  assert.strictEqual(end.sandbox.hit, 1);

  // 钩子没来：window.load 兜底
  const fallback = makeEnv();
  fallback.run(assemble(nc(), entry(['window.hit = (window.hit || 0) + 1'], { at: 'document_end' })));
  fallback.fireWin('load');
  assert.strictEqual(fallback.sandbox.hit, 1);

  // 脚本启动时文档已过 loading：立即执行
  const late = makeEnv({ readyState: 'interactive' });
  late.run(assemble(nc(), entry(['window.hit = 1'], { at: 'document_end' })));
  assert.strictEqual(late.sandbox.hit, 1);

  // document_idle：钩子之后再等 load 或 200ms
  const idle = makeEnv();
  idle.run(assemble(nc(), entry(['window.n = (window.n || 0) + 1'], { at: 'document_idle' })));
  idle.sandbox[HOOK]();
  assert.strictEqual(idle.sandbox.n, undefined);
  idle.runTimers();
  idle.fireWin('load');
  assert.strictEqual(idle.sandbox.n, 1);

  // 多个条目按声明顺序执行；一个抛错不影响后一个
  const order = makeEnv();
  const two = entry(['window.o = (window.o || "") + "a"; throw new Error("x")'], { at: 'document_end' }).slice(0, -1) + ',' +
    entry(['window.o = (window.o || "") + "b"'], { at: 'document_end' }).slice(1);
  order.run(assemble(nc(), two));
  order.sandbox[HOOK]();
  assert.strictEqual(order.sandbox.o, 'ab');

  // 没设 nativeEnd（页面世界 / 旧路径）：仍然自己监听 DOMContentLoaded
  const legacy = makeEnv();
  legacy.run(assemble(cfg(), entry(['window.hit = 1'], { at: 'document_end' })));
  assert.strictEqual(legacy.sandbox[HOOK], undefined);
  legacy.fireDoc('DOMContentLoaded');
  assert.strictEqual(legacy.sandbox.hit, 1);
});

test('css is inserted; defers when document has no head yet', () => {
  const a = makeEnv();
  a.run(assemble(cfg(), entry([''], { css: ['body{color:red}'] })));
  assert.strictEqual(a.appended.length, 1);
  assert.strictEqual(a.appended[0].textContent, 'body{color:red}');

  const b = makeEnv({ noHead: true });
  b.run(assemble(cfg(), entry([''], { css: ['x{}'] })));
  assert.strictEqual(b.appended.length, 0);
  b.doc.head = { appendChild: (n) => b.appended.push(n) };
  b.fireDoc('DOMContentLoaded');
  assert.strictEqual(b.appended.length, 1);
});

test('a throwing content script does not stop the next one', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry(['throw new Error("boom")', 'window.second = 1'])));
  assert.strictEqual(env.sandbox.second, 1);
});

test('storage.get: promise style, request shape and null result', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.p = browser.storage.local.get("a").then(function (r) { window.res = r; });' +
    'window.q = browser.storage.local.clear().then(function (r) { window.clearRes = r; });',
  ])));
  assert.deepStrictEqual(env.posted[0], { extId: 'demo@x.org', id: '1', api: 'storage.local.get', args: ['a'] });
  assert.strictEqual(env.posted[1].api, 'storage.local.clear');
  env.native({ id: '1', ok: true, result: { a: 5 } });
  env.native({ id: '2', ok: true, result: null });
  await env.sandbox.p; await env.sandbox.q;
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.res)), { a: 5 });
  assert.strictEqual(env.sandbox.clearRes, undefined);
});

test('storage.get with undefined keys sends null', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry(['browser.storage.sync.get();'])));
  assert.deepStrictEqual(env.posted[0].args, [null]);
});

test('errors reject with message and code; chrome callback sees lastError only inside callback', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.pr = browser.storage.local.set({a:1}).catch(function (e) { window.err = e; });' +
    'chrome.storage.local.set({b:2}, function () { window.lastInCb = chrome.runtime.lastError && chrome.runtime.lastError.message; });' +
    'window.getLast = function () { return chrome.runtime.lastError; };',
  ])));
  env.native({ id: '1', ok: false, error: { code: 'QUOTA', message: 'too big' } });
  env.native({ id: '2', ok: false, error: { code: 'DENIED_PERMISSION', message: 'nope' } });
  await env.sandbox.pr; await tick();
  assert.strictEqual(env.sandbox.err.message, 'too big');
  assert.strictEqual(env.sandbox.err.code, 'QUOTA');
  assert.strictEqual(env.sandbox.lastInCb, 'nope');
  assert.strictEqual(env.sandbox.getLast(), undefined);
});

test('chrome callback success path', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry(['chrome.storage.local.get(["k"], function (r) { window.got = r; });'])));
  env.native({ id: '1', ok: true, result: { k: 'v' } });
  await tick();
  assert.strictEqual(env.sandbox.got.k, 'v');
});

test('storage.onChanged fires on both the global and per-area events', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.log = [];' +
    'browser.storage.onChanged.addListener(function (c, area) { window.log.push("g:" + area + ":" + c.a.newValue); });' +
    'browser.storage.local.onChanged.addListener(function (c) { window.log.push("l:" + c.a.newValue); });' +
    'browser.storage.sync.onChanged.addListener(function () { window.log.push("WRONG"); });',
  ])));
  env.native({ event: 'storage.onChanged', payload: { areaName: 'local', changes: { a: { newValue: 3 } } } });
  assert.deepStrictEqual(Array.from(env.sandbox.log), ['g:local:3', 'l:3']);
});

test('runtime.sendMessage argument normalisation', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'browser.runtime.sendMessage({x:1});' +
    'browser.runtime.sendMessage("hello");' +
    'browser.runtime.sendMessage("other@x.org", {y:2});' +
    'browser.runtime.sendMessage("other@x.org", "m", {includeTlsChannelId:false});' +
    'chrome.runtime.sendMessage("ping", function () {});',
  ])));
  const a = env.posted.map((p) => p.args);
  assert.deepStrictEqual(a[0], [null, { x: 1 }, null]);
  assert.deepStrictEqual(a[1], [null, 'hello', null]);
  assert.deepStrictEqual(a[2], ['other@x.org', { y: 2 }, null]);
  assert.deepStrictEqual(a[3], ['other@x.org', 'm', { includeTlsChannelId: false }]);
  assert.deepStrictEqual(a[4], [null, 'ping', null]);
});


test('tabs.sendMessage forwards tabId, message and options to the native router', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), '[]'));
  const p = env.sandbox.browser.tabs.sendMessage(42, { ping: true }, { frameId: 0 });
  const call = env.posted.find((x) => x.api === 'tabs.sendMessage');
  assert.deepStrictEqual(call.args, [42, { ping: true }, { frameId: 0 }]);
  env.native({ id: call.id, ok: true, result: 'pong' });
  assert.strictEqual(await p, 'pong');
});

test('tabs.captureVisibleTab supports options and window overloads', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), '[]'));
  const p1 = env.sandbox.browser.tabs.captureVisibleTab({ format: 'jpeg', quality: 65 });
  const c1 = env.posted.find((x) => x.api === 'tabs.captureVisibleTab');
  assert.deepStrictEqual(c1.args, [{ format: 'jpeg', quality: 65 }]);
  env.native({ id: c1.id, ok: true, result: 'data:image/jpeg;base64,AA==' });
  assert.strictEqual(await p1, 'data:image/jpeg;base64,AA==');
  const p2 = env.sandbox.chrome.tabs.captureVisibleTab(1, { format: 'png' });
  const c2 = env.posted.filter((x) => x.api === 'tabs.captureVisibleTab')[1];
  assert.deepStrictEqual(c2.args, [1, { format: 'png' }]);
  env.native({ id: c2.id, ok: true, result: 'data:image/png;base64,AA==' });
  assert.strictEqual(await p2, 'data:image/png;base64,AA==');
});

test('tabs duplicate and move forward to native handlers', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry([
    'window.dup = browser.tabs.duplicate(4); window.mov = browser.tabs.move([4,5], {index:0});'
  ])));
  const calls = env.posted.filter(x => x.api !== 'runtime.hello');
  assert.deepStrictEqual(calls.map(x => x.api), ['tabs.duplicate', 'tabs.move']);
  assert.deepStrictEqual(calls[1].args, [[4,5], { index: 0 }]);
  env.native({ id: calls[0].id, ok: true, result: { id: 8 } });
  env.native({ id: calls[1].id, ok: true, result: [{ id: 4 }, { id: 5 }] });
  await Promise.all([env.sandbox.dup, env.sandbox.mov]);
});

test('content-script mode handshakes only when installed for a browser tab', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: false, tabId: 42 }), '[]'));
  assert.strictEqual(env.sandbox.window.browser, undefined);
  assert.ok(env.posted.some((x) => x.api === 'runtime.hello'));
});

test('tabs.onHighlighted dispatches Chrome highlightInfo payload', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry(['browser.tabs.onHighlighted.addListener(function (info) { window.highlighted = info; });'])));
  env.native({ event: 'tabs.onHighlighted', payload: { highlightInfo: { tabIds: [7], windowId: 1 } } });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.highlighted)), { tabIds: [7], windowId: 1 });
});

test('chrome.extension promise methods honour callback style (Dark Reader collectData awaits isAllowedFileSchemeAccess(cb))', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry([`
    window.__r = [];
    chrome.extension.isAllowedFileSchemeAccess(function (v) { window.__r.push(['file', v]); });
    chrome.extension.isAllowedIncognitoAccess(function (v) { window.__r.push(['incog', v]); });
    browser.extension.isAllowedFileSchemeAccess().then(function (v) { window.__r.push(['promise', v]); });
    window.__url = chrome.extension.getURL('a.html');
  `])));
  await tick(); await tick();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__r)).sort(), [['file', false], ['incog', false], ['promise', false]]);
  assert.ok(String(env.sandbox.__url).endsWith('/a.html'));
});

test('runtime.onMessage: sync, return true + sendResponse, promise, and no listener', async () => {
  // 没有监听器
  let env = makeEnv();
  env.run(assemble(cfg(), entry([''])));
  env.native({ type: 'message', cid: 'c0', message: 'x' });
  assert.deepStrictEqual(env.posted[0].args, ['c0', null, { noReceiver: true }]);

  // 监听器收到消息但没有回复：标记 noResponse（不是 noReceiver），由 broker 决定发送方 resolve undefined
  env = makeEnv();
  env.run(assemble(cfg(), entry(['browser.runtime.onMessage.addListener(function () { });'])));
  env.native({ type: 'message', cid: 'c1', message: 'x' });
  assert.deepStrictEqual(env.posted[0].args, ['c1', null, { noResponse: true }]);

  // 同步 sendResponse：即使监听器没有 return true，也立即完成回复
  env = makeEnv();
  env.run(assemble(cfg(), entry(['browser.runtime.onMessage.addListener(function (m, s, send) { send("sync-answer"); });'])));
  env.native({ type: 'message', cid: 'cSync', message: 'x' });
  assert.deepStrictEqual(env.posted[0].args, ['cSync', 'sync-answer', null]);

  // return true + 稍后 sendResponse
  env = makeEnv();
  env.run(assemble(cfg(), entry([
    'browser.runtime.onMessage.addListener(function (m, s, send) { window.send = send; return true; });',
  ])));
  env.native({ type: 'message', cid: 'c2', message: { q: 1 } });
  assert.strictEqual(env.posted.length, 0);
  env.sandbox.send({ ans: 2 });
  env.sandbox.send({ ans: 3 }); // 只有第一次有效
  assert.strictEqual(env.posted.length, 1);
  assert.deepStrictEqual(env.posted[0].args, ['c2', { ans: 2 }, null]);

  // Promise 返回值就是回复
  env = makeEnv();
  env.run(assemble(cfg(), entry([
    'browser.runtime.onMessage.addListener(function (m) { return Promise.resolve({ echo: m }); });',
  ])));
  env.native({ type: 'message', cid: 'c3', message: 'hi' });
  await tick();
  assert.deepStrictEqual(env.posted[0].args, ['c3', { echo: 'hi' }, null]);
});

test('MV3 offscreen namespace is permission gated and exposes lifecycle methods', async () => {
  let env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1' } }), entry([''])));
  assert.strictEqual(env.sandbox.browser.offscreen, undefined);

  env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['offscreen'] } }), entry([''])));
  assert.strictEqual(typeof env.sandbox.browser.offscreen.createDocument, 'function');
  assert.strictEqual(typeof env.sandbox.chrome.offscreen.closeDocument, 'function');
  env.sandbox.browser.offscreen.hasDocument();
  await tick();
  assert.ok(env.posted.some((p) => p.api === 'offscreen.hasDocument'));
});

test('getManifest is synchronous and returns a fresh copy; getURL and platform', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'var m = browser.runtime.getManifest(); m.name = "changed";' +
    'window.name2 = browser.runtime.getManifest().name;' +
    'window.u = browser.runtime.getURL("/a/b.html");' +
    'window.id = chrome.runtime.id;' +
    'window.ext = browser.extension.getURL("x.js");' +
    'browser.runtime.getPlatformInfo();',
  ])));
  assert.strictEqual(env.sandbox.name2, 'Demo');
  assert.strictEqual(env.sandbox.u, 'https://abc.ext.mafari.internal/a/b.html');
  assert.strictEqual(env.sandbox.ext, 'https://abc.ext.mafari.internal/x.js');
  assert.strictEqual(env.sandbox.id, 'demo@x.org');
  assert.strictEqual(env.posted[0].api, 'runtime.getPlatformInfo');
});

test('runtime.openOptionsPage uses the native API and supports promise + callback styles', async () => {
  const env = makeEnv({ page: true });
  env.run(assemble(cfg({ page: true }), entry(['window.p = browser.runtime.openOptionsPage(); chrome.runtime.openOptionsPage(function () { window.cb = true; });'])));
  const calls = env.posted.filter((x) => x.api === 'runtime.openOptionsPage');
  assert.strictEqual(calls.length, 2);
  assert.deepStrictEqual(calls.map((x) => x.args), [[], []]);
  env.native({ id: calls[0].id, ok: true, result: null });
  env.native({ id: calls[1].id, ok: true, result: null });
  await env.sandbox.p;
  await tick();
  assert.strictEqual(env.sandbox.cb, true);
});

test('permissions API exposes promise and chrome callback query methods', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.p = browser.permissions.getAll().then(function (v) { window.all = v; });' +
    'chrome.permissions.contains({permissions:["storage"]}, function (v) { window.hasStorage = v; });'
  ])));
  assert.strictEqual(env.posted[0].api, 'permissions.getAll');
  assert.strictEqual(env.posted[1].api, 'permissions.contains');
  assert.deepStrictEqual(env.posted[1].args, [{ permissions: ['storage'] }]);
  env.native({ id: env.posted[0].id, ok: true, result: { permissions: ['storage'], origins: [] } });
  env.native({ id: env.posted[1].id, ok: true, result: true });
  await env.sandbox.p;
  await tick();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.all)), { permissions: ['storage'], origins: [] });
  assert.strictEqual(env.sandbox.hasStorage, true);
});

test('webNavigation API is exposed only when declared and forwards frame queries', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['webNavigation'] } }), entry([''])));
  assert.strictEqual(typeof env.sandbox.browser.webNavigation.getFrame, 'function');
  const p = env.sandbox.browser.webNavigation.getFrame({ tabId: 7, frameId: 0 });
  const call = env.posted.find(x => x.api === 'webNavigation.getFrame');
  assert.deepStrictEqual(call.args, [{ tabId: 7, frameId: 0 }]);
  env.native({ id: call.id, ok: true, result: { tabId: 7, frameId: 0 } });
  assert.deepStrictEqual(await p, { tabId: 7, frameId: 0 });
});

test('webRequest exposes filtered onBeforeRequest and registers/removes listeners natively', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'Demo', version: '1', permissions: ['webRequest', 'https://*.example.com/*'] } }), entry([
    'window.requests = []; window.listener = function (d) { window.requests.push(d.url); };' +
    'browser.webRequest.onBeforeRequest.addListener(window.listener, { urls: ["https://*.example.com/*"], types: ["script"], tabId: 4 }, []);'
  ])));
  const add = env.posted.find(x => x.api === 'webRequest.addListener');
  assert.ok(add);
  assert.strictEqual(add.args[0], 'onBeforeRequest');
  assert.strictEqual(add.args[2].tabId, 4);
  env.native({ event: 'webRequest.onBeforeRequest', payload: { url: 'https://cdn.example.com/a.js', tabId: 4, listenerTokens: [add.args[1]] } });
  assert.deepStrictEqual(Array.from(env.sandbox.requests), ['https://cdn.example.com/a.js']);
  env.sandbox.browser.webRequest.onBeforeRequest.removeListener(env.sandbox.listener);
  const remove = env.posted.find(x => x.api === 'webRequest.removeListener');
  assert.ok(remove);
  assert.strictEqual(remove.args[0], add.args[1]);
  await tick();
});

test('webRequest blocking listener returns cancel / redirect decisions to native', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'Demo', version: '1', permissions: ['webRequest', 'webRequestBlocking', 'https://*.example.com/*'] } }), entry([
    'window.listener = function () { return { cancel: true }; };' +
    'browser.webRequest.onBeforeRequest.addListener(window.listener, { urls: ["https://*.example.com/*"] }, ["blocking"]);'
  ])));
  const add = env.posted.find(x => x.api === 'webRequest.addListener');
  assert.ok(add);
  assert.deepStrictEqual(Array.from(add.args[3]), ['blocking']);
  env.native({ event: 'webRequest.onBeforeRequest', payload: { url: 'https://cdn.example.com/a.js', listenerTokens: [add.args[1]], blockingToken: 'wrb-test' } });
  const resolve = env.posted.find(x => x.api === 'webRequest.resolveBlocking');
  assert.ok(resolve);
  assert.strictEqual(resolve.args[0], 'wrb-test');
  assert.strictEqual(resolve.args[1].cancel, true);
  await tick();
});

test('Mafari native userscripts API is exposed only to pages declaring mafariScripts', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['mafariScripts'] } }), entry([''])));
  assert.ok(env.sandbox.browser['mafari'].userscripts.getAll);
});

test('webRequest main-frame terminal events register and dispatch independently', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['webRequest', 'https://example.com/*'] } }), entry([
    'window.completed = []; window.failed = [];' +
    'window.done = function (d) { window.completed.push(d.requestId); };' +
    'window.fail = function (d) { window.failed.push(d.error); };' +
    'browser.webRequest.onCompleted.addListener(window.done, { urls: ["https://example.com/*"] }, []);' +
    'browser.webRequest.onErrorOccurred.addListener(window.fail, { urls: ["https://example.com/*"] }, []);'
  ])));
  const adds = env.posted.filter(x => x.api === 'webRequest.addListener');
  assert.deepStrictEqual(adds.map(x => x.args[0]).sort(), ['onCompleted', 'onErrorOccurred']);
  const done = adds.find(x => x.args[0] === 'onCompleted');
  const fail = adds.find(x => x.args[0] === 'onErrorOccurred');
  env.native({ event: 'webRequest.onCompleted', payload: { requestId: 'r1', url: 'https://example.com/', listenerTokens: [done.args[1]] } });
  env.native({ event: 'webRequest.onErrorOccurred', payload: { requestId: 'r2', url: 'https://example.com/bad', error: 'net::ERR_FAILED', listenerTokens: [fail.args[1]] } });
  assert.deepStrictEqual(Array.from(env.sandbox.completed), ['r1']);
  assert.deepStrictEqual(Array.from(env.sandbox.failed), ['net::ERR_FAILED']);
  await tick();
});

test('webNavigation events dispatch committed and error details to listeners', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['webNavigation'] } }), entry([
    'window.nav = []; window.navErrors = [];' +
    'browser.webNavigation.onCommitted.addListener(function (d) { window.nav.push(d.tabId + ":" + d.frameId + ":" + d.url); });' +
    'browser.webNavigation.onErrorOccurred.addListener(function (d) { window.navErrors.push(d.error); });'
  ])));
  env.native({ event: 'webNavigation.onCommitted', payload: { tabId: 9, frameId: 0, url: 'https://example.com/', timeStamp: 1 } });
  env.native({ event: 'webNavigation.onErrorOccurred', payload: { tabId: 9, frameId: 0, url: 'https://example.com/', error: 'net::ERR_FAILED', timeStamp: 2 } });
  assert.deepStrictEqual(Array.from(env.sandbox.nav), ['9:0:https://example.com/']);
  assert.deepStrictEqual(Array.from(env.sandbox.navErrors), ['net::ERR_FAILED']);
});

test('permissions request/remove and lifecycle events are exposed', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry([
    'window.added = null; window.removed = null;' +
    'browser.permissions.onAdded.addListener(function (p) { window.added = p; });' +
    'browser.permissions.onRemoved.addListener(function (p) { window.removed = p; });' +
    'window.req = browser.permissions.request({permissions:["alarms"], origins:["https://x.test/*"]});' +
    'window.rem = browser.permissions.remove({permissions:["alarms"]});'
  ])));
  const calls = env.posted.filter(x => x.api !== 'runtime.hello');
  assert.deepStrictEqual(calls.map(x => x.api), ['permissions.request', 'permissions.remove']);
  env.native({ id: calls[0].id, ok: true, result: true });
  env.native({ id: calls[1].id, ok: true, result: true });
  env.native({ event: 'permissions.onAdded', payload: { permissions: ['alarms'], origins: [] } });
  env.native({ event: 'permissions.onRemoved', payload: { permissions: ['alarms'], origins: [] } });
  await Promise.all([env.sandbox.req, env.sandbox.rem]);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.added)), { permissions: ['alarms'], origins: [] });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.removed)), { permissions: ['alarms'], origins: [] });
});

test('runtime.connect returns a Port, queues early messages, and receives remote messages/disconnect', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.connected = 0; window.received = null; window.disconnected = 0;' +
    'var port = browser.runtime.connect({name:"mafari-test"});' +
    'port.onMessage.addListener(function (m) { window.received = m; });' +
    'port.onDisconnect.addListener(function () { window.disconnected++; });' +
    'port.postMessage({hello:"world"}); window.port = port;'
  ])));
  assert.strictEqual(env.posted[0].api, 'runtime.connect');
  assert.deepStrictEqual(env.posted[0].args, [null, { name: 'mafari-test' }]);
  env.native({ id: env.posted[0].id, ok: true, result: 'port-1' });
  await tick();
  assert.strictEqual(env.posted[1].api, 'runtime.port.postMessage');
  assert.deepStrictEqual(env.posted[1].args, ['port-1', { hello: 'world' }]);
  env.native({ type: 'portMessage', portId: 'port-1', message: { answer: 42 } });
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.received)), { answer: 42 });
  env.native({ type: 'portDisconnect', portId: 'port-1' });
  assert.strictEqual(env.sandbox.disconnected, 1);
  env.native('not json');
  env.native({ id: '999', ok: true });
  env.native({ event: 'whatever' });
});

test('runtime.Port.disconnect closes only the local side and does not fire its own onDisconnect', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'window.disconnected = 0;' +
    'var port = browser.runtime.connect({name:"local-close"});' +
    'port.onDisconnect.addListener(function () { window.disconnected++; });' +
    'window.port = port;'
  ])));
  const connect = env.posted.find(x => x.api === 'runtime.connect');
  env.native({ id: connect.id, ok: true, result: 'port-local-close' });
  await tick();
  env.sandbox.port.disconnect();
  assert.strictEqual(env.sandbox.disconnected, 0);
  const close = env.posted.find(x => x.api === 'runtime.port.disconnect');
  assert.deepStrictEqual(close.args, ['port-local-close']);
  // A late native disconnect cannot notify the already-closed local Port a second time.
  env.native({ type: 'portDisconnect', portId: 'port-local-close' });
  assert.strictEqual(env.sandbox.disconnected, 0);
});

test('runtime.onConnect receives inbound Ports', () => {
  const env = makeEnv({ page: true });
  env.run(assemble(cfg({ page: true }), entry([
    'window.inbound = null; browser.runtime.onConnect.addListener(function (p) {' +
    'window.inbound = p.name; p.onMessage.addListener(function (m) { window.inboundMessage = m; });' +
    '});'
  ])));
  env.native({ type: 'portConnect', portId: 'remote-1', name: 'worker', sender: { id: 'local-test' } });
  assert.strictEqual(env.sandbox.inbound, 'worker');
  env.native({ type: 'portMessage', portId: 'remote-1', message: 'hello' });
  assert.strictEqual(env.sandbox.inboundMessage, 'hello');
});

test('circular args reject instead of throwing synchronously', async () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([
    'var o = {}; o.self = o;' +
    'window.p = browser.storage.local.set(o).then(function () { window.ok = 1; }, function (e) { window.failed = e.message; });',
  ])));
  await env.sandbox.p;
  assert.strictEqual(env.sandbox.ok, undefined);
  assert.ok(env.sandbox.failed);
  assert.strictEqual(env.posted.length, 0);
});

test('page mode: browser/chrome become non-enumerable globals and a hello handshake is sent', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), '[]'));
  assert.ok(env.sandbox.browser && env.sandbox.chrome);
  assert.strictEqual(typeof env.sandbox.browser.storage.local.get, 'function');
  assert.strictEqual(typeof env.sandbox.chrome.runtime.sendMessage, 'function');
  assert.strictEqual(Object.keys(env.sandbox).indexOf('browser'), -1);
  assert.strictEqual(env.posted[0].api, 'runtime.hello');
  assert.strictEqual(env.posted[0].extId, 'demo@x.org');
});

test('content-script mode never defines window.browser / window.chrome and sends no hello', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry([''])));
  assert.strictEqual(env.sandbox.browser, undefined);
  assert.strictEqual(env.sandbox.chrome, undefined);
  assert.strictEqual(env.posted.length, 0);
});

test('page mode: onMessage listener on a page receives native delivery and replies via runtime.reply', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), '[]'));
  env.sandbox.browser.runtime.onMessage.addListener(function (m) { return Promise.resolve({ got: m }); });
  env.native({ type: 'message', cid: 'k1', message: 'hello', sender: { id: 'demo@x.org' } });
  await tick();
  const reply = env.posted.find((p) => p.api === 'runtime.reply');
  assert.deepStrictEqual(reply.args, ['k1', { got: 'hello' }, null]);
});

const ALARM_MANIFEST = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['alarms'] };

test('alarms: only on extension pages that declare the permission', () => {
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest: ALARM_MANIFEST }), entry(['window.has = typeof browser.alarms;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined', 'content script must not get alarms');

  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.alarms, undefined);
  assert.strictEqual(noPerm.sandbox.chrome.alarms, undefined);

  const ok = makeEnv();
  ok.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  assert.strictEqual(typeof ok.sandbox.browser.alarms.create, 'function');
  assert.strictEqual(typeof ok.sandbox.chrome.alarms.onAlarm.addListener, 'function');
});

test('alarms: request shapes (name / info overloads, optional names become null)', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  const a = env.sandbox.browser.alarms;
  a.create({ delayInMinutes: 1 });
  a.create('n', { periodInMinutes: 2 });
  a.get(); a.get('n'); a.getAll(); a.clear(); a.clear('n'); a.clearAll();
  const calls = env.posted.filter((p) => p.api && p.api.startsWith('alarms.')).map((p) => [p.api, p.args]);
  assert.deepStrictEqual(calls, [
    ['alarms.create', [null, { delayInMinutes: 1 }]],
    ['alarms.create', ['n', { periodInMinutes: 2 }]],
    ['alarms.get', [null]], ['alarms.get', ['n']], ['alarms.getAll', []],
    ['alarms.clear', [null]], ['alarms.clear', ['n']], ['alarms.clearAll', []],
  ]);
});

test('alarms: null result resolves to undefined; chrome callback style works', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  const p = env.sandbox.browser.alarms.get('missing');
  const id = env.posted[env.posted.length - 1].id;
  env.native({ id, ok: true, result: null });
  assert.strictEqual(await p, undefined);
  let got = 'unset';
  env.sandbox.chrome.alarms.clear('x', (r) => { got = r; });
  env.native({ id: env.posted[env.posted.length - 1].id, ok: true, result: false });
  await tick();
  assert.strictEqual(got, false);
});

test('events: alarms.onAlarm / runtime.onStartup / runtime.onInstalled reach listeners via the dispatch table', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  const log = [];
  const b = env.sandbox.browser;
  b.alarms.onAlarm.addListener((al) => log.push('alarm:' + al.name));
  b.runtime.onStartup.addListener(() => log.push('startup'));
  b.runtime.onInstalled.addListener((d) => log.push('installed:' + d.reason));
  env.native({ event: 'runtime.onStartup', payload: {} });
  env.native({ event: 'alarms.onAlarm', payload: { name: 'a', scheduledTime: 1 } });
  env.native({ event: 'runtime.onInstalled', payload: { reason: 'update', previousVersion: '0.9' } });
  env.native({ event: 'no.such.event', payload: {} });
  assert.deepStrictEqual(log, ['startup', 'alarm:a', 'installed:update']);
});

test('events: early events are buffered per kind (max 32), replayed in order once a listener registers', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  env.native({ event: 'runtime.onStartup', payload: {} });
  for (let i = 0; i < 40; i++) env.native({ event: 'alarms.onAlarm', payload: { name: 'a' + i } });
  const log = [];
  env.sandbox.browser.alarms.onAlarm.addListener((al) => log.push(al.name));
  assert.deepStrictEqual(log, [], 'replay is asynchronous');
  env.runTimers();
  assert.strictEqual(log.length, 32);
  assert.strictEqual(log[0], 'a0');
  assert.strictEqual(log[31], 'a31');
  // startup was buffered separately and is still available
  let started = 0;
  env.sandbox.browser.runtime.onStartup.addListener(() => started++);
  env.runTimers();
  assert.strictEqual(started, 1);
});

test('events: buffering stops 5s after the page started; non-buffered events are never queued', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: ALARM_MANIFEST }), '[]'));
  env.clock.t += 5001;
  env.native({ event: 'alarms.onAlarm', payload: { name: 'late' } });
  env.native({ event: 'storage.onChanged', payload: { areaName: 'local', changes: { k: { newValue: 1 } } } });
  const log = [];
  env.sandbox.browser.alarms.onAlarm.addListener((al) => log.push(al.name));
  env.sandbox.browser.storage.onChanged.addListener(() => log.push('storage'));
  env.runTimers();
  assert.deepStrictEqual(log, []);
});

test('events: content scripts never buffer', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ manifest: ALARM_MANIFEST }), entry(['window.api = browser;'])));
  env.native({ event: 'runtime.onStartup', payload: {} });
  let n = 0;
  env.sandbox.api.runtime.onStartup.addListener(() => n++);
  env.runTimers();
  assert.strictEqual(n, 0);
});

const CLIPBOARD_MANIFEST = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['clipboardRead', 'clipboardWrite'] };

test('clipboard: only extension pages with declared permissions expose the matching methods', () => {
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest: CLIPBOARD_MANIFEST }), entry(['window.has = typeof browser.clipboard;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined', 'content script must not get clipboard');

  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.clipboard, undefined);
  assert.strictEqual(noPerm.sandbox.chrome.clipboard, undefined);

  const ok = makeEnv();
  ok.run(assemble(cfg({ page: true, manifest: CLIPBOARD_MANIFEST }), '[]'));
  assert.strictEqual(typeof ok.sandbox.browser.clipboard.readText, 'function');
  assert.strictEqual(typeof ok.sandbox.browser.clipboard.writeText, 'function');
  assert.strictEqual(typeof ok.sandbox.chrome.clipboard.readText, 'function');
  assert.strictEqual(typeof ok.sandbox.chrome.clipboard.writeText, 'function');
});

test('clipboard: read and write use distinct native RPC names', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: CLIPBOARD_MANIFEST }), '[]'));
  env.sandbox.browser.clipboard.readText();
  env.sandbox.browser.clipboard.writeText('copied');
  assert.deepStrictEqual(env.posted.filter((p) => p.api && p.api.startsWith('clipboard.')).map((p) => [p.api, p.args]), [
    ['clipboard.readText', []], ['clipboard.writeText', ['copied']]
  ]);
});

const NOTIFICATIONS_MANIFEST = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['notifications'] };

test('notifications: only extension pages declaring notifications get the namespace', () => {
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest: NOTIFICATIONS_MANIFEST }), entry(['window.has = typeof browser.notifications;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined');
  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.notifications, undefined);
  const page = makeEnv();
  page.run(assemble(cfg({ page: true, manifest: NOTIFICATIONS_MANIFEST }), '[]'));
  for (const method of ['create', 'update', 'clear', 'getAll']) assert.strictEqual(typeof page.sandbox.browser.notifications[method], 'function');
  assert.strictEqual(typeof page.sandbox.chrome.notifications.create, 'function');
});

test('notifications: overloads and native event dispatch preserve IDs and close state', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: NOTIFICATIONS_MANIFEST }), entry([
    "browser.notifications.create('job', { type: 'basic', title: 'Done', message: 'OK' });",
    "browser.notifications.create({ type: 'basic', title: 'Auto', message: 'Generated' });",
    "browser.notifications.update('job', { message: 'Updated' }); browser.notifications.clear('job'); browser.notifications.getAll();",
    "window.notificationEvents = []; browser.notifications.onClicked.addListener(function (id) { window.notificationEvents.push('click:' + id); });",
    "browser.notifications.onClosed.addListener(function (id, byUser) { window.notificationEvents.push('close:' + id + ':' + byUser); });"
  ])));
  const calls = env.posted.filter((x) => x.api && x.api.indexOf('notifications.') === 0);
  assert.deepStrictEqual(calls.map((x) => x.api), ['notifications.create', 'notifications.create', 'notifications.update', 'notifications.clear', 'notifications.getAll']);
  assert.deepStrictEqual(calls[0].args, ['job', { type: 'basic', title: 'Done', message: 'OK' }]);
  assert.deepStrictEqual(calls[1].args, [{ type: 'basic', title: 'Auto', message: 'Generated' }]);
  env.native({ event: 'notifications.onClicked', payload: { notificationId: 'job' } });
  env.native({ event: 'notifications.onClosed', payload: { notificationId: 'job', byUser: true } });
  assert.deepStrictEqual(Array.from(env.sandbox.notificationEvents), ['click:job', 'close:job:true']);
});

const SCRIPTING_MANIFEST = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['scripting'] };

test('scripting: only on extension pages that declare the permission', () => {
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest: SCRIPTING_MANIFEST }), entry(['window.has = typeof browser.scripting;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined', 'content script must not get scripting');

  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.scripting, undefined);
  assert.strictEqual(noPerm.sandbox.chrome.scripting, undefined);

  const ok = makeEnv();
  ok.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  for (const k of ['executeScript', 'insertCSS', 'removeCSS', 'registerContentScripts',
    'getRegisteredContentScripts', 'updateContentScripts', 'unregisterContentScripts']) {
    assert.strictEqual(typeof ok.sandbox.browser.scripting[k], 'function', 'browser.scripting.' + k);
    assert.strictEqual(typeof ok.sandbox.chrome.scripting[k], 'function', 'chrome.scripting.' + k);
  }
});

test('scripting: optional_permissions also counts as declared', () => {
  const env = makeEnv();
  const m = { manifest_version: 3, name: 'Demo', version: '1', optional_permissions: ['scripting'] };
  env.run(assemble(cfg({ page: true, manifest: m }), '[]'));
  assert.strictEqual(typeof env.sandbox.browser.scripting.executeScript, 'function');
});

test('scripting.executeScript: func is sent as source text, only whitelisted fields are forwarded', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  env.sandbox.browser.scripting.executeScript({
    target: { tabId: 7 }, func: (a, b) => a + b, args: [1, 2], world: 'MAIN', injectImmediately: true, bogus: 1,
  });
  env.sandbox.browser.scripting.executeScript({ target: { tabId: 7 }, files: ['a.js', 'b.js'] });
  const calls = env.posted.filter((p) => p.api === 'scripting.executeScript');
  assert.strictEqual(calls.length, 2);
  assert.deepStrictEqual(Object.keys(calls[0].args[0]).sort(), ['args', 'func', 'target', 'world']);
  assert.strictEqual(calls[0].args[0].func, '(a, b) => a + b');
  assert.deepStrictEqual(calls[0].args[0].args, [1, 2]);
  assert.deepStrictEqual(calls[1].args, [{ target: { tabId: 7 }, files: ['a.js', 'b.js'] }]);
});

test('scripting.executeScript: func that is not a function rejects locally; missing details reach native as null', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  await assert.rejects(env.sandbox.browser.scripting.executeScript({ target: { tabId: 1 }, func: 'alert(1)' }), /func must be a function/);
  assert.strictEqual(env.posted.filter((p) => p.api === 'scripting.executeScript').length, 0, 'nothing may be posted');
  env.sandbox.browser.scripting.executeScript();
  assert.deepStrictEqual(env.posted[env.posted.length - 1].args, [null]);
});

test('scripting.insertCSS / removeCSS: css / files / target only', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  const s = env.sandbox.browser.scripting;
  s.insertCSS({ target: { tabId: 3 }, css: 'a{}', origin: 'USER', bogus: 1 });
  s.removeCSS({ target: { tabId: 3 }, files: ['x.css'] });
  const calls = env.posted.filter((p) => p.api && p.api.startsWith('scripting.')).map((p) => [p.api, p.args]);
  assert.deepStrictEqual(calls, [
    ['scripting.insertCSS', [{ target: { tabId: 3 }, css: 'a{}', origin: 'USER' }]],
    ['scripting.removeCSS', [{ target: { tabId: 3 }, files: ['x.css'] }]],
  ]);
});

test('scripting: native result passes through; chrome callback style and lastError work', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  const p = env.sandbox.browser.scripting.executeScript({ target: { tabId: 1 }, func: () => 1 });
  env.native({ id: env.posted[env.posted.length - 1].id, ok: true, result: [{ frameId: 0, result: 1 }] });
  assert.deepStrictEqual(await p, [{ frameId: 0, result: 1 }]);

  let got = 'unset', err = 'unset';
  env.sandbox.chrome.scripting.executeScript({ target: { tabId: 1 }, func: () => 1 }, (r) => {
    got = r; err = env.sandbox.chrome.runtime.lastError && env.sandbox.chrome.runtime.lastError.message;
  });
  env.native({ id: env.posted[env.posted.length - 1].id, ok: false, error: { code: 'DENIED_PERMISSION', message: 'nope' } });
  await tick();
  assert.strictEqual(got, undefined);
  assert.strictEqual(err, 'nope');
});

test('scripting.registerContentScripts family: forwarded to native with promise support', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: SCRIPTING_MANIFEST }), '[]'));
  const s = env.sandbox.browser.scripting;
  const p = s.registerContentScripts([{ id: 'a', matches: ['*://*/*'], js: ['a.js'] }]);
  s.getRegisteredContentScripts(); s.updateContentScripts([]); s.unregisterContentScripts({ ids: ['a'] });
  const calls = env.posted.filter((p2) => p2.api && p2.api.startsWith('scripting.')).map((p2) => [p2.api, p2.args]);
  assert.deepStrictEqual(calls.map((c) => c[0]), [
    'scripting.registerContentScripts', 'scripting.getRegisteredContentScripts',
    'scripting.updateContentScripts', 'scripting.unregisterContentScripts',
  ]);
  assert.deepStrictEqual(calls[1][1], [null]);
  const regId = env.posted.find((p2) => p2.api === 'scripting.registerContentScripts').id; // posted[0] 是 runtime.hello
  env.native({ id: regId, ok: true, result: null });
  await p;
});

test('contextMenus API exposes create/update/remove/removeAll and click event', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: ['contextMenus'] } }), entry([
    "window.menuId = browser.contextMenus.create({ title: 'Open', contexts: ['link'], documentUrlPatterns: ['https://*.example.com/*'], targetUrlPatterns: ['https://target.example/*'] });",
    "browser.contextMenus.update(window.menuId, { title: 'Updated' }); browser.contextMenus.remove(window.menuId); browser.contextMenus.removeAll();",
    "window.clicks = []; browser.contextMenus.onClicked.addListener(function (info, tab) { window.clicks.push(info.menuItemId + ':' + tab.id); });"
  ])));
  const calls = env.posted.filter((x) => x.api && x.api.indexOf('contextMenus.') === 0);
  assert.deepStrictEqual(calls.map((x) => x.api), ['contextMenus.create', 'contextMenus.update', 'contextMenus.remove', 'contextMenus.removeAll']);
  assert.strictEqual(calls[0].args[0].id, 'mafari-menu-1');
  env.native({ event: 'contextMenus.onClicked', payload: { info: { menuItemId: 'mafari-menu-1' }, tab: { id: 7 } } });
  assert.deepStrictEqual(Array.from(env.sandbox.clicks), ['mafari-menu-1:7']);
});

test('bookmarks: namespace is extension-page-only and requires the manifest permission', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['bookmarks'] };
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest }), entry(['window.has = typeof browser.bookmarks;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined');
  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1' } }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.bookmarks, undefined);
  const page = makeEnv();
  page.run(assemble(cfg({ page: true, manifest }), '[]'));
  for (const method of ['getTree', 'getSubTree', 'get', 'getChildren', 'search', 'create', 'update', 'move', 'remove', 'removeTree']) {
    assert.strictEqual(typeof page.sandbox.browser.bookmarks[method], 'function', method);
    assert.strictEqual(typeof page.sandbox.chrome.bookmarks[method], 'function', 'chrome.' + method);
  }
});

test('bookmarks: methods forward to native and events preserve Firefox argument order', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['bookmarks'] };
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest }), entry([
    "browser.bookmarks.getTree(); browser.bookmarks.getSubTree('folder'); browser.bookmarks.get(['a','b']); browser.bookmarks.getChildren('folder');",
    "browser.bookmarks.search('hello'); browser.bookmarks.create({ parentId: 'menu________', title: 'x', url: 'https://x.test' });",
    "browser.bookmarks.update('a', { title: 'new' }); browser.bookmarks.move('a', { parentId: 'toolbar_____' }); browser.bookmarks.remove('a'); browser.bookmarks.removeTree('folder');",
    "window.bookmarkEvents = []; browser.bookmarks.onCreated.addListener(function (id, node) { window.bookmarkEvents.push('c:' + id + ':' + node.title); });",
    "browser.bookmarks.onChanged.addListener(function (id, info) { window.bookmarkEvents.push('u:' + id + ':' + info.title); });",
    "browser.bookmarks.onMoved.addListener(function (id, info) { window.bookmarkEvents.push('m:' + id + ':' + info.index); });",
    "browser.bookmarks.onRemoved.addListener(function (id, info) { window.bookmarkEvents.push('r:' + id + ':' + info.index); });"
  ])));
  const calls = env.posted.filter((x) => x.api && x.api.indexOf('bookmarks.') === 0);
  assert.deepStrictEqual(calls.map((x) => x.api), [
    'bookmarks.getTree', 'bookmarks.getSubTree', 'bookmarks.get', 'bookmarks.getChildren', 'bookmarks.search',
    'bookmarks.create', 'bookmarks.update', 'bookmarks.move', 'bookmarks.remove', 'bookmarks.removeTree'
  ]);
  env.native({ event: 'bookmarks.onCreated', payload: { id: 'n1', bookmark: { id: 'n1', title: 'Created' } } });
  env.native({ event: 'bookmarks.onChanged', payload: { id: 'n1', changeInfo: { title: 'Changed' } } });
  env.native({ event: 'bookmarks.onMoved', payload: { id: 'n1', moveInfo: { index: 2 } } });
  env.native({ event: 'bookmarks.onRemoved', payload: { id: 'n1', removeInfo: { index: 2 } } });
  assert.deepStrictEqual(Array.from(env.sandbox.bookmarkEvents), ['c:n1:Created', 'u:n1:Changed', 'm:n1:2', 'r:n1:2']);
});

test('downloads: namespace is extension-page-only and requires the manifest permission', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['downloads'] };
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest }), entry(['window.has = typeof browser.downloads;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined');
  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1' } }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.downloads, undefined);
  const page = makeEnv();
  page.run(assemble(cfg({ page: true, manifest }), '[]'));
  for (const method of ['download', 'search', 'pause', 'resume', 'cancel', 'erase']) {
    assert.strictEqual(typeof page.sandbox.browser.downloads[method], 'function', method);
    assert.strictEqual(typeof page.sandbox.chrome.downloads[method], 'function', 'chrome.' + method);
  }
});

test('downloads: methods forward to native and creation/erasure events are delivered', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['downloads'] };
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest }), entry([
    "browser.downloads.download({ url: 'https://x.test/a.zip', filename: 'a.zip' }); browser.downloads.search({ state: 'complete' });",
    "browser.downloads.pause(3); browser.downloads.resume(3); browser.downloads.cancel(3); browser.downloads.erase({ id: '3' });",
    "window.downloadEvents = []; browser.downloads.onCreated.addListener(function (item) { window.downloadEvents.push('c:' + item.id); });",
    "browser.downloads.onErased.addListener(function (id) { window.downloadEvents.push('e:' + id); });"
  ])));
  const calls = env.posted.filter((x) => x.api && x.api.indexOf('downloads.') === 0);
  assert.deepStrictEqual(calls.map((x) => x.api), ['downloads.download', 'downloads.search', 'downloads.pause', 'downloads.resume', 'downloads.cancel', 'downloads.erase']);
  env.native({ event: 'downloads.onCreated', payload: { id: 3, filename: 'a.zip' } });
  env.native({ event: 'downloads.onErased', payload: { id: 3 } });
  assert.deepStrictEqual(Array.from(env.sandbox.downloadEvents), ['c:3', 'e:3']);
});

test('cookies: namespace is extension-page-only and requires the manifest permission', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['cookies'] };
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest }), entry(['window.has = typeof browser.cookies;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined');
  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1' } }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.cookies, undefined);
  const page = makeEnv();
  page.run(assemble(cfg({ page: true, manifest }), '[]'));
  for (const method of ['get', 'getAll', 'set', 'remove']) {
    assert.strictEqual(typeof page.sandbox.browser.cookies[method], 'function', method);
    assert.strictEqual(typeof page.sandbox.chrome.cookies[method], 'function', 'chrome.' + method);
  }
});

test('cookies: get/set/remove forward details to native', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['cookies'] };
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest }), entry([
    "browser.cookies.get({ url: 'https://example.com/', name: 'sid' });",
    "browser.cookies.getAll({ url: 'https://example.com/' });",
    "browser.cookies.set({ url: 'https://example.com/', name: 'sid', value: 'abc', secure: true });",
    "browser.cookies.remove({ url: 'https://example.com/', name: 'sid', path: '/' });",
    "chrome.cookies.get({ url: 'https://example.com/', name: 'sid' }, function () {});"
  ])));
  const calls = env.posted.filter((x) => x.api && x.api.indexOf('cookies.') === 0);
  assert.deepStrictEqual(calls.map((x) => x.api), ['cookies.get', 'cookies.getAll', 'cookies.set', 'cookies.remove', 'cookies.get']);
  assert.strictEqual(calls[2].args[0].value, 'abc');
});

test('history API is extension-page-only and exposes native-backed methods', () => {
  const manifest = { manifest_version: 3, name: 'Demo', version: '1', permissions: ['history'] };
  const cs = makeEnv();
  cs.run(assemble(cfg({ manifest }), entry(['window.has = typeof browser.history;'])));
  assert.strictEqual(cs.sandbox.has, 'undefined');
  const noPerm = makeEnv();
  noPerm.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1' } }), '[]'));
  assert.strictEqual(noPerm.sandbox.browser.history, undefined);
  const page = makeEnv();
  page.run(assemble(cfg({ page: true, manifest }), entry([
    "browser.history.search({ text: 'docs', maxResults: 20 });",
    "browser.history.deleteUrl({ url: 'https://example.com/' });",
    "browser.history.deleteRange({ startTime: 1, endTime: 2 });",
    "browser.history.deleteAll();",
    "chrome.history.search({ text: 'docs' }, function () {});"
  ])));
  assert.deepStrictEqual(page.posted.filter((x) => x.api && x.api.indexOf('history.') === 0).map((x) => x.api),
    ['history.search', 'history.deleteUrl', 'history.deleteRange', 'history.deleteAll', 'history.search']);
  assert.strictEqual(typeof page.sandbox.chrome.history.search, 'function');
});

test('action and browserAction expose compatible setters through shared API shape', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: [] } }), entry([
    "browser.action.setBadgeText({ text: '3' }); browser.action.setBadgeBackgroundColor({ color: '#123456' }); browser.action.setTitle({ title: 'New' }); browser.action.setPopup({ popup: 'popup.html' });",
    "chrome.browserAction.setBadgeText({ text: '4' });"
  ])));
  const calls = env.posted.filter((x) => x.api && (x.api.indexOf('action.') === 0 || x.api.indexOf('browserAction.') === 0));
  assert.deepStrictEqual(calls.map((x) => x.api), ['action.setBadgeText', 'action.setBadgeBackgroundColor', 'action.setTitle', 'action.setPopup', 'browserAction.setBadgeText']);
});

// ───────────── 后台页启动不能因为「命名空间 / 事件对象不存在」抛 TypeError ─────────────
// 现象：uBO / 篡改猴 / 沉浸式翻译的 popup 白屏、转圈，或「插件未能连接当前页面」。
// 原因：它们的后台页在启动时无条件调用 webRequest.onHeadersReceived 等；垫片原来只有 3 个 webRequest 事件，
// 后台页在那一行抛 TypeError，之后的 runtime.onMessage / onConnect 永远没注册，所有 popup / content script 的请求都没人应。
const UBO_PERMS = ['alarms', 'dns', 'menus', 'privacy', 'storage', 'tabs', 'unlimitedStorage', 'webNavigation', 'webRequest', 'webRequestBlocking', '<all_urls>'];
const TM_PERMS = ['alarms', 'notifications', 'tabs', 'idle', 'webNavigation', 'webRequest', 'webRequestBlocking', 'unlimitedStorage', 'storage', 'contextMenus', 'clipboardWrite', 'cookies', 'contextualIdentities', 'downloads', '<all_urls>'];
const IMT_PERMS = ['storage', 'activeTab', 'contextMenus', 'webRequest', 'webRequestBlocking', '<all_urls>'];

for (const [label, perms] of [['uBlock Origin', UBO_PERMS], ['Tampermonkey', TM_PERMS], ['Immersive Translate', IMT_PERMS]]) {
  test('background bootstrap does not throw on the Firefox-style APIs ' + label + ' calls unguarded', () => {
    const env = makeEnv();
    env.run(assemble(cfg({ page: true, background: true, manifest: { manifest_version: 2, name: label, version: '1', permissions: perms } }), entry([`
      window.__err = null;
      try {
        var wr = browser.webRequest, f = function () {}, flt = { urls: ['<all_urls>'] };
        wr.onBeforeRequest.addListener(f, flt, ['blocking']);
        ['onBeforeSendHeaders', 'onSendHeaders', 'onHeadersReceived', 'onAuthRequired', 'onResponseStarted', 'onBeforeRedirect']
          .forEach(function (n) { wr[n].addListener(f, flt, ['blocking', 'responseHeaders']); });
        wr.handlerBehaviorChanged();
        browser.browserAction.onClicked.addListener(f);
        browser.browserAction.setIcon({ path: 'x.png' }); browser.browserAction.setBadgeTextColor({ color: 'white' });
        browser.runtime.onSuspend.addListener(f); browser.runtime.onMessageExternal.addListener(f);
        browser.runtime.setUninstallURL('https://x').catch(f);
        browser.tabs.onReplaced.addListener(f); browser.tabs.onAttached.addListener(f);
        browser.windows.onFocusChanged.addListener(f); browser.windows.getCurrent(); chrome.windows.getAll({ populate: true }, f);
        browser.storage.managed.get().catch(f);
        window.__bg = browser.extension.getBackgroundPage() === window;
      } catch (e) { window.__err = String(e && e.message || e); }
    `])));
    assert.strictEqual(env.sandbox.__err, null);
    assert.strictEqual(env.sandbox.__bg, true);
  });
}

test('feature-detectable namespaces stay absent (privacy / dns / contextualIdentities), so extensions take their own fallback path', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: UBO_PERMS.concat(['contextualIdentities']) } }), entry([
    'window.__has = [browser.privacy, browser.dns, browser.contextualIdentities, browser.commands].map(function (x) { return x !== undefined; });',
  ])));
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__has)), [false, false, false, false]);
});

test('inert events and no-op setters never reach the native bridge (no fake success against a native handler)', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['webRequest', 'idle'] } }), entry([
    "var f = function () {}; browser.webRequest.onBeforeSendHeaders.addListener(f, {}, ['blocking']); browser.browserAction.onClicked.addListener(f); browser.browserAction.setIcon({}); browser.idle.onStateChanged.addListener(f);",
  ])));
  assert.deepStrictEqual(env.posted.map((x) => x.api), ['runtime.hello']);
});

test('methods the host cannot do reject with UNSUPPORTED instead of throwing synchronously or faking success', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['idle'] } }), entry([`
    window.__codes = [];
    [function () { return browser.tabs.executeScript(1, { code: '1' }); }, function () { return browser.idle.queryState(60); },
     function () { return browser.windows.create({}); }]
      .forEach(function (fn) { fn().then(function () { window.__codes.push('resolved'); }, function (e) { window.__codes.push(e.code); }); });
  `])));
  await tick(); await tick();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__codes)), ['UNSUPPORTED', 'UNSUPPORTED', 'UNSUPPORTED']);
});

test('userScripts.configureWorld / getWorldConfigurations / resetWorldConfiguration resolve locally (Tampermonkey MV3 init needs them)', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'x', version: '1', permissions: ['userScripts'] } }), entry([`
    window.__r = [];
    var us = chrome.userScripts;
    window.__r.push(typeof us.configureWorld, typeof us.getWorldConfigurations, typeof us.resetWorldConfiguration);
    us.configureWorld({ csp: "script-src 'self'", messaging: true }).then(function () { return us.getWorldConfigurations(); })
      .then(function (l) { window.__r.push(l.length, l[0].messaging); return us.resetWorldConfiguration(); })
      .then(function () { return us.getWorldConfigurations(); })
      .then(function (l) { window.__r.push(l.length); }, function (e) { window.__r.push('err:' + e.message); });
  `])));
  await tick(); await tick(); await tick(); await tick();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__r)), ['function', 'function', 'function', 1, true, 0]);
  assert.ok(!env.posted.some((x) => /^userScripts\.(configureWorld|getWorldConfigurations|resetWorldConfiguration)$/.test(x.api)), 'must not cross the bridge');
});

test('windows: single-window model, ids agree with tabs.windowId', async () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry([`
    window.__w = [];
    browser.windows.getCurrent().then(function (w) { window.__w.push(w.id, w.type, w.focused); });
    browser.windows.get(99).catch(function (e) { window.__w.push(e.message); });
    chrome.windows.getAll(function (l) { window.__w.push(l.length); });
  `])));
  await tick(); await tick();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__w)), [1, 'normal', true, 'No window with id: 99.', 1]);
});

test('menus.create({ onclick }) is honoured locally and the function never crosses the bridge', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['contextMenus'] } }), entry([
    "window.__clicked = []; browser.menus.create({ id: 'm1', title: 'T', onclick: function (info, tab) { window.__clicked.push(info.menuItemId + ':' + tab.id); } });",
  ])));
  const create = env.posted.find((x) => x.api === 'contextMenus.create');
  assert.ok(create && !('onclick' in create.args[0]));
  env.native({ event: 'contextMenus.onClicked', payload: { info: { menuItemId: 'm1' }, tab: { id: 3 } } });
  env.runTimers();
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__clicked)), ['m1:3']);
});

test('extension.getBackgroundPage is null outside the background page (callers fall back to messaging)', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true }), entry(['window.__bg = browser.extension.getBackgroundPage();'])));
  assert.strictEqual(env.sandbox.__bg, null);
});

// ───────────── 兜底：不存在的 onXxx 事件不能把后台页带死；方法 / 命名空间的特性检测不能被破坏 ─────────────
test('catch-all: any unknown onXxx event on an existing namespace is a registrable inert event', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['webNavigation', 'downloads', 'notifications', 'cookies', 'tabs'] } }), entry([`
    window.__err = null; window.__r = [];
    try {
      var f = function () {};
      browser.webNavigation.onCreatedNavigationTarget.addListener(f); browser.webNavigation.onDOMContentLoaded.addListener(f);
      browser.downloads.onChanged.addListener(f); browser.notifications.onButtonClicked.addListener(f);
      browser.cookies.onChanged.addListener(f);
      browser.tabs.onSomethingFirefoxAddsNextYear.addListener(f);       // 完全没听说过的事件
      chrome.runtime.onSomethingElse.addListener(f);                     // chrome.* 同样兜底
      window.__r.push(browser.tabs.onSomethingFirefoxAddsNextYear.hasListener(f), browser.tabs.onSomethingFirefoxAddsNextYear.hasListeners());
      browser.tabs.onSomethingFirefoxAddsNextYear.removeListener(f);
      window.__r.push(browser.tabs.onSomethingFirefoxAddsNextYear.hasListener(f));
    } catch (e) { window.__err = String(e && e.message || e); }
  `])));
  assert.strictEqual(env.sandbox.__err, null);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__r)), [true, true, false]);
});

test('catch-all does not invent methods or namespaces, and does not change what Object.keys / in report', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['tabs'] } }), entry([`
    window.__r = [
      typeof browser.tabs.someFutureMethod, 'onFutureEvent' in browser.tabs, Object.keys(browser.tabs).indexOf('onFutureEvent') >= 0,
      typeof browser.privacy, typeof browser.dns, typeof browser.commands,
      typeof browser.tabs.query, browser.tabs.onUpdated === browser.tabs.onUpdated
    ];
  `])));
  assert.deepStrictEqual(JSON.parse(JSON.stringify(env.sandbox.__r)), ['undefined', false, false, 'undefined', 'undefined', 'undefined', 'function', true]);
});

test('catch-all also covers the small namespaces a content script sees', () => {
  const env = makeEnv();
  env.run(assemble(cfg({}), entry(["window.__ok = false; browser.runtime.onSuspend.addListener(function () {}); browser.runtime.onNewThing.addListener(function () {}); window.__ok = true;"])));
  assert.strictEqual(env.sandbox.__ok, true);
});

test('inert events warn once (unless quiet) and never reach the native bridge', () => {
  const env = makeEnv();
  const warns = [];
  env.sandbox.console.warn = (m) => warns.push(String(m));
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['webRequest'] } }), entry([`
    var f = function () {}, g = function () {};
    browser.webRequest.onBeforeSendHeaders.addListener(f); browser.webRequest.onBeforeSendHeaders.addListener(g);
    browser.runtime.onSuspend.addListener(f);
  `])));
  assert.strictEqual(warns.filter((w) => w.includes('webRequest.onBeforeSendHeaders')).length, 1);
  assert.strictEqual(warns.filter((w) => w.includes('runtime.onSuspend')).length, 0);
  assert.deepStrictEqual(env.posted.map((x) => x.api), ['runtime.hello']);
});

test('background page prints a one-line startup summary; other pages do not', () => {
  const lines = [];
  const bg = makeEnv();
  bg.sandbox.console.info = (m) => lines.push(String(m));
  bg.run(assemble(cfg({ page: true, background: true }), '[]'));
  assert.strictEqual(lines.filter((l) => l.startsWith('[mafari] shim ready')).length, 1);
  lines.length = 0;
  const popup = makeEnv();
  popup.sandbox.console.info = (m) => lines.push(String(m));
  popup.run(assemble(cfg({ page: true }), '[]'));
  assert.strictEqual(lines.length, 0);
});

// ───────────── 垫片 ⇄ 原生 的静态一致性（不跑 Kotlin，只读源码）：专抓「写了没接上」────────────
const EXT_SRC = path.join(root, 'app/src/main/java/dev/dsh/miuixbrowser/ext');
const extKt = fs.readdirSync(EXT_SRC).filter((f) => f.endsWith('.kt')).map((f) => [f, fs.readFileSync(path.join(EXT_SRC, f), 'utf8')]);
/** 垫片里直接写死名字的 rpc 调用（storage.* 的名字是拼出来的，不在此列） */
const SHIM_RPC = [...new Set([...shim.matchAll(/rpc\('([A-Za-z]+\.[A-Za-z.]+)'/g)].map((m) => m[1]))];
/** 垫片已经发、但原生明确还没实现（调用会回 UNSUPPORTED）的 API：实现后必须从这里删掉 */
const KNOWN_UNIMPLEMENTED = new Set([]);

test('wiring: every API name the shim sends has a native handler (or is on the known-unimplemented list)', () => {
  const registered = new Set();
  for (const [, src] of extKt) for (const m of src.matchAll(/\.register\("([A-Za-z]+\.[A-Za-z.]+)"/g)) registered.add(m[1]);
  assert.ok(SHIM_RPC.length > 10 && registered.size > 10, 'regex found nothing — source layout changed?');
  const missing = SHIM_RPC.filter((a) => !registered.has(a) && !KNOWN_UNIMPLEMENTED.has(a));
  assert.deepStrictEqual(missing, [], '垫片会发出、原生却没注册的 API：' + missing.join(', '));
  const stale = [...KNOWN_UNIMPLEMENTED].filter((a) => registered.has(a));
  assert.deepStrictEqual(stale, [], '已经实现了，请从 KNOWN_UNIMPLEMENTED 删掉：' + stale.join(', '));
});

test('wiring: every `fun register(router: ApiRouter)` is actually called from ExtensionSystem', () => {
  const defs = extKt.filter(([f, src]) => f !== 'ExtensionSystem.kt' && /fun register\(router: ApiRouter\)/.test(src)).map(([f]) => f);
  const sys = extKt.find(([f]) => f === 'ExtensionSystem.kt')[1];
  const calls = (sys.match(/\.register\(router\)/g) || []).length;
  assert.ok(defs.length >= 6, 'expected the api classes: ' + defs);
  assert.strictEqual(calls, defs.length, defs.length + ' 个 register(router) 定义（' + defs.join(', ') + '），ExtensionSystem 里只调用了 ' + calls + ' 个');
});

(async () => {
  let failed = 0;
  for (const [name, fn] of tests) {
    try { await fn(); console.log('ok   ' + name); }
    catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + (e && e.stack || e)); }
  }
  console.log(failed ? failed + ' failed' : 'all ' + tests.length + ' passed');
  process.exit(failed ? 1 : 0);
})();

// ───────────── onHeadersReceived / onDOMContentLoaded 现在是真实事件（来自 NavigationListener），要走原生桥 ─────────────
test('webRequest.onHeadersReceived registers natively with its extraInfoSpec and receives main-frame events', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['webRequest'] } }), entry([`
    window.__got = [];
    browser.webRequest.onHeadersReceived.addListener(function (d) { window.__got.push(d.statusCode); }, { urls: ['<all_urls>'] }, ['responseHeaders']);
  `])));
  const reg = env.posted.find((x) => x.api === 'webRequest.addListener');
  assert.ok(reg, 'onHeadersReceived must register with the native router');
  assert.strictEqual(reg.args[0], 'onHeadersReceived');
  assert.deepStrictEqual(JSON.parse(JSON.stringify(reg.args[3])), ['responseHeaders']);
});

test('webNavigation.onDOMContentLoaded is a real event', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 2, name: 'x', version: '1', permissions: ['webNavigation'] } }), entry([`
    window.__n = 0;
    browser.webNavigation.onDOMContentLoaded.addListener(function () { window.__n++; });
  `])));
  assert.strictEqual(typeof env.sandbox.__n, 'number');
});

