// 事件隔离：播放器 / 小球上的点击不能被页面的全局监听（含捕获阶段、onclick 属性）看到
const fs = require('fs'), path = require('path');
const { build, BRIDGE, startServer, chromium } = require('./harness');
const code = fs.readFileSync(process.argv[2] || path.join(__dirname, 'float-player-gm4_user.js'), 'utf8');
let pass = 0, fail = 0;
const ok = (n, c, x) => { c ? pass++ : fail++; console.log((c ? '  ok   ' : '  FAIL ') + n + (c ? '' : ' -> ' + JSON.stringify(x))); };
const SHADOW_OPEN = `(function(){ var o = Element.prototype.attachShadow; Element.prototype.attachShadow = function(i){ var r = o.call(this, Object.assign({}, i, {mode:'open'})); (window.__roots = window.__roots||[]).push(r); return r; }; })();`;
const GRANTS = ['GM_xmlhttpRequest','GM.xmlHttpRequest','GM_getValue','GM_setValue','GM_registerMenuCommand','unsafeWindow','GM_setClipboard'];
(async () => {
  const srv = await startServer(); const base = 'http://127.0.0.1:' + srv.address().port;
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 420, height: 800 }, hasTouch: true });
  const pg = await ctx.newPage(); pg.errors = [];
  pg.on('pageerror', e => pg.errors.push(e.message));
  await pg.addInitScript(BRIDGE); await pg.addInitScript(SHADOW_OPEN);
  await pg.addInitScript(build({ grant: GRANTS, runAt: 'document-start' }, code, false));
  await pg.goto(base + '/');
  // 页面（广告脚本）此时才注册全局监听：各种写法都来一遍
  await pg.evaluate(() => {
    window.__hits = {};
    const hit = (k) => () => { window.__hits[k] = (window.__hits[k] || 0) + 1; };
    window.addEventListener('click', hit('win-capture-click'), true);
    window.addEventListener('mousedown', hit('win-capture-mousedown'), true);
    document.addEventListener('pointerdown', hit('doc-pointerdown'));
    document.addEventListener('pointerup', hit('doc-capture-pointerup'), { capture: true });
    document.documentElement.addEventListener('click', hit('html-click'), true);
    document.body.addEventListener('mouseup', hit('body-mouseup'));
    document.addEventListener('click', { handleEvent: hit('doc-handleEvent') }, true);
    document.onclick = hit('doc-onclick');
    window.onmousedown = hit('win-onmousedown');
    document.body.onpointerdown = hit('body-onpointerdown');
    const removed = hit('removed'); document.addEventListener('click', removed, true); document.removeEventListener('click', removed, true);
    // 普通元素上的监听必须保持原样
    window.__div = document.createElement('div'); window.__div.addEventListener('click', hit('plain-div')); document.body.appendChild(window.__div);
    window.__getOnclick = () => typeof document.onclick;
  });
  await pg.evaluate(() => fetch('/a.m3u8').catch(() => {}));
  await pg.waitForTimeout(500);
  const reset = () => pg.evaluate(() => { window.__hits = {}; });
  const hits = () => pg.evaluate(() => JSON.parse(JSON.stringify(window.__hits)));

  // 1) 页面空白处：监听照常触发（没有被误伤）
  const ap = await pg.evaluate(() => { const q = document.getElementById('app').getBoundingClientRect(); return { x: q.x + 5, y: q.y + q.height/2 }; });
  await pg.mouse.click(ap.x, ap.y); await pg.waitForTimeout(100);
  let h = await hits();
  ok('页面其它地方：捕获 / 冒泡 / handleEvent / onclick 属性监听都正常触发',
    ['win-capture-click','win-capture-mousedown','doc-pointerdown','doc-capture-pointerup','html-click','body-mouseup','doc-handleEvent','doc-onclick','win-onmousedown','body-onpointerdown'].every(k => h[k] > 0), h);
  ok('removeEventListener 仍然有效', !h.removed, h);
  ok('document.onclick 读回来仍是页面设的函数', await pg.evaluate(() => window.__getOnclick()) === 'function');
  await reset(); await pg.evaluate(() => window.__div.click());
  ok('普通元素监听不受影响', (await hits())['plain-div'] === 1);

  // 2) 点小球
  await reset();
  const bb = await pg.evaluate(() => { const b = document.querySelector('[data-mf-float-ball]'); const q = b.getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; });
  await pg.mouse.click(bb.x, bb.y); await pg.waitForTimeout(500);
  h = await hits();
  ok('点小球：页面全局监听一个都没收到', Object.keys(h).length === 0, h);
  const has = await pg.evaluate(() => !!(window.__roots || []).find(x => x.querySelector('.iosgp-wrapper')));
  ok('点小球仍能打开播放器', has);

  // 3) 播放器里：按钮 / 进度条 / 空白处，全屏前后各来一遍
  const clickIn = async (sel) => {
    const pt = await pg.evaluate((sel) => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); r.querySelector('.iosgp-controls').classList.add('iosgp-visible'); const e = r.querySelector(sel); const q = e.getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; }, sel);
    await pg.mouse.click(pt.x, pt.y); await pg.waitForTimeout(150);
  };
  for (const phase of ['窗口', '全屏']) {
    if (phase === '全屏') {
      await clickIn('button[title="全屏"]'); await pg.waitForTimeout(600);
      ok('已进入全屏', await pg.evaluate(() => !!document.fullscreenElement));
      await reset();
    } else await reset();
    for (const [name, sel] of [['进度条', '.iosgp-track'], ['播放键', '.iosgp-btn-play'], ['静音键', 'button[title="取消静音"], button[title="静音"]'], ['画面空白处', '.iosgp-wrapper']]) {
      await reset(); await clickIn(sel);
      h = await hits();
      ok(`${phase}：点${name}，页面全局监听一个都没收到`, Object.keys(h).length === 0, h);
    }
    // 触摸（真实 touch 事件）
    await reset();
    const tp = await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); const q = r.querySelector('.iosgp-track').getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; });
    await pg.touchscreen.tap(tp.x, tp.y); await pg.waitForTimeout(150);
    ok(`${phase}：触摸进度条，页面全局监听一个都没收到`, Object.keys(await hits()).length === 0, await hits());
  }
  ok('隔离不影响播放器自己：点进度条后进度逻辑无报错', pg.errors.length === 0, pg.errors);
  await browser.close(); srv.close();
  console.log(`\n${pass} passed, ${fail} failed`); process.exit(fail ? 1 : 0);
})();
