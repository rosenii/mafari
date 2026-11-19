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

test('runtime.onMessage: sync, return true + sendResponse, promise, and no listener', async () => {
  // 没有监听器
  let env = makeEnv();
  env.run(assemble(cfg(), entry([''])));
  env.native({ type: 'message', cid: 'c0', message: 'x' });
  assert.deepStrictEqual(env.posted[0].args, ['c0', null, { noReceiver: true }]);

  // 同步监听器没有返回 true：立即回 undefined
  env = makeEnv();
  env.run(assemble(cfg(), entry(['browser.runtime.onMessage.addListener(function () { });'])));
  env.native({ type: 'message', cid: 'c1', message: 'x' });
  assert.deepStrictEqual(env.posted[0].args, ['c1', null, null]);

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
    ['scripting.insertCSS', [{ target: { tabId: 3 }, css: 'a{}' }]],
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

test('action and browserAction expose compatible setters through shared API shape', () => {
  const env = makeEnv();
  env.run(assemble(cfg({ page: true, manifest: { manifest_version: 3, name: 'Demo', version: '1', permissions: [] } }), entry([
    "browser.action.setBadgeText({ text: '3' }); browser.action.setBadgeBackgroundColor({ color: '#123456' }); browser.action.setTitle({ title: 'New' }); browser.action.setPopup({ popup: 'popup.html' });",
    "chrome.browserAction.setBadgeText({ text: '4' });"
  ])));
  const calls = env.posted.filter((x) => x.api && (x.api.indexOf('action.') === 0 || x.api.indexOf('browserAction.') === 0));
  assert.deepStrictEqual(calls.map((x) => x.api), ['action.setBadgeText', 'action.setBadgeBackgroundColor', 'action.setTitle', 'action.setPopup', 'browserAction.setBadgeText']);
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
