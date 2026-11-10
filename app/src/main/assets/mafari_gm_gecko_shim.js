/*
 * Gecko 版「MafariGM」桥：提供与 GmBridge（@JavascriptInterface）同名同签名的同步接口，
 * 让 mafari_gm_runtime.js 原样运行。Kotlin（GeckoGm.buildSource）把文末的快照占位符替换成
 * { id, values: { key: rawJsonString } }，并把本表达式的值作为 __mafariBridge 传给运行时。
 *
 *  - 读值：同步读内联快照（注册脚本时的值）；写值：先改本地快照（脚本立刻能读到自己写的），再异步交给原生
 *  - xhr / cookie：事件 → 中继 → 后台页；结果回来存进表里，xhrPoll 取走（运行时本来就是轮询）
 *  - 通知 / 下载 / 开标签 / 菜单 / 剪贴板：事件 → 中继 → 原生端口；回调以 {t:'cb', fn, args} 回来
 * 本文件不是严格模式，且只依赖 document 的事件，不碰 browser.* （MAIN 世界没有）。
 */
(function (S) {
  var W = window, D = document;
  var REQ = 'mafari-gm:req', RES = 'mafari-gm:res';
  // 在 document-start 抓住原生函数，页面之后改写 dispatchEvent / JSON 也不影响我们
  var dispatch = D.dispatchEvent.bind(D);
  var CE = W.CustomEvent;
  var stringify = JSON.stringify.bind(JSON);
  var parse = JSON.parse.bind(JSON);
  var SAFE = /^[A-Za-z0-9_]{1,80}$/;

  var iid = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var values = Object.create(null);
  Object.keys(S.values || {}).forEach(function (k) { values[k] = S.values[k]; });

  // 握手：同一 document-start 阶段 MAIN 脚本与中继谁先执行不保证。中继没就绪前的消息先排队，
  // 收到中继的 ready（它启动时广播一次，并回应我们的 hi）后再冲出去。
  var ready = false, queue = [];
  function raw(o) {
    try { dispatch(new CE(REQ, { detail: stringify(o) })); } catch (e) {}
  }
  function send(o) {
    if (ready) { raw(o); return; }
    if (queue.length < 2000) queue.push(o);
  }
  function flush() {
    ready = true;
    var q = queue; queue = [];
    for (var i = 0; i < q.length; i++) raw(q[i]);
  }

  var xhrs = Object.create(null), results = Object.create(null), cookieCbs = Object.create(null);

  D.addEventListener(RES, function (ev) {
    var m;
    try { m = parse(ev.detail); } catch (e) { return; }
    if (!m) return;
    switch (m.t) {
      case 'ready': if (!ready) flush(); break;
      case 'xhrRes':
        if (xhrs[m.reqId]) results[m.reqId] = m.json;
        break;
      case 'cookieRes': {
        var cb = cookieCbs[m.cbId];
        if (cb) { delete cookieCbs[m.cbId]; try { cb(m.arr || []); } catch (e) {} }
        break;
      }
      case 'cb': // 对应 GmBridge.callJs：一次性全局回调
        if (typeof m.fn === 'string' && SAFE.test(m.fn)) {
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
  }, true);

  function uuid() {
    try { if (W.crypto && W.crypto.randomUUID) return W.crypto.randomUUID(); } catch (e) {}
    return 'm' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  raw({ t: 'hi' }); // 若中继已就绪，它会回 ready

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
