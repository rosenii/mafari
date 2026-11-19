function (CFG, ENTRIES) {
  'use strict';
  // 由 ExtensionInjection 拼成 (<本文件>)(CFG, ENTRIES) 后经 addDocumentStartJavaScript 注入。
  // 约束：必须在文档起始就捕获原生对象与桥，之后页面脚本改什么都影响不到这里。
  var win = window, doc = document;
  var bridge = win[CFG.bridge];
  if (!bridge || typeof bridge.postMessage !== 'function') return;
  var post = bridge.postMessage.bind(bridge);
  // 把桥从页面全局里删掉：只有这个闭包还拿得到
  try { delete win[CFG.bridge]; } catch (e) { }
  try { if (win[CFG.bridge]) win[CFG.bridge] = undefined; } catch (e) { }

  var P = Promise, J = JSON, jstr = J.stringify, jparse = J.parse, U = win.URL, RE = RegExp;
  var ST = win.setTimeout, E = Error, OK = Object.keys, slice = Array.prototype.slice;
  var docAdd = doc.addEventListener.bind(doc), winAdd = win.addEventListener.bind(win);
  var cerr = (win.console && win.console.error) ? win.console.error.bind(win.console) : function () { };

  // ───────────── 与原生通信 ─────────────
  var nextId = 1, pending = {};
  function rpc(api, args) {
    return new P(function (resolve, reject) {
      var id = String(nextId++);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        post(jstr({ extId: CFG.extId, id: id, api: api, args: args || [] }));
      } catch (e) {
        delete pending[id];
        reject(new E(e && e.message ? e.message : 'bridge unavailable'));
      }
    });
  }

  // ───────────── 事件 ─────────────
  var L = {};
  function ev(name) {
    return {
      addListener: function (f) { var a = L[name] = L[name] || []; if (typeof f === 'function' && a.indexOf(f) < 0) a.push(f); },
      removeListener: function (f) { var a = L[name] || [], i = a.indexOf(f); if (i >= 0) a.splice(i, 1); },
      hasListener: function (f) { return (L[name] || []).indexOf(f) >= 0; }
    };
  }
  function fire(name, args) {
    (L[name] || []).slice().forEach(function (f) { try { f.apply(null, args); } catch (e) { cerr(e); } });
  }

  function onNative(raw) {
    var m;
    try { m = jparse(raw); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.id !== undefined && m.id !== null && pending[m.id]) {
      var h = pending[m.id];
      delete pending[m.id];
      if (m.ok) {
        h.resolve(m.result === null ? undefined : m.result);
      } else {
        var err = new E((m.error && m.error.message) || 'error');
        err.code = m.error && m.error.code;
        h.reject(err);
      }
      return;
    }
    if (m.event === 'storage.onChanged' && m.payload) {
      fire('storage.onChanged', [m.payload.changes, m.payload.areaName]);
      fire('storage.' + m.payload.areaName + '.onChanged', [m.payload.changes]);
      return;
    }
    if (m.type === 'message') deliverMessage(m);
  }
  bridge.onmessage = function (e) { onNative(e && e.data); };

  // runtime.onMessage 的投递与回复：监听器返回 true / Promise 表示异步回复
  function deliverMessage(m) {
    var listeners = (L['runtime.onMessage'] || []).slice();
    var done = false;
    function reply(v, noReceiver) {
      if (done) return;
      done = true;
      rpc('runtime.reply', [m.cid, v === undefined ? null : v, noReceiver ? { noReceiver: true } : null]).catch(function () { });
    }
    if (!listeners.length) { reply(undefined, true); return; }
    var async = false;
    listeners.forEach(function (f) {
      var r;
      try { r = f(m.message, m.sender || {}, function (v) { reply(v); }); } catch (e) { cerr(e); return; }
      if (r === true) async = true;
      else if (r && typeof r.then === 'function') { async = true; r.then(function (v) { reply(v); }, function () { reply(undefined); }); }
    });
    if (!async) reply(undefined);
  }

  // ───────────── chrome 回调风格封装 ─────────────
  var lastError = { v: undefined };
  function cbify(fn) {
    return function () {
      var a = slice.call(arguments);
      var cb = (a.length && typeof a[a.length - 1] === 'function') ? a.pop() : null;
      var p = fn.apply(null, a);
      if (!cb) return p;
      p.then(function (r) { cb(r); }, function (e) {
        lastError.v = { message: e && e.message };
        try { cb(); } finally { lastError.v = undefined; }
      });
    };
  }
  function cbObj(o) {
    var out = {};
    OK(o).forEach(function (k) { out[k] = typeof o[k] === 'function' && o[k].__p ? cbify(o[k]) : o[k]; });
    return out;
  }
  function promised(f) { f.__p = true; return f; }

  // ───────────── API ─────────────
  var manifestJson = jstr(CFG.manifest);
  function getURL(path) { return CFG.origin + '/' + String(path === undefined ? '' : path).replace(/^\/+/, ''); }

  function sendMessage() {
    var a = slice.call(arguments), extId = null, message, opts = null;
    if (typeof a[0] === 'string' && a.length >= 2 && typeof a[1] !== 'function') {
      extId = a[0]; message = a[1];
      opts = (a[2] === undefined || typeof a[2] === 'function') ? null : a[2];
    } else {
      message = a[0];
      opts = (a.length > 1 && a[1] !== undefined && typeof a[1] !== 'function') ? a[1] : null;
    }
    return rpc('runtime.sendMessage', [extId, message === undefined ? null : message, opts]);
  }

  function storageArea(name) {
    var p = 'storage.' + name + '.';
    var nz = function (v) { return v === undefined ? null : v; };
    return {
      get: promised(function (keys) { return rpc(p + 'get', [nz(keys)]); }),
      getBytesInUse: promised(function (keys) { return rpc(p + 'getBytesInUse', [nz(keys)]); }),
      set: promised(function (items) { return rpc(p + 'set', [items]); }),
      remove: promised(function (keys) { return rpc(p + 'remove', [keys]); }),
      clear: promised(function () { return rpc(p + 'clear', []); }),
      onChanged: ev('storage.' + name + '.onChanged')
    };
  }

  var runtime = {
    id: CFG.extId,
    getURL: getURL,
    getManifest: function () { return jparse(manifestJson); },
    getPlatformInfo: promised(function () { return rpc('runtime.getPlatformInfo', []); }),
    sendMessage: promised(sendMessage),
    connect: function () { throw new E('runtime.connect is not supported yet'); },
    onMessage: ev('runtime.onMessage'),
    onConnect: ev('runtime.onConnect')
  };
  var storage = {
    local: storageArea('local'), sync: storageArea('sync'), session: storageArea('session'),
    onChanged: ev('storage.onChanged')
  };
  var i18n = {
    getMessage: function (k) { return String(k); },
    getUILanguage: function () { return (win.navigator && win.navigator.language) || 'en'; }
  };
  var ext = { getURL: getURL };

  var browser = { runtime: runtime, storage: storage, i18n: i18n, extension: ext };

  var chromeRuntime = cbObj(runtime);
  Object.defineProperty(chromeRuntime, 'lastError', { get: function () { return lastError.v; }, enumerable: true });
  var chrome = {
    runtime: chromeRuntime,
    storage: {
      local: cbObj(storage.local), sync: cbObj(storage.sync), session: cbObj(storage.session),
      onChanged: storage.onChanged
    },
    i18n: i18n,
    extension: ext
  };

  // 拓展自己的页面（后台页 / popup / options）：CFG.page 为 true 时把 browser / chrome 挂到 window 上，
  // 并发一次握手，让原生拿到回复通道（之后才能向本页面投递 runtime.onMessage）。
  // content script 里不挂全局：它们只通过参数拿到 browser / chrome。
  if (CFG.page) {
    var defGlobal = function (k, v) {
      try { Object.defineProperty(win, k, { value: v, configurable: true, writable: true, enumerable: false }); } catch (e) { }
    };
    defGlobal('browser', browser);
    defGlobal('chrome', chrome);
    rpc('runtime.hello', []).catch(function () { });
  }

  // ───────────── 匹配与执行 ─────────────
  var DEFAULT_PORT = { http: 80, https: 443, ws: 80, wss: 443, ftp: 21 };
  var reCache = {};
  function matchDesc(d, href) {
    var u;
    try { u = new U(href); } catch (e) { return false; }
    var sch = u.protocol.slice(0, -1).toLowerCase();
    if (d.s.indexOf(sch) < 0) return false;
    if (d.h !== null) {
      var h = u.hostname.toLowerCase();
      if (!(h === d.h || (d.sub && h.length > d.h.length && h.slice(-(d.h.length + 1)) === '.' + d.h))) return false;
    }
    if (d.p !== null) {
      var port = u.port ? parseInt(u.port, 10) : DEFAULT_PORT[sch];
      if (port !== parseInt(d.p, 10)) return false;
    }
    if (d.r !== null) {
      var re = reCache[d.r] || (reCache[d.r] = new RE(d.r));
      if (!re.test(u.pathname + u.search)) return false;
    }
    return true;
  }
  function anyMatch(list, href) {
    for (var i = 0; i < list.length; i++) if (matchDesc(list[i], href)) return true;
    return false;
  }

  function insertCss(text) {
    var go = function () {
      var st = doc.createElement('style');
      st.textContent = text;
      (doc.head || doc.documentElement).appendChild(st);
    };
    if (doc.head || doc.documentElement) go(); else docAdd('DOMContentLoaded', go, { once: true });
  }

  function runWhen(at, fn) {
    if (at === 'document_start') { fn(); return; }
    var afterDcl = function (f) {
      if (doc.readyState !== 'loading') f(); else docAdd('DOMContentLoaded', f, { once: true });
    };
    if (at === 'document_end') { afterDcl(fn); return; }
    // document_idle：DOMContentLoaded 之后，load 或 200ms 先到者
    afterDcl(function () {
      var ran = false;
      var go = function () { if (!ran) { ran = true; fn(); } };
      if (doc.readyState === 'complete') { ST(go, 0); return; }
      winAdd('load', go, { once: true });
      ST(go, 200);
    });
  }

  var href = win.location.href;
  var isTop = (win.top === win);
  ENTRIES.forEach(function (en) {
    if (!en.all && !isTop) return;
    if (!anyMatch(en.m, href) || anyMatch(en.x, href)) return;
    runWhen(en.at, function () {
      en.css.forEach(function (c) { try { insertCss(c); } catch (e) { cerr(e); } });
      en.js.forEach(function (fn) { try { fn(browser, chrome); } catch (e) { cerr(e); } });
    });
  });
}
