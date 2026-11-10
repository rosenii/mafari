/*
 * 后台页（MV2 持久页）：
 *  1. 与原生保持一条长连接（connectNative 'mafariGmBg'），接收「脚本全量同步」并用 contentScripts.register 注册；
 *  2. GM_xmlhttpRequest：fetch（扩展页不受 CORS 限制）+ webRequest 改写 Cookie/Referer/Origin/UA；
 *  3. GM_cookie：cookies API（含 HttpOnly 与完整属性）；
 *  4. 值变化广播：写入 storage.local.bus，各标签的中继监听 storage.onChanged。
 * 值与脚本的唯一权威是原生（SharedPreferences "userscript_gm"），这里不存值，避免与 WebView 标签分叉。
 * contentScripts.register 不跨会话持久化：每次后台启动都由原生重新同步。
 */
'use strict';

const NATIVE = 'mafariGmBg';
const registered = new Map(); // scriptId -> { sig, handle }
let port = null;
let busN = 0;
let applyChain = Promise.resolve();

function post(m) { try { if (port) port.postMessage(m); } catch (e) {} }

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE);
    port.onMessage.addListener(onNative);
    port.onDisconnect.addListener(() => { port = null; setTimeout(connect, 1000); });
    post({ t: 'hello' });
  } catch (e) { port = null; setTimeout(connect, 2000); }
}

function onNative(m) {
  if (!m) return;
  if (m.t === 'sync') {
    applyChain = applyChain.then(() => applyAll(m)).catch((e) => post({ t: 'error', where: 'sync', msg: String(e) }));
  } else if (m.t === 'val') {
    browser.storage.local.set({ bus: { n: ++busN, id: m.id, k: m.k, raw: m.raw === undefined ? null : m.raw, src: m.src || '' } });
  }
}

/** m.scripts: [{id, sig, code, matches, excludeMatches, noframes}]；全量替换，按 sig 做差量 */
async function applyAll(m) {
  const want = new Map(m.scripts.map((s) => [s.id, s]));
  for (const [id, r] of [...registered]) {
    const w = want.get(id);
    if (!w || w.sig !== r.sig) {
      try { await r.handle.unregister(); } catch (e) {}
      registered.delete(id);
    }
  }
  let failed = 0;
  for (const s of m.scripts) {
    if (registered.has(s.id)) continue;
    try {
      const handle = await browser.contentScripts.register({
        matches: s.matches && s.matches.length ? s.matches : ['*://*/*'],
        excludeMatches: s.excludeMatches && s.excludeMatches.length ? s.excludeMatches : undefined,
        js: [{ code: s.code }],
        runAt: 'document_start',       // 精确的 run-at 由运行时在各框架内等待，与 WebView 一致
        allFrames: !s.noframes,
        matchOriginAsFallback: true,   // about:blank / srcdoc / blob: 子框架按来源匹配
        world: 'MAIN'                  // 与页面同一个 JS 世界：unsafeWindow === window，语义同 WebView
      });
      registered.set(s.id, { sig: s.sig, handle });
    } catch (e) {
      failed++;
      post({ t: 'error', where: 'register', id: s.id, msg: String(e) });
    }
  }
  post({ t: 'synced', rev: m.rev, count: registered.size, failed });
}

// ------------------------------------------------------------------ GM_xmlhttpRequest

const pending = new Map(); // reqId -> { ctl, headers }
const OVERRIDE = /^(cookie|referer|origin|user-agent)$/i; // fetch 禁止直接设置，交给 webRequest 改写
const B64 = {
  enc(buf) {
    const b = new Uint8Array(buf); let s = ''; const C = 0x8000;
    for (let i = 0; i < b.length; i += C) s += String.fromCharCode.apply(null, b.subarray(i, i + C));
    return btoa(s);
  },
  dec(s) {
    if (!s) return undefined;
    const bin = atob(s); const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
};

browser.webRequest.onBeforeSendHeaders.addListener((d) => {
  const h = d.requestHeaders;
  const i = h.findIndex((x) => x.name.toLowerCase() === 'x-mafari-req');
  if (i < 0) return undefined;
  const id = h[i].value;
  h.splice(i, 1);
  const p = pending.get(id);
  if (p) {
    for (const k of Object.keys(p.headers)) {
      if (!OVERRIDE.test(k)) continue;
      const j = h.findIndex((x) => x.name.toLowerCase() === k.toLowerCase());
      if (j >= 0) h[j].value = String(p.headers[k]); else h.push({ name: k, value: String(p.headers[k]) });
    }
  }
  return { requestHeaders: h };
}, { urls: ['<all_urls>'], types: ['xmlhttprequest'] }, ['blocking', 'requestHeaders']);

async function doXhr(m, sender) {
  const reqId = m.reqId;
  const out = (o) => Object.assign({ t: 'xhrRes', reqId }, { json: JSON.stringify(o) });
  if (sender.tab && sender.tab.incognito) {
    // 后台页的 Cookie 罐不是私有会话的：私有标签里不代发请求，避免串 Cookie
    return out({ state: 'error', error: 'GM_xmlhttpRequest is disabled in private tabs' });
  }
  let o;
  try { o = JSON.parse(m.opts); } catch (e) { return out({ state: 'error', error: 'bad options' }); }
  if (!/^https?:/i.test(o.url || '')) return out({ state: 'error', error: 'unsupported url' });

  const ctl = new AbortController();
  const headers = o.headers || {};
  pending.set(reqId, { ctl, headers });
  const h = new Headers();
  for (const k of Object.keys(headers)) {
    if (OVERRIDE.test(k) || /^(accept-encoding|content-length|host|connection)$/i.test(k)) continue;
    try { h.set(k, String(headers[k])); } catch (e) {}
  }
  h.set('X-Mafari-Req', reqId);

  const method = String(o.method || 'GET').toUpperCase();
  const init = {
    method, headers: h, signal: ctl.signal, cache: 'no-store',
    credentials: o.anonymous ? 'omit' : 'include',
    redirect: o.redirect === 'manual' ? 'manual' : o.redirect === 'error' ? 'error' : 'follow'
  };
  const body = B64.dec(m.body);
  if (body && method !== 'GET' && method !== 'HEAD') init.body = body;

  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; ctl.abort(); }, (o.timeout > 0 ? o.timeout : 30000));
  try {
    const res = await fetch(o.url, init);
    const buf = await res.arrayBuffer();
    let raw = '';
    res.headers.forEach((v, k) => { raw += k + ': ' + v + '\r\n'; });
    return out({
      state: 'load', status: res.status, statusText: res.statusText, finalUrl: res.url || o.url,
      headers: raw, body: B64.enc(buf)
    });
  } catch (e) {
    if (timedOut) return out({ state: 'timeout', error: 'timeout' });
    if (ctl.signal.aborted) return out({ state: 'abort' });
    return out({ state: 'error', error: (e && e.message) || 'request failed' });
  } finally {
    clearTimeout(timer);
    pending.delete(reqId);
  }
}

// ------------------------------------------------------------------ GM_cookie

function toGm(c) {
  return {
    name: c.name, value: c.value, domain: c.domain, hostOnly: c.hostOnly, path: c.path,
    secure: c.secure, httpOnly: c.httpOnly, session: c.session,
    expirationDate: c.expirationDate, sameSite: c.sameSite
  };
}

async function cookieList(m, sender) {
  if (sender.tab && sender.tab.incognito) return { t: 'cookieRes', cbId: m.cbId, arr: [] };
  let arr = [];
  try { arr = (await browser.cookies.getAll({ url: m.url })).map(toGm); } catch (e) {}
  return { t: 'cookieRes', cbId: m.cbId, arr };
}

async function cookieSet(m, sender) {
  if (sender.tab && sender.tab.incognito) return;
  const o = m.opts || {};
  try {
    if (o.expirationDate === 0) { await browser.cookies.remove({ url: o.url, name: o.name }); return; }
    const d = { url: o.url, name: o.name, value: o.value || '', path: o.path || '/', secure: !!o.secure, httpOnly: !!o.httpOnly };
    if (o.domain) d.domain = o.domain;
    if (o.expirationDate) d.expirationDate = o.expirationDate;
    if (o.sameSite) d.sameSite = { Strict: 'strict', Lax: 'lax', None: 'no_restriction' }[o.sameSite];
    await browser.cookies.set(d);
  } catch (e) {}
}

// ------------------------------------------------------------------ 来自中继的请求

browser.runtime.onMessage.addListener((m, sender) => {
  if (!m || typeof m.t !== 'string') return undefined;
  switch (m.t) {
    case 'xhr': return doXhr(m, sender);
    case 'xhrAbort': { const p = pending.get(m.reqId); if (p) p.ctl.abort(); return undefined; }
    case 'cookieList': return cookieList(m, sender);
    case 'cookieSet': return cookieSet(m, sender).then(() => undefined);
    default: return undefined;
  }
});

connect();
