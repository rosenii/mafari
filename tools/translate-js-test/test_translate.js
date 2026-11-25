/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

const { JSDOM } = require('jsdom');
const fs = require('fs');
const assert = require('assert');
const SRC = fs.readFileSync(process.argv[2], 'utf8');

function make(html, opts) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  const sent = [];
  w.eval(SRC);
  w.__mafariTr.setTransport(m => sent.push(m));
  return { dom, w, d: w.document, tr: w.__mafariTr, sent };
}
const J = x => JSON.parse(JSON.stringify(x));
const T = s => '〔' + s + '〕';
const flush = (ms = 5) => new Promise(r => setTimeout(r, ms));
function answer(h, filter) {
  const reqs = h.sent.filter(m => m.t === 'req' && !m._done && (!filter || filter(m)));
  reqs.forEach(m => { m._done = true; h.tr.recv({ t: 'res', id: m.id, texts: m.texts.map(T) }); });
  return reqs.length;
}
const states = h => h.sent.filter(m => m.t === 'state');
let n = 0;
async function test(name, fn) { try { await fn(); n++; console.log('ok   ' + name); } catch (e) { console.log('FAIL ' + name + '\n' + e.stack); process.exitCode = 1; } }

const PAGE = `<!doctype html><html><head><title>Hello World</title><style>p{}</style></head><body>
<h1>  Welcome home  </h1>
<p id="p1">First paragraph.</p>
<p id="p2">123 456</p>
<p id="zh">这是中文内容，不需要翻译</p>
<p id="mixed">Hello 世界 again</p>
<code id="code">const x = 1;</code><pre id="pre">preformatted text</pre>
<script id="sc">var a = "script text";</script>
<div translate="no" id="no">Do not translate</div>
<div class="notranslate" id="nt">Also not</div>
<div hidden id="hid">hidden text</div>
<textarea id="ta">textarea body</textarea>
<input id="inp" placeholder="Search here" title="Search title">
<img id="img" alt="A cat photo" src="x.png">
<ul><li>One <b>bold</b> two</li></ul>
</body></html>`;

(async () => {
  await test('basic: collects, skips, preserves whitespace, translates attrs and title', async () => {
    const h = make(PAGE);
    h.tr.start({ target: 'zh-CN' });
    const texts = h.sent.filter(m => m.t === 'req').flatMap(m => m.texts);
    assert(texts.includes('Welcome home'), JSON.stringify(texts));
    for (const bad of ['123 456', 'const x = 1;', 'preformatted text', 'Do not translate', 'Also not', 'hidden text', 'textarea body'])
      assert(!texts.includes(bad), 'should skip: ' + bad);
    assert(!texts.some(t => t.includes('这是中文')), 'zh skipped');
    assert(!texts.some(t => t.includes('script text')));
    assert(texts.includes('Hello 世界 again'), 'mixed text kept');
    answer(h);
    assert.strictEqual(h.d.querySelector('h1').textContent, '  〔Welcome home〕  ');
    assert.strictEqual(h.d.getElementById('p1').textContent, '〔First paragraph.〕');
    assert.strictEqual(h.d.getElementById('p2').textContent, '123 456');
    assert.strictEqual(h.d.getElementById('inp').getAttribute('placeholder'), '〔Search here〕');
    assert.strictEqual(h.d.getElementById('inp').getAttribute('title'), '〔Search title〕');
    assert.strictEqual(h.d.getElementById('img').getAttribute('alt'), '〔A cat photo〕');
    assert.strictEqual(h.d.title, '〔Hello World〕');
    assert.strictEqual(h.d.getElementById('code').textContent, 'const x = 1;');
    // inline element split: each text node separately, tags intact
    assert.strictEqual(h.d.querySelector('li').innerHTML, '〔One〕 <b>〔bold〕</b> 〔two〕'.replace('〔One〕 ', '〔One〕 ').replace(' 〔two〕', ' 〔two〕'));
    const last = states(h).pop();
    assert.strictEqual(last.phase, 'done');
    assert.strictEqual(last.done, last.total);
  });

  await test('target not Chinese: Chinese text is translated, English not skipped', async () => {
    const h = make('<body><p>这是中文内容需要翻译成英文</p><p>Already English</p></body>');
    h.tr.start({ target: 'en' });
    const texts = h.sent.filter(m => m.t === 'req').flatMap(m => m.texts);
    assert.deepStrictEqual(J(texts).sort(), ['Already English', '这是中文内容需要翻译成英文'].sort());
  });

  await test('batching: <=20 per batch, <=3 in flight, rest after answers', async () => {
    const body = Array.from({ length: 65 }, (_, i) => `<p>Line number ${i}</p>`).join('');
    const h = make('<body>' + body + '</body>');
    h.tr.start({ target: 'zh-CN' });
    let reqs = h.sent.filter(m => m.t === 'req');
    assert.strictEqual(reqs.length, 3);
    assert(reqs.every(r => r.texts.length <= 20));
    answer(h);
    await flush();
    reqs = h.sent.filter(m => m.t === 'req');
    assert.strictEqual(reqs.length, 4);
    answer(h);
    assert.strictEqual(states(h).pop().phase, 'done');
    assert.strictEqual(states(h).pop().done, 65);
  });

  await test('batching by chars', async () => {
    const body = Array.from({ length: 6 }, (_, i) => `<p>${'word '.repeat(150)}${i}</p>`).join('');
    const h = make('<body>' + body + '</body>');
    h.tr.start({});
    const reqs = h.sent.filter(m => m.t === 'req');
    assert(reqs.length >= 2);
    assert(reqs.every(r => r.texts.join('').length <= 3000 + 800));
  });

  await test('viewport-near nodes are sent first (order stable otherwise)', async () => {
    const h = make('<body><p>aaa one</p><p>bbb two</p></body>');
    h.tr.start({});
    const texts = h.sent.filter(m => m.t === 'req').flatMap(m => m.texts);
    assert.deepStrictEqual(J(texts), ['aaa one', 'bbb two']);
  });

  await test('original / translation toggle and stop restore', async () => {
    const h = make(PAGE);
    h.tr.start({});
    answer(h);
    const html1 = h.d.body.innerHTML;
    assert(h.tr.showOriginal());
    assert.strictEqual(h.d.getElementById('p1').textContent, 'First paragraph.');
    assert.strictEqual(h.d.title, 'Hello World');
    assert.strictEqual(h.d.getElementById('inp').getAttribute('placeholder'), 'Search here');
    assert.strictEqual(states(h).pop().phase, 'original');
    assert(h.tr.showTranslation());
    assert.strictEqual(h.d.body.innerHTML, html1);
    assert.strictEqual(h.d.title, '〔Hello World〕');
    h.tr.stop();
    assert.strictEqual(h.d.getElementById('p1').textContent, 'First paragraph.');
    assert.strictEqual(h.d.title, 'Hello World');
    assert.strictEqual(states(h).pop().phase, 'idle');
    // body is exactly original again
    const fresh = new JSDOM(PAGE).window.document;
    assert.strictEqual(h.d.body.innerHTML, fresh.body.innerHTML);
  });

  await test('dynamic content is translated; own writes do not loop', async () => {
    const h = make('<body><p id="a">Static text</p><div id="box"></div></body>');
    h.tr.start({});
    answer(h);
    await flush(50);
    const before = h.sent.filter(m => m.t === 'req').length;
    assert.strictEqual(before, 1, 'own writes must not trigger new requests');
    const p = h.d.createElement('p'); p.textContent = 'Late arriving text'; h.d.getElementById('box').appendChild(p);
    await flush(450);
    const reqs = h.sent.filter(m => m.t === 'req');
    assert.strictEqual(reqs.length, 2);
    assert.deepStrictEqual(J(reqs[1].texts), ['Late arriving text']);
    answer(h);
    assert.strictEqual(p.textContent, '〔Late arriving text〕');
    assert.strictEqual(states(h).pop().phase, 'done');
  });

  await test('page changes a node text after translation: re-translated', async () => {
    const h = make('<body><p id="a">Counter 1</p></body>');
    h.tr.start({});
    answer(h);
    const p = h.d.getElementById('a');
    p.firstChild.nodeValue = 'Counter 2';
    await flush(450);
    const reqs = h.sent.filter(m => m.t === 'req');
    assert.deepStrictEqual(J(reqs[reqs.length - 1].texts), ['Counter 2']);
    answer(h);
    assert.strictEqual(p.textContent, '〔Counter 2〕');
  });

  await test('page changes text while request in flight: stale result dropped', async () => {
    const h = make('<body><p id="a">Old value</p></body>');
    h.tr.start({});
    const p = h.d.getElementById('a');
    p.firstChild.nodeValue = 'New value';
    answer(h); // late answer for "Old value"
    assert.strictEqual(p.textContent, 'New value');
    await flush(450);
    const reqs = h.sent.filter(m => m.t === 'req');
    assert.deepStrictEqual(J(reqs[reqs.length - 1].texts), ['New value']);
  });

  await test('error pauses; retry resends only untranslated', async () => {
    const body = Array.from({ length: 30 }, (_, i) => `<p>Row ${i}</p>`).join('');
    const h = make('<body>' + body + '</body>');
    h.tr.start({});
    const reqs = h.sent.filter(m => m.t === 'req');
    assert.strictEqual(reqs.length, 2); // 20 + 10
    reqs[0]._done = true; h.tr.recv({ t: 'res', id: reqs[0].id, texts: reqs[0].texts.map(T) });
    reqs[1]._done = true; h.tr.recv({ t: 'err', id: reqs[1].id, kind: 'NETWORK', detail: 'timeout' });
    const st = states(h).pop();
    assert.strictEqual(st.phase, 'error');
    assert.strictEqual(st.err.kind, 'NETWORK');
    assert.strictEqual(st.done, 20);
    const count = h.sent.filter(m => m.t === 'req').length;
    assert.strictEqual(count, 2, 'no more requests while in error');
    assert(h.tr.retry());
    const again = h.sent.filter(m => m.t === 'req' && !m._done);
    assert.strictEqual(again.length, 1);
    assert.strictEqual(again[0].texts.length, 10);
    answer(h);
    assert.strictEqual(states(h).pop().phase, 'done');
    assert.strictEqual(h.d.body.querySelectorAll('p')[29].textContent, '〔Row 29〕');
  });

  await test('restart (e.g. new target language) restores then re-translates; old responses ignored', async () => {
    const h = make('<body><p id="a">Hello there</p></body>');
    h.tr.start({ target: 'zh-CN' });
    const first = h.sent.filter(m => m.t === 'req')[0];
    h.tr.start({ target: 'ja' });
    h.tr.recv({ t: 'res', id: first.id, texts: first.texts.map(T) }); // stale
    assert.strictEqual(h.d.getElementById('a').textContent, 'Hello there');
    answer(h, m => !m._done && m.id !== first.id);
    assert.strictEqual(h.d.getElementById('a').textContent, '〔Hello there〕');
    assert.strictEqual(h.tr.status().target, 'ja');
  });

  await test('empty page / nothing translatable ends as done', async () => {
    const h = make('<body><p>12345</p><p>这是中文内容不翻译</p></body>');
    h.tr.start({});
    assert.strictEqual(h.sent.filter(m => m.t === 'req').length, 0);
    assert.strictEqual(states(h).pop().phase, 'done');
  });

  await test('node cap: long pages stop at MAX_NODES', async () => {
    const body = Array.from({ length: 4500 }, (_, i) => `<i>Item ${i}</i>`).join('');
    const h = make('<body>' + body + '</body>');
    h.tr.start({});
    assert(h.tr.status().total <= 4001 && h.tr.status().total >= 4000, h.tr.status().total);
  });

  await test('re-injecting the script is a no-op (keeps state)', async () => {
    const h = make('<body><p>Hello again</p></body>');
    h.tr.start({});
    const ref = h.w.__mafariTr;
    h.w.eval(SRC);
    assert.strictEqual(h.w.__mafariTr, ref);
  });

  await test('webview transport posts JSON via MafariTr.post', async () => {
    const dom = new JSDOM('<body><p>Hello webview</p></body>', { runScripts: 'outside-only' });
    const w = dom.window; const got = [];
    w.MafariTr = { post: s => got.push(JSON.parse(s)) };
    w.eval(SRC);
    w.__mafariTr.attachWebView();
    w.__mafariTr.start({ target: 'zh-CN' });
    assert(got.some(m => m.t === 'req' && m.texts[0] === 'Hello webview'));
    assert(got.some(m => m.t === 'state' && m.phase === 'translating'));
  });

  await test('webview transport prefers MafariTr.postMessage (WebMessageListener)', async () => {
    const dom = new JSDOM('<body><p>Hello listener</p></body>', { runScripts: 'outside-only' });
    const w = dom.window; const got = [];
    w.MafariTr = { postMessage: s => got.push(JSON.parse(s)) };
    w.eval(SRC);
    w.__mafariTr.attachWebView();
    w.__mafariTr.start({ target: 'zh-CN' });
    assert(got.some(m => m.t === 'req' && m.texts[0] === 'Hello listener'));
  });

  await test('gecko glue: connectNative, commands and replies', async () => {
    const dom = new JSDOM('<body><p>Hello gecko</p></body>', { runScripts: 'outside-only' });
    const w = dom.window; const posted = []; let onMsg;
    w.browser = { runtime: { id: 'translate@mafari.dev', connectNative: name => {
      assert.strictEqual(name, 'mafariTranslate');
      return { postMessage: m => posted.push(m), onMessage: { addListener: f => onMsg = f }, onDisconnect: { addListener() {} } };
    } } };
    w.eval(SRC);
    onMsg({ t: 'start', target: 'ja' });
    const req = posted.find(m => m.t === 'req');
    assert.deepStrictEqual(J(req.texts), ['Hello gecko']);
    onMsg({ t: 'res', id: req.id, texts: ['こんにちは'] });
    assert.strictEqual(w.document.querySelector('p').textContent, 'こんにちは');
    onMsg({ t: 'original' });
    assert.strictEqual(w.document.querySelector('p').textContent, 'Hello gecko');
    onMsg({ t: 'translation' });
    assert.strictEqual(w.document.querySelector('p').textContent, 'こんにちは');
    onMsg({ t: 'stop' });
    assert.strictEqual(w.document.querySelector('p').textContent, 'Hello gecko');
  });

  console.log(n + ' passed');
})();
