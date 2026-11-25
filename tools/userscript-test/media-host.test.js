/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// 悬浮媒体播放器 × Mafari 宿主能力（GM_mafariHost）的端到端测试。用法：node media-host.test.js <脚本路径>
const fs = require('fs');
const path = require('path');
const { build, BRIDGE, startServer, chromium } = require('./harness');
const code = fs.readFileSync(process.argv[2] || path.join(__dirname, 'float-player-gm4_user.js'), 'utf8');
const GRANTS = ['GM_xmlhttpRequest','GM.xmlHttpRequest','GM_getValue','GM_setValue','GM_registerMenuCommand','GM_mafariHost','GM.mafariHost','unsafeWindow','GM_setClipboard'];
let pass = 0, fail = 0;
const ok = (n, c, x) => { c ? pass++ : fail++; console.log((c ? '  ok   ' : '  FAIL ') + n + (c ? '' : ' -> ' + JSON.stringify(x))); };

// 模拟原生宿主：记录调用、按 op 回结果，事件经 window.__emit 推
const HOST = (withHost) => `(function(){
  if (!${withHost}) return;
  var sub = null; window.__hostCalls = [];
  window.__emit = function(o){ if (sub && window[sub]) window[sub]('e', JSON.stringify(o)); };
  var origAttach = Element.prototype.attachShadow;
  Element.prototype.attachShadow = function(i){ var r = origAttach.call(this, Object.assign({}, i, {mode:'open'})); (window.__roots = window.__roots||[]).push(r); return r; };
  (function(){
    window.MafariGM.host = function(op, json, cbId){
      var args = JSON.parse(json); window.__hostCalls.push({op: op, args: args});
      var fn = '__mafariHostCallback_' + cbId;
      var res = op === 'caps' ? {api:1, engine:'webview', pip:true, cast:true, ext:true} : {ok:true};
      if (op === 'sub') sub = fn;
      setTimeout(function(){ try { window[fn]('r', JSON.stringify(res)); } catch(e){} }, 5);
    };
  })();
})();`;
const SHADOW_OPEN = `(function(){ var o = Element.prototype.attachShadow; Element.prototype.attachShadow = function(i){ var r = o.call(this, Object.assign({}, i, {mode:'open'})); (window.__roots = window.__roots||[]).push(r); return r; }; })();`;

(async () => {
  const srv = await startServer(); const base = 'http://127.0.0.1:' + srv.address().port;
  const browser = await chromium.launch();
  async function open(withHost) {
    const ctx = await browser.newContext({ viewport: { width: 420, height: 800 }, hasTouch: true });
    const pg = await ctx.newPage(); pg.errors = [];
    pg.on('pageerror', e => pg.errors.push('PAGEERR ' + e.message));
    pg.on('console', m => { if (m.type() === 'error') pg.errors.push(m.text()); });
    await pg.addInitScript(BRIDGE);
    await pg.addInitScript(withHost ? HOST(true) : SHADOW_OPEN);
    await pg.addInitScript(build({ grant: GRANTS, runAt: 'document-start' }, code, false));
    await pg.goto(base + '/');
    // 让脚本嗅到一个 m3u8，再点悬浮球打开播放器
    await pg.evaluate(() => fetch('/a.m3u8').catch(()=>{}));
    await pg.waitForTimeout(600);
    await pg.evaluate(() => { document.querySelectorAll('div').forEach(d => { if (d.className === '' ) {} }); });
    return pg;
  }
  const rootEval = (pg, fn, ...a) => pg.evaluate(([f, a]) => { const rs = window.__roots || []; const r = rs.find(x => x.querySelector('.iosgp-wrapper')); return new Function('r','a', f)(r, a); }, [fn.toString().replace(/^[^{]*\{|\}$/g, ''), a]);

  // ---- 找到并打开播放器（悬浮球在 light DOM 的 shadow 里，直接用 openPlayer 不可达 → 点球）
  async function ensurePlayer(pg) {
    for (let i = 0; i < 12; i++) {
      if (await pg.evaluate(() => !!(window.__roots||[]).find(x => x.querySelector('.iosgp-wrapper')))) return true;
      const bb = await pg.evaluate(() => { const b = document.querySelector('[data-mf-float-ball]'); if (!b || getComputedStyle(b).display === 'none') return null; const q = b.getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; });
      if (bb) await pg.mouse.click(bb.x, bb.y);
      await pg.waitForTimeout(300);
    }
    return false;
  }

  console.log('# Mafari 宿主：画中画');
  let pg = await open(true);
  const has = await ensurePlayer(pg);
  ok('播放器已创建', has, pg.errors);
  if (has) {
    await pg.waitForTimeout(300);
    const calls0 = await pg.evaluate(() => window.__hostCalls.map(c => c.op));
    ok('caps + sub 已发出', calls0.includes('caps') && calls0.includes('sub'), calls0);

    // 点「画中画」按钮（真实鼠标，保证有用户激活 → requestFullscreen 可用）
    const pt = await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); r.querySelector('.iosgp-controls').classList.add('iosgp-visible'); const b = [...r.querySelectorAll('button')].find(b => b.title === '画中画'); const q = b.getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; });
    await pg.mouse.click(pt.x, pt.y);
    await pg.waitForTimeout(900);
    const st = await pg.evaluate(() => ({ calls: window.__hostCalls.map(c => c.op + ':' + JSON.stringify(c.args)), fs: !!document.fullscreenElement, mini: !!window.__roots.find(x => x.querySelector('.iosgp-wrapper')).querySelector('.iosgp-mini') }));
    ok('先全屏再发 pip', st.fs && st.calls.some(c => c.startsWith('pip:')), st);
    ok('pip 参数带宽高 / playing', st.calls.some(c => /^pip:\{"w":\d+,"h":\d+,"playing":false\}$/.test(c)), st.calls);
    ok('没有退化成页内小窗', !st.mini);

    await pg.evaluate(() => window.__emit({ ev: 'pip', on: true }));
    await pg.waitForTimeout(100);
    ok('进入画中画：wrapper 加 iosgp-pip', await pg.evaluate(() => window.__roots.find(x => x.querySelector('.iosgp-wrapper')).querySelector('.iosgp-wrapper').classList.contains('iosgp-pip')));
    // 小窗操作栏按钮 → 脚本动作
    await pg.evaluate(() => window.__emit({ ev: 'act', a: 'play' }));
    await pg.evaluate(() => window.__emit({ ev: 'pip', on: false }));
    await pg.waitForTimeout(500);
    const after = await pg.evaluate(() => ({ cls: window.__roots.find(x => x.querySelector('.iosgp-wrapper')).querySelector('.iosgp-wrapper').classList.contains('iosgp-pip'), fs: !!document.fullscreenElement }));
    ok('还原：去掉 iosgp-pip，并退回进画中画前的非全屏悬浮窗', !after.cls && !after.fs, after);

    console.log('# Mafari 宿主：投屏 / 外部播放器');
    const clickMenu = async (label) => {
      await pg.evaluate((label) => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); const it = [...r.querySelectorAll('.iosgp-menu-item')].find(b => b.textContent.includes(label)); it.click(); }, label);
      await pg.waitForTimeout(250);
    };
    await clickMenu('投屏');
    const c1 = await pg.evaluate(() => window.__hostCalls.filter(c => c.op === 'cast').map(c => c.args));
    ok('投屏 → host cast，带 url / 请求头', c1.length === 1 && /a\.m3u8/.test(c1[0].url) && c1[0].type === 'hls' && c1[0].referer.startsWith('http') && c1[0].ua.length > 5, c1);
    await pg.evaluate(() => window.__emit({ ev: 'cast', state: 'playing', name: '客厅电视', pos: 65, dur: 5400, playing: true }));
    await pg.waitForTimeout(150);
    const cu = await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); return { label: [...r.querySelectorAll('.iosgp-menu-label')].map(x => x.textContent), cur: r.querySelector('.iosgp-time').textContent, dur: [...r.querySelectorAll('.iosgp-time')][1].textContent }; });
    ok('投屏中：菜单变「停止投屏（设备）」', cu.label.some(l => l.includes('停止投屏') && l.includes('客厅电视')), cu);
    ok('投屏中：进度条显示电视进度 1:05 / 1:30:00', cu.cur === '1:05' && /1:30:00/.test(cu.dur), cu);
    // 投屏中点播放 / 快进 → castCtl
    await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); r.querySelector('.iosgp-btn-play').click(); [...r.querySelectorAll('.iosgp-btn-skip')][1].click(); });
    const ctl = await pg.evaluate(() => window.__hostCalls.filter(c => c.op === 'castCtl').map(c => JSON.stringify(c.args)));
    ok('投屏中：播放键 → castCtl pause，快进 → seek 75', ctl.includes('{"a":"pause"}') && ctl.includes('{"a":"seek","pos":75}'), ctl);
    await clickMenu('停止投屏');
    ok('点「停止投屏」→ castCtl stop', (await pg.evaluate(() => window.__hostCalls.filter(c => c.op === 'castCtl').map(c => c.args.a))).includes('stop'));
    await pg.evaluate(() => window.__emit({ ev: 'cast', state: 'stopped' }));
    await pg.waitForTimeout(150);
    ok('结束后菜单还原为「投屏」', await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); return [...r.querySelectorAll('.iosgp-menu-label')].some(x => x.textContent === '投屏'); }));
    await clickMenu('外部播放器');
    const ex = await pg.evaluate(() => window.__hostCalls.filter(c => c.op === 'external').map(c => c.args));
    ok('外部播放器 → host external（Referer / UA）', ex.length === 1 && ex[0].referer && ex[0].ua, ex);
    ok('无脚本报错', pg.errors.filter(e => /PAGEERR/.test(e)).length === 0, pg.errors);
  }
  await pg.context().close();

  console.log('# 非 Mafari 环境（没有 GM_mafariHost）：行为不变');
  pg = await open(false);
  const has2 = await ensurePlayer(pg);
  ok('播放器已创建', has2, pg.errors);
  if (has2) {
    const pt = await pg.evaluate(() => { const r = window.__roots.find(x => x.querySelector('.iosgp-wrapper')); r.querySelector('.iosgp-controls').classList.add('iosgp-visible'); const b = [...r.querySelectorAll('button')].find(b => b.title === '画中画'); const q = b.getBoundingClientRect(); return { x: q.x + q.width/2, y: q.y + q.height/2 }; });
    await pg.mouse.click(pt.x, pt.y);
    await pg.waitForTimeout(500);
    ok('无宿主且浏览器无 PiP → 页内小窗', await pg.evaluate(() => !!window.__roots.find(x => x.querySelector('.iosgp-wrapper')).querySelector('.iosgp-mini') || document.pictureInPictureElement !== null));
    ok('无脚本报错', pg.errors.filter(e => /PAGEERR/.test(e)).length === 0, pg.errors);
  }
  await browser.close(); srv.close();
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})();
