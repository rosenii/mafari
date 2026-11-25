/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

function (CFG, ENTRIES) {
  'use strict';
  // 由 ExtensionInjection 拼成 (<本文件>)(CFG, ENTRIES) 后经 addDocumentStartJavaScript 注入。
  // 约束：必须在文档起始就捕获原生对象与桥，之后页面脚本改什么都影响不到这里。
  var win = window, doc = document;
  // CFG.mainWorld：页面世界（MAIN）里跑的 content script。没有桥、没有 browser / chrome 参数（与 Chrome 一致），
  // 只负责匹配 / run_at / 插 CSS / 执行脚本。CFG.isolatedWorld：跑在 WebView 真正的隔离世界里，桥对象只存在于该世界，页面碰不到。
  var MAIN = !!CFG.mainWorld;
  var bridge = MAIN ? null : win[CFG.bridge];
  if (!MAIN && (!bridge || typeof bridge.postMessage !== 'function')) return;
  var post = MAIN ? null : bridge.postMessage.bind(bridge);
  // 把桥从全局里删掉：只有这个闭包还拿得到（隔离世界里这是第二道保险，旧路径里这是唯一一道）
  if (!MAIN) {
    try { delete win[CFG.bridge]; } catch (e) { }
    try { if (win[CFG.bridge]) win[CFG.bridge] = undefined; } catch (e) { }
  }

  var P = Promise, J = JSON, jstr = J.stringify, jparse = J.parse, U = win.URL, RE = RegExp;
  var ST = win.setTimeout, E = Error, OK = Object.keys, slice = Array.prototype.slice;
  var DN = Date.now.bind(Date), T0 = DN();
  var docAdd = doc.addEventListener.bind(doc), winAdd = win.addEventListener.bind(win);
  var cerr = (win.console && win.console.error) ? win.console.error.bind(win.console) : function () { };
  var DEFAULT_PORT = { http: 80, https: 443, ws: 80, wss: 443, ftp: 21 };
  var reCache = {};
  // MAIN 世界：不构造任何 API，直接按匹配规则执行 content script（runEntries 是函数声明，已提升）
  if (MAIN) { runEntries(win.browser, win.chrome); return; }

  // ───────────── 与原生通信 ─────────────
  var nextId = 1, pending = {}, ports = {}, pendingPorts = {};
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
  // 早于监听器注册到达的事件先缓存（只缓存下面 BUFFERED 里的几种，每种最多 BUF_MAX 条，页面启动 BUF_MS 之后不再缓存），
  // 第一个监听器注册时统一补投。常见时序：后台页的脚本是异步加载 / 动态 import 的，原生已经把 onStartup / onAlarm 送到了。
  var BUF = {}, BUF_MAX = 32, BUF_MS = 5000;
  var BUFFERED = { 'storage.onChanged': 1, 'storage.local.onChanged': 1, 'storage.session.onChanged': 1, 'storage.sync.onChanged': 1, 'runtime.onStartup': 1, 'runtime.onInstalled': 1, 'runtime.onConnect': 1, 'tabs.onHighlighted': 1, 'alarms.onAlarm': 1, 'webNavigation.onBeforeNavigate': 1, 'webNavigation.onCommitted': 1, 'webNavigation.onCompleted': 1, 'webNavigation.onDOMContentLoaded': 1, 'webNavigation.onErrorOccurred': 1, 'webNavigation.onHistoryStateUpdated': 1, 'webNavigation.onReferenceFragmentUpdated': 1, 'contextMenus.onClicked': 1, 'notifications.onClicked': 1, 'notifications.onClosed': 1, 'bookmarks.onCreated': 1, 'bookmarks.onChanged': 1, 'bookmarks.onMoved': 1, 'bookmarks.onRemoved': 1, 'downloads.onCreated': 1, 'downloads.onErased': 1 };
  function ev(name) {
    return {
      addListener: function (f) {
        var a = L[name] = L[name] || [];
        if (typeof f !== 'function' || a.indexOf(f) >= 0) return;
        a.push(f);
        var q = BUF[name];
        if (q && q.length) {
          delete BUF[name];
          ST(function () { q.forEach(function (args) { fire(name, args); }); }, 0);
        }
      },
      removeListener: function (f) { var a = L[name] || [], i = a.indexOf(f); if (i >= 0) a.splice(i, 1); },
      hasListener: function (f) { return (L[name] || []).indexOf(f) >= 0; },
      hasListeners: function () { return (L[name] || []).length > 0; }
    };
  }
  function fire(name, args) {
    (L[name] || []).slice().forEach(function (f) { try { f.apply(null, args); } catch (e) { cerr(e); } });
  }
  // 原生事件 → 要触发的监听器列表 [[监听器名, 参数数组], ...]。新增事件只需在这张表里加一行
  var menuHandlers = {}; // menus.create({ onclick }) —— Firefox 支持的写法；函数不能过桥，留在本地
  var DISPATCH = {
    'storage.onChanged': function (p) {
      return [['storage.onChanged', [p.changes, p.areaName]], ['storage.' + p.areaName + '.onChanged', [p.changes]]];
    },
    'runtime.onStartup': function () { return [['runtime.onStartup', []]]; },
    'runtime.onInstalled': function (p) { return [['runtime.onInstalled', [p]]]; },
    'alarms.onAlarm': function (p) { return [['alarms.onAlarm', [p]]]; },
    'tabs.onCreated': function (p) { return [['tabs.onCreated', [p.tab]]]; },
    'tabs.onUpdated': function (p) { return [['tabs.onUpdated', [p.tabId, p.changeInfo, p.tab]]]; },
    'tabs.onRemoved': function (p) { return [['tabs.onRemoved', [p.tabId, p.removeInfo]]]; },
    'tabs.onActivated': function (p) { return [['tabs.onActivated', [p.activeInfo]]]; },
    'tabs.onHighlighted': function (p) { return [['tabs.onHighlighted', [p.highlightInfo]]]; },
    'tabs.onMoved': function (p) { return [['tabs.onMoved', [p.tabId, p.moveInfo]]]; },
    'permissions.onAdded': function (p) { return [['permissions.onAdded', [p]]]; },
    'permissions.onRemoved': function (p) { return [['permissions.onRemoved', [p]]]; },
    'webNavigation.onBeforeNavigate': function (p) { return [['webNavigation.onBeforeNavigate', [p]]]; },
    'webNavigation.onCommitted': function (p) { return [['webNavigation.onCommitted', [p]]]; },
    'webNavigation.onCompleted': function (p) { return [['webNavigation.onCompleted', [p]]]; },
    'webNavigation.onErrorOccurred': function (p) { return [['webNavigation.onErrorOccurred', [p]]]; },
    'webNavigation.onDOMContentLoaded': function (p) { return [['webNavigation.onDOMContentLoaded', [p]]]; },
    'webNavigation.onHistoryStateUpdated': function (p) { return [['webNavigation.onHistoryStateUpdated', [p]]]; },
    'webNavigation.onReferenceFragmentUpdated': function (p) { return [['webNavigation.onReferenceFragmentUpdated', [p]]]; },
    'contextMenus.onClicked': function (p) {
      var h = menuHandlers[(p.info || {}).menuItemId];
      if (typeof h === 'function') ST(function () { try { h(p.info || {}, p.tab || {}); } catch (e) { cerr(e); } }, 0);
      return [['contextMenus.onClicked', [p.info || {}, p.tab || {}]]];
    },
    'notifications.onClicked': function (p) { return [['notifications.onClicked', [p.notificationId]]]; },
    'notifications.onClosed': function (p) { return [['notifications.onClosed', [p.notificationId, !!p.byUser]]]; },
    'bookmarks.onCreated': function (p) { return [['bookmarks.onCreated', [p.id, p.bookmark]]]; },
    'bookmarks.onChanged': function (p) { return [['bookmarks.onChanged', [p.id, p.changeInfo]]]; },
    'bookmarks.onMoved': function (p) { return [['bookmarks.onMoved', [p.id, p.moveInfo]]]; },
    'bookmarks.onRemoved': function (p) { return [['bookmarks.onRemoved', [p.id, p.removeInfo]]]; },
    'downloads.onCreated': function (p) { return [['downloads.onCreated', [p]]]; },
    'downloads.onErased': function (p) { return [['downloads.onErased', [p.id]]]; },
    'webRequest.onBeforeRequest': function (p) { return [['webRequest.onBeforeRequest', [p]]]; },
    'webRequest.onBeforeRedirect': function (p) { return [['webRequest.onBeforeRedirect', [p]]]; },
    'webRequest.onHeadersReceived': function (p) { return [['webRequest.onHeadersReceived', [p]]]; },
    'webRequest.onResponseStarted': function (p) { return [['webRequest.onResponseStarted', [p]]]; },
    'webRequest.onCompleted': function (p) { return [['webRequest.onCompleted', [p]]]; },
    'webRequest.onErrorOccurred': function (p) { return [['webRequest.onErrorOccurred', [p]]]; }
  };
  function deliverEvent(name, args) {
    if ((L[name] || []).length) { fire(name, args); return; }
    if (!CFG.page || !BUFFERED[name] || DN() - T0 > BUF_MS) return;
    var q = BUF[name] = BUF[name] || [];
    if (q.length < BUF_MAX) q.push(args);
  }
  function dispatchEvent(name, payload) {
    var table = DISPATCH[name];
    if (!table) return;
    var list;
    try { list = table(payload || {}); } catch (e) { return; }
    list.forEach(function (t) {
      if (t[0].indexOf('webRequest.') === 0 && webRequest && webRequest[t[0].slice(11)]) {
        var eventName = t[0].slice(11), detail = t[1][0] || {}, tokens = detail.listenerTokens || [];
        var response = null, hadBlockingListener = false;
        webRequestListeners[eventName].slice().forEach(function (x) {
          if (tokens.indexOf(x.token) < 0) return;
          if (detail.blockingToken && x.extra.indexOf('blocking') >= 0) hadBlockingListener = true;
          try {
            var r = x.fn.apply(null, [detail]);
            if (detail.blockingToken && x.extra.indexOf('blocking') >= 0 && r && typeof r === 'object') {
              if (r.cancel === true) response = r;
              else if (!response && typeof r.redirectUrl === 'string' && r.redirectUrl) response = r;
            }
          } catch (e) { cerr(e); }
        });
        // EventSink 会广播到该拓展的多个页面上下文；只有实际注册了匹配 blocking listener 的上下文才可回复，
        // 否则 popup/options 中的空回复可能抢先完成同一拓展的决策，吞掉后台页的 cancel / redirect。
        if (detail.blockingToken && hadBlockingListener) {
          rpc('webRequest.resolveBlocking', [detail.blockingToken, response || {}]).catch(function (e) { cerr(e); });
        }
      } else deliverEvent(t[0], t[1]);
    });
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
    if (typeof m.event === 'string') { dispatchEvent(m.event, m.payload); return; }
    if (m.type === 'message') { deliverMessage(m); return; }
    if (m.type === 'portConnect') { receivePortConnect(m); return; }
    if (m.type === 'portMessage') { receivePortMessage(m); return; }
    if (m.type === 'portDisconnect') { receivePortDisconnect(m); return; }
  }
  bridge.onmessage = function (e) { onNative(e && e.data); };

  // runtime.onMessage 的投递与回复：监听器返回 true / Promise 表示异步回复
  function deliverMessage(m) {
    var listeners = (L['runtime.onMessage'] || []).slice();
    var done = false;
    // opts: 'noReceiver' = 这个上下文根本没有 onMessage 监听器；'noResponse' = 有监听器但没有给出响应。
    // 两者对发送方的结果不同（Firefox）：前者 reject「Receiving end does not exist」，后者 resolve undefined。
    function reply(v, opt, msg) {
      if (done) return;
      done = true;
      var o = opt === 'noReceiver' ? { noReceiver: true } : opt === 'noResponse' ? { noResponse: true } : opt === 'error' ? { error: msg || 'error' } : null;
      rpc('runtime.reply', [m.cid, v === undefined ? null : v, o]).catch(function () { });
    }
    if (!listeners.length) { reply(undefined, 'noReceiver'); return; }
    var async = false;
    listeners.forEach(function (f) {
      var r;
      try { r = f(m.message, m.sender || {}, function (v) { reply(v); }); } catch (e) { cerr(e); return; }
      if (r === true) async = true;
      else if (r && typeof r.then === 'function') { async = true; r.then(function (v) { reply(v); }, function (e) { cerr(e); reply(undefined, 'error', e && e.message ? String(e.message) : String(e)); }); }
    });
    // 监听器收到了消息但既没同步 sendResponse、也没返回 true / Promise：不是「没有接收端」。
    // 交给 broker 汇总——所有目标都没响应时，发送方 resolve undefined（Firefox 语义），而不是 NO_RECEIVER。
    // 这里不伪造成功回复：只要还有别的上下文给出真正的响应，那个响应优先。
    if (!async && !done) reply(undefined, 'noResponse');
  }

  // runtime.connect / Port。Port 在 JS 侧同步返回；底层连接异步建立，建立前 postMessage 会排队。
  function makePort(id, name, sender, remote) {
    var msgListeners = [], disconnectListeners = [];
    function eventFor(list) {
      return {
        addListener: function (f) { if (typeof f === 'function' && list.indexOf(f) < 0) list.push(f); },
        removeListener: function (f) { var i = list.indexOf(f); if (i >= 0) list.splice(i, 1); },
        hasListener: function (f) { return list.indexOf(f) >= 0; }
      };
    }
    function notify(list, args) { list.slice().forEach(function (f) { try { f.apply(null, args); } catch (e) { cerr(e); } }); }
    var p = {
      name: name || '',
      sender: sender || undefined,
      onMessage: eventFor(msgListeners),
      onDisconnect: eventFor(disconnectListeners),
      postMessage: function (message) {
        if (p._closed) throw new E('Attempting to use a disconnected port object');
        if (!p._id) { p._queue.push(message); return; }
        rpc('runtime.port.postMessage', [p._id, message]).catch(function (e) { cerr(e); });
      },
      disconnect: function () {
        if (p._closed) return;
        var id = p._id;
        // A locally initiated disconnect closes this side without firing its own onDisconnect.
        p._close(false);
        if (id) rpc('runtime.port.disconnect', [id]).catch(function () {});
      },
      _id: id || null,
      _queue: [],
      _closed: false,
      _hasMessageListeners: function () { return msgListeners.length > 0; },
      _message: function (message) { if (!p._closed) notify(msgListeners, [message, p]); },
      _close: function (notifyLocal) {
        if (p._closed) return;
        p._closed = true;
        if (p._id) delete ports[p._id];
        if (notifyLocal !== false) notify(disconnectListeners, [p]);
      }
    };
    if (id) ports[id] = p;
    return p;
  }
  function receivePortConnect(m) {
    if (!m.portId || ports[m.portId]) return;
    var p = makePort(m.portId, m.name, m.sender, true);
    if (!(L['runtime.onConnect'] || []).length) cwarnPort('[mafari] onConnect arrived with no listener (buffered), port name=' + (m.name || ''));
    deliverEvent('runtime.onConnect', [p]);
  }
  function receivePortMessage(m) {
    var p = ports[m.portId];
    if (!p) { cwarnPort('[mafari] port message dropped: unknown port ' + m.portId + ' name=' + (m.name || '')); return; }
    if (!p._hasMessageListeners()) cwarnPort('[mafari] port message dropped: no onMessage listener yet, port name=' + (p.name || ''));
    p._message(m.message);
  }
  function cwarnPort(t) { try { win.console.warn(t); } catch (e) { } }
  function receivePortDisconnect(m) {
    var p = ports[m.portId];
    if (p) p._close();
  }
  function connect() {
    var a = slice.call(arguments), target = null, info = null;
    if (typeof a[0] === 'string') { target = a[0]; info = a[1] || {}; }
    else info = a[0] || {};
    if (target && target !== CFG.extId) throw new E('Connecting to another extension is not supported');
    var p = makePort(null, info.name || '', null, false);
    rpc('runtime.connect', [target, info]).then(function (id) {
      if (p._closed) { rpc('runtime.port.disconnect', [id]).catch(function () {}); return; }
      p._id = id;
      ports[id] = p;
      var q = p._queue.splice(0);
      q.forEach(function (message) { p.postMessage(message); });
    }, function (err) {
      p._close();
      cerr(err);
    });
    return p;
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

  // ───────────── 兼容面：永不触发的事件 / 明确不支持的方法 ─────────────
  // 目标面向 Firefox for Android：它上面能跑的拓展，在这里不该因为「命名空间 / 事件对象不存在」在启动阶段就抛 TypeError
  // （后台页启动时抛错 = 后台页永远不会注册 onMessage / onConnect，popup 就一直转圈或白屏）。
  // 规则：事件对象总是存在，可以 addListener，但宿主不会触发（首次注册时往控制台警告一次，诊断日志里能看到）；
  // 带返回值的方法返回 UNSUPPORTED 拒绝，不伪造成功。
  var cwarn = (win.console && win.console.warn) ? win.console.warn.bind(win.console) : function () { };
  var warned = {};
  function inertEvent(name, quiet) {
    var list = [];
    return {
      addListener: function (f) {
        if (typeof f !== 'function' || list.indexOf(f) >= 0) return;
        list.push(f);
        if (!quiet && !warned[name]) { warned[name] = 1; cwarn('[mafari] ' + name + ': 监听器已注册，但 WebView 适配层不会触发该事件'); }
      },
      removeListener: function (f) { var i = list.indexOf(f); if (i >= 0) list.splice(i, 1); },
      hasListener: function (f) { return list.indexOf(f) >= 0; },
      hasListeners: function () { return list.length > 0; }
    };
  }
  function unsupported(name) {
    return promised(function () {
      var e = new E(name + ' is not supported by Mafari WebView');
      e.code = 'UNSUPPORTED';
      return P.reject(e);
    });
  }

  // 兜底：拓展在后台页顶层对「命名空间上不存在的 onXxx 事件」直接 addListener 会抛 TypeError，把整个后台页带死。
  // 逐个补事件永远补不完（Firefox 有上百个），所以对每个命名空间对象再包一层 Proxy：读到不存在的 `onXxx` 就给一个
  // 惰性事件（可注册、不触发，首次注册时警告一次）。只兜事件名；方法不兜——方法缺失要让拓展的特性检测走降级路径，
  // 带返回值的方法该显式返回 UNSUPPORTED。事件对象缓存在 Proxy 外，不改被包对象，所以 Object.keys / in 的结果不变。
  var HAS_PROXY = typeof win.Proxy === 'function';
  function withInertEvents(obj, ns) {
    if (!HAS_PROXY || !obj) return obj;
    var made = {};
    return new win.Proxy(obj, {
      get: function (t, k, r) {
        if (typeof k === 'string' && !(k in t) && /^on[A-Z]/.test(k)) return made[k] || (made[k] = inertEvent(ns + '.' + k));
        return Reflect.get(t, k, r);
      }
    });
  }

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
    getVersion: function () { return String(CFG.manifest && CFG.manifest.version || ''); },
    getPlatformInfo: promised(function () { return rpc('runtime.getPlatformInfo', []); }),
    openOptionsPage: promised(function () { return rpc('runtime.openOptionsPage', []); }),
    sendMessage: promised(sendMessage),
    connect: connect,
    onMessage: ev('runtime.onMessage'),
    onConnect: ev('runtime.onConnect'),
    onStartup: ev('runtime.onStartup'),
    onInstalled: ev('runtime.onInstalled'),
    // 下面这些在 Firefox 里一直存在；拓展常在后台页顶层直接 addListener / 调用
    onSuspend: inertEvent('runtime.onSuspend', true),
    onSuspendCanceled: inertEvent('runtime.onSuspendCanceled', true),
    onUpdateAvailable: inertEvent('runtime.onUpdateAvailable', true),
    onBrowserUpdateAvailable: inertEvent('runtime.onBrowserUpdateAvailable', true),
    onMessageExternal: inertEvent('runtime.onMessageExternal', true),
    onConnectExternal: inertEvent('runtime.onConnectExternal', true),
    // 卸载后打开的页面：WebView 适配层没有这个入口，接受并忽略（拓展只是想统计 / 问卷，失败不该拖垮后台页）
    setUninstallURL: promised(function () { return P.resolve(); }),
    // Firefox 专有；Tampermonkey 等在后台页启动时就会调，缺失会让启动流程中断
    getBrowserInfo: promised(function () {
      return P.resolve({ name: 'Mafari', vendor: 'Mafari', version: String(CFG.appVersion || '1.0.0'), buildID: '' });
    }),
    requestUpdateCheck: unsupported('runtime.requestUpdateCheck'),
    reload: unsupported('runtime.reload'),
    // 只有后台页自己能拿到自己的 window；别的页面在另一个 WebView 里，跨 WebView 拿不到
    getBackgroundPage: promised(function () {
      if (CFG.background) return P.resolve(win);
      if (Number(CFG.manifest && CFG.manifest.manifest_version) >= 3) return unsupported('runtime.getBackgroundPage')();
      return rpc('runtime.getBackgroundPage', []).then(function (ctx) {
        // Different WebView instances cannot expose each other's Window objects.
        // Return a compatibility descriptor instead of pretending this is a real Window.
        return ctx || undefined;
      });
    })
  };
  if (Number(CFG.manifest && CFG.manifest.manifest_version) >= 3) {
    runtime.getContexts = promised(function (filter) { return rpc('runtime.getContexts', [filter || {}]); });
  }
  var storage = {
    local: storageArea('local'), sync: storageArea('sync'), session: storageArea('session'),
    // 没有企业策略：与 Firefox「没有 managed schema」时一致，读取失败而不是命名空间不存在
    managed: {
      get: promised(function () { return P.reject(new E('Managed storage manifest not found')); }),
      getBytesInUse: promised(function () { return P.reject(new E('Managed storage manifest not found')); }),
      onChanged: inertEvent('storage.managed.onChanged', true)
    },
    onChanged: ev('storage.onChanged')
  };
  // i18n：getMessage 是同步的，所以消息表由原生按设备语言合并好（default_locale 兜底、键名已转小写）放进 CFG.i18n，这里只查表。
  // CFG.i18n = { locale: 'zh_CN', msgs: { key: { m: '文本含 $name$ $1 $$', p: { name: '$1' } } } }；拓展没有 _locales 时为空表
  var I18N = CFG.i18n || null, MSGS = (I18N && I18N.msgs) || {};
  var HOP = Object.prototype.hasOwnProperty, IA = Array.isArray;
  var UI_LOCALE = (I18N && I18N.locale) || String((win.navigator && win.navigator.language) || 'en').replace(/-/g, '_');
  var RTL = /^(ar|he|iw|fa|ur)(_|$)/i.test(UI_LOCALE);
  var BUILTIN = {
    '@@extension_id': CFG.extId, '@@ui_locale': UI_LOCALE,
    '@@bidi_dir': RTL ? 'rtl' : 'ltr', '@@bidi_reversed_dir': RTL ? 'ltr' : 'rtl',
    '@@bidi_start_edge': RTL ? 'right' : 'left', '@@bidi_end_edge': RTL ? 'left' : 'right'
  };
  // 一次扫描：$$ → $；$name$ → 占位符（content 里的 $1-$9 再换成 substitutions）；$1-$9 → substitutions。
  // 占位符名必须以字母 / _ / @ 开头，所以 "$1$" 永远按「$1 后跟一个 $」处理。缺的 substitution 当空串；
  // 一次扫描就完事，替换进去的内容不会被再次展开（拓展的消息文本里出现 $1 不会被 substitutions 里的 $ 影响）
  function expandMessage(text, subs, ph) {
    var sub = function (n) { var v = subs[n - 1]; return v === undefined ? '' : String(v); };
    return text.replace(/\$(?:(\$)|([A-Za-z_@][A-Za-z0-9_@]*)\$|([1-9]))/g, function (all, dollar, name, digit) {
      if (dollar) return '$';
      if (digit) return sub(+digit);
      var k = name.toLowerCase();
      if (!ph || !HOP.call(ph, k)) return '';
      return String(ph[k]).replace(/\$([1-9])/g, function (a, d) { return sub(+d); });
    });
  }
  var i18n = {
    getMessage: function (name, substitutions) {
      var key = String(name).toLowerCase();
      if (key.slice(0, 2) === '@@') return HOP.call(BUILTIN, key) ? BUILTIN[key] : '';
      if (!HOP.call(MSGS, key)) return '';
      var subs = (substitutions === undefined || substitutions === null) ? [] : (IA(substitutions) ? substitutions : [substitutions]);
      if (subs.length > 9) return undefined; // 与 Chrome 一致：最多 9 个
      var m = MSGS[key];
      return expandMessage(String(m.m), subs, m.p);
    },
    getUILanguage: function () { return UI_LOCALE.replace(/_/g, '-'); },
    detectLanguage: unsupported('i18n.detectLanguage'),
    getAcceptLanguages: promised(function () {
      var l = win.navigator && win.navigator.languages;
      return P.resolve(l && l.length ? slice.call(l) : [UI_LOCALE.replace(/_/g, '-')]);
    })
  };
  var ext = {
    getURL: getURL,
    inIncognitoContext: false,
    getBackgroundPage: function () { return CFG.background ? win : null; },
    getViews: function (q) { return (CFG.background && (!q || !q.type || q.type === 'background')) ? [win] : []; },
    isAllowedIncognitoAccess: promised(function () { return P.resolve(false); }),
    isAllowedFileSchemeAccess: promised(function () { return P.resolve(false); })
  };

  // alarms：只在拓展自己的页面里提供，且 manifest 要声明 alarms 权限（原生侧还会再校验一次）
  function declares(perm) {
    var m = CFG.manifest || {}, lists = [m.permissions, m.optional_permissions];
    for (var i = 0; i < lists.length; i++) if (lists[i] && lists[i].indexOf && lists[i].indexOf(perm) >= 0) return true;
    return false;
  }
  var alarms = null;
  if (CFG.page && declares('alarms')) {
    var nn = function (v) { return v === undefined ? null : v; };
    alarms = {
      // create([name], alarmInfo)
      create: promised(function (a, b) {
        var name = null, info = a;
        if (typeof a === 'string' || (a === undefined && b !== undefined)) { name = nn(a); info = b; }
        return rpc('alarms.create', [name, nn(info)]);
      }),
      get: promised(function (name) { return rpc('alarms.get', [nn(name)]); }),
      getAll: promised(function () { return rpc('alarms.getAll', []); }),
      clear: promised(function (name) { return rpc('alarms.clear', [nn(name)]); }),
      clearAll: promised(function () { return rpc('alarms.clearAll', []); }),
      onAlarm: ev('alarms.onAlarm')
    };
  }

  // tabs：只在拓展自己的页面里提供（content script 不能调，原生侧也会拒绝）。query / create 等本身不要权限，
  // url / title 等字段是否返回由原生按 tabs 权限 / host 权限裁剪。
  // 参数约定：可选的 tabId 缺省时传 null（= 当前前台标签），属性对象缺省时传 null / {}。
  var tabs = null;
  if (CFG.page) {
    var nul = function (v) { return v === undefined ? null : v; };
    var isObj = function (v) { return v !== null && typeof v === 'object'; };
    tabs = {
      TAB_ID_NONE: -1,
      query: promised(function (q) { return rpc('tabs.query', [q === undefined || q === null ? {} : q]); }),
      get: promised(function (tabId) { return rpc('tabs.get', [tabId]); }),
      getCurrent: promised(function () { return rpc('tabs.getCurrent', []); }),
      create: promised(function (props) { return rpc('tabs.create', [nul(props)]); }),
      // update([tabId], updateProperties)
      update: promised(function (a, b) {
        return isObj(a) ? rpc('tabs.update', [null, a]) : rpc('tabs.update', [nul(a), nul(b)]);
      }),
      duplicate: promised(function (tabId) { return rpc('tabs.duplicate', [tabId]); }),
      move: promised(function (tabIds, moveProperties) { return rpc('tabs.move', [tabIds, nul(moveProperties)]); }),
      // remove(tabId | tabId[])
      remove: promised(function (ids) { return rpc('tabs.remove', [ids]); }),
      // reload([tabId], [reloadProperties])
      reload: promised(function (a, b) {
        return isObj(a) ? rpc('tabs.reload', [null, a]) : rpc('tabs.reload', [nul(a), nul(b)]);
      }),
      goBack: promised(function (tabId) { return rpc('tabs.goBack', [nul(tabId)]); }),
      goForward: promised(function (tabId) { return rpc('tabs.goForward', [nul(tabId)]); }),
      // 向指定标签已注入的 content script 发送一次性消息；原生只路由到该标签，不广播。
      sendMessage: promised(function (tabId, message, options) {
        return rpc('tabs.sendMessage', [tabId, message === undefined ? null : message, nul(options)]);
      }),
      // MV2 注入接口：WebView 上注入的代码拿不到 browser / chrome，不能照 Firefox 语义工作，明确拒绝
      // MV2 注入：只支持主框架、file 形式（insertCSS 另支持 code）；host 权限由原生按目标标签校验
      executeScript: promised(function (a, b) {
        return isObj(a) ? rpc('tabs.executeScript', [null, a]) : rpc('tabs.executeScript', [nul(a), nul(b)]);
      }),
      insertCSS: promised(function (a, b) {
        return isObj(a) ? rpc('tabs.insertCSS', [null, a]) : rpc('tabs.insertCSS', [nul(a), nul(b)]);
      }),
      removeCSS: promised(function (a, b) {
        return isObj(a) ? rpc('tabs.removeCSS', [null, a]) : rpc('tabs.removeCSS', [nul(a), nul(b)]);
      }),
      detectLanguage: unsupported('tabs.detectLanguage'),
      captureVisibleTab: promised(function (windowId, options) {
        if (isObj(windowId)) return rpc('tabs.captureVisibleTab', [windowId]);
        return rpc('tabs.captureVisibleTab', [nul(windowId), nul(options)]);
      }),
      getZoom: unsupported('tabs.getZoom'),
      setZoom: unsupported('tabs.setZoom'),
      onCreated: ev('tabs.onCreated'),
      onUpdated: ev('tabs.onUpdated'),
      onRemoved: ev('tabs.onRemoved'),
      onActivated: ev('tabs.onActivated'),
      onMoved: ev('tabs.onMoved'),
      // 单窗口模型下这几个事件不会发生，不算「没实现」，不警告
      onReplaced: inertEvent('tabs.onReplaced', true),
      onAttached: inertEvent('tabs.onAttached', true),
      onDetached: inertEvent('tabs.onDetached', true),
      onHighlighted: ev('tabs.onHighlighted'),
      onZoomChange: inertEvent('tabs.onZoomChange', true)
    };
  }

  // scripting：只在拓展自己的页面里提供（content script 不能调，原生侧也会拒绝），且 manifest 要声明 scripting 权限。
  // func 是函数对象，JSON 序列化会把它丢掉，所以这里转成源码字符串再发；其它字段按白名单复制，
  // 原生不认的（injectImmediately 等）不转发。executeScript 的结果只有主框架一项，见 ScriptingApi 的说明。
  // 动态脚本注册由原生持久化，并在下一次导航时生效；当前已加载文档不会回溯注入。
  var scripting = null;
  if (CFG.page && declares('scripting')) {
    var sn = function (v) { return v === undefined ? null : v; };
    var sObj = function (v) { return v !== null && typeof v === 'object'; };
    var sPick = function (o, keys) {
      var out = {};
      keys.forEach(function (k) { if (o[k] !== undefined) out[k] = o[k]; });
      return out;
    };
    scripting = {
      executeScript: promised(function (inj) {
        if (!sObj(inj)) return rpc('scripting.executeScript', [sn(inj)]);
        var d = sPick(inj, ['target', 'args', 'files', 'world']);
        if (inj.func !== undefined) {
          if (typeof inj.func !== 'function') return P.reject(new E('scripting.executeScript: func must be a function'));
          d.func = Function.prototype.toString.call(inj.func);
        }
        return rpc('scripting.executeScript', [d]);
      }),
      insertCSS: promised(function (inj) {
        return rpc('scripting.insertCSS', [sObj(inj) ? sPick(inj, ['target', 'css', 'files', 'origin']) : sn(inj)]);
      }),
      removeCSS: promised(function (inj) {
        return rpc('scripting.removeCSS', [sObj(inj) ? sPick(inj, ['target', 'css', 'files', 'origin']) : sn(inj)]);
      }),
      registerContentScripts: promised(function (list) { return rpc('scripting.registerContentScripts', [sn(list)]); }),
      getRegisteredContentScripts: promised(function (filter) { return rpc('scripting.getRegisteredContentScripts', [sn(filter)]); }),
      updateContentScripts: promised(function (list) { return rpc('scripting.updateContentScripts', [sn(list)]); }),
      unregisterContentScripts: promised(function (filter) { return rpc('scripting.unregisterContentScripts', [sn(filter)]); })
    };
  }

  // contentScripts.register：Firefox MV2 专有（uBlock Origin 等在后台页启动时就用）。只在拓展自己的页面提供，
  // 转成原生的动态脚本注册（不持久化，进程内有效）；只支持 { file } 形式的 js / css，内联 code 会被拒绝。
  var contentScripts = null;
  if (CFG.page && Number(CFG.manifest && CFG.manifest.manifest_version) < 3) {
    var csSeq = 1;
    contentScripts = {
      register: promised(function (details) {
        if (details === null || typeof details !== 'object') return P.reject(new E('contentScripts.register: details must be an object'));
        var d = {}; OK(details).forEach(function (k) { d[k] = details[k]; });
        d.id = 'mafari-cs-' + (csSeq++) + '-' + Math.random().toString(36).slice(2, 8);
        return rpc('contentScripts.register', [d]).then(function (id) {
          var done = false;
          return { unregister: promised(function () { if (done) return P.resolve(); done = true; return rpc('contentScripts.unregister', [id]); }) };
        });
      })
    };
  }

  // permissions：request/remove 由浏览器原生确认 UI 执行；事件在所有拓展上下文中分发。
  var permissions = {
    getAll: promised(function () { return rpc('permissions.getAll', []); }),
    contains: promised(function (p) { return rpc('permissions.contains', [p === undefined ? {} : p]); }),
    request: promised(function (p) { return rpc('permissions.request', [p === undefined ? {} : p]); }),
    remove: promised(function (p) { return rpc('permissions.remove', [p === undefined ? {} : p]); }),
    onAdded: ev('permissions.onAdded'),
    onRemoved: ev('permissions.onRemoved')
  };

  // action / browserAction share one native implementation but remain separate API entry points.
  function makeAction(prefix) {
    var noop = promised(function () { return P.resolve(); });
    return {
      setBadgeText: promised(function (d) { return rpc(prefix + '.setBadgeText', [d || {}]); }),
      setBadgeBackgroundColor: promised(function (d) { return rpc(prefix + '.setBadgeBackgroundColor', [d || {}]); }),
      setTitle: promised(function (d) { return rpc(prefix + '.setTitle', [d || {}]); }),
      setPopup: promised(function (d) { return rpc(prefix + '.setPopup', [d || {}]); }),
      getTitle: promised(function (d) { return rpc(prefix + '.getTitle', [d || {}]); }),
      getBadgeText: promised(function (d) { return rpc(prefix + '.getBadgeText', [d || {}]); }),
      // uBO 等会在每次标签变化时 setIcon；Mafari 的拓展菜单不画动态图标，纯外观调用，接受并忽略（不是能力缺失）
      setIcon: noop, setBadgeTextColor: noop, enable: noop, disable: noop,
      isEnabled: promised(function () { return P.resolve(true); }),
      openPopup: unsupported(prefix + '.openPopup'),
      // 工具栏点击事件：没有工具栏按钮，popup 由「拓展」菜单打开
      onClicked: inertEvent(prefix + '.onClicked')
    };
  }
  var action = CFG.page ? makeAction('action') : null;
  var browserAction = CFG.page ? makeAction('browserAction') : null;

  // Context menu definitions are managed natively; create keeps Chrome's synchronous id return.
  var contextMenus = null, menuSequence = 1;
  if (CFG.page && (declares('contextMenus') || declares('menus'))) {
    contextMenus = {
      create: function (properties, callback) {
        var p = properties || {}, id = p.id === undefined ? 'mafari-menu-' + (menuSequence++) : String(p.id);
        var item = {}; OK(p).forEach(function (k) { if (k !== 'onclick') item[k] = p[k]; }); item.id = id;
        if (typeof p.onclick === 'function') menuHandlers[id] = p.onclick;
        rpc('contextMenus.create', [item]).then(function () { if (typeof callback === 'function') callback(); }, function (e) {
          if (typeof callback === 'function') { lastError.v = { message: e && e.message }; try { callback(); } finally { lastError.v = undefined; } }
        });
        return id;
      },
      update: promised(function (id, properties) { return rpc('contextMenus.update', [id, properties || {}]); }),
      remove: promised(function (id) { delete menuHandlers[id]; return rpc('contextMenus.remove', [id]); }),
      removeAll: promised(function () { menuHandlers = {}; return rpc('contextMenus.removeAll', []); }),
      onClicked: ev('contextMenus.onClicked')
    };
  }

  var webRequest = null, webRequestSequence = 1;
  if (CFG.page && declares('webRequest')) {
    var webRequestListeners = { onBeforeRequest: [], onBeforeRedirect: [], onHeadersReceived: [], onResponseStarted: [], onCompleted: [], onErrorOccurred: [] };
    function makeWebRequestEvent(eventName) {
      return {
        addListener: function (fn, filter, extraInfoSpec) {
          if (typeof fn !== 'function') return;
          var token = 'wr-' + (webRequestSequence++) + '-' + Math.random().toString(36).slice(2);
          var entry = { fn: fn, token: token, filter: filter || {}, extra: extraInfoSpec || [] };
          webRequestListeners[eventName].push(entry);
          rpc('webRequest.addListener', [eventName, token, entry.filter, entry.extra]).catch(function (e) {
            var i = webRequestListeners[eventName].indexOf(entry); if (i >= 0) webRequestListeners[eventName].splice(i, 1); cerr(e);
          });
        },
        removeListener: function (fn) {
          for (var i = webRequestListeners[eventName].length - 1; i >= 0; i--) {
            if (webRequestListeners[eventName][i].fn === fn) {
              var entry = webRequestListeners[eventName].splice(i, 1)[0];
              rpc('webRequest.removeListener', [entry.token]).catch(function () {});
            }
          }
        },
        hasListener: function (fn) { return webRequestListeners[eventName].some(function (x) { return x.fn === fn; }); }
      };
    }
    webRequest = {
      onBeforeRequest: makeWebRequestEvent('onBeforeRequest'),
      onBeforeRedirect: makeWebRequestEvent('onBeforeRedirect'),
      onHeadersReceived: makeWebRequestEvent('onHeadersReceived'),
      onResponseStarted: makeWebRequestEvent('onResponseStarted'),
      onCompleted: makeWebRequestEvent('onCompleted'),
      onErrorOccurred: makeWebRequestEvent('onErrorOccurred'),
      // 请求头 / 认证阶段 WebView 看不到（shouldInterceptRequest 只在发请求前给一次）。
      // 主框架的响应头 / 状态码来自 androidx.webkit NavigationListener（系统 WebView 支持时）：onHeadersReceived 只读，且只覆盖主文档。
      // 其余事件对象必须存在，否则 uBO / 篡改猴 / 沉浸式翻译的后台页在 `.addListener` 这一行就抛 TypeError，事件本身不会触发。
      onBeforeSendHeaders: inertEvent('webRequest.onBeforeSendHeaders'),
      onSendHeaders: inertEvent('webRequest.onSendHeaders'),
      onAuthRequired: inertEvent('webRequest.onAuthRequired'),
      // Firefox 里这些选项枚举是存在的（没有 EXTRA_HEADERS）：Violentmonkey 在后台页顶层直接读
      // browser.webRequest.OnBeforeSendHeadersOptions.EXTRA_HEADERS，对象缺失会抛 TypeError，后台页整个起不来。
      OnBeforeRequestOptions: { BLOCKING: 'blocking', REQUEST_BODY: 'requestBody' },
      OnBeforeSendHeadersOptions: { BLOCKING: 'blocking', REQUEST_HEADERS: 'requestHeaders' },
      OnSendHeadersOptions: { REQUEST_HEADERS: 'requestHeaders' },
      OnHeadersReceivedOptions: { BLOCKING: 'blocking', RESPONSE_HEADERS: 'responseHeaders' },
      OnAuthRequiredOptions: { BLOCKING: 'blocking', RESPONSE_HEADERS: 'responseHeaders', ASYNC_BLOCKING: 'asyncBlocking' },
      OnResponseStartedOptions: { RESPONSE_HEADERS: 'responseHeaders' },
      OnBeforeRedirectOptions: { RESPONSE_HEADERS: 'responseHeaders' },
      OnCompletedOptions: { RESPONSE_HEADERS: 'responseHeaders' },
      handlerBehaviorChanged: promised(function () { return P.resolve(); }),
      MAX_HANDLER_BEHAVIOR_CHANGED_CALLS_PER_10_MINUTES: 20
    };
  }

  var declarativeNetRequest = null;
  if (CFG.page && declares('declarativeNetRequest')) {
    declarativeNetRequest = {
      getDynamicRules: promised(function () { return rpc('declarativeNetRequest.getDynamicRules', []); }),
      updateDynamicRules: promised(function (options) { return rpc('declarativeNetRequest.updateDynamicRules', [options || {}]); }),
      getEnabledRulesets: promised(function () { return rpc('declarativeNetRequest.getEnabledRulesets', []); }),
      updateEnabledRulesets: promised(function (options) { return rpc('declarativeNetRequest.updateEnabledRulesets', [options || {}]); }),
      onRuleMatchedDebug: inertEvent('declarativeNetRequest.onRuleMatchedDebug', true)
    };
  }

  var userScripts = null;
  var userScriptWorlds = {};
  if (CFG.page && declares('userScripts')) {
    userScripts = {
      register: promised(function (scripts) { return rpc('userScripts.register', [scripts || []]); }),
      getScripts: promised(function (filter) { return rpc('userScripts.getScripts', [filter || {}]); }),
      update: promised(function (scripts) { return rpc('userScripts.update', [scripts || []]); }),
      unregister: promised(function (options) { return rpc('userScripts.unregister', [options || {}]); }),
      // Tampermonkey MV3 后台初始化时必调 configureWorld（设置 USER_SCRIPT world 的 CSP / 消息开关）；
      // 缺失会抛 TypeError，它的 userscripts 引擎就判定初始化失败，弹窗随后 unable to load tree 白屏。
      // Mafari 的脚本注入不走 Chrome 的 USER_SCRIPT world，这里只记录配置、返回 resolve 即可。
      configureWorld: promised(function (properties) { userScriptWorlds.default = properties || {}; return P.resolve(); }),
      getWorldConfigurations: promised(function () {
        return P.resolve(Object.keys(userScriptWorlds).map(function (k) { return Object.assign({ worldId: k === 'default' ? undefined : k }, userScriptWorlds[k]); }));
      }),
      resetWorldConfiguration: promised(function (worldId) { delete userScriptWorlds[worldId || 'default']; return P.resolve(); }),
      onBeforeScript: inertEvent('userScripts.onBeforeScript', true),
      onAfterScript: inertEvent('userScripts.onAfterScript', true)
    };
  }

  var webNavigation = null;
  if (CFG.page && declares('webNavigation')) {
    webNavigation = {
      getFrame: promised(function (details) { return rpc('webNavigation.getFrame', [nul(details)]); }),
      getAllFrames: promised(function (details) { return rpc('webNavigation.getAllFrames', [nul(details)]); }),
      onBeforeNavigate: ev('webNavigation.onBeforeNavigate'),
      onCommitted: ev('webNavigation.onCommitted'),
      onCompleted: ev('webNavigation.onCompleted'),
      onErrorOccurred: ev('webNavigation.onErrorOccurred'),
      onHistoryStateUpdated: ev('webNavigation.onHistoryStateUpdated'),
      onReferenceFragmentUpdated: ev('webNavigation.onReferenceFragmentUpdated'),
      // uBO 等的后台页在顶层直接 addListener（弹窗拦截 / 标签替换）；宿主不产生这几个事件
      onCreatedNavigationTarget: inertEvent('webNavigation.onCreatedNavigationTarget'),
      onDOMContentLoaded: ev('webNavigation.onDOMContentLoaded'),
      onTabReplaced: inertEvent('webNavigation.onTabReplaced', true)
    };
  }

  // Notifications: basic text notifications only; native side enforces the declared permission and Android app-level grant.
  var notifications = null;
  if (CFG.page && declares('notifications')) {
    notifications = {
      create: promised(function (notificationId, options) {
        if (notificationId && typeof notificationId === 'object') return rpc('notifications.create', [notificationId]);
        return rpc('notifications.create', [notificationId == null ? '' : String(notificationId), options || {}]);
      }),
      update: promised(function (notificationId, options) { return rpc('notifications.update', [String(notificationId), options || {}]); }),
      clear: promised(function (notificationId) { return rpc('notifications.clear', [String(notificationId)]); }),
      getAll: promised(function () { return rpc('notifications.getAll', []); }),
      onClicked: ev('notifications.onClicked'),
      onClosed: ev('notifications.onClosed'),
      onButtonClicked: inertEvent('notifications.onButtonClicked'),
      onShown: inertEvent('notifications.onShown', true)
    };
  }

  // Bookmarks: backed by the same FavoritesStore as Mafari's own bookmark UI and Floccus Gecko bridge.
  var bookmarks = null;
  if (CFG.page && declares('bookmarks')) {
    bookmarks = {
      getTree: promised(function () { return rpc('bookmarks.getTree', []); }),
      getSubTree: promised(function (id) { return rpc('bookmarks.getSubTree', [String(id)]); }),
      get: promised(function (ids) { return rpc('bookmarks.get', [ids]); }),
      getChildren: promised(function (id) { return rpc('bookmarks.getChildren', [String(id)]); }),
      search: promised(function (query) { return rpc('bookmarks.search', [query]); }),
      create: promised(function (details) { return rpc('bookmarks.create', [details || {}]); }),
      update: promised(function (id, changes) { return rpc('bookmarks.update', [String(id), changes || {}]); }),
      move: promised(function (id, destination) { return rpc('bookmarks.move', [String(id), destination || {}]); }),
      remove: promised(function (id) { return rpc('bookmarks.remove', [String(id)]); }),
      removeTree: promised(function (id) { return rpc('bookmarks.removeTree', [String(id)]); }),
      onCreated: ev('bookmarks.onCreated'),
      onChanged: ev('bookmarks.onChanged'),
      onMoved: ev('bookmarks.onMoved'),
      onRemoved: ev('bookmarks.onRemoved')
    };
  }

  // Downloads: backed by Mafari's persistent built-in downloader. Unsupported Chrome fields are rejected natively.
  var downloads = null;
  if (CFG.page && declares('downloads')) {
    downloads = {
      download: promised(function (options) { return rpc('downloads.download', [options || {}]); }),
      search: promised(function (query) { return rpc('downloads.search', [query || {}]); }),
      pause: promised(function (id) { return rpc('downloads.pause', [id]); }),
      resume: promised(function (id) { return rpc('downloads.resume', [id]); }),
      cancel: promised(function (id) { return rpc('downloads.cancel', [id]); }),
      erase: promised(function (query) { return rpc('downloads.erase', [query || {}]); }),
      onCreated: ev('downloads.onCreated'),
      onErased: ev('downloads.onErased'),
      // 进度 / 状态变化宿主不推送（TM 等启动时就注册）
      onChanged: inertEvent('downloads.onChanged'),
      open: unsupported('downloads.open'), show: unsupported('downloads.show'),
      showDefaultFolder: promised(function () { return P.resolve(); }),
      removeFile: unsupported('downloads.removeFile'), acceptDanger: unsupported('downloads.acceptDanger'),
      setShelfEnabled: promised(function () { return P.resolve(); })
    };
  }

  // Cookies: WebView only exposes URL-scoped CookieManager operations; no global enumeration or change events.
  var history = null;
  if (CFG.page && declares('history')) {
    history = {
      search: promised(function (details) { return rpc('history.search', [details || {}]); }),
      deleteUrl: promised(function (details) { return rpc('history.deleteUrl', [details || {}]); }),
      deleteRange: promised(function (details) { return rpc('history.deleteRange', [details || {}]); }),
      deleteAll: promised(function () { return rpc('history.deleteAll', []); })
    };
  }

  var cookies = null;
  if (CFG.page && declares('cookies')) {
    cookies = {
      get: promised(function (details) { return rpc('cookies.get', [details || {}]); }),
      // 只支持带 url 的调用（WebView 没有全局枚举）；不带 url 时原生端会报 unsupported
      getAll: promised(function (details) { return rpc('cookies.getAll', [details || {}]); }),
      set: promised(function (details) { return rpc('cookies.set', [details || {}]); }),
      remove: promised(function (details) { return rpc('cookies.remove', [details || {}]); }),
      // WebView 只有按 URL 的 CookieManager：getAll 必须带 url，没有全局枚举，也没有变更事件
      onChanged: inertEvent('cookies.onChanged')
    };
  }

  // Mafari clipboard bridge: deliberately extension-page-only; native permission checks remain authoritative.
  var clipboard = null;
  if (CFG.page && (declares('clipboardRead') || declares('clipboardWrite'))) {
    clipboard = {};
    if (declares('clipboardRead')) clipboard.readText = promised(function () { return rpc('clipboard.readText', []); });
    if (declares('clipboardWrite')) clipboard.writeText = promised(function (text) { return rpc('clipboard.writeText', [String(text == null ? '' : text)]); });
  }

  // windows：Firefox 永远有这个命名空间（不需要权限），TM 等会直接调 windows.getCurrent。
  // Mafari 是单窗口模型（与 tabs 的 windowId 一致），所以返回唯一那个窗口是真实情况，不是伪造。
  var windows = null;
  if (CFG.page) {
    var WID = 1;
    var mkWin = function (info) {
      var w = { id: WID, focused: true, incognito: false, type: 'normal', state: 'normal', alwaysOnTop: false };
      if (info && info.populate && tabs) return tabs.query({}).then(function (t) { w.tabs = t; return w; });
      return P.resolve(w);
    };
    var checkWin = function (id) {
      return (id === WID || id === -2 || id === undefined || id === null) ? null : new E('No window with id: ' + id + '.');
    };
    windows = {
      WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
      get: promised(function (id, info) { var e = checkWin(id); return e ? P.reject(e) : mkWin(info); }),
      getCurrent: promised(function (info) { return mkWin(info); }),
      getLastFocused: promised(function (info) { return mkWin(info); }),
      getAll: promised(function (info) { return mkWin(info).then(function (w) { return [w]; }); }),
      // focused / state 等无法改变；单窗口里「聚焦」本来就成立
      update: promised(function (id) { var e = checkWin(id); return e ? P.reject(e) : mkWin(); }),
      create: unsupported('windows.create'),
      remove: unsupported('windows.remove'),
      onCreated: inertEvent('windows.onCreated', true),
      onRemoved: inertEvent('windows.onRemoved', true),
      onFocusChanged: inertEvent('windows.onFocusChanged', true)
    };
  }

  var idle = null;
  if (CFG.page && declares('idle')) {
    idle = {
      queryState: unsupported('idle.queryState'),
      setDetectionInterval: promised(function () { return P.resolve(); }),
      onStateChanged: inertEvent('idle.onStateChanged')
    };
  }
  if (contextMenus) {
    contextMenus.onShown = inertEvent('menus.onShown', true);
    contextMenus.onHidden = inertEvent('menus.onHidden', true);
    contextMenus.refresh = promised(function () { return P.resolve(); });
  }

  // Mafari 专有桥接：管理浏览器原生 Userscript 库。默认不暴露，必须在 manifest 声明 mafariScripts。
  var mafari = null;
  if (CFG.page && declares('mafariScripts')) {
    var nativeUserscripts = {
      getAll: promised(function () { return rpc('mafari.userscripts.getAll', []); }),
      get: promised(function (id) { return rpc('mafari.userscripts.get', [id]); }),
      create: promised(function (name, code, match) { return rpc('mafari.userscripts.create', [name || '', code || '', match || '']); }),
      update: promised(function (id, name, code, enabled, match) {
        return rpc('mafari.userscripts.update', [id, name || '', code || '', enabled !== false, match || '']);
      }),
      setEnabled: promised(function (id, enabled) { return rpc('mafari.userscripts.setEnabled', [id, !!enabled]); }),
      remove: promised(function (id) { return rpc('mafari.userscripts.remove', [id]); }),
      install: promised(function (url) { return rpc('mafari.userscripts.install', [String(url || '')]); }),
      refresh: promised(function (id) { return rpc('mafari.userscripts.refresh', [String(id || '')]); }),
      refreshAll: promised(function (onlyEnabled) { return rpc('mafari.userscripts.refreshAll', [!!onlyEnabled]); }),
      getAutoUpdate: promised(function () { return rpc('mafari.userscripts.getAutoUpdate', []); }),
      setAutoUpdate: promised(function (mode) { return rpc('mafari.userscripts.setAutoUpdate', [mode]); })
    };
    mafari = { userscripts: nativeUserscripts };
  }

  // 内容脚本（非 page）里 browser 只有 runtime / storage / i18n / extension 这几个小命名空间，同样包一层
  var offscreen = null;
  if (CFG.page && declares('offscreen') && Number(CFG.manifest && CFG.manifest.manifest_version) >= 3) {
    offscreen = {
      createDocument: promised(function (details) { return rpc('offscreen.createDocument', [details || {}]); }),
      closeDocument: promised(function () { return rpc('offscreen.closeDocument', []); }),
      hasDocument: promised(function () { return rpc('offscreen.hasDocument', []); })
    };
  }

  // Chrome offscreen documents expose only the runtime API surface.
  var browser = CFG.offscreen ? { runtime: runtime } : { runtime: runtime, storage: storage, i18n: i18n, extension: ext, permissions: permissions };
  if (!CFG.offscreen && mafari) browser['mafari'] = mafari;
  if (!CFG.offscreen && offscreen) browser.offscreen = offscreen;
  if (!CFG.offscreen && windows) browser.windows = windows;
  if (!CFG.offscreen && idle) browser.idle = idle;
  if (!CFG.offscreen && bookmarks) browser.bookmarks = bookmarks;
  if (!CFG.offscreen && downloads) browser.downloads = downloads;
  if (!CFG.offscreen && cookies) browser.cookies = cookies;
  if (!CFG.offscreen && history) browser.history = history;
  if (!CFG.offscreen && clipboard) browser.clipboard = clipboard;
  if (!CFG.offscreen && notifications) browser.notifications = notifications;
  if (!CFG.offscreen && alarms) browser.alarms = alarms;
  if (!CFG.offscreen && tabs) browser.tabs = tabs;
  if (!CFG.offscreen && scripting) browser.scripting = scripting;
  if (!CFG.offscreen && contentScripts) browser.contentScripts = contentScripts;
  if (!CFG.offscreen && webNavigation) browser.webNavigation = webNavigation;
  if (!CFG.offscreen && webRequest) browser.webRequest = webRequest;
  if (!CFG.offscreen && declarativeNetRequest) browser.declarativeNetRequest = declarativeNetRequest;
  if (!CFG.offscreen && userScripts) browser.userScripts = userScripts;
  if (!CFG.offscreen && action) browser.action = action;
  if (!CFG.offscreen && browserAction) browser.browserAction = browserAction;
  if (!CFG.offscreen && contextMenus) { browser.contextMenus = contextMenus; browser.menus = contextMenus; }

  var chromeRuntime = cbObj(runtime);
  Object.defineProperty(chromeRuntime, 'lastError', { get: function () { return lastError.v; }, enumerable: true });
  var chrome = CFG.offscreen ? { runtime: chromeRuntime } : {
    runtime: chromeRuntime,
    storage: {
      local: cbObj(storage.local), sync: cbObj(storage.sync), session: cbObj(storage.session), managed: cbObj(storage.managed),
      onChanged: storage.onChanged
    },
    i18n: cbObj(i18n),
    // extension 里的 isAllowedFileSchemeAccess / isAllowedIncognitoAccess 是 promise 方法，Chrome 风格的调用方传回调
    // （Dark Reader 的 collectData 就是 new Promise(r => chrome.extension.isAllowedFileSchemeAccess(r))）。
    // 不过 cbObj 的话回调永远不被调用，等它的 Promise.all 会卡死，popup 空白。
    extension: cbObj(ext),
    permissions: cbObj(permissions)
  };
  if (!CFG.offscreen && mafari) chrome['mafari'] = { userscripts: cbObj(mafari.userscripts) };
  if (!CFG.offscreen && offscreen) chrome.offscreen = cbObj(offscreen);
  if (!CFG.offscreen && windows) chrome.windows = cbObj(windows);
  if (!CFG.offscreen && idle) chrome.idle = cbObj(idle);
  if (!CFG.offscreen && bookmarks) chrome.bookmarks = cbObj(bookmarks);
  if (!CFG.offscreen && downloads) chrome.downloads = cbObj(downloads);
  if (!CFG.offscreen && cookies) chrome.cookies = cbObj(cookies);
  if (!CFG.offscreen && history) chrome.history = cbObj(history);
  if (!CFG.offscreen && clipboard) chrome.clipboard = cbObj(clipboard);
  if (!CFG.offscreen && notifications) chrome.notifications = cbObj(notifications);
  if (!CFG.offscreen && alarms) chrome.alarms = cbObj(alarms);
  if (!CFG.offscreen && tabs) chrome.tabs = cbObj(tabs);
  if (!CFG.offscreen && scripting) chrome.scripting = cbObj(scripting);
  if (!CFG.offscreen && webNavigation) chrome.webNavigation = cbObj(webNavigation);
  if (!CFG.offscreen && webRequest) chrome.webRequest = cbObj(webRequest);
  if (!CFG.offscreen && declarativeNetRequest) chrome.declarativeNetRequest = cbObj(declarativeNetRequest);
  if (!CFG.offscreen && userScripts) chrome.userScripts = cbObj(userScripts);
  if (!CFG.offscreen && action) chrome.action = cbObj(action);
  if (!CFG.offscreen && browserAction) chrome.browserAction = cbObj(browserAction);
  if (!CFG.offscreen && contextMenus) { chrome.contextMenus = cbObj(contextMenus); chrome.contextMenus.create = contextMenus.create; chrome.menus = chrome.contextMenus; }

  // 给每个命名空间套上「不存在的 onXxx 事件 → 惰性事件」兜底（storage.local 等子对象的 onChanged 本来就有，不用管）
  [browser, chrome].forEach(function (root) {
    OK(root).forEach(function (ns) { if (root[ns] && typeof root[ns] === 'object') root[ns] = withInertEvents(root[ns], ns); });
  });
  // 后台页起来后留一行启动摘要：诊断日志里看到它，说明新垫片已加载、后台页脚本开始执行；之后没有 Uncaught 错误才说明启动成功
  if (CFG.background) {
    try { win.console.info('[mafari] shim ready: ' + OK(browser).join(',')); } catch (e) { }
    // 后台页没注册 runtime.onMessage = 脚本在注册前抛错 / 卡住：其它页面的 runtime.sendMessage 会一直 NO_RECEIVER（Floccus MV3 就是这样转圈）
    ST(function () {
      if (!(L['runtime.onMessage'] || []).length) {
        cerr('[mafari] 后台页启动 3 秒后仍没有 runtime.onMessage 监听器：脚本多半在注册之前抛错或卡住（看上面的 Uncaught / importScripts failed），其它页面的 runtime.sendMessage 会一直 NO_RECEIVER');
      }
    }, 3000);
  }

  // 拓展自己的页面（后台页 / popup / options）：CFG.page 为 true 时把 browser / chrome 挂到 window 上。
  // 所有上下文都握手：原生由此登记当前标签里的 content script，供 tabs.sendMessage 定向投递。
  // content script 里不挂全局：它们只通过参数拿到 browser / chrome。
  // 隔离世界（CFG.isolatedWorld）里也挂全局：页面世界看不到这个世界的 window，scripting.executeScript 注入的文件 / func 才能用上 chrome.*
  if (CFG.page || CFG.isolatedWorld) {
    var defGlobal = function (k, v) {
      try { Object.defineProperty(win, k, { value: v, configurable: true, writable: true, enumerable: false }); } catch (e) { }
    };
    defGlobal('browser', browser);
    defGlobal('chrome', chrome);
  }
  if (CFG.page || CFG.tabId !== undefined && CFG.tabId !== null) {
    rpc('runtime.hello', []).catch(function () { });
  }

  // 拓展页里的跨域 fetch：Chrome / Firefox 里拓展凭 host 权限可以绕过 CORS，WebView 里这些页面只是普通网页，会被 CORS 拦下
  // （例如后台页请求 api.xxx.com 的预检不过）。指向别的 origin 的 http(s) 请求转给原生（mafari.net.fetch）代发，
  // 原生按拓展已获授权的 host 放行。没有权限 / 请求体类型不支持（FormData 等）/ 原生不可用时原样走原生 fetch，行为与改动前一致。
  // 不带 cookie（等同 credentials: omit）；XMLHttpRequest 不在此列。
  if (CFG.page && !MAIN && typeof win.fetch === 'function' && typeof win.Response === 'function') {
    var pageNativeFetch = win.fetch;
    var netB64 = function (buf) {
      var u8 = new Uint8Array(buf), out = '', i;
      for (i = 0; i < u8.length; i += 0x8000) out += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
      return win.btoa(out);
    };
    // 返回 Promise<{ bytes: ArrayBuffer|null, type: string|null }>；不支持的请求体类型 resolve(undefined) → 走原生 fetch
    var netBody = function (b) {
      if (b === undefined || b === null) return P.resolve({ bytes: null, type: null });
      if (typeof b === 'string') return P.resolve({ bytes: new win.TextEncoder().encode(b).buffer, type: 'text/plain;charset=UTF-8' });
      if (win.URLSearchParams && b instanceof win.URLSearchParams) return P.resolve({ bytes: new win.TextEncoder().encode(b.toString()).buffer, type: 'application/x-www-form-urlencoded;charset=UTF-8' });
      if (b instanceof win.ArrayBuffer) return P.resolve({ bytes: b, type: null });
      if (win.ArrayBuffer.isView && win.ArrayBuffer.isView(b)) return P.resolve({ bytes: b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength), type: null });
      if (win.Blob && b instanceof win.Blob) return b.arrayBuffer().then(function (bytes) { return { bytes: bytes, type: b.type || null }; });
      return P.resolve(undefined);
    };
    win.fetch = function (input, init) {
      var self = this, args = arguments;
      var viaNative = function () { return pageNativeFetch.apply(self, args); };
      var isReq = typeof win.Request === 'function' && input instanceof win.Request;
      var abs;
      try { abs = new U(isReq ? input.url : String(input), win.location && win.location.href); } catch (e) { return viaNative(); }
      var proto = abs.protocol;
      if ((proto !== 'http:' && proto !== 'https:') || abs.origin === CFG.origin || (win.location && abs.origin === win.location.origin)) return viaNative();
      init = init || {};
      var method = String(init.method || (isReq && input.method) || 'GET').toUpperCase();
      var signal = init.signal || (isReq ? input.signal : null);
      if (signal && signal.aborted) return P.reject(new win.DOMException('The operation was aborted.', 'AbortError'));
      var headers = [];
      try { new win.Headers(init.headers || (isReq ? input.headers : undefined)).forEach(function (v, k) { headers.push([k, v]); }); } catch (e) { return viaNative(); }
      var bodySrc = init.body !== undefined ? P.resolve(init.body) : (isReq && method !== 'GET' && method !== 'HEAD' ? input.clone().arrayBuffer() : P.resolve(null));
      var call = bodySrc.then(netBody).then(function (b) {
        if (b === undefined) return { native: true };
        if (b.type && !headers.some(function (h) { return h[0].toLowerCase() === 'content-type'; })) headers.push(['content-type', b.type]);
        return rpc('mafari.net.fetch', [{
          url: abs.href, method: method, headers: headers, redirect: init.redirect || (isReq && input.redirect) || 'follow',
          bodyB64: b.bytes && b.bytes.byteLength ? netB64(b.bytes) : null
        }]).then(function (r) { return { r: r }; }, function (e) {
          var c = e && e.code;
          if (c === 'DENIED_PERMISSION' || c === 'UNSUPPORTED' || c === 'UNKNOWN_API') return { native: true };
          throw new TypeError(c === 'NETWORK' ? 'Failed to fetch' : 'Failed to fetch: ' + (e && e.message));
        });
      }).then(function (o) {
        if (o.native) return viaNative();
        var r = o.r, bin = win.atob(r.b64 || ''), bytes = new Uint8Array(bin.length), i;
        for (i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        var resp = new win.Response(r.status === 204 || r.status === 205 || r.status === 304 ? null : bytes, { status: r.status, statusText: r.statusText || '', headers: r.headers || [] });
        try { Object.defineProperty(resp, 'url', { value: r.url || abs.href }); } catch (e) { }
        return resp;
      });
      if (!signal || typeof signal.addEventListener !== 'function') return call;
      return new P(function (resolve, reject) {
        signal.addEventListener('abort', function () { reject(new win.DOMException('The operation was aborted.', 'AbortError')); }, { once: true });
        call.then(resolve, reject);
      });
    };
  }

  // ───────────── 匹配与执行 ─────────────
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

  // 原生 document_end（只在隔离世界，CFG.nativeEnd 由宿主设置）：宿主在这个世界里另注册了一段
  // INJECTION_EVENT_DOCUMENT_END（对应 DOMContentLoaded）脚本，到点调用这里挂的世界私有钩子。
  // 钩子没被调用时（注册失败 / 事件没来）靠 window.load 兜底，保证条目一定会跑；文档已过了 loading 阶段则立即触发。
  var endFired = false, endQueue = [];
  function fireEnd() {
    if (endFired) return;
    endFired = true;
    try { delete win[CFG.nativeEnd]; } catch (e) { }
    var q = endQueue;
    endQueue = [];
    q.forEach(function (f) { try { f(); } catch (e) { cerr(e); } });
  }
  if (CFG.nativeEnd) {
    try { Object.defineProperty(win, CFG.nativeEnd, { value: fireEnd, configurable: true }); } catch (e) { }
    if (doc.readyState !== 'loading') fireEnd(); else winAdd('load', fireEnd, { once: true });
  }
  function whenEnd(f) {
    if (!CFG.nativeEnd) {
      // 页面世界 / 旧路径：自己监听 DOMContentLoaded
      if (doc.readyState !== 'loading') f(); else docAdd('DOMContentLoaded', f, { once: true });
      return;
    }
    if (endFired) f(); else endQueue.push(f);
  }

  function runWhen(at, fn) {
    if (at === 'document_start') { fn(); return; }
    if (at === 'document_end') { whenEnd(fn); return; }
    // document_idle：document_end 之后，load 或 200ms 先到者
    whenEnd(function () {
      var ran = false;
      var go = function () { if (!ran) { ran = true; fn(); } };
      if (doc.readyState === 'complete') { ST(go, 0); return; }
      winAdd('load', go, { once: true });
      ST(go, 200);
    });
  }

  // 模块加载器：content script 里的 import(...) 被改写成它。页面 CSP 会拦截 import(拓展 origin 的 URL)，所以拓展内的模块
  // 不走网络，而是由原生送进来，有两种方式：
  //   en.mods ：随条目内联（路径 → async function(exp, req, url, imp)），用于没有桥的 MAIN 世界等；
  //   en.lazy ：只有路径；首次用到时经桥请求 mafari.module.load，原生用隔离世界的 executeJavaScript 调
  //             __mafariModDefine(path, fn) 把模块函数送进来（同样不受页面 CSP 约束），源码不占每个页面的注入脚本。
  // 都没有的（外部 URL 等）才回退原生 import()。模块每个世界只执行一次（modCache）。
  var modFns = {}, modLazy = {}, modWait = {}, modCache = {};
  if (CFG.isolatedWorld) {
    // 只在隔离世界里挂：页面世界看不到这个世界的全局
    globalThis.__mafariModDefine = function (path, fn) { modFns[path] = fn; };
  }
  // content script 里的 fetch 受页面 CSP 的 connect-src 约束（github、browsercheck.net 等站点会拦 https://<拓展>.ext.mafari.internal/...），
  // content script 读自己的 default_config.json 之类就会失败。指向拓展 origin 的 GET 改走桥读文件（仅 web_accessible 资源），
  // 其余请求原样交给原生 fetch。隔离世界和旧的页面世界路径（不支持隔离世界 / 首次加载走旧路径）都要装；MAIN 世界没有桥，不装。
  if (CFG.isolatedWorld || (!CFG.page && !MAIN)) {
    var nativeFetch = globalThis.fetch;
    if (typeof nativeFetch === 'function') {
      globalThis.fetch = function (input, init) {
        try {
          var url = typeof input === 'string' ? input : (input && input.url) || String(input);
          var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
          var abs = new U(url, win.location && win.location.href).href;
          if (method === 'GET' && abs.indexOf(CFG.origin + '/') === 0) {
            var path = abs.slice(CFG.origin.length + 1).replace(/[?#].*$/, '');
            return rpc('mafari.resource.read', [path]).then(function (r) {
              if (!r || r.status !== 200) return new Response(null, { status: (r && r.status) || 404 });
              var bin = atob(r.b64), bytes = new Uint8Array(bin.length);
              for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              return new Response(bytes, { status: 200, headers: { 'content-type': r.mime || 'application/octet-stream' } });
            });
          }
        } catch (e) { /* 解析失败就按原样请求 */ }
        return nativeFetch.apply(this, arguments);
      };
    }
  }
  function makeImporter(en) {
    var base = CFG.origin + '/';
    var own = Object.prototype.hasOwnProperty;
    var mods = en.mods || {};
    (en.lazy || []).forEach(function (p) { modLazy[p] = 1; });
    function norm(from, rel) {
      var parts = rel.charAt(0) === '/' ? [] : from.split('/').slice(0, -1);
      rel.split('/').forEach(function (seg) {
        if (seg === '' || seg === '.') return;
        if (seg === '..') { if (parts.length) parts.pop(); } else parts.push(seg);
      });
      return parts.join('/');
    }
    function toPath(spec) {
      var s = String(spec).replace(/[?#].*$/, '');
      if (s.indexOf(base) === 0) return s.slice(base.length);
      if (/^[a-z][a-z0-9+.-]*:/i.test(s) || s.indexOf('//') === 0) return null;
      return norm('', s);
    }
    function fnFor(path) {
      if (own.call(mods, path)) return P.resolve(mods[path]);
      if (own.call(modFns, path)) return P.resolve(modFns[path]);
      if (!own.call(modLazy, path)) return null;
      return modWait[path] || (modWait[path] = rpc('mafari.module.load', [path]).then(function () {
        if (!own.call(modFns, path)) throw new E('module not delivered: ' + path);
        return modFns[path];
      }, function (e) { delete modWait[path]; throw e; }));
    }
    function load(path) {
      if (own.call(modCache, path)) return modCache[path];
      var fp = fnFor(path);
      if (!fp) return null;
      return (modCache[path] = fp.then(function (fn) {
        var exp = {};
        return P.resolve(fn(exp, function (p) {
          var l = load(p);
          return l || P.reject(new E('module not found: ' + p));
        }, base + path, imp)).then(function () { return exp; });
      }));
    }
    function imp(spec) {
      var p = toPath(spec);
      var l = p && load(p);
      if (l) return l;
      return import(String(spec));
    }
    return imp;
  }

  function runEntries(br, ch) {
    var href = win.location.href;
    var isTop = (win.top === win);
    ENTRIES.forEach(function (en) {
      if (!en.all && !isTop) return;
      if (!anyMatch(en.m, href) || anyMatch(en.x, href)) return;
      runWhen(en.at, function () {
        en.css.forEach(function (c) { try { insertCss(c); } catch (e) { cerr(e); } });
        var imp = (en.mods || en.lazy) ? makeImporter(en) : function (u) { return import(String(u)); };
        en.js.forEach(function (fn) { try { fn(br, ch, imp); } catch (e) { cerr(e); } });
      });
    });
  }
  runEntries(browser, chrome);
}
