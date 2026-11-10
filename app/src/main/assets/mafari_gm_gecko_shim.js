/*
 * Gecko 版「MafariGM」桥：提供与 GmBridge（@JavascriptInterface）同名同签名的同步接口，
 * 让 mafari_gm_runtime.js 原样运行。Kotlin（GeckoGm.buildSource）把文末的快照占位符替换成
 * { id, values: { key: rawJsonString } }，并把本表达式的值作为 __mafariBridge 传给运行时。
 *
 *  - 读值：同步读内联快照（注册脚本时的值）；写值：先改本地快照（脚本立刻能读到自己写的），再异步交给原生
 *  - xhr / cookie：私有通道 → 中继 → 后台页；结果回来存进表里，xhrPoll 取走（运行时本来就是轮询）
 *  - 通知 / 下载 / 开标签 / 菜单 / 剪贴板：私有通道 → 中继 → 原生端口；回调以 {t:'cb', fn, args} 回来
 *
 * 安全：MAIN 世界与页面共用，DOM 事件 / window.postMessage 页面都能监听和伪造，所以请求不走事件。
 * 启动时（页面脚本还没跑）用 window.postMessage 把一个 MessageChannel 的 port2 交给中继，之后所有请求只走这条
 * 页面拿不到的私有通道；每条请求另带 (脚本 id, 能力令牌)，后台页 / 原生端逐条校验，令牌只存在于本闭包里。
 * 本文件不是严格模式，不碰 browser.* （MAIN 世界没有）。
 */
(function (S) {
  var W = window, D = document;
  // 在 document-start 抓住原生函数，页面之后改写 postMessage / JSON / MessageChannel 也不影响我们
  var postMsg = W.postMessage.bind(W);
  var MC = W.MessageChannel;
  var addWinListener = W.addEventListener.bind(W);
  var stringify = JSON.stringify.bind(JSON);
  var parse = JSON.parse.bind(JSON);
  var CB_FN = /^__mafari(?:Dl|Ntf)Callback_[A-Za-z0-9_]{1,80}$/;
  var MARK = 'mafariGm';

  var iid = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var values = Object.create(null);
  Object.keys(S.values || {}).forEach(function (k) { values[k] = S.values[k]; });

  // 握手：同一 document-start 阶段 MAIN 脚本与中继谁先执行不保证。
  // shim 先发 hi（带新 port2）；中继晚到时会广播 ready，shim 再发一次。收到中继经该通道回的 ack 后才算接通，
  // 之前的消息先排队。握手次数有上限，页面刷 ready 也不会无限建通道。
  var port = null, queue = [], hellos = 0;
  function onRes(raw) {
    var m;
    try { m = parse(raw); } catch (e) { return; }
    if (m) handleRes(m);
  }
  function hello() {
    if (port || hellos >= 8) return;
    hellos++;
    try {
      var ch = new MC();
      var mine = ch.port1;
      mine.onmessage = function (ev) {
        var d = ev.data;
        if (d === 'ack') { if (!port) { port = mine; flush(); } return; }
        if (port === mine && typeof d === 'string') onRes(d);
      };
      postMsg({ mafariGm: 'hi' }, '*', [ch.port2]);
    } catch (e) {}
  }
  function raw(o) {
    try { o.sid = S.id; o.tok = S.tok; port.postMessage(stringify(o)); } catch (e) {}
  }
  function send(o) {
    if (port) { raw(o); return; }
    if (queue.length < 2000) queue.push(o);
  }
  function flush() {
    var q = queue; queue = [];
    for (var i = 0; i < q.length; i++) raw(q[i]);
  }
  addWinListener('message', function (ev) {
    var d = ev && ev.data;
    if (ev.source === W && d && d.mafariGm === 'ready' && !port) hello();
  }, true);

  var xhrs = Object.create(null), results = Object.create(null), cookieCbs = Object.create(null);

  function handleRes(m) {
    switch (m.t) {
      case 'xhrRes':
        if (xhrs[m.reqId]) results[m.reqId] = m.json;
        break;
      case 'cookieRes': {
        var cb = cookieCbs[m.cbId];
        if (cb) { delete cookieCbs[m.cbId]; try { cb(m.arr || []); } catch (e) {} }
        break;
      }
      case 'cb': // 对应 GmBridge.callJs：只允许调用运行时自己注册的一次性回调（__mafariDlCallback_* / __mafariNtfCallback_*）
        if (typeof m.fn === 'string' && CB_FN.test(m.fn)) {
          try { var f = W[m.fn]; if (typeof f === 'function') f.apply(W, m.args || []); } catch (e) {}
        }
        break;
      case 'val': // 其它标签 / 框架 / WebView 标签写了同一个脚本的值：更新快照，运行时的 1s 轮询会发现并触发监听
        if (m.id === S.id && m.src !== iid) {
          if (m.raw === null || m.raw === undefined) delete values[m.k]; else values[m.k] = m.raw;
        }
        break;
      case 'menuRun': { // 所有 shim 都会收到，只执行一次
        if (W.__mafariMenuRunN === m.n) break;
        try { Object.defineProperty(W, '__mafariMenuRunN', { value: m.n, configurable: true, writable: true }); } catch (e) {}
        try { if (typeof W.__mafariMenuRun === 'function') W.__mafariMenuRun(String(m.cid)); } catch (e) {}
        break;
      }
    }
  }

  function uuid() {
    try { if (W.crypto && W.crypto.randomUUID) return W.crypto.randomUUID(); } catch (e) {}
    return 'm' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  hello(); // 中继若已就绪会 ack；没就绪则等它的 ready 再来一次

  var bridge = {
    // —— 值 ——
    getValue: function (id, k) { var v = values[k]; return v === undefined ? null : v; },
    setValue: function (id, k, v) { values[k] = v; send({ t: 'set', id: S.id, k: k, v: v, src: iid }); },
    deleteValue: function (id, k) { delete values[k]; send({ t: 'del', id: S.id, k: k, src: iid }); },
    listValues: function () { return stringify(Object.keys(values)); },
    getValues: function (id, namesJson) {
      var out = {}, names = [];
      try { names = parse(namesJson); } catch (e) {}
      names.forEach(function (n) { if (values[n] !== undefined) out[n] = values[n]; });
      return stringify(out);
    },

    // —— 请求（后台页 fetch）——
    xhrStart: function (reqId, optionsJson, bodyB64) {
      xhrs[reqId] = 1;
      send({ t: 'xhr', reqId: reqId, opts: optionsJson, body: bodyB64 || '' });
    },
    xhrPoll: function (reqId) {
      var r = results[reqId];
      if (r === undefined) return null;
      delete results[reqId]; delete xhrs[reqId];
      return r;
    },
    xhrAbort: function (reqId) {
      delete results[reqId]; delete xhrs[reqId];
      send({ t: 'xhrAbort', reqId: reqId });
    },

    // —— Cookie ——
    cookies: function (url) { // 同步只能给非 HttpOnly 的本页 Cookie；完整列表走 cookieListAsync
      try { if (new URL(url, W.location.href).origin === W.location.origin) return D.cookie; } catch (e) {}
      return '';
    },
    cookieListAsync: function (url, cb) {
      var cbId = 'ck' + Math.random().toString(36).slice(2);
      cookieCbs[cbId] = cb;
      send({ t: 'cookieList', url: url, cbId: cbId });
    },
    cookieSet: function (json) {
      var o; try { o = parse(json); } catch (e) { return; }
      send({ t: 'cookieSet', opts: o });
    },

    // —— 原生能力 ——
    toast: function (msg) { send({ t: 'toast', msg: String(msg) }); },
    copy: function (text) { send({ t: 'copy', text: String(text) }); },
    copyHtml: function (html) { send({ t: 'copyHtml', html: String(html) }); },
    openTab: function (url, optsJson) {
      var id = uuid();
      send({ t: 'openTab', id: id, url: String(url), opts: optsJson || '{}' });
      return id;
    },
    closeTab: function (id) { send({ t: 'closeTab', id: String(id) }); },
    download: function (url, name) { send({ t: 'download', url: String(url), name: String(name || '') }); },
    downloadAsync: function (url, name, cbId) {
      send({ t: 'download', url: String(url), name: String(name || ''), cbId: String(cbId) });
    },
    notification: function (json, cbId) { send({ t: 'notify', json: json, cbId: String(cbId) }); },

    ran: function (id) { if (W.top === W.self) send({ t: 'ran', id: String(id) }); },

    // —— 菜单：运行时在注册 / 注销命令后调用，仅主框架上报 ——
    menuChanged: function () {
      try {
        if (W.top !== W.self) return;
        var list = typeof W.__mafariMenuList === 'function' ? W.__mafariMenuList() : '[]';
        send({ t: 'menu', list: list });
      } catch (e) {}
    }
    // 未实现（运行时有降级）：streamOpen/Write/Close/Abort、createBlobUrl、cookieList（同步版）
  };
  return bridge;
})(__MAFARI_SNAPSHOT__)
