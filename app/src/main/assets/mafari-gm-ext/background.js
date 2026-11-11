/*
 * 后台页（MV2 持久页）：
 *  1. 与原生保持一条长连接（connectNative 'mafariGmBg'），接收「脚本全量同步」并用 contentScripts.register 注册；
 *  2. GM_xmlhttpRequest：fetch（扩展页不受 CORS 限制）+ webRequest 改写 Cookie/Referer/Origin/UA；
 *  3. GM_cookie：cookies API（含 HttpOnly 与完整属性）；
 *  4. 中继（relay.js）：按「已启用脚本匹配范围的并集」动态注册（先于脚本），无脚本的页面不加载；
 *     中继与本页保持一条 runtime 端口：bind 校验 + 值变化广播（只发给绑定了该脚本的中继，不写 storage）。
 * 安全：所有来自中继的请求必须带 (sid, tok)，与原生同步下来的脚本凭据逐条比对；xhr / cookie 还要过
 *  - 来源页校验：请求所在框架的 URL 必须落在该脚本注册的匹配范围内；
 *  - 目标主机策略（@connect）：见 connectAllows / cookieAllows——同站始终放行，@connect 列出的放行，
 *    私有网络 / 本机地址必须显式 @connect，声明了 @connect 的脚本不能越界；每一跳重定向都重新检查。
 * 值与脚本的唯一权威是原生（SharedPreferences "userscript_gm"），这里不存值，避免与 WebView 标签分叉。
 * contentScripts.register 不跨会话持久化：每次后台启动都由原生重新同步。
 */
'use strict';

const NATIVE = 'mafariGmBg';
const registered = new Map(); // scriptId -> { sig, handle, tok, connect, matchRes, excludeRes }
let relayReg = null;          // { sig, handle }：动态注册的中继
const relayPorts = new Set(); // 各框架中继的端口，port.sids = 已通过 bind 校验的脚本 id
const BIG_VALUE = 64 * 1024;  // 值广播超过此长度不带内容（接收方按需 getValue 拉取）
let port = null;
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
  } else if (m.t === 'dlCookie') {
    dlCookie(m);
  } else if (m.t === 'val') {
    broadcastVal(m);
  }
}

/** 值变化广播：只发给已绑定该脚本的中继；大值不带内容 */
function broadcastVal(m) {
  if (typeof m.id !== 'string') return;
  const raw = typeof m.raw === 'string' ? m.raw : null;
  const big = !!m.big || (raw !== null && raw.length > BIG_VALUE);
  const out = { t: 'val', id: m.id, k: m.k, src: m.src || '' };
  if (big) out.big = true; else out.raw = raw;
  for (const p of relayPorts) {
    if (!p.sids.has(m.id)) continue;
    try { p.postMessage(out); } catch (e) {}
  }
}

browser.runtime.onConnect.addListener((p) => {
  if (!p || p.name !== 'mafariRelay' || !p.sender || p.sender.id !== browser.runtime.id) return;
  p.sids = new Set();
  relayPorts.add(p);
  p.onMessage.addListener((m) => {
    if (!m || m.t !== 'bind') return;
    // 令牌 + 本框架 URL 在脚本匹配范围内；通过后该中继端口才会收到这个脚本的广播
    const ok = !!authorize(m, p.sender);
    if (ok) p.sids.add(String(m.sid));
    try { p.postMessage({ t: 'bound', rid: m.rid, ok }); } catch (e) {}
  });
  p.onDisconnect.addListener(() => { relayPorts.delete(p); });
});

/**
 * 下载器取登录态：原生端（Downloader）重新发 HTTP 请求时拿不到 Gecko 的 Cookie，这里替它读。
 * 只响应原生端口上的请求（页面 / 内容脚本够不到这条端口）；无痕会话的 Cookie 罐与本页不同，原生端不会请求。
 * 同时回报 navigator.userAgent：部分站点的登录 / 反爬 Cookie 绑定 UA，下载请求要用同一个。
 */
async function dlCookie(m) {
  let cookie = '';
  try {
    if (/^https?:/i.test(String(m.url || ''))) {
      // getAll 已按 url 的域名 / 路径 / Secure 过滤并按路径长度降序排好，和浏览器发出的 Cookie 头顺序一致
      const arr = await browser.cookies.getAll({ url: m.url });
      cookie = arr.map((c) => c.name + '=' + c.value).join('; ');
    }
  } catch (e) {}
  post({ t: 'dlCookieRes', rid: String(m.rid || ''), cookie, ua: navigator.userAgent });
}

/**
 * m.scripts: [{id, sig, tok, connect, code?, matches, excludeMatches, noframes}]；全量替换，按 sig 做差量。
 * code 可省略：原生知道本后台已注册了同 sig 的脚本时不再重复发送源码（只有 sig 变了才带 code）。
 */
async function applyAll(m) {
  const want = new Map(m.scripts.map((s) => [s.id, s]));
  for (const [id, r] of [...registered]) {
    const w = want.get(id);
    if (!w || w.sig !== r.sig) {
      try { await r.handle.unregister(); } catch (e) {}
      registered.delete(id);
    }
  }
  // 中继先于脚本注册：同为 document_start 时，先注册的先执行，shim 首次握手时中继已在监听
  await syncRelay(m.scripts);
  let failed = 0;
  for (const s of m.scripts) {
    if (registered.has(s.id)) continue;
    if (typeof s.code !== 'string') { failed++; post({ t: 'error', where: 'register', id: s.id, msg: 'missing code' }); continue; }
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
      registered.set(s.id, {
        sig: s.sig, handle, tok: String(s.tok || ''),
        connect: normConnect(s.connect),
        matchRes: compileAll(s.matches && s.matches.length ? s.matches : ['*://*/*']),
        excludeRes: compileAll(s.excludeMatches || [])
      });
    } catch (e) {
      failed++;
      post({ t: 'error', where: 'register', id: s.id, msg: String(e) });
    }
  }
  post({ t: 'synced', rev: m.rev, count: registered.size, failed });
}

/** 中继按所有脚本 matches 的并集注册；没有脚本就不注册（无脚本页面零中继成本）。matches / allFrames 变了才重注册 */
async function syncRelay(scripts) {
  const set = new Set();
  let frames = false;
  for (const s of scripts) {
    for (const x of (s.matches && s.matches.length ? s.matches : ['*://*/*'])) set.add(x);
    if (!s.noframes) frames = true;
  }
  const matches = [...set].sort();
  const sig = scripts.length ? JSON.stringify([matches, frames]) : '';
  if (relayReg && relayReg.sig === sig) return;
  if (relayReg) { try { await relayReg.handle.unregister(); } catch (e) {} relayReg = null; }
  if (!sig) return;
  try {
    const handle = await browser.contentScripts.register({
      matches,
      js: [{ file: 'relay.js' }],
      runAt: 'document_start',
      allFrames: frames,
      matchAboutBlank: true,
      matchOriginAsFallback: true
    });
    relayReg = { sig, handle };
  } catch (e) { post({ t: 'error', where: 'relay', id: '', msg: String(e) }); }
}

// ------------------------------------------------------------------ 授权与策略

const esc = (t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** 把 match pattern（<all_urls> / scheme://host/path，host 可为 * 或 *.x）编译成正则；不认识的返回 null */
function compileMatch(p) {
  try {
    if (p === '<all_urls>') return /^(?:https?|ftp|file):/i;
    const m = /^(\*|https?|file|ftp):\/\/([^\/]*)(\/.*)$/.exec(p);
    if (!m) return null;
    const scheme = m[1] === '*' ? 'https?' : m[1];
    const host = m[2] === '*' ? '[^/]*' : m[2].startsWith('*.') ? '(?:[^/]*\\.)?' + esc(m[2].slice(2)) : esc(m[2]);
    const path = m[3].split('*').map(esc).join('.*');
    return new RegExp('^' + scheme + ':\\/\\/' + host + '(?::\\d+)?' + path + '$', 'i');
  } catch (e) { return null; }
}
const compileAll = (list) => list.map(compileMatch).filter(Boolean);

function normConnect(list) {
  const out = [];
  for (const raw of Array.isArray(list) ? list : []) {
    let c = String(raw).trim().toLowerCase();
    if (!c) continue;
    if (c !== '*' && c !== 'self') c = c.replace(/^[a-z][a-z0-9+.\-]*:\/\//, '').replace(/[\/?#].*$/, '').replace(/^\*\./, '').replace(/:\d+$/, '');
    if (c) out.push(c);
  }
  return out;
}

function hostOf(u) { try { return new URL(u).hostname.toLowerCase(); } catch (e) { return ''; } }

/** 本机 / 局域网 / 链路本地 / 单标签内网名。解析到内网的公网域名（DNS rebinding）这里看不出来，属已知限制 */
function isPrivateHost(h) {
  h = String(h).replace(/^\[|\]$/g, '').toLowerCase().replace(/\.$/, '');
  if (!h) return true;
  if (h === 'localhost' || /\.(localhost|local|internal|lan|home\.arpa)$/.test(h)) return true;
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (v4) {
    const a = +v4[1], b = +v4[2];
    return a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (h.includes(':')) {
    if (h === '::' || h === '::1') return true;
    if (/^f[cd]/.test(h) || /^fe[89ab]/.test(h)) return true;
    const dotted = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(h);
    if (dotted) return isPrivateHost(dotted[1]);
    const hex = /^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(h);
    if (hex) {
      const x = parseInt(hex[1], 16), y = parseInt(hex[2], 16);
      return isPrivateHost([x >> 8, x & 255, y >> 8, y & 255].join('.'));
    }
    return false;
  }
  return !h.includes('.'); // 单标签名（router、nas…）只会在内网解析
}

const sameSite = (host, page) => !!page && (host === page || host.endsWith('.' + page));
const listed = (list, host) => list.some((c) => c !== '*' && c !== 'self' && (host === c || host.endsWith('.' + c)));

/** GM_xmlhttpRequest 的目标主机策略 */
function connectAllows(pol, host) {
  host = String(host).toLowerCase();
  if (sameSite(host, pol.pageHost) || listed(pol.connect, host)) return true;
  if (isPrivateHost(host)) return false;                   // 内网 / 本机必须显式 @connect
  return pol.connect.length === 0 || pol.connect.includes('*'); // 没声明：放行公网（兼容）；声明了就按声明
}

/** GM_cookie 更严：只给同站或 @connect 明确列出的主机（'*' 不算，没声明也不算） */
function cookieAllows(pol, host) {
  host = String(host).toLowerCase();
  return sameSite(host, pol.pageHost) || listed(pol.connect, host);
}

/** 校验一条来自中继的请求：脚本凭据 + 来源框架。通过返回策略对象，否则 null */
function authorize(m, sender) {
  if (!sender || sender.id !== browser.runtime.id) return null;
  const r = registered.get(m.sid);
  if (!r || !r.tok || typeof m.tok !== 'string' || m.tok !== r.tok) return null;
  const frameUrl = String(sender.url || '').replace(/#.*$/, '');
  const tabUrl = String((sender.tab && sender.tab.url) || '').replace(/#.*$/, '');
  if (/^(?:https?|ftp|file):/i.test(frameUrl) && r.matchRes.length) {
    // 匹配表只是 Gecko 注入时的预过滤；这里再确认一次请求确实来自该脚本应当运行的框架
    if (!r.matchRes.some((re) => re.test(frameUrl))) return null;
    if (r.excludeRes.some((re) => re.test(frameUrl))) return null;
  }
  const pageUrl = /^https?:/i.test(frameUrl) ? frameUrl : tabUrl; // about:blank / blob: 子框架退回标签地址
  return { sid: m.sid, connect: r.connect, pageHost: hostOf(pageUrl) };
}

// ------------------------------------------------------------------ GM_xmlhttpRequest

const pending = new Map(); // reqId -> { ctl, headers, pol, blocked }
const OVERRIDE = /^(cookie|referer|origin|user-agent)$/i; // fetch 禁止直接设置，交给 webRequest 改写
const METHODS = /^(GET|HEAD|POST|PUT|DELETE|PATCH|OPTIONS)$/;
// 运行时生成的 reqId 形如「脚本id:时间戳随机串」（含冒号），这里只需保证它能安全放进 header 值、长度有界
const REQ_ID = /^[A-Za-z0-9_:.\-]{1,200}$/;
const MAX_BODY_B64 = 48 * 1024 * 1024;   // 请求体 base64 上限（约 36MB）
const MAX_RESPONSE = 48 * 1024 * 1024;   // 响应体上限，防止撑爆后台页 / 通道
const MAX_PENDING_PER_SCRIPT = 32;
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

/** 读响应体并限长（超限立即中止） */
async function readLimited(res, max) {
  const len = Number(res.headers.get('content-length'));
  if (len > max) throw new Error('response too large');
  if (!res.body || !res.body.getReader) {
    const b = await res.arrayBuffer();
    if (b.byteLength > max) throw new Error('response too large');
    return b;
  }
  const reader = res.body.getReader();
  const chunks = []; let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    n += value.length;
    if (n > max) { try { reader.cancel(); } catch (e) {} throw new Error('response too large'); }
    chunks.push(value);
  }
  const out = new Uint8Array(n); let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out.buffer;
}

// 每一跳（含重定向）都在这里过目标主机策略；helper header 在这里剥掉并按需改写 Cookie / Referer / Origin / UA
browser.webRequest.onBeforeSendHeaders.addListener((d) => {
  const h = d.requestHeaders;
  const i = h.findIndex((x) => x.name.toLowerCase() === 'x-mafari-req');
  if (i < 0) return undefined;
  const id = h[i].value;
  h.splice(i, 1);
  const p = pending.get(id);
  if (!p || d.tabId !== -1) return { requestHeaders: h }; // 只对后台页自己发的请求生效，页面伪造该头无效
  if (!connectAllows(p.pol, hostOf(d.url))) { p.blocked = hostOf(d.url); return { cancel: true }; }
  for (const k of Object.keys(p.headers)) {
    if (!OVERRIDE.test(k)) continue;
    const j = h.findIndex((x) => x.name.toLowerCase() === k.toLowerCase());
    if (j >= 0) h[j].value = String(p.headers[k]); else h.push({ name: k, value: String(p.headers[k]) });
  }
  return { requestHeaders: h };
}, { urls: ['<all_urls>'], types: ['xmlhttprequest'] }, ['blocking', 'requestHeaders']);

async function doXhr(m, sender) {
  const reqId = typeof m.reqId === 'string' ? m.reqId : '';
  const out = (o) => Object.assign({ t: 'xhrRes', reqId }, { json: JSON.stringify(o) });
  const fail = (error) => out({ state: 'error', error });
  const pol = authorize(m, sender);
  if (!pol) return fail('GM_xmlhttpRequest: not authorized');
  if (!REQ_ID.test(reqId)) return fail('bad request id');
  if (sender.tab && sender.tab.incognito) {
    // 后台页的 Cookie 罐不是私有会话的：私有标签里不代发请求，避免串 Cookie
    return fail('GM_xmlhttpRequest is disabled in private tabs');
  }
  let o;
  try { o = JSON.parse(m.opts); } catch (e) { return fail('bad options'); }
  if (!o || typeof o !== 'object' || !/^https?:/i.test(String(o.url || ''))) return fail('unsupported url');
  const host = hostOf(o.url);
  if (!host) return fail('bad url');
  if (!connectAllows(pol, host)) return fail('blocked by @connect policy: ' + host + '（在脚本头部添加 // @connect ' + host + '）');
  const method = String(o.method || 'GET').toUpperCase();
  if (!METHODS.test(method)) return fail('unsupported method');
  if (typeof m.body === 'string' && m.body.length > MAX_BODY_B64) return fail('request body too large');
  let n = 0;
  for (const p of pending.values()) if (p.sid === pol.sid) n++;
  if (n >= MAX_PENDING_PER_SCRIPT) return fail('too many concurrent requests');
  if (pending.has(reqId)) return fail('duplicate request id');

  const ctl = new AbortController();
  const headers = (o.headers && typeof o.headers === 'object') ? o.headers : {};
  const entry = { ctl, headers, pol, sid: pol.sid, blocked: '' };
  pending.set(reqId, entry);
  const h = new Headers();
  for (const k of Object.keys(headers)) {
    if (OVERRIDE.test(k) || /^(accept-encoding|content-length|host|connection|x-mafari-req)$/i.test(k)) continue;
    try { h.set(k, String(headers[k])); } catch (e) {}
  }
  if (o.user && !Object.keys(headers).some((k) => k.toLowerCase() === 'authorization')) {
    try { h.set('Authorization', 'Basic ' + btoa(unescape(encodeURIComponent(o.user + ':' + (o.password || ''))))); } catch (e) {}
  }
  h.set('X-Mafari-Req', reqId);

  const init = {
    method, headers: h, signal: ctl.signal, cache: 'no-store',
    credentials: o.anonymous ? 'omit' : 'include',
    redirect: o.redirect === 'manual' ? 'manual' : o.redirect === 'error' ? 'error' : 'follow'
  };
  let body;
  try { body = B64.dec(m.body); } catch (e) { pending.delete(reqId); return fail('bad body'); }
  if (body && method !== 'GET' && method !== 'HEAD') init.body = body;

  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; ctl.abort(); }, (o.timeout > 0 ? Math.min(o.timeout, 10 * 60 * 1000) : 30000));
  try {
    const res = await fetch(o.url, init);
    const buf = await readLimited(res, MAX_RESPONSE);
    let raw = '';
    res.headers.forEach((v, k) => { raw += k + ': ' + v + '\r\n'; });
    return out({
      state: 'load', status: res.status, statusText: res.statusText, finalUrl: res.url || o.url,
      headers: raw, body: B64.enc(buf)
    });
  } catch (e) {
    if (entry.blocked) return fail('blocked by @connect policy (redirect): ' + entry.blocked);
    if (timedOut) return out({ state: 'timeout', error: 'timeout' });
    if (ctl.signal.aborted) return out({ state: 'abort' });
    return fail((e && e.message) || 'request failed');
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

function cookieTarget(m, sender, url) {
  const pol = authorize(m, sender);
  if (!pol || (sender.tab && sender.tab.incognito)) return null;
  if (!/^https?:/i.test(String(url || ''))) return null;
  const host = hostOf(url);
  return host && cookieAllows(pol, host) ? pol : null;
}

async function cookieList(m, sender) {
  const empty = { t: 'cookieRes', cbId: m.cbId, arr: [] };
  if (!cookieTarget(m, sender, m.url)) return empty;
  let arr = [];
  try { arr = (await browser.cookies.getAll({ url: m.url })).map(toGm); } catch (e) {}
  return { t: 'cookieRes', cbId: m.cbId, arr };
}

async function cookieSet(m, sender) {
  const o = (m.opts && typeof m.opts === 'object') ? m.opts : {};
  if (!cookieTarget(m, sender, o.url) || typeof o.name !== 'string') return;
  try {
    if (o.expirationDate === 0) { await browser.cookies.remove({ url: o.url, name: o.name }); return; }
    const d = { url: o.url, name: o.name, value: String(o.value || ''), path: o.path || '/', secure: !!o.secure, httpOnly: !!o.httpOnly };
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
    case 'xhrAbort': {
      const p = pending.get(m.reqId);
      if (p && m.sid === p.sid && authorize(m, sender)) p.ctl.abort();
      return undefined;
    }
    case 'cookieList': return cookieList(m, sender);
    case 'cookieSet': return cookieSet(m, sender).then(() => undefined);
    default: return undefined;
  }
});

connect();
