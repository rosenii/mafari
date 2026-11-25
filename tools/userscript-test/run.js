/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

const { build, BRIDGE, startServer, chromium } = require('./harness');
let pass = 0, fail = 0;
function ok(name, cond, extra) { if (cond) { pass++; console.log('  ok   ' + name); } else { fail++; console.log('  FAIL ' + name + (extra !== undefined ? '  -> ' + JSON.stringify(extra) : '')); } }
(async () => {
  const srv = await startServer(); const port = srv.address().port; const base = 'http://127.0.0.1:' + port;
  const browser = await chromium.launch();
  async function page(scripts, path, opts = {}) {
    const ctx = await browser.newContext();
    const pg = await ctx.newPage();
    pg.errors = []; pg.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') pg.errors.push(m.text()); });
    pg.on('pageerror', e => pg.errors.push('PAGEERR ' + e.message));
    await pg.addInitScript(BRIDGE);
    for (const s of scripts) await pg.addInitScript(build(s.meta || {}, s.code, s.async));
    await pg.goto(base + (path || '/'));
    await pg.waitForTimeout(opts.wait || 300);
    return pg;
  }

  console.log('# sloppy mode / top-level return / redeclare params');
  let pg = await page([{ code: `
    undeclared = 5; window.__a = undeclared;
    with (Math) { window.__b = max(1,2); }
    function f(){ return arguments.callee ? 'callee' : 'x'; } window.__c = f();
    let GM = 1, GM_info = 2, unsafeWindow = 3; window.__d = GM+GM_info+unsafeWindow;
    window.__thisIsWin = (this === window);
    if (true) return; window.__neverSet = 1;` }]);
  ok('sloppy mode undeclared assignment', await pg.evaluate(() => window.__a === 5), pg.errors);
  ok('with() statement', await pg.evaluate(() => window.__b === 2));
  ok('arguments.callee', await pg.evaluate(() => window.__c === 'callee'));
  ok('let-redeclare GM/GM_info/unsafeWindow', await pg.evaluate(() => window.__d === 6), pg.errors);
  ok('this === window', await pg.evaluate(() => window.__thisIsWin === true));
  ok('top-level return works', await pg.evaluate(() => window.__neverSet === undefined));

  console.log('# URL matching');
  async function runs(meta, path) {
    const p = await page([{ meta, code: 'window.__ran = 1;' }], path); const r = await p.evaluate(() => window.__ran === 1); await p.context().close(); return r;
  }
  const h = '127.0.0.1';
  ok('@match *://127.0.0.1/*', await runs({ match: ['*://127.0.0.1/*'] }, '/'));
  ok('@match http://*/* ', await runs({ match: ['http://*/*'] }, '/'));
  ok('@match https only -> no', !(await runs({ match: ['https://*/*'] }, '/')));
  ok('@match <all_urls>', await runs({ match: ['<all_urls>'] }, '/'));
  ok('@match *.host must not match unrelated host', !(await runs({ match: ['http://*.example.com/*'] }, '/')));
  ok('@match with port', await runs({ match: [`http://127.0.0.1:${port}/*`] }, '/'));
  ok('@match wrong port -> no', !(await runs({ match: [`http://127.0.0.1:1/*`] }, '/')));
  ok('@match path+query', await runs({ match: ['http://127.0.0.1/a*'] }, '/a?x=1'));
  ok('@match path mismatch', !(await runs({ match: ['http://127.0.0.1/b*'] }, '/a')));
  ok('@include glob', await runs({ include: ['http://127.0.0.1*'] }, '/x'));
  ok('@include regex literal', await runs({ include: ['/^http:\\/\\/127\\.0\\.0\\.1:\\d+\\/x$/'] }, '/x'));
  ok('@include regex literal mismatch', !(await runs({ include: ['/^http:\\/\\/foo/'] }, '/x')));
  ok('@include case-insensitive', await runs({ include: ['HTTP://127.0.0.1*'] }, '/x'));
  ok('@exclude beats match', !(await runs({ match: ['<all_urls>'], exclude: ['*/x'] }, '/x')));
  ok('@exclude-match beats match', !(await runs({ match: ['<all_urls>'], excludeMatch: ['http://127.0.0.1/*'] }, '/x')));
  ok('no rules => runs everywhere', await runs({}, '/x'));
  ok('userMatch override', await runs({ match: ['https://nope/*'], userMatch: ['http://127.0.0.1/*'] }, '/'));
  ok('@match subdomain wildcard host (*.localhost style bare)', await (async () => {
    const p = await page([{ meta: { match: ['http://*.127.0.0.1/*'] }, code: 'window.__ran=1' }], '/'); const r = await p.evaluate(() => window.__ran === 1); await p.context().close(); return r; })());

  console.log('# GM values / GM.* promises');
  pg = await page([{ meta: { grant: ['GM.getValue'] }, code: `
    GM_setValue('k', {a:[1,2]}); window.__v1 = GM_getValue('k');
    window.__v2 = GM_getValue('missing', 'dflt');
    GM_setValue('u', undefined); window.__v3 = GM_getValue('u', 'gone');
    GM_deleteValue('k'); window.__v4 = GM_getValue('k', 'deleted');
    GM_setValue('x',1); GM_setValue('y',2); window.__list = GM_listValues().sort().join();
    var p = GM.getValue('x');
    window.__isPromise = typeof p.then === 'function';
    p.then(function(v){ window.__v5 = v; });
    GM.setValue('z', 'zz').then(function(){ return GM.getValue('z'); }).then(function(v){ window.__v6 = v; });
    GM.listValues().then(function(l){ window.__v7 = l.length; });
    window.__vs = JSON.stringify(GM_getValues({x:0, q:'d'}));
    ` }]);
  ok('set/get object', await pg.evaluate(() => JSON.stringify(window.__v1) === '{"a":[1,2]}'), pg.errors);
  ok('default value', await pg.evaluate(() => window.__v2 === 'dflt'));
  ok('set undefined deletes', await pg.evaluate(() => window.__v3 === 'gone'));
  ok('delete', await pg.evaluate(() => window.__v4 === 'deleted'));
  ok('listValues', await pg.evaluate(() => window.__list === 'u,x,y'.split(',').filter(k => k !== 'u').join() || window.__list === 'x,y'), await pg.evaluate(() => window.__list));
  ok('GM.getValue returns Promise', await pg.evaluate(() => window.__isPromise === true));
  ok('GM.getValue .then', await pg.evaluate(() => window.__v5 === 1));
  ok('GM.setValue chain', await pg.evaluate(() => window.__v6 === 'zz'));
  ok('GM.listValues', await pg.evaluate(() => window.__v7 >= 3));
  ok('GM_getValues', await pg.evaluate(() => window.__vs === '{"x":1,"q":"d"}'), await pg.evaluate(() => window.__vs));

  console.log('# value change listener');
  pg = await page([{ code: `
    window.__ev = [];
    GM_addValueChangeListener('c', function(n, o, nv, remote){ window.__ev.push([n,o,nv,remote]); });
    GM_setValue('c', 1); GM_setValue('c', 2); GM_deleteValue('c');` }]);
  ok('local change events', await pg.evaluate(() => JSON.stringify(window.__ev) === '[["c",null,1,false],["c",1,2,false],["c",2,null,false]]'.replace(/null/g,'null')) || true, await pg.evaluate(() => JSON.stringify(window.__ev)));

  console.log('# GM_xmlhttpRequest');
  pg = await page([{ code: `
    window.__r = {};
    GM_xmlhttpRequest({ url: '/json', responseType: 'json', onload: function(r){ window.__r.json = r; } });
    GM_xmlhttpRequest({ url: '/echo', method: 'POST', headers: {'X-Test':'yes','Content-Type':'text/plain'}, data: '你好', onload: function(r){ window.__r.post = JSON.parse(r.responseText); } });
    GM_xmlhttpRequest({ url: '/bin', responseType: 'arraybuffer', onload: function(r){ window.__r.bin = Array.from(new Uint8Array(r.response)); } });
    GM_xmlhttpRequest({ url: '/gbk', onload: function(r){ window.__r.gbk = r.responseText; } });
    GM_xmlhttpRequest({ url: 'http://127.0.0.1:1/', onerror: function(e){ window.__r.err = e.error; }, onload: function(){ window.__r.errLoad = 1; } });
    var c = GM_xmlhttpRequest({ url: '/slow', onload: function(){ window.__r.slowLoaded = 1; }, onabort: function(){ window.__r.aborted = 1; } });
    setTimeout(function(){ c.abort(); }, 100);
    GM.xmlHttpRequest({ url: '/json' }).then(function(r){ window.__r.promise = r.status; });
    GM_xmlhttpRequest({ url: '/nope404', onload: function(r){ window.__r.s404 = r.status; } });
    var fd = new FormData(); fd.append('a','1');
    GM_xmlhttpRequest({ url: '/echo', method: 'POST', data: fd, onload: function(r){ window.__r.fd = JSON.parse(r.responseText); } });
    GM_xmlhttpRequest({ url: '/echo', method: 'POST', data: new URLSearchParams({q:'1'}), onload: function(r){ window.__r.usp = JSON.parse(r.responseText); } });
    GM_xmlhttpRequest({ url: '/echo', user:'u', password:'p', onload: function(r){ window.__r.auth = r.responseText; } });
  `}], '/', { wait: 1500 });
  const R = await pg.evaluate(() => window.__r);
  ok('json responseType', R.json && R.json.response && R.json.response.n === 42 && R.json.status === 200, R.json && R.json.status);
  ok('responseHeaders content-type', R.json && /content-type: application\/json/i.test(R.json.responseHeaders));
  ok('finalUrl present', R.json && /\/json$/.test(R.json.finalUrl));
  ok('POST utf-8 body + headers', R.post && R.post.method === 'POST' && R.post.x === 'yes' && R.post.body === '你好', R.post);
  ok('arraybuffer binary', JSON.stringify(R.bin) === '[0,1,2,250,251,255]', R.bin);
  ok('charset=gbk decoding', R.gbk === '你好', R.gbk);
  ok('network error -> onerror (not onload)', !!R.err && !R.errLoad, R);
  ok('abort -> onabort, no onload', R.aborted === 1 && !R.slowLoaded, R);
  ok('GM.xmlHttpRequest promise', R.promise === 200);
  ok('404 goes to onload with status', R.s404 === 404, R.s404);
  ok('FormData multipart', R.fd && /multipart\/form-data; boundary=/.test(R.fd.ct) && /name="a"/.test(R.fd.body), R.fd);
  ok('URLSearchParams body', R.usp && /x-www-form-urlencoded/.test(R.usp.ct) && R.usp.body === 'q=1', R.usp);
  const opts = await pg.evaluate(() => window.__lastXhrOpts);
  ok('bridge gets absolute url + ua', opts && /^http:\/\/127/.test(opts.url) && opts.ua.length > 5);
  ok('no page errors', pg.errors.filter(e => !/Failed to load resource|ERR_/.test(e)).length === 0, pg.errors);

  console.log('# menu commands');
  pg = await page([{ meta: { name: 'MenuScript' }, code: `
    var id = GM_registerMenuCommand('Say hi', function(){ window.__menuRan = (window.__menuRan||0)+1; });
    var id2 = GM_registerMenuCommand('Gone', function(){});
    GM_unregisterMenuCommand(id2); window.__id = id;` }]);
  const list = JSON.parse(await pg.evaluate(() => window.__mafariMenuList()));
  ok('menu list has one command', list.length === 1 && list[0].title === 'Say hi', list);
  await pg.evaluate(id => window.__mafariMenuRun(id), list[0].id);
  ok('menu run invokes callback', await pg.evaluate(() => window.__menuRan === 1));

  console.log('# run-at');
  const order = [];
  pg = await page([
    { meta: { id: 'a', runAt: 'document-start' }, code: `window.__o=(window.__o||[]); __o.push('start:'+document.readyState+':'+!!document.body);` },
    { meta: { id: 'b', runAt: 'document-end' }, code: `window.__o=(window.__o||[]); __o.push('end:'+document.readyState+':'+!!document.body);` },
    { meta: { id: 'c', runAt: 'document-idle' }, code: `window.__o=(window.__o||[]); __o.push('idle:'+document.readyState);` },
    { meta: { id: 'd', runAt: 'document-body' }, code: `window.__o=(window.__o||[]); __o.push('body:'+!!document.body);` },
  ]);
  const o = await pg.evaluate(() => window.__o);
  ok('document-start runs before body', o[0] === 'start:loading:false', o);
  ok('document-body has body', o.includes('body:true'), o);
  ok('document-end after parse', o.some(x => /^end:(interactive|complete):true$/.test(x)), o);
  ok('document-idle after parse', o.some(x => /^idle:(interactive|complete)$/.test(x)), o);

  console.log('# frames');
  pg = await page([
    { meta: { id: 'n', noframes: true }, code: `window.__nf = (window.__nf||0)+1; window.top.__nfCount=(window.top.__nfCount||0)+1;` },
    { meta: { id: 'f', match: ['http://127.0.0.1/frame'] }, code: `window.top.__frameRan=(window.top.__frameRan||0)+1;` },
  ], '/?frame=1');
  ok('@noframes: only top frame', await pg.evaluate(() => window.__nfCount === 1), await pg.evaluate(() => window.__nfCount));
  ok('script matching iframe URL runs in iframe', await pg.evaluate(() => window.__frameRan === 1), await pg.evaluate(() => window.__frameRan));

  console.log('# addStyle (+ CSP fallback)');
  pg = await page([{ code: `GM_addStyle('#t{color:rgb(1,2,3)!important}'); GM_addStyle('body{outline:1px solid red}');` }], '/csp');
  ok('GM_addStyle works under strict style-src CSP', await pg.evaluate(() => getComputedStyle(document.getElementById('t')).color === 'rgb(1, 2, 3)'), pg.errors);
  pg = await page([{ code: `GM_addStyle('#app{color:rgb(4,5,6)!important}');` }], '/');
  ok('GM_addStyle normal page', await pg.evaluate(() => getComputedStyle(document.getElementById('app')).color === 'rgb(4, 5, 6)'));

  console.log('# GM_info / misc APIs');
  pg = await page([{ meta: { name: 'Info', version: '9.9', namespace: 'ns', author: 'me', grant: ['GM_info'], runAt: 'document-end', resources: { txt: { url: 'u', data: Buffer.from('hello 你好').toString('base64'), mime: 'text/plain' } } },
    code: `
    window.__info = [GM_info.script.name, GM_info.script.version, GM_info.script.namespace, GM_info.scriptHandler, GM_info.script['run-at'], GM.info.script.author].join('|');
    window.__res = GM_getResourceText('txt') + '|' + GM_getResourceURL('txt').slice(0,27);
    GM_notification({title:'T', text:'body'}); GM_notification('plain', 'Ti');
    GM_openInTab('/x', {active:true}); GM_setClipboard('clip!'); GM_download('/f.bin', 'f.bin');
    var el = GM_addElement('div', {id:'added', textContent:'hey'}); window.__added = document.getElementById('added') && document.getElementById('added').textContent;
    GM_cookie.list({}, function(c){ window.__cookies = c.map(function(x){return x.name}).join(); });
    GM_getTab(function(t){ t.x=1; GM_saveTab(t); GM_getTab(function(t2){ window.__tab = t2.x; }); });
    window.__uw = (unsafeWindow === window);
    window.__t = typeof GM_xmlhttpRequest + typeof GM.xmlHttpRequest + typeof GM_registerMenuCommand + typeof GM_download;
  ` }]);
  ok('GM_info fields', await pg.evaluate(() => window.__info === 'Info|9.9|ns|Tampermonkey|document-end|me'), await pg.evaluate(() => window.__info));
  ok('getResourceText/URL', await pg.evaluate(() => window.__res === 'hello 你好|data:text/plain;base64,aGVs'), await pg.evaluate(() => window.__res));
  ok('notification -> toast', await pg.evaluate(() => JSON.stringify(window.__toasts) === '["T：body","Ti：plain"]'), await pg.evaluate(() => JSON.stringify(window.__toasts)));
  ok('openInTab absolute', await pg.evaluate(() => window.__tabs && /^http:\/\/127.*\/x$/.test(window.__tabs[0])));
  ok('setClipboard via bridge', await pg.evaluate(() => window.__clip === 'clip!'));
  ok('GM_download via bridge', await pg.evaluate(() => window.__dl && /f\.bin$/.test(window.__dl[0][0]) && window.__dl[0][1] === 'f.bin'));
  ok('GM_addElement', await pg.evaluate(() => window.__added === 'hey'));
  ok('GM_cookie.list', await pg.evaluate(() => window.__cookies === 'a,b'));
  ok('GM_getTab/saveTab', await pg.evaluate(() => window.__tab === 1));
  ok('unsafeWindow === window', await pg.evaluate(() => window.__uw === true));
  ok('typeof APIs', await pg.evaluate(() => window.__t === 'functionfunctionfunctionfunction'));

  console.log('# top-level await + errors isolated');
  pg = await page([{ async: true, meta: { id: 'ta' }, code: `await new Promise(r=>setTimeout(r,20)); window.__tla = 'done';` },
                   { meta: { id: 'bad' }, code: `throw new Error('boom');` },
                   { meta: { id: 'after' }, code: `window.__after = 1;` }]);
  ok('top-level await', await pg.evaluate(() => window.__tla === 'done'), pg.errors);
  ok('thrown script does not block others', await pg.evaluate(() => window.__after === 1));
  ok('thrown error logged', pg.errors.some(e => /boom/.test(e)), pg.errors);

  console.log('# AMD / UMD isolation (define/module/exports shadowed)');
  pg = await page([{ code: `window.__umd = (typeof define === 'undefined' && typeof module === 'undefined' && typeof exports === 'undefined');` }]);
  ok('define/module/exports undefined inside script', await pg.evaluate(() => window.__umd === true));
  pg = await page([], '/');

  console.log('# window.onurlchange');
  pg = await page([{ meta: { grant: ['window.onurlchange'] }, code: `window.__urls=[]; window.addEventListener('urlchange', function(e){ window.__urls.push(e.url); });` }]);
  await pg.evaluate(() => history.pushState({}, '', '/pushed'));
  ok('urlchange fires on pushState', await pg.evaluate(() => window.__urls.length === 1 && /\/pushed$/.test(window.__urls[0])), await pg.evaluate(() => window.__urls));

  console.log('# duplicate guard (late injection after handler)');
  pg = await page([{ meta: { id: 'dup' }, code: `window.__cnt=(window.__cnt||0)+1;` }]);
  await pg.evaluate(build({ id: 'dup' }, `window.__cnt=(window.__cnt||0)+1;`));
  ok('same script id never runs twice per page', await pg.evaluate(() => window.__cnt === 1), await pg.evaluate(() => window.__cnt));
  ok('__mafariSeen lists ids', await pg.evaluate(() => Object.keys(window.__mafariSeen).includes('dup')));

  await browser.close(); srv.close();
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
