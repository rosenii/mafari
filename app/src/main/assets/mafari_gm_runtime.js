/*
 * Mafari userscript runtime (v2).
 * Kotlin (UserscriptEngine.buildSource) substitutes three double-underscore placeholders, each exactly once:
 * the metadata JSON object, the inner function keyword (async for @top-level-await),
 * and the concatenated @require sources + userscript body.
 * This file is intentionally NOT strict-mode: userscripts are frequently sloppy-mode code.
 *
 * Native bridge surface this runtime understands (all optional except the base 5):
 *   Required: getValue, setValue, deleteValue, listValues, toast
 *   Optional (graceful fallback):
 *     - xhrStart / xhrPoll / xhrAbort
 *     - streamOpen / streamWrite / streamClose / streamAbort
 *     - copy / copyHtml
 *     - download(url, name)              fire-and-forget
 *     - downloadAsync(url, name, cbId)   async; native calls
 *                                        __mafariDlCallback_<cbId>(ok, err)
 *     - openTab(url, optionsJson) -> id  optional id for close
 *     - closeTab(id)
 *     - cookies(url) -> raw cookie string
 *     - cookieList(url) -> JSON array of {name,value,domain,path,httpOnly,secure,session,expirationDate}
 *     - cookieSet(json)                  set full cookie via native
 *     - notification(json, id)           native calls __mafariNtfCallback_<id>(kind),
 *                                        kind = 'click' | 'close' | 'timeout'
 *     - createBlobUrl(resourceName) -> blob: URL
 *     - getValues(scriptId, namesJson) -> JSON { name: rawString }
 */
(function () {
  var W = window;
  var META = __MAFARI_META__;

  // ------------------------------------------------------------------ frames / guards
  try { if (META.noframes && W.top !== W.self) return; } catch (e) { if (META.noframes) return; }
  var SEEN = W.__mafariSeen || (W.__mafariSeen = {});
  if (SEEN[META.id]) return;
  SEEN[META.id] = 1;

  var _setTimeout = W.setTimeout.bind(W);
  var _clearTimeout = (W.clearTimeout || clearTimeout).bind(W);
  var _setInterval = W.setInterval.bind(W);
  var _clearInterval = W.clearInterval.bind(W);

  var bridge = null;
  // Gecko：由 GeckoGm 在外层闭包里传入 __mafariBridge（页面拿不到）；WebView：window.MafariGM
  try { bridge = (typeof __mafariBridge !== 'undefined' && __mafariBridge) || W.MafariGM || null; } catch (e) { bridge = null; }

  function log(level, msg) {
    try { console[level]('[Mafari] ' + META.name + ': ' + msg); } catch (e) {}
  }

  // ------------------------------------------------------------------ URL matching
  function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  var reCache = {};
  function cachedRe(src, flags) {
    var key = src + '\x00' + flags;
    var r = reCache[key];
    if (!r) r = reCache[key] = new RegExp(src, flags);
    return r;
  }

  function regexLiteral(p, url) {
    var m = /^\/([\s\S]+)\/([a-z]*)$/.exec(p);
    if (!m) return null;
    // Strip g/y: they make .test() stateful via lastIndex.
    var flags = m[2].replace(/[gy]/g, '');
    try { return cachedRe(m[1], flags).test(url); } catch (e) { return false; }
  }
  function globTest(p, url) {
    var r = regexLiteral(p, url);
    if (r !== null) return r;
    if (p === '*') return true;
    var re = cachedRe('^' + p.split('*').map(escRe).join('.*') + '$', 'i');
    return re.test(url) || re.test(url.replace(/#[\s\S]*$/, ''));
  }
  function matchPatternTest(p, href) {
    var r = regexLiteral(p, href);
    if (r !== null) return r;
    if (p === '<all_urls>') return /^(https?|file|ftp):/i.test(href);
    var m = /^(\*|[a-z][a-z0-9+.\-*]*):\/\/([^\/]*)(\/[\s\S]*)?$/i.exec(p);
    if (!m) return globTest(p, href);
    var u;
    try { u = new URL(href); } catch (e) { return false; }
    var proto = u.protocol.slice(0, -1).toLowerCase();
    var sch = m[1].toLowerCase();
    if (sch === '*') {
      if (proto !== 'http' && proto !== 'https') return false;
    } else if (sch.indexOf('*') >= 0) {
      if (!cachedRe('^' + sch.split('*').map(escRe).join('.*') + '$', '').test(proto)) return false;
    } else if (sch !== proto) {
      return false;
    }
    var hp = m[2].toLowerCase(), port = '';
    var pm = /:(\d+|\*)$/.exec(hp);
    if (pm) { port = pm[1]; hp = hp.slice(0, pm.index); }
    var host = u.hostname.toLowerCase();
    var hostOk;
    if (hp === '*' || hp === '') hostOk = (hp === '*') || host === '';
    else if (hp.indexOf('*.') === 0 && hp.indexOf('*', 1) < 0) {
      var base = hp.slice(2);
      hostOk = host === base || host.slice(-(base.length + 1)) === '.' + base;
    } else if (hp.indexOf('*') >= 0) {
      hostOk = cachedRe('^' + hp.split('*').map(escRe).join('.*') + '$', '').test(host);
    } else hostOk = host === hp;
    if (!hostOk) return false;
    if (port && port !== '*') {
      var up = u.port || (proto === 'https' ? '443' : proto === 'http' ? '80' : '');
      if (up !== port) return false;
    }
    var pp = m[3] || '/*';
    var re = cachedRe('^' + pp.split('*').map(escRe).join('.*') + '$', '');
    var target = u.pathname + u.search;
    return re.test(target) || re.test(target + u.hash);
  }
  function anyOf(list, fn, href) {
    for (var i = 0; list && i < list.length; i++) { if (fn(list[i], href)) return true; }
    return false;
  }
  function shouldRun(href) {
    if (anyOf(META.exclude, globTest, href)) return false;
    if (anyOf(META.excludeMatch, matchPatternTest, href)) return false;
    if (META.userMatch && META.userMatch.length) return anyOf(META.userMatch, matchPatternTest, href);
    var hasRules = (META.match && META.match.length) || (META.include && META.include.length);
    if (!hasRules) return true;
    return anyOf(META.match, matchPatternTest, href) || anyOf(META.include, globTest, href);
  }
  if (!shouldRun(W.location.href)) return;
  (W.__mafariRan || (W.__mafariRan = {}))[META.id] = 1;
  // Gecko 没有 evaluateJavascript 可供原生来问：上报「本页真正运行了哪些脚本」（WebView 的桥没有该方法，忽略）
  try { if (bridge && typeof bridge.ran === 'function') bridge.ran(META.id); } catch (e) {}

  // ------------------------------------------------------------------ helpers
  function whenRoot(cb) {
    if (document.documentElement) { cb(); return; }
    var mo = new MutationObserver(function () {
      if (document.documentElement) { mo.disconnect(); cb(); }
    });
    mo.observe(document, { childList: true });
  }
  function bytesToB64(bytes) {
    var s = '', CH = 0x8000;
    for (var i = 0; i < bytes.length; i += CH) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
    }
    return btoa(s);
  }
  function b64ToBytes(b64) {
    if (!b64) return new Uint8Array(0);
    var bin = atob(b64), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function utf8(str) { return new TextEncoder().encode(str); }
  function decodeText(bytes, charset) {
    try { return new TextDecoder(charset || 'utf-8').decode(bytes); }
    catch (e) { return new TextDecoder('utf-8').decode(bytes); }
  }
  function absUrl(u) {
    try { return new URL(String(u), W.location.href).href; } catch (e) { return String(u); }
  }
  function safeCall(fn, thisArg, args, what) {
    if (typeof fn !== 'function') return;
    try { return fn.apply(thisArg, args); }
    catch (e) { log('error', (what || 'callback') + ' threw: ' + (e && e.message)); }
  }
  function shallowCopy(o) {
    var r = {};
    if (o) Object.keys(o).forEach(function (k) { r[k] = o[k]; });
    return r;
  }
  function setCb(name, fn) {
    try { W[name] = fn; } catch (e) {}
  }
  function delCb(name) { try { delete W[name]; } catch (e) {} }

  // ------------------------------------------------------------------ values
  var mem = {};
  var lastListedValues = null;

  function rawGet(k) {
    if (bridge) {
      try {
        var v = bridge.getValue(META.id, String(k));
        return v;
      } catch (e) {}
    }
    return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null;
  }
  function rawSet(k, s) {
    if (bridge) {
      try { bridge.setValue(META.id, String(k), s); return; } catch (e) {}
    }
    mem[k] = s;
  }
  function rawDel(k) {
    if (bridge) {
      try { bridge.deleteValue(META.id, String(k)); return; } catch (e) {}
    }
    delete mem[k];
  }
  function parseVal(raw, d) {
    if (raw === null || raw === undefined) return d;
    try { return JSON.parse(raw); } catch (e) { return raw; }
  }

  var listeners = {}, listenerSeq = 0, pollTimer = null, lastSeen = {};
  var POLL_INTERVAL_MS = 1000;

  function fireChange(name, oldV, newV, remote) {
    var ids = Object.keys(listeners);
    for (var i = 0; i < ids.length; i++) {
      var l = listeners[ids[i]];
      if (l && l.name === name) safeCall(l.cb, W, [name, oldV, newV, remote], 'value listener');
    }
  }
  function pollTick() {
    var names = {};
    Object.keys(listeners).forEach(function (id) {
      if (listeners[id]) names[listeners[id].name] = 1;
    });
    var nameList = Object.keys(names);
    if (!nameList.length) { stopPoll(); return; }
    // Batch query when native supports it.
    if (bridge && typeof bridge.getValues === 'function') {
      var results = null;
      try {
        var rawJson = bridge.getValues(META.id, JSON.stringify(nameList));
        if (rawJson !== null && rawJson !== undefined) results = JSON.parse(rawJson);
      } catch (e) { results = null; }
      if (results && typeof results === 'object') {
        nameList.forEach(function (n) {
          var raw = Object.prototype.hasOwnProperty.call(results, n) ? results[n] : null;
          var prev = lastSeen[n];
          if (raw !== prev) {
            lastSeen[n] = raw;
            fireChange(n, parseVal(prev, undefined), parseVal(raw, undefined), true);
          }
        });
        return;
      }
    }
    // Fallback: one-by-one.
    nameList.forEach(function (n) {
      var raw = rawGet(n);
      if (raw !== lastSeen[n]) {
        var oldV = parseVal(lastSeen[n], undefined);
        lastSeen[n] = raw;
        fireChange(n, oldV, parseVal(raw, undefined), true);
      }
    });
  }
  // 桥支持 onValueChange（Gecko shim）：别的标签 / 框架写值时由桥直接通知，不开 1s 轮询（省定时器 + 每秒一次 getValues）
  var hooked = false;
  function hasListenerFor(n) {
    for (var id in listeners) if (listeners[id] && listeners[id].name === n) return true;
    return false;
  }
  function checkKey(n) {
    function apply(raw) {
      var prev = lastSeen[n];
      if (raw === prev) return;
      lastSeen[n] = raw;
      fireChange(n, parseVal(prev, undefined), parseVal(raw, undefined), true);
    }
    if (bridge && typeof bridge.getValueAsync === 'function') { // 大值只有异步能拿到；同步命中的直接回调
      var sync = true;
      try {
        bridge.getValueAsync(META.id, n, function (raw) {
          if (raw === null && !sync) return; // 异步拿到 null 可能是超时 / 放弃，不当成删除（真删除的广播是同步命中的）
          apply(raw);
        });
        sync = false;
        return;
      } catch (e) {}
    }
    apply(rawGet(n));
  }
  function onBridgeChange(k) { if (hasListenerFor(k)) checkKey(k); }
  function ensurePoll() {
    if (hooked || pollTimer) return;
    if (bridge && typeof bridge.onValueChange === 'function') {
      try { bridge.onValueChange(META.id, onBridgeChange); hooked = true; return; } catch (e) {}
    }
    pollTimer = _setInterval(pollTick, POLL_INTERVAL_MS);
  }
  function stopPoll() {
    if (pollTimer) { _clearInterval(pollTimer); pollTimer = null; }
    if (hooked) { hooked = false; try { bridge.onValueChange(META.id, null); } catch (e) {} }
  }

  function GM_getValue(k, d) { return parseVal(rawGet(k), d); }
  function GM_setValue(k, v) {
    var oldRaw = rawGet(k);
    if (v === undefined) { rawDel(k); }
    else {
      var s;
      try { s = JSON.stringify(v); } catch (e) { log('error', 'GM_setValue: value not serializable'); return; }
      if (s === undefined) { rawDel(k); } else { rawSet(k, s); }
    }
    var newRaw = rawGet(k);
    lastSeen[k] = newRaw;
    if (oldRaw !== newRaw) fireChange(k, parseVal(oldRaw, undefined), parseVal(newRaw, undefined), false);
    // Keep the list cache coherent with a successful bridge.
    if (lastListedValues) {
      if (newRaw === null) {
        var ix = lastListedValues.indexOf(k);
        if (ix >= 0) lastListedValues.splice(ix, 1);
      } else if (lastListedValues.indexOf(k) < 0) {
        lastListedValues.push(k);
      }
    }
  }
  function GM_deleteValue(k) {
    var oldRaw = rawGet(k);
    rawDel(k);
    lastSeen[k] = null;
    if (oldRaw !== null) fireChange(k, parseVal(oldRaw, undefined), undefined, false);
    if (lastListedValues) {
      var ix = lastListedValues.indexOf(k);
      if (ix >= 0) lastListedValues.splice(ix, 1);
    }
  }
  function GM_listValues() {
    if (bridge) {
      try {
        var s = bridge.listValues(META.id);
        if (s !== null && s !== undefined) {
          var arr = JSON.parse(s);
          if (Array.isArray(arr)) { lastListedValues = arr; return arr.slice(); }
        }
      } catch (e) {}
    }
    if (lastListedValues) return lastListedValues.slice();
    return Object.keys(mem);
  }
  function GM_getValues(keys) {
    var out = {};
    if (Array.isArray(keys)) keys.forEach(function (k) { out[k] = GM_getValue(k); });
    else if (keys && typeof keys === 'object') Object.keys(keys).forEach(function (k) { out[k] = GM_getValue(k, keys[k]); });
    else GM_listValues().forEach(function (k) { out[k] = GM_getValue(k); });
    return out;
  }
  function GM_setValues(obj) { Object.keys(obj || {}).forEach(function (k) { GM_setValue(k, obj[k]); }); }
  function GM_deleteValues(keys) { (keys || []).forEach(GM_deleteValue); }
  function GM_addValueChangeListener(name, cb) {
    var id = ++listenerSeq;
    listeners[id] = { name: name, cb: cb };
    if (!Object.prototype.hasOwnProperty.call(lastSeen, name)) {
      lastSeen[name] = rawGet(name);
      if (lastSeen[name] === null && bridge && typeof bridge.getValueAsync === 'function') {
        // 大值同步读不到（首次是 null）：后台补一次基线，免得之后拉到值时被当成「新增」
        try { bridge.getValueAsync(META.id, String(name), function (raw) { if (raw !== null && lastSeen[name] === null) lastSeen[name] = raw; }); } catch (e) {}
      }
    }
    ensurePoll();
    return id;
  }
  function GM_removeValueChangeListener(id) {
    delete listeners[id];
    if ((pollTimer || hooked) && !Object.keys(listeners).length) stopPoll();
  }

  // ------------------------------------------------------------------ styles / elements
  function GM_addStyle(css) {
    var st = document.createElement('style');
    st.textContent = css;
    st.setAttribute('data-mafari', META.name);
    whenRoot(function () {
      (document.head || document.documentElement).appendChild(st);
      // CSP may refuse inline <style>; fall back to a constructable stylesheet.
      try {
        if (!st.sheet && document.adoptedStyleSheets !== undefined && typeof CSSStyleSheet === 'function') {
          var sheet = new CSSStyleSheet();
          sheet.replaceSync(css);
          document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
        }
      } catch (e) {}
    });
    return st;
  }
  function GM_addElement(a, b, c) {
    var parent, tag, attrs;
    if (typeof a === 'string') { tag = a; attrs = b; }
    else { parent = a; tag = b; attrs = c; }
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'textContent' || k === 'innerHTML' || k === 'text') el[k] = attrs[k];
      else el.setAttribute(k, attrs[k]);
    });
    var attach = function () { (parent || document.head || document.documentElement).appendChild(el); };
    if (parent || document.documentElement) attach(); else whenRoot(attach);
    return el;
  }

  // ------------------------------------------------------------------ resources
  function resourceEntry(name) { return META.resources && META.resources[name]; }
  function GM_getResourceText(name) {
    var r = resourceEntry(name);
    return r && r.data ? decodeText(b64ToBytes(r.data)) : null;
  }
  var blobUrlCache = {};
  function GM_getResourceURL(name) {
    var r = resourceEntry(name);
    if (!r) return null;
    if (!r.data) return r.url || null;
    if (blobUrlCache[name]) return blobUrlCache[name];
    // 1) Prefer native blob URL (zero-copy, short lived string).
    if (bridge && typeof bridge.createBlobUrl === 'function') {
      try {
        var u = bridge.createBlobUrl(name);
        if (u) { blobUrlCache[name] = u; return u; }
      } catch (e) {}
    }
    // 2) Blob + URL.createObjectURL.
    try {
      var bytes = b64ToBytes(r.data);
      var blob = new Blob([bytes], { type: r.mime || 'application/octet-stream' });
      var url = URL.createObjectURL(blob);
      blobUrlCache[name] = url;
      return url;
    } catch (e) {}
    // 3) Fallback: inline data URI.
    return 'data:' + (r.mime || 'application/octet-stream') + ';base64,' + r.data;
  }

  /*@@BEGIN xhr*/
  // ------------------------------------------------------------------ xmlHttpRequest
  function headerValue(rawHeaders, name) {
    var re = cachedRe('^' + escRe(name) + ':\\s*(.*)$', 'im');
    var m = re.exec(rawHeaders || '');
    return m ? m[1].trim() : '';
  }
  function prepareBody(details) {
    var headers = {};
    var src = details.headers || {};
    Object.keys(src).forEach(function (k) { headers[k] = src[k]; });
    function hasHeader(n) { return Object.keys(headers).some(function (k) { return k.toLowerCase() === n; }); }
    var d = details.data;
    var done = function (bytes, ct) {
      if (ct && !hasHeader('content-type')) headers['Content-Type'] = ct;
      return { b64: bytes && bytes.length ? bytesToB64(bytes) : '', headers: headers };
    };
    if (d === undefined || d === null) return Promise.resolve(done(null));
    if (typeof d === 'string') {
      if (details.binary) {
        var b = new Uint8Array(d.length);
        for (var i = 0; i < d.length; i++) b[i] = d.charCodeAt(i) & 0xff;
        return Promise.resolve(done(b));
      }
      return Promise.resolve(done(utf8(d)));
    }
    if (typeof URLSearchParams !== 'undefined' && d instanceof URLSearchParams)
      return Promise.resolve(done(utf8(d.toString()), 'application/x-www-form-urlencoded;charset=UTF-8'));
    if (typeof FormData !== 'undefined' && d instanceof FormData) {
      var req = new Request(W.location.href, { method: 'POST', body: d });
      return req.arrayBuffer().then(function (buf) {
        var ct = req.headers.get('content-type');
        Object.keys(headers).forEach(function (k) { if (k.toLowerCase() === 'content-type') delete headers[k]; });
        return done(new Uint8Array(buf), ct);
      });
    }
    if (typeof Blob !== 'undefined' && d instanceof Blob)
      return d.arrayBuffer().then(function (buf) { return done(new Uint8Array(buf), d.type || ''); });
    if (d instanceof ArrayBuffer) return Promise.resolve(done(new Uint8Array(d)));
    if (ArrayBuffer.isView(d)) return Promise.resolve(done(new Uint8Array(d.buffer, d.byteOffset, d.byteLength)));
    return Promise.resolve(done(utf8(String(d))));
  }

  function pollXhr(reqId, onDone) {
    if (bridge && typeof bridge.xhrWait === 'function') { // 推送式：结果到达时由 shim 直接回调，不再轮询
      var waiting = true;
      try {
        bridge.xhrWait(reqId, function (raw) {
          if (!waiting) return;
          waiting = false;
          var res;
          try { res = JSON.parse(raw); } catch (e) { res = { state: 'error', error: 'bad bridge response' }; }
          onDone(res);
        });
      } catch (e) { waiting = false; onDone({ state: 'error', error: String(e) }); }
      return { stop: function () { waiting = false; } };
    }
    var delay = 15;
    var stopped = false;
    function tick() {
      if (stopped) return;
      var raw = null;
      try { raw = bridge.xhrPoll(reqId); }
      catch (e) { stopped = true; onDone({ state: 'error', error: String(e) }); return; }
      if (raw) {
        stopped = true;
        var res;
        try { res = JSON.parse(raw); } catch (e) { res = { state: 'error', error: 'bad bridge response' }; }
        onDone(res);
        return;
      }
      delay = Math.min(delay * 1.5, 150);
      _setTimeout(tick, delay);
    }
    tick();
    return { stop: function () { stopped = true; } };
  }

  // Forbidden request headers per Fetch spec — passing them to fetch throws.
  var FORBIDDEN_FETCH_HEADERS = /^(accept-charset|accept-encoding|access-control-request-headers|access-control-request-method|connection|content-length|cookie|cookie2|date|dnt|expect|host|keep-alive|origin|referer|te|trailer|transfer-encoding|upgrade|via|proxy-|sec-)/i;
  function sanitizeFetchHeaders(headers) {
    var out = {};
    Object.keys(headers || {}).forEach(function (k) {
      if (!FORBIDDEN_FETCH_HEADERS.test(k)) out[k] = headers[k];
    });
    return out;
  }

  function fetchFallback(url, method, headers, bodyB64, details, onDone) {
    var init = {
      method: method,
      headers: sanitizeFetchHeaders(headers),
      credentials: details.anonymous ? 'omit' : 'include'
    };
    if (details.redirect === 'manual') init.redirect = 'manual';
    else if (details.redirect === 'error') init.redirect = 'error';
    if (bodyB64 && method !== 'GET' && method !== 'HEAD') init.body = b64ToBytes(bodyB64);

    var ac = null, tid = null;
    if (details.timeout > 0 && typeof AbortController === 'function') {
      ac = new AbortController();
      init.signal = ac.signal;
      tid = _setTimeout(function () {
        try { ac.abort(); } catch (e) {}
        onDone({ state: 'timeout', error: 'timeout' });
      }, details.timeout);
    }
    function clearT() { if (tid) { _clearTimeout(tid); tid = null; } }

    fetch(url, init).then(function (r) {
      clearT();
      return r.arrayBuffer().then(function (buf) {
        var hs = '';
        r.headers.forEach(function (v, k) { hs += k + ': ' + v + '\r\n'; });
        onDone({
          state: 'load', status: r.status, statusText: r.statusText,
          finalUrl: r.url || url, headers: hs, bytes: new Uint8Array(buf)
        });
      });
    }, function (e) {
      clearT();
      onDone({ state: 'error', error: String(e && e.message || e) });
    });
  }

  function buildResponse(res, details, readyState) {
    var bytes = res.bytes || b64ToBytes(res.body);
    var hdrs = res.headers || '';
    var ct = details.overrideMimeType || headerValue(hdrs, 'content-type');
    var cs = /charset=([^;\s]+)/i.exec(ct || '');
    var rt = String(details.responseType || '').toLowerCase();
    var out = {
      readyState: readyState === undefined ? 4 : readyState,
      status: res.status || 0,
      statusText: res.statusText || '',
      responseHeaders: hdrs,
      finalUrl: res.finalUrl || details.url,
      context: details.context,
      response: null,
      responseText: ''
    };
    var text = null;
    function getText() { if (text === null) text = decodeText(bytes, cs && cs[1]); return text; }
    if (rt === 'arraybuffer') {
      out.response = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    } else if (rt === 'blob') {
      out.response = new Blob([bytes], { type: ct || '' });
    } else if (rt === 'json') {
      try { out.response = JSON.parse(getText()); } catch (e) { out.response = null; }
      out.responseText = getText();
    } else if (rt === 'document') {
      out.responseText = getText();
      try { out.response = new DOMParser().parseFromString(out.responseText, /xml/i.test(ct) ? 'text/xml' : 'text/html'); } catch (e) {}
    } else {
      out.response = out.responseText = getText();
    }
    var xml;
    Object.defineProperty(out, 'responseXML', {
      enumerable: true,
      get: function () {
        if (xml === undefined) {
          try { xml = new DOMParser().parseFromString(getText(), /html/i.test(ct) ? 'text/html' : 'text/xml'); } catch (e) { xml = null; }
        }
        return xml;
      }
    });
    return out;
  }

  function GM_xmlhttpRequest(details) {
    details = details || {};
    var aborted = false, finished = false;
    var reqId = META.id + ':' + Date.now().toString(36) + Math.random().toString(36).slice(2);
    var pollHandle = null;

    var ctl = {
      abort: function () {
        if (finished || aborted) return;
        aborted = true;
        if (pollHandle) { try { pollHandle.stop(); } catch (e) {} }
        try { if (bridge) bridge.xhrAbort(reqId); } catch (e) {}
        safeCall(details.onabort, details, [{ readyState: 0, status: 0, finalUrl: details.url, context: details.context }], 'onabort');
        if (details.__reject) details.__reject({ error: 'aborted', abort: true });
      }
    };

    var url = absUrl(details.url);
    var method = String(details.method || 'GET').toUpperCase();
    var base = { readyState: 1, status: 0, finalUrl: url, context: details.context };
    safeCall(details.onloadstart, details, [base], 'onloadstart');
    safeCall(details.onreadystatechange, details, [base], 'onreadystatechange');

    prepareBody(details).then(function (prep) {
      if (aborted) return;
      var headers = prep.headers;
      if (details.user) {
        headers['Authorization'] = 'Basic ' + btoa(unescape(encodeURIComponent(details.user + ':' + (details.password || ''))));
      }
      if (details.cookie) headers['Cookie'] = details.cookie;

      var onResult = function (res) {
        if (aborted || finished) return;
        finished = true;
        if (res.state === 'abort') return;

        // Decode payload once; both onprogress and buildResponse reuse it.
        var bodyBytes = res.bytes || b64ToBytes(res.body || '');

        if (res.state === 'load') {
          var resp = buildResponse(res, details, 4);
          safeCall(details.onreadystatechange, details, [resp], 'onreadystatechange');
          safeCall(details.onprogress, details, [{
            lengthComputable: true,
            loaded: bodyBytes.length,
            total: bodyBytes.length,
            done: 1, position: 0, totalSize: 0,
            finalUrl: resp.finalUrl, context: details.context, status: resp.status,
            readyState: 4, response: resp.response,
            responseHeaders: resp.responseHeaders, responseText: resp.responseText,
            statusText: resp.statusText
          }], 'onprogress');
          safeCall(details.onload, details, [resp], 'onload');
          safeCall(details.onloadend, details, [resp], 'onloadend');
          if (details.__resolve) details.__resolve(resp);
        } else {
          var err = {
            readyState: 4, status: 0, statusText: '', responseText: '', response: null,
            responseHeaders: '', finalUrl: url, context: details.context,
            error: res.error || 'Network error'
          };
          safeCall(details.onreadystatechange, details, [err], 'onreadystatechange');
          if (res.state === 'timeout') safeCall(details.ontimeout, details, [err], 'ontimeout');
          else safeCall(details.onerror, details, [err], 'onerror');
          safeCall(details.onloadend, details, [err], 'onloadend');
          if (details.__reject) details.__reject(err);
        }
      };

      var useBridge = bridge && typeof bridge.xhrStart === 'function' && /^https?:/i.test(url);
      if (!useBridge) { fetchFallback(url, method, headers, prep.b64, details, onResult); return; }

      var opts = {
        url: url, method: method, headers: headers, timeout: details.timeout || 0,
        anonymous: !!details.anonymous, ua: navigator.userAgent, lang: navigator.language, sid: META.id,
        redirect: details.redirect || 'follow', referrer: W.location.href
      };
      try { bridge.xhrStart(reqId, JSON.stringify(opts), prep.b64); }
      catch (e) { onResult({ state: 'error', error: String(e) }); return; }
      pollHandle = pollXhr(reqId, onResult);
    });

    return ctl;
  }
  function promisifyXhr(details) {
    var ctl;
    var p = new Promise(function (resolve, reject) {
      var d = shallowCopy(details);
      d.__resolve = resolve; d.__reject = reject;
      ctl = GM_xmlhttpRequest(d);
    });
    p.abort = function () { if (ctl) ctl.abort(); };
    return p;
  }

  /*@@STUB xhr
  // 脚本声明了 @grant 却没声明 GM_xmlhttpRequest / GM.xmlHttpRequest：整段（~250 行）由 UserscriptEngine 裁掉，换成这两个桩
  function GM_xmlhttpRequest() {
    try { console.warn('[Mafari] 脚本未声明 @grant GM_xmlhttpRequest / GM.xmlHttpRequest'); } catch (e) {}
    return { abort: function () {} };
  }
  function promisifyXhr() {
    return Promise.reject(new Error('未声明 @grant GM.xmlHttpRequest'));
  }
  xhr@@*/
  // ------------------------------------------------------------------ misc APIs
  function GM_notification(a, b, c, d) {
    var det = (a && typeof a === 'object') ? a : { text: a, title: b, image: c, onclick: d };
    var title = det.title ? String(det.title) : '';
    var text = det.text !== undefined ? String(det.text) : '';

    if (bridge && typeof bridge.notification === 'function') {
      var cbId = 'ntf_' + Date.now().toString(36) + Math.random().toString(36).slice(2);
      setCb('__mafariNtfCallback_' + cbId, function (kind) {
        delCb('__mafariNtfCallback_' + cbId);
        if (kind === 'click' && typeof det.onclick === 'function') safeCall(det.onclick, det, [], 'onclick');
        if (typeof det.ondone === 'function') safeCall(det.ondone, det, [kind === 'click'], 'ondone');
      });
      try {
        bridge.notification(JSON.stringify({
          title: title, text: text, image: det.image || '',
          timeout: det.timeout || 0, tag: det.tag || '',
          highlight: !!det.highlight, silent: !!det.silent
        }), cbId);
        return;
      } catch (e) {}
    }

    // Fallback: toast only; do NOT fake ondone.
    try { if (bridge && typeof bridge.toast === 'function') bridge.toast(title ? title + '：' + text : text); } catch (e) {}
  }

  function GM_openInTab(url, opts) {
    var abs = absUrl(url);
    var options = (opts && typeof opts === 'object') ? opts : { active: opts !== false };
    var tabId = '', winRef = null;
    try {
      if (bridge && typeof bridge.openTab === 'function') {
        try {
          var raw = bridge.openTab(abs, JSON.stringify({
            active: options.active !== false,
            insert: !!options.insert,
            setParent: !!options.setParent
          }));
          if (raw !== null && raw !== undefined) tabId = String(raw);
        } catch (e) { try { bridge.openTab(abs); } catch (e2) {} }
      } else {
        winRef = W.open(abs, '_blank');
      }
    } catch (e) {}

    var closed = false;
    var handlers = [];
    var tab = {
      onclose: null,
      close: function () {
        if (closed) return;
        closed = true;
        try {
          if (tabId && bridge && typeof bridge.closeTab === 'function') bridge.closeTab(tabId);
          else if (winRef) winRef.close();
        } catch (e) {}
        if (typeof tab.onclose === 'function') safeCall(tab.onclose, tab, [], 'onclose');
        for (var i = 0; i < handlers.length; i++) safeCall(handlers[i], tab, [], 'onclose');
      },
      addEventListener: function (ev, fn) {
        if (ev === 'close' && typeof fn === 'function') handlers.push(fn);
      },
      removeEventListener: function (ev, fn) {
        var i = handlers.indexOf(fn);
        if (i >= 0) handlers.splice(i, 1);
      }
    };
    Object.defineProperty(tab, 'closed', {
      enumerable: true,
      get: function () { return closed; }
    });
    return tab;
  }

  function GM_setClipboard(data, info) {
    var text = String(data);
    var type = (info && typeof info === 'object' ? info.type : info) || 'text/plain';
    if (type === 'text/html') {
      if (bridge && typeof bridge.copyHtml === 'function') {
        try { bridge.copyHtml(text); return; } catch (e) {}
      }
      try {
        if (navigator.clipboard && typeof ClipboardItem !== 'undefined') {
          navigator.clipboard.write([new ClipboardItem({ 'text/html': new Blob([text], { type: 'text/html' }) })]).catch(function () {});
          return;
        }
      } catch (e) {}
    }
    if (bridge && typeof bridge.copy === 'function') {
      try { bridge.copy(text); return; } catch (e) {}
    }
    try { navigator.clipboard.writeText(text); return; } catch (e) {}
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      document.documentElement.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); } catch (e2) {}
      ta.remove();
    } catch (e) {}
  }

  function GM_download(a, b) {
    var det = (a && typeof a === 'object') ? a : { url: a, name: b };
    var url = absUrl(det.url);
    var name = det.name || '';
    var aborted = false;
    var done = false;

    function success() {
      if (aborted || done) return;
      done = true;
      safeCall(det.onload, det, [{ finalUrl: url, readyState: 4, status: 200 }], 'onload');
      safeCall(det.ondownload, det, [{ finalUrl: url, readyState: 4, status: 200 }], 'ondownload');
    }
    function failure(err) {
      if (aborted || done) return;
      done = true;
      safeCall(det.onerror, det, [{ error: err || 'not_succeeded', details: String(err), finalUrl: url }], 'onerror');
    }

    var ctl = {
      abort: function () {
        if (aborted || done) return;
        aborted = true;
        try {
          if (bridge && typeof bridge.downloadAbort === 'function') bridge.downloadAbort(url);
        } catch (e) {}
      }
    };

    var isLocalScheme = /^(blob|data):/i.test(url);
    var canNative = !isLocalScheme && bridge && typeof bridge.download === 'function';

    if (!canNative) {
      try {
        var an = document.createElement('a');
        an.href = url;
        an.download = name || '';
        document.documentElement.appendChild(an);
        an.click();
        an.remove();
        _setTimeout(success, 0);
      } catch (e) { failure(String(e)); }
      return ctl;
    }

    // Prefer async native download (real onload/onerror).
    if (typeof bridge.downloadAsync === 'function') {
      var cbId = 'dl_' + Date.now().toString(36) + Math.random().toString(36).slice(2);
      setCb('__mafariDlCallback_' + cbId, function (ok, err) {
        delCb('__mafariDlCallback_' + cbId);
        if (ok) success(); else failure(err || 'download failed');
      });
      try { bridge.downloadAsync(url, name, cbId); }
      catch (e) { delCb('__mafariDlCallback_' + cbId); failure(String(e)); }
      return ctl;
    }

    // Legacy: fire-and-forget; caller receives no onload, only synchronous onerror.
    try {
      bridge.download(url, String(name));
    } catch (e) { failure(String(e)); }
    return ctl;
  }

  // ------------------------------------------------------------------ menu
  var menu = W.__mafariMenu;
  if (!menu) {
    menu = { seq: 0, cmds: {} };
    Object.defineProperty(W, '__mafariMenu', { value: menu, configurable: true });
    Object.defineProperty(W, '__mafariMenuList', {
      configurable: true,
      value: function () {
        return JSON.stringify(Object.keys(menu.cmds).map(function (id) {
          return { id: id, title: menu.cmds[id].title, script: menu.cmds[id].script };
        }));
      }
    });
    Object.defineProperty(W, '__mafariMenuRun', {
      configurable: true,
      value: function (id) {
        var c = menu.cmds[id];
        if (!c) return;
        var ev; try { ev = new MouseEvent('click'); } catch (e) { ev = {}; }
        safeCall(c.fn, W, [ev], 'menu command');
      }
    });
  }
  function GM_registerMenuCommand(name, fn, opts) {
    var optsObj = (opts && typeof opts === 'object') ? opts : null;
    var id = (optsObj && optsObj.id) ? String(optsObj.id) : (META.id + ':' + (++menu.seq));
    var title = String(name);
    if (optsObj && optsObj.title) title = String(optsObj.title);
    menu.cmds[id] = {
      title: title, fn: fn, script: META.name,
      accessKey: optsObj && optsObj.accessKey ? String(optsObj.accessKey) : null,
      autoClose: !!(optsObj && optsObj.autoClose)
    };
    notifyMenu();
    return id;
  }
  function GM_unregisterMenuCommand(id) { delete menu.cmds[id]; notifyMenu(); }
  // Gecko 没有 evaluateJavascript 可供原生来问，菜单变化时主动上报（WebView 的桥没有该方法，直接忽略）
  function notifyMenu() {
    try { if (bridge && typeof bridge.menuChanged === 'function') bridge.menuChanged(); } catch (e) {}
  }

  // ------------------------------------------------------------------ tabs
  var tabObj = {};
  function GM_getTab(cb) { safeCall(cb, null, [tabObj]); }
  function GM_saveTab(o) { tabObj = o || {}; }
  function GM_getTabs(cb) { var t = {}; t[0] = tabObj; safeCall(cb, null, [t]); }

  /*@@BEGIN cookie*/
  // ------------------------------------------------------------------ cookies
  function cookieHost(url) { try { return new URL(url).hostname; } catch (e) { return W.location.hostname; } }
  function documentCookies(url, nameFilter) {
    var out = [];
    try {
      var s = bridge && typeof bridge.cookies === 'function' ? bridge.cookies(url) : document.cookie;
      String(s || '').split(/;\s*/).forEach(function (kv) {
        if (!kv) return;
        var i = kv.indexOf('=');
        var n = i < 0 ? kv : kv.slice(0, i);
        var v = i < 0 ? '' : kv.slice(i + 1);
        if (nameFilter && nameFilter !== n) return;
        out.push({
          name: n, value: v, domain: cookieHost(url), hostOnly: true,
          path: '/', secure: /^https:/.test(url), httpOnly: false, session: true
        });
      });
    } catch (e) {}
    return out;
  }
  var GM_cookie = {
    list: function (details, cb) {
      details = details || {};
      var url = details.url || W.location.href;
      if (bridge && typeof bridge.cookieListAsync === 'function') {
        bridge.cookieListAsync(url, function (arr) {
          if (details.name) arr = arr.filter(function (c) { return c.name === details.name; });
          safeCall(cb, null, [arr, undefined]);
        });
        return;
      }
      if (bridge && typeof bridge.cookieList === 'function') {
        try {
          var raw = bridge.cookieList(url);
          if (raw) {
            var arr = typeof raw === 'string' ? JSON.parse(raw) : raw;
            if (Array.isArray(arr)) {
              if (details.name) arr = arr.filter(function (c) { return c.name === details.name; });
              safeCall(cb, null, [arr, undefined]);
              return;
            }
          }
        } catch (e) {}
      }
      safeCall(cb, null, [documentCookies(url, details.name), undefined]);
    },
    set: function (details, cb) {
      try {
        details = details || {};
        var url = details.url || W.location.href;
        if (bridge && typeof bridge.cookieSet === 'function') {
          bridge.cookieSet(JSON.stringify({
            url: url, name: details.name, value: details.value || '',
            domain: details.domain || null, path: details.path || '/',
            secure: !!details.secure, httpOnly: !!details.httpOnly,
            expirationDate: details.expirationDate || null,
            sameSite: details.sameSite || null
          }));
          safeCall(cb, null, [undefined]);
          return;
        }
        var c = details.name + '=' + (details.value || '') + '; path=' + (details.path || '/');
        if (details.domain) c += '; domain=' + details.domain;
        if (details.secure) c += '; secure';
        if (details.expirationDate) c += '; expires=' + new Date(details.expirationDate * 1000).toUTCString();
        if (bridge && typeof bridge.setCookie === 'function') bridge.setCookie(url, c); else document.cookie = c;
        safeCall(cb, null, [undefined]);
      } catch (e) { safeCall(cb, null, [String(e)]); }
    },
    delete: function (details, cb) {
      try {
        details = details || {};
        var url = details.url || W.location.href;
        if (bridge && typeof bridge.cookieSet === 'function') {
          bridge.cookieSet(JSON.stringify({
            url: url, name: details.name, value: '',
            domain: details.domain || null, path: details.path || '/',
            expirationDate: 0
          }));
          safeCall(cb, null, [undefined]);
          return;
        }
        var c = details.name + '=; path=' + (details.path || '/') + '; expires=Thu, 01 Jan 1970 00:00:00 GMT';
        if (details.domain) c += '; domain=' + details.domain;
        if (bridge && typeof bridge.setCookie === 'function') bridge.setCookie(url, c); else document.cookie = c;
        safeCall(cb, null, [undefined]);
      } catch (e) { safeCall(cb, null, [String(e)]); }
    }
  };

  /*@@STUB cookie
  // 脚本声明了 @grant 却没声明 GM_cookie / GM.cookie：整段由 UserscriptEngine 裁掉，换成桩
  var GM_cookie = {
    list: function (d, cb) { try { if (typeof cb === 'function') cb(undefined, '未声明 @grant GM_cookie'); } catch (e) {} },
    set: function (d, cb) { try { if (typeof cb === 'function') cb('未声明 @grant GM_cookie'); } catch (e) {} },
    delete: function (d, cb) { try { if (typeof cb === 'function') cb('未声明 @grant GM_cookie'); } catch (e) {} }
  };
  cookie@@*/
  // ------------------------------------------------------------------ onurlchange (shared across scripts)
  if (META.grant && META.grant.indexOf('window.onurlchange') >= 0) {
    var state = W.__mafariUrlWatchState;
    if (!state) {
      state = W.__mafariUrlWatchState = {
        subs: [],
        lastHref: W.location.href,
        currentOnUrlChange: null
      };
      var notify = function () {
        var now = W.location.href;
        if (now === state.lastHref) return;
        state.lastHref = now;
        var ev = new Event('urlchange');
        ev.url = now;
        try { W.dispatchEvent(ev); } catch (e) {}
        var subs = state.subs.slice();
        for (var i = 0; i < subs.length; i++) safeCall(subs[i], W, [ev], 'onurlchange');
      };
      ['pushState', 'replaceState'].forEach(function (m) {
        var orig = W.history[m];
        W.history[m] = function () { var r = orig.apply(this, arguments); notify(); return r; };
      });
      W.addEventListener('popstate', notify);
      W.addEventListener('hashchange', notify);
      // Intercept assignment so multiple scripts can each supply their own handler.
      try {
        Object.defineProperty(W, 'onurlchange', {
          configurable: true,
          get: function () { return state.currentOnUrlChange; },
          set: function (fn) {
            state.currentOnUrlChange = fn;
            if (typeof fn === 'function' && state.subs.indexOf(fn) < 0) state.subs.push(fn);
          }
        });
      } catch (e) {}
    }
    // Nothing to register per-script; assignment of W.onurlchange is intercepted globally.
  }

  // ------------------------------------------------------------------ GM_info / GM
  var m = META.meta || {};

  function detectPlatform() {
    var ua = navigator.userAgent || '';
    if (/Firefox/i.test(ua)) return 'firefox';
    if (/Edg\//.test(ua)) return 'edge';
    if (/OPR\//.test(ua)) return 'opera';
    if (/Safari/.test(ua) && !/Chrome/.test(ua)) return 'safari';
    return 'chrome';
  }

  var GM_info = {
    scriptHandler: 'Tampermonkey',
    version: '5.3.3',
    mafari: true,
    sandboxMode: 'raw',
    isIncognito: false,
    isFirstPartyIsolation: false,
    downloadMode: 'native',
    scriptWillUpdate: false,
    platform: detectPlatform(),
    scriptMetaStr: META.metaBlock || '',
    script: {
      name: META.name,
      namespace: META.namespace || '',
      version: META.version || '',
      description: META.description || '',
      author: META.author || '',
      homepage: META.homepage || '',
      icon: META.icon || '',
      matches: META.match || [],
      includes: META.include || [],
      excludes: META.exclude || [],
      'exclude-match': META.excludeMatch || [],
      grant: META.grant || [],
      require: META.require || [],
      resources: Object.keys(META.resources || {}).map(function (n) {
        return { name: n, url: META.resources[n].url };
      }),
      'run-at': META.runAt,
      noframes: !!META.noframes,
      uuid: META.id,
      unwrap: false,
      options: { run_at: META.runAt, noframes: !!META.noframes, override: {} },
      metadata: m
    }
  };
  GM_info.script.name_i18n = {};

  function p(fn) {
    return function () {
      var args = arguments, self = this;
      return new Promise(function (resolve, reject) {
        try { resolve(fn.apply(self, args)); } catch (e) { reject(e); }
      });
    };
  }

  // 原生流式写文件：写入 Download/mafari，逐段落盘，不占内存。返回 Promise<{name, write, close, abort}>
  function openFileStream(name, mime) {
    return new Promise(function (resolve, reject) {
      if (!bridge || typeof bridge.streamOpen !== 'function') { reject(new Error('unsupported')); return; }
      var info;
      try { info = JSON.parse(bridge.streamOpen(String(name || ''), String(mime || ''))); }
      catch (e) { reject(e); return; }
      if (!info || !info.id) { reject(new Error((info && info.error) || 'open failed')); return; }
      var id = info.id, closed = false;
      resolve({
        name: info.name,
        write: function (data) {
          if (closed) return Promise.reject(new Error('stream closed'));
          var blob = data instanceof Blob ? data : new Blob([data]);
          var SLICE = 3 * 1024 * 1024;
          var chain = Promise.resolve();
          for (var off = 0; off < blob.size; off += SLICE) {
            (function (o) {
              chain = chain.then(function () {
                return blob.slice(o, o + SLICE).arrayBuffer();
              }).then(function (buf) {
                var err = bridge.streamWrite(id, bytesToB64(new Uint8Array(buf)));
                if (err) throw new Error(err);
              });
            })(off);
          }
          return chain;
        },
        close: function () {
          if (closed) return Promise.resolve();
          closed = true;
          var err = bridge.streamClose(id);
          return err ? Promise.reject(new Error(err)) : Promise.resolve();
        },
        abort: function () {
          if (closed) return Promise.resolve();
          closed = true;
          try { bridge.streamAbort(id); } catch (e) {}
          return Promise.resolve();
        }
      });
    });
  }

  // GM.download returns a Promise resolving to a handle; abort is on the promise.
  function GM_download_promise(details) {
    var innerAbort = null;
    var promise = new Promise(function (resolve, reject) {
      var d = shallowCopy(details);
      d.onload = function (r) { resolve(r); };
      d.onerror = function (e) { reject(e); };
      d.ontimeout = function (e) { reject(e); };
      var ctl = GM_download(d);
      innerAbort = ctl.abort;
    });
    promise.abort = function () { if (innerAbort) innerAbort(); };
    return promise;
  }

  var GM = {
    openFileStream: openFileStream,
    info: GM_info,
    // 大值（Gecko 桥不内联进快照）只能异步拿：有 getValueAsync 就走它，其余桥沿用同步读
    getValue: function (k, d) {
      if (bridge && typeof bridge.getValueAsync === 'function') {
        return new Promise(function (resolve) {
          try { bridge.getValueAsync(META.id, String(k), function (raw) { resolve(parseVal(raw, d)); }); }
          catch (e) { resolve(GM_getValue(k, d)); }
        });
      }
      return p(GM_getValue)(k, d);
    },
    setValue: p(GM_setValue), deleteValue: p(GM_deleteValue),
    listValues: p(GM_listValues),
    // 大值同步读不到：有 getValueAsync 就逐个异步取（已在本地的同步回调，没有额外 Promise），全部到齐后一次 resolve
    getValues: function (keys) {
      if (!bridge || typeof bridge.getValueAsync !== 'function') return p(GM_getValues)(keys);
      return new Promise(function (resolve) {
        var list, defs = null;
        if (Array.isArray(keys)) list = keys;
        else if (keys && typeof keys === 'object') { defs = keys; list = Object.keys(keys); }
        else list = GM_listValues();
        var out = {}, left = list.length;
        if (!left) { resolve(out); return; }
        list.forEach(function (k) {
          var done = false;
          function fin(raw) {
            if (done) return;
            done = true;
            out[k] = parseVal(raw, defs ? defs[k] : undefined);
            if (--left === 0) resolve(out);
          }
          try { bridge.getValueAsync(META.id, String(k), fin); } catch (e) { fin(rawGet(k)); }
        });
      });
    },
    setValues: p(GM_setValues), deleteValues: p(GM_deleteValues),
    addValueChangeListener: p(GM_addValueChangeListener), removeValueChangeListener: p(GM_removeValueChangeListener),
    addStyle: p(GM_addStyle), addElement: p(GM_addElement),
    getResourceText: p(GM_getResourceText),
    getResourceUrl: p(GM_getResourceURL), getResourceURL: p(GM_getResourceURL),
    xmlHttpRequest: promisifyXhr, xmlhttpRequest: promisifyXhr,
    notification: p(GM_notification), openInTab: p(GM_openInTab), setClipboard: p(GM_setClipboard),
    download: GM_download_promise,
    registerMenuCommand: p(GM_registerMenuCommand), unregisterMenuCommand: p(GM_unregisterMenuCommand),
    getTab: p(function () { return tabObj; }), saveTab: p(GM_saveTab),
    getTabs: p(function () { var t = {}; t[0] = tabObj; return t; }),
    cookie: {
      list: function (d) { return new Promise(function (res, rej) { GM_cookie.list(d, function (l, e) { e ? rej(e) : res(l); }); }); },
      set: function (d) { return new Promise(function (res, rej) { GM_cookie.set(d, function (e) { e ? rej(e) : res(); }); }); },
      delete: function (d) { return new Promise(function (res, rej) { GM_cookie.delete(d, function (e) { e ? rej(e) : res(); }); }); }
    },
    log: function () { try { console.log.apply(console, arguments); } catch (e) {} }
  };

  var userScript = function (
    GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_getValues, GM_setValues, GM_deleteValues,
    GM_addValueChangeListener, GM_removeValueChangeListener, GM_addStyle, GM_addElement,
    GM_getResourceText, GM_getResourceURL, GM_xmlhttpRequest, GM_notification, GM_openInTab,
    GM_setClipboard, GM_download, GM_registerMenuCommand, GM_unregisterMenuCommand,
    GM_getTab, GM_saveTab, GM_getTabs, GM_cookie, GM_log, GM, unsafeWindow, define, module, exports
  ) {
    return (__MAFARI_FN__ () {
__MAFARI_CODE__
    }).call(unsafeWindow);
  };

  function execute() {
    var result;
    try {
      result = userScript.call(W,
        GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_getValues, GM_setValues, GM_deleteValues,
        GM_addValueChangeListener, GM_removeValueChangeListener, GM_addStyle, GM_addElement,
        GM_getResourceText, GM_getResourceURL, GM_xmlhttpRequest, GM_notification, GM_openInTab,
        GM_setClipboard, GM_download, GM_registerMenuCommand, GM_unregisterMenuCommand,
        GM_getTab, GM_saveTab, GM_getTabs, GM_cookie,
        function () { try { console.log.apply(console, arguments); } catch (e) {} },
        GM, W, undefined, undefined, undefined);
    } catch (err) {
      log('error', (err && err.stack) || (err && err.message) || err);
      return;
    }
    if (result && typeof result.then === 'function') {
      try {
        result.then(null, function (err) {
          log('error', (err && err.stack) || (err && err.message) || err);
        });
      } catch (e) {
        log('error', 'then threw: ' + (e && e.message));
      }
    }
  }

  // ------------------------------------------------------------------ run-at scheduling
  function waitBody(cb) {
    if (document.body) { cb(); return; }
    var mo = new MutationObserver(function () {
      if (document.body) { mo.disconnect(); cb(); }
    });
    mo.observe(document.documentElement || document, { childList: true, subtree: true });
    document.addEventListener('DOMContentLoaded', function () { mo.disconnect(); cb(); }, { once: true });
  }
  (function schedule() {
    var ra = META.runAt || 'document-idle';
    var go = (function () {
      var done = false;
      return function () {
        if (done) return;
        done = true;
        try { execute(); } catch (e) { log('error', (e && e.stack) || e); }
      };
    })();
    if (ra === 'document-start') { go(); }
    else if (ra === 'document-body') { waitBody(go); }
    else if (ra === 'document-end') {
      if (document.readyState !== 'loading') go();
      else document.addEventListener('DOMContentLoaded', go, { once: true });
    } else {
      if (document.readyState !== 'loading') _setTimeout(go, 0);
      else document.addEventListener('DOMContentLoaded', function () { _setTimeout(go, 0); }, { once: true });
    }
  })();
})();