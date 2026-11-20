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
  var DN = Date.now.bind(Date), T0 = DN();
  var docAdd = doc.addEventListener.bind(doc), winAdd = win.addEventListener.bind(win);
  var cerr = (win.console && win.console.error) ? win.console.error.bind(win.console) : function () { };

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
  var BUFFERED = { 'runtime.onStartup': 1, 'runtime.onInstalled': 1, 'runtime.onConnect': 1, 'alarms.onAlarm': 1, 'webNavigation.onBeforeNavigate': 1, 'webNavigation.onCommitted': 1, 'webNavigation.onCompleted': 1, 'webNavigation.onErrorOccurred': 1, 'webNavigation.onHistoryStateUpdated': 1, 'webNavigation.onReferenceFragmentUpdated': 1, 'contextMenus.onClicked': 1, 'notifications.onClicked': 1, 'notifications.onClosed': 1, 'bookmarks.onCreated': 1, 'bookmarks.onChanged': 1, 'bookmarks.onMoved': 1, 'bookmarks.onRemoved': 1, 'downloads.onCreated': 1, 'downloads.onErased': 1 };
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
    'tabs.onMoved': function (p) { return [['tabs.onMoved', [p.tabId, p.moveInfo]]]; },
    'permissions.onAdded': function (p) { return [['permissions.onAdded', [p]]]; },
    'permissions.onRemoved': function (p) { return [['permissions.onRemoved', [p]]]; },
    'webNavigation.onBeforeNavigate': function (p) { return [['webNavigation.onBeforeNavigate', [p]]]; },
    'webNavigation.onCommitted': function (p) { return [['webNavigation.onCommitted', [p]]]; },
    'webNavigation.onCompleted': function (p) { return [['webNavigation.onCompleted', [p]]]; },
    'webNavigation.onErrorOccurred': function (p) { return [['webNavigation.onErrorOccurred', [p]]]; },
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
        webRequestListeners[t[0].slice(11)].slice().forEach(function (x) { if ((t[1][0].listenerTokens || []).indexOf(x.token) < 0) return; try { x.fn.apply(null, [t[1][0]]); } catch (e) { cerr(e); } });
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
    deliverEvent('runtime.onConnect', [p]);
  }
  function receivePortMessage(m) {
    var p = ports[m.portId];
    if (p) p._message(m.message);
  }
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
    setUninstallURL: promised(function () { return P.resolve(); }),
    requestUpdateCheck: unsupported('runtime.requestUpdateCheck'),
    reload: unsupported('runtime.reload'),
    // 只有后台页自己能拿到自己的 window；别的页面在另一个 WebView 里，跨 WebView 拿不到
    getBackgroundPage: promised(function () {
      return CFG.background ? P.resolve(win) : unsupported('runtime.getBackgroundPage')();
    })
  };
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
      executeScript: unsupported('tabs.executeScript'),
      insertCSS: unsupported('tabs.insertCSS'),
      removeCSS: unsupported('tabs.removeCSS'),
      detectLanguage: unsupported('tabs.detectLanguage'),
      captureVisibleTab: unsupported('tabs.captureVisibleTab'),
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
      onHighlighted: inertEvent('tabs.onHighlighted', true),
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
        return rpc('scripting.insertCSS', [sObj(inj) ? sPick(inj, ['target', 'css', 'files']) : sn(inj)]);
      }),
      removeCSS: promised(function (inj) {
        return rpc('scripting.removeCSS', [sObj(inj) ? sPick(inj, ['target', 'css', 'files']) : sn(inj)]);
      }),
      registerContentScripts: promised(function (list) { return rpc('scripting.registerContentScripts', [sn(list)]); }),
      getRegisteredContentScripts: promised(function (filter) { return rpc('scripting.getRegisteredContentScripts', [sn(filter)]); }),
      updateContentScripts: promised(function (list) { return rpc('scripting.updateContentScripts', [sn(list)]); }),
      unregisterContentScripts: promised(function (filter) { return rpc('scripting.unregisterContentScripts', [sn(filter)]); })
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
    var webRequestListeners = { onBeforeRequest: [], onCompleted: [], onErrorOccurred: [] };
    function makeWebRequestEvent(eventName) {
      return {
        addListener: function (fn, filter, extraInfoSpec) {
          if (typeof fn !== 'function') return;
          var token = 'wr-' + (webRequestSequence++);
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
      onCompleted: makeWebRequestEvent('onCompleted'),
      onErrorOccurred: makeWebRequestEvent('onErrorOccurred'),
      // 请求头 / 响应头 / 重定向 / 认证阶段 WebView 看不到（shouldInterceptRequest 只在发请求前给一次）：
      // 事件对象必须存在，否则 uBO / 篡改猴 / 沉浸式翻译的后台页在 `webRequest.onHeadersReceived.addListener` 这一行就抛 TypeError，
      // 后台页从此不响应任何消息。事件本身不会触发。
      onBeforeSendHeaders: inertEvent('webRequest.onBeforeSendHeaders'),
      onSendHeaders: inertEvent('webRequest.onSendHeaders'),
      onHeadersReceived: inertEvent('webRequest.onHeadersReceived'),
      onAuthRequired: inertEvent('webRequest.onAuthRequired'),
      onResponseStarted: inertEvent('webRequest.onResponseStarted'),
      onBeforeRedirect: inertEvent('webRequest.onBeforeRedirect'),
      handlerBehaviorChanged: promised(function () { return P.resolve(); }),
      MAX_HANDLER_BEHAVIOR_CHANGED_CALLS_PER_10_MINUTES: 20
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
      onDOMContentLoaded: inertEvent('webNavigation.onDOMContentLoaded'),
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
      set: promised(function (details) { return rpc('cookies.set', [details || {}]); }),
      remove: promised(function (details) { return rpc('cookies.remove', [details || {}]); }),
      // WebView 只有按 URL 的 CookieManager：没有全局枚举（getAll 故意不提供，让拓展特性检测后降级），也没有变更事件
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

  // 内容脚本（非 page）里 browser 只有 runtime / storage / i18n / extension 这几个小命名空间，同样包一层
  var browser = { runtime: runtime, storage: storage, i18n: i18n, extension: ext, permissions: permissions };
  if (windows) browser.windows = windows;
  if (idle) browser.idle = idle;
  if (bookmarks) browser.bookmarks = bookmarks;
  if (downloads) browser.downloads = downloads;
  if (cookies) browser.cookies = cookies;
  if (history) browser.history = history;
  if (clipboard) browser.clipboard = clipboard;
  if (notifications) browser.notifications = notifications;
  if (alarms) browser.alarms = alarms;
  if (tabs) browser.tabs = tabs;
  if (scripting) browser.scripting = scripting;
  if (webNavigation) browser.webNavigation = webNavigation;
  if (webRequest) browser.webRequest = webRequest;
  if (action) browser.action = action;
  if (browserAction) browser.browserAction = browserAction;
  if (contextMenus) { browser.contextMenus = contextMenus; browser.menus = contextMenus; }

  var chromeRuntime = cbObj(runtime);
  Object.defineProperty(chromeRuntime, 'lastError', { get: function () { return lastError.v; }, enumerable: true });
  var chrome = {
    runtime: chromeRuntime,
    storage: {
      local: cbObj(storage.local), sync: cbObj(storage.sync), session: cbObj(storage.session), managed: cbObj(storage.managed),
      onChanged: storage.onChanged
    },
    i18n: cbObj(i18n),
    extension: ext,
    permissions: cbObj(permissions)
  };
  if (windows) chrome.windows = cbObj(windows);
  if (idle) chrome.idle = cbObj(idle);
  if (bookmarks) chrome.bookmarks = cbObj(bookmarks);
  if (downloads) chrome.downloads = cbObj(downloads);
  if (cookies) chrome.cookies = cbObj(cookies);
  if (history) chrome.history = cbObj(history);
  if (clipboard) chrome.clipboard = cbObj(clipboard);
  if (notifications) chrome.notifications = cbObj(notifications);
  if (alarms) chrome.alarms = cbObj(alarms);
  if (tabs) chrome.tabs = cbObj(tabs);
  if (scripting) chrome.scripting = cbObj(scripting);
  if (webNavigation) chrome.webNavigation = cbObj(webNavigation);
  if (webRequest) chrome.webRequest = cbObj(webRequest);
  if (action) chrome.action = cbObj(action);
  if (browserAction) chrome.browserAction = cbObj(browserAction);
  if (contextMenus) { chrome.contextMenus = cbObj(contextMenus); chrome.contextMenus.create = contextMenus.create; chrome.menus = chrome.contextMenus; }

  // 给每个命名空间套上「不存在的 onXxx 事件 → 惰性事件」兜底（storage.local 等子对象的 onChanged 本来就有，不用管）
  [browser, chrome].forEach(function (root) {
    OK(root).forEach(function (ns) { if (root[ns] && typeof root[ns] === 'object') root[ns] = withInertEvents(root[ns], ns); });
  });
  // 后台页起来后留一行启动摘要：诊断日志里看到它，说明新垫片已加载、后台页脚本开始执行；之后没有 Uncaught 错误才说明启动成功
  if (CFG.background) {
    try { win.console.info('[mafari] shim ready: ' + OK(browser).join(',')); } catch (e) { }
  }

  // 拓展自己的页面（后台页 / popup / options）：CFG.page 为 true 时把 browser / chrome 挂到 window 上。
  // 所有上下文都握手：原生由此登记当前标签里的 content script，供 tabs.sendMessage 定向投递。
  // content script 里不挂全局：它们只通过参数拿到 browser / chrome。
  if (CFG.page) {
    var defGlobal = function (k, v) {
      try { Object.defineProperty(win, k, { value: v, configurable: true, writable: true, enumerable: false }); } catch (e) { }
    };
    defGlobal('browser', browser);
    defGlobal('chrome', chrome);
  }
  if (CFG.page || CFG.tabId !== undefined && CFG.tabId !== null) {
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
