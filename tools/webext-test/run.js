// 自测拓展 hello-ext 的冒烟测试：把真正的 mafari_ext_shim.js + content.js 放进 vm，用假的原生端应答 storage / sendMessage，
// 检查横幅文字。用法：node tools/webext-test/run.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.resolve(__dirname, '..', '..');
const shim = fs.readFileSync(path.join(root, 'app/src/main/assets/mafari_ext_shim.js'), 'utf8');
const content = fs.readFileSync(path.join(__dirname, 'hello-ext/content.js'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, 'hello-ext/manifest.json'), 'utf8'));

const ALL = { s: ['http', 'https'], h: null, sub: false, p: null, r: null };

function run({ backgroundAlive }) {
  const store = {};
  const appended = [];
  const bar = { style: {}, textContent: '', id: '', addEventListener() {}, remove() {} };
  const doc = {
    readyState: 'complete',
    head: { appendChild() {} },
    body: { appendChild: (n) => appended.push(n) },
    documentElement: { appendChild: (n) => appended.push(n) },
    createElement: () => bar,
    getElementById: () => null,
    addEventListener() {},
  };
  const bridge = {
    onmessage: null,
    postMessage(s) {
      const req = JSON.parse(s);
      if (!req.api) return; // 握手等
      let result = null, ok = true, error;
      if (req.api === 'storage.local.get') {
        const keys = [].concat(req.args[0] || []);
        result = {};
        keys.forEach((k) => { if (k in store) result[k] = store[k]; });
      } else if (req.api === 'storage.local.set') {
        Object.assign(store, req.args[0]);
      } else if (req.api === 'runtime.sendMessage') {
        if (backgroundAlive) result = { pong: 'pong test' };
        else { ok = false; error = { code: 'NO_RECEIVER', message: 'no receiver' }; }
      } else {
        ok = false; error = { code: 'UNSUPPORTED', message: req.api };
      }
      const reply = ok ? { id: req.id, ok: true, result } : { id: req.id, ok: false, error };
      Promise.resolve().then(() => bridge.onmessage({ data: JSON.stringify(reply) }));
    },
  };
  const sandbox = {
    document: doc, console, URL, Promise, JSON, RegExp, Error, Object, Array,
    setTimeout, navigator: { language: 'zh-CN' },
    __mext_test: bridge,
  };
  sandbox.window = sandbox;
  sandbox.top = sandbox;
  sandbox.location = { href: 'https://example.com/' };
  sandbox.addEventListener = () => {};
  vm.createContext(sandbox);

  const cfg = { extId: 'local-test', bridge: '__mext_test', origin: 'https://x.ext.mafari.internal', manifest };
  const entries = '[{"m":[' + JSON.stringify(ALL) + '],"x":[],"all":false,"at":"document_idle","css":[],"js":[function(browser,chrome){\n' + content + '\n}]}]';
  vm.runInContext('(' + shim + ')(' + JSON.stringify(cfg) + ',' + entries + ');', sandbox);
  return { bar, store, appended, sandbox };
}

const tick = () => new Promise((r) => setTimeout(r, 20));

(async () => {
  // 后台页在线
  let t = run({ backgroundAlive: true });
  await tick();
  assert.strictEqual(t.appended.length, 1, 'banner appended once');
  assert.ok(t.bar.textContent.includes('content script 已注入'), t.bar.textContent);
  assert.ok(t.bar.textContent.includes('storage 计数 1'), t.bar.textContent);
  assert.ok(t.bar.textContent.includes('后台页：pong'), t.bar.textContent);
  console.log('ok   banner with background:', t.bar.textContent);

  // 后台页没起来：前两项仍然正常
  t = run({ backgroundAlive: false });
  await tick();
  assert.ok(t.bar.textContent.includes('storage 计数 1'), t.bar.textContent);
  assert.ok(t.bar.textContent.includes('后台页无响应'), t.bar.textContent);
  console.log('ok   banner without background:', t.bar.textContent);
  console.log('all passed');
})().catch((e) => { console.error(e); process.exit(1); });
