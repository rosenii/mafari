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

test('connect is explicitly unsupported; unknown replies are ignored', () => {
  const env = makeEnv();
  env.run(assemble(cfg(), entry(['try { browser.runtime.connect(); } catch (e) { window.msg = e.message; }'])));
  assert.ok(/not supported/.test(env.sandbox.msg));
  env.native('not json');
  env.native({ id: '999', ok: true });
  env.native({ event: 'whatever' });
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

(async () => {
  let failed = 0;
  for (const [name, fn] of tests) {
    try { await fn(); console.log('ok   ' + name); }
    catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + (e && e.stack || e)); }
  }
  console.log(failed ? failed + ' failed' : 'all ' + tests.length + ' passed');
  process.exit(failed ? 1 : 0);
})();
