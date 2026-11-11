/*
 * Gecko 版「MafariGM」桥：提供与 GmBridge（@JavascriptInterface）同名同签名的同步接口，
 * 让 mafari_gm_runtime.js 原样运行。Kotlin（GeckoGm.buildSource）把文末的快照占位符替换成
 * { id, tok, values: { key: rawJsonString }, big: [key…] }，并把本表达式的值作为 __mafariBridge 传给运行时。
 *
 *  - 读值：同步读内联快照（只读引用，不复制）+ 本地覆盖层 ov（脚本自己的写入 / 其它标签广播来的变化）。
 *    超过内联阈值的大值不在快照里（键名列在 S.big），首次读取时经 getValue 协议异步拉取
 *  - xhr / cookie：私有通道 → 中继 → 后台页；结果回来存进表里，xhrPoll 取走（运行时本来就是轮询）
 *  - 通知 / 下载 / 开标签 / 菜单 / 剪贴板：私有通道 → 中继 → 原生端口；回调以 {t:'cb', fn, args} 回来
 *
 * 安全：MAIN 世界与页面共用，DOM 事件 / window.postMessage 页面都能监听和伪造，所以请求不走事件。
 * 首次真正用到桥时，用 window.postMessage 把一个 MessageChannel 的 port2 交给中继（中继在捕获阶段
 * stopImmediatePropagation，页面监听器看不到端口），之后所有流量只走这条页面拿不到的私有通道。
 * 通道接通后第一条消息是 bind{sid,tok}：中继交后台页校验（令牌 + 本框架 URL 在脚本匹配范围内），
 * 通过后该端口才能收发；令牌只在 bind 里出现一次，只存在于本闭包。
 * 本文件不是严格模式，不碰 browser.* （MAIN 世界没有）。
 *
 * 开销：惰性——脚本没真正运行（没匹配到本页）就不握手、不建通道、不排队；
 * 值层零复制；队列按字节限流；xhr 结果 / 挂起请求有 TTL 与数量上限。
 */
(function (S) {
  // 空桥：没有脚本 id 的框架（不应出现）返回 null——运行时 bridge 为空时整体走自带降级（mem / fetch 等），
  // 比返回缺方法的空对象更稳（不会出现 bridge 非空却没有某个方法）
  if (!S || typeof S.id !== 'string' || !S.id) return null;

  var W = window, D = document;
  // 在 document-start 抓住原生函数，页面之后改写 postMessage / JSON / MessageChannel 也不影响我们
  var postMsg = W.postMessage.bind(W);
  var MC = W.MessageChannel;
  var addWinListener = W.addEventListener.bind(W);
  var stringify = JSON.stringify.bind(JSON);
  var parse = JSON.parse.bind(JSON);
  var setT = W.setTimeout.bind(W);
  var clearT = W.clearTimeout.bind(W);
  var hasOwn = Object.prototype.hasOwnProperty;
  var CB_FN = /^__mafari(?:Dl|Ntf)Callback_[A-Za-z0-9_]{1,80}$/;

  var iid = Math.random().toString(36).slice(2) + Date.now().toString(36);

  // ------------------------------------------------------------------ 值：快照只读 + 覆盖层
  // base：Kotlin 内联的快照对象，只读引用（不复制：每个框架省一份全量）。
  // ov：覆盖层。string = 本地 / 广播来的新值；null = 已删除；STALE = 远端有值但还没拉（大值）。
  var base = S.values || {};
  var bigKeys = Object.create(null);
  (S.big || []).forEach(function (k) { bigKeys[k] = 1; });
  var ov = Object.create(null);
  var STALE = {};
  var fetching = Object.create(null); // key -> { cbs, stale, old, timer }
  var FETCH_TIMEOUT = 15000, MAX_FETCHING = 64, nfetch = 0;

  function look(k) { // string | null(不存在) | STALE
    if (k in ov) return ov[k];
    if (hasOwn.call(base, k)) return base[k];
    return bigKeys[k] ? STALE : null;
  }
  function keyList() {
    var out = [], seen = Object.create(null), k;
    for (k in base) if (hasOwn.call(base, k)) { seen[k] = 1; if (!(k in ov) || ov[k] !== null) out.push(k); }
    for (k in bigKeys) if (!seen[k]) { seen[k] = 1; if (!(k in ov) || ov[k] !== null) out.push(k); }
    for (k in ov) if (!seen[k] && ov[k] !== null) out.push(k);
    return out;
  }
  function finishFetch(k, timedOut) {
    var f = fetching[k];
    if (!f) return;
    delete fetching[k]; nfetch--;
    try { clearT(f.timer); } catch (e) {}
    // 广播已告知值变了、但新值没拉到：丢掉已知过期的旧值，下次读取重拉（本地写过的除外）
    if (timedOut && f.old && !f.stale) ov[k] = STALE;
    var cur = look(k);
    var v = cur === STALE ? null : cur;
    for (var i = 0; i < f.cbs.length; i++) { try { f.cbs[i](v); } catch (e) {} }
  }
  function fetchKey(k, cb, old) { // old：本地有旧值，但广播已告知它过期
    var f = fetching[k];
    if (!f) {
      if (nfetch >= MAX_FETCHING) { // 在拉的太多：这次不拉，标成待拉，下次读取再说
        if (old) ov[k] = STALE;
        if (cb) { try { cb(null); } catch (e) {} }
        return;
      }
      nfetch++;
      f = fetching[k] = { cbs: [], stale: false, old: false, timer: setT(function () { finishFetch(k, true); }, FETCH_TIMEOUT) };
      send({ t: 'getValue', k: k });
    }
    if (old) f.old = true;
    if (cb) f.cbs.push(cb);
  }
  function touched(k) { var f = fetching[k]; if (f) f.stale = true; } // 本地写过：迟到的拉取结果不得覆盖

  // ------------------------------------------------------------------ 握手 / 发送
  // 惰性：第一次 send / getValue 才握手。握手最多 3 次（页面刷 ready 也不会无限建通道）；
  // 收到中继经该通道回的 ack 后才算接通，之前的消息（已序列化的字符串）按字节限额排队。
  var port = null, queue = [], qBytes = 0, hellos = 0, asked = false, dead = false;
  var QUEUE_MAX_BYTES = 4 * 1024 * 1024, QUEUE_MAX_ITEMS = 2000, HELLO_MAX = 3;

  function onRes(raw) {
    var m;
    try { m = parse(raw); } catch (e) { return; }
    if (m && typeof m === 'object') handleRes(m);
  }
  function hello() {
    if (port || hellos >= HELLO_MAX) return;
    hellos++;
    if (hellos >= HELLO_MAX) { // 最后一次握手也没等到 ack：放弃，清掉排队的消息，之后的 send 直接丢弃
      setT(function () { if (!port) { dead = true; queue = []; qBytes = 0; } }, 10000);
    }
    try {
      var ch = new MC();
      var mine = ch.port1;
      mine.onmessage = function (ev) {
        var d = ev.data;
        if (d === 'ack') {
          if (port) { try { mine.close(); } catch (e) {} return; }
          port = mine;
          try { port.postMessage(stringify({ t: 'bind', sid: S.id, tok: S.tok })); } catch (e) {}
          flush();
          return;
        }
        if (port === mine && typeof d === 'string') onRes(d);
      };
      postMsg({ mafariGm: 'hi' }, '*', [ch.port2]);
    } catch (e) {}
  }
  function onReady(ev) {
    var d = ev && ev.data;
    if (ev.source === W && d && d.mafariGm === 'ready' && !port) hello();
  }
  function ensureConn() {
    if (asked) return;
    asked = true;
    addWinListener('message', onReady, true); // 中继晚到时靠它的 ready 再握一次
    hello();
  }
  function send(o) {
    var s;
    try { s = stringify(o); } catch (e) { return; }
    if (port) { try { port.postMessage(s); } catch (e) {} return; }
    if (dead) return;
    ensureConn();
    if (port) { try { port.postMessage(s); } catch (e) {} return; }
    if (queue.length < QUEUE_MAX_ITEMS && qBytes + s.length <= QUEUE_MAX_BYTES) { queue.push(s); qBytes += s.length; }
  }
  function flush() {
    var q = queue; queue = []; qBytes = 0;
    for (var i = 0; i < q.length; i++) { try { port.postMessage(q[i]); } catch (e) {} }
  }

  // ------------------------------------------------------------------ xhr 表（带 TTL 与上限）
  var XHR_RESULT_TTL = 60 * 1000;      // 结果回来后 60s 没被 poll 就淘汰（只发不 poll 的泄漏）
  var XHR_LIFE = 11 * 60 * 1000;       // 后台页最长 10 分钟超时，之后还没回就当丢了
  var XHR_MAX = 256;
  var xhrs = Object.create(null);      // reqId -> 起始时间
  var results = Object.create(null);   // reqId -> { json, at }
  var nx = 0, cookieCbs = Object.create(null), ncb = 0;

  function dropXhr(id) {
    delete results[id];
    if (id in xhrs) { delete xhrs[id]; nx--; }
  }
  function sweepXhr(now) {
    var id;
    for (id in results) if (now - results[id].at > XHR_RESULT_TTL) dropXhr(id);
    for (id in xhrs) if (!(id in results) && now - xhrs[id] > XHR_LIFE) dropXhr(id);
  }

  function handleRes(m) {
    switch (m.t) {
      case 'xhrRes':
        if (typeof m.json === 'string' && m.reqId in xhrs) results[m.reqId] = { json: m.json, at: Date.now() };
        break;
      case 'cookieRes': {
        var cb = cookieCbs[m.cbId];
        if (cb) { delete cookieCbs[m.cbId]; ncb--; try { cb(Array.isArray(m.arr) ? m.arr : []); } catch (e) {} }
        break;
      }
      case 'cb': // 对应 GmBridge.callJs：只允许调用运行时自己注册的一次性回调（__mafariDlCallback_* / __mafariNtfCallback_*）
        if (typeof m.fn === 'string' && CB_FN.test(m.fn)) {
          var f = W[m.fn];
          try { delete W[m.fn]; } catch (e) {} // 先摘掉再调：一次性，防重入 / 页面替换后重复触发
          if (typeof f === 'function') { try { f.apply(W, Array.isArray(m.args) ? m.args : []); } catch (e) {} }
        }
        break;
      case 'valRes': // getValue 的异步结果（大值）
        if (m.id === S.id && typeof m.k === 'string' && fetching[m.k]) {
          if (!fetching[m.k].stale) ov[m.k] = (typeof m.raw === 'string') ? m.raw : null;
          finishFetch(m.k);
        }
        break;
      case 'val': // 其它标签 / 框架 / WebView 标签写了同一个脚本的值：更新覆盖层，运行时的 1s 轮询会发现并触发监听
        if (m.id === S.id && m.src !== iid && typeof m.k === 'string') {
          var k = m.k;
          if (typeof m.raw === 'string') { ov[k] = m.raw; touched(k); }
          else if (m.big) { // 大值：广播不带内容。已有旧值就先留着并后台拉新，没有就标成待拉
            bigKeys[k] = 1;
            if (typeof look(k) === 'string') fetchKey(k, null, true); else ov[k] = STALE;
          } else { ov[k] = null; touched(k); }
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

  var bridge = {
    // —— 值 ——（缺失一律返回 null：运行时据此区分；存下的 JSON null 是字符串 "null"，不会混淆）
    getValue: function (id, k) {
      ensureConn(); // 要收其它标签的广播，必须接通
      k = String(k);
      var v = look(k);
      if (v === STALE) { fetchKey(k); return null; } // 同步 API 拿不到：先返回缺失并预取，之后的读取就有了
      return v;
    },
    getValueAsync: function (id, k, cb) { // 运行时的 GM.getValue（Promise）走这里，大值也能拿到
      ensureConn();
      k = String(k);
      var v = look(k);
      if (v !== STALE) { try { cb(v); } catch (e) {} return; }
      fetchKey(k, cb);
    },
    setValue: function (id, k, v) {
      k = String(k); v = String(v);
      ov[k] = v; touched(k);
      send({ t: 'set', k: k, v: v, src: iid });
    },
    deleteValue: function (id, k) {
      k = String(k);
      ov[k] = null; touched(k);
      send({ t: 'del', k: k, src: iid });
    },
    listValues: function () { ensureConn(); return stringify(keyList()); },
    getValues: function (id, namesJson) {
      ensureConn();
      var out = {}, names = [];
      try { names = parse(namesJson); } catch (e) {}
      if (!Array.isArray(names)) names = [];
      names.forEach(function (n) {
        n = String(n);
        var v = look(n);
        if (v === STALE) fetchKey(n); else if (v !== null) out[n] = v;
      });
      return stringify(out);
    },

    // —— 请求（后台页 fetch）——
    xhrStart: function (reqId, optionsJson, bodyB64) {
      reqId = String(reqId);
      var now = Date.now();
      sweepXhr(now); // 每次都清：n ≤ 256，成本可忽略；只发不 poll 的结果也能按 TTL 淘汰
      if (nx >= XHR_MAX) { // 仍然满：直接回一个错误结果，不再往后台堆
        xhrs[reqId] = now; nx++;
        results[reqId] = { json: stringify({ state: 'error', error: 'too many pending requests' }), at: now };
        return;
      }
      if (!(reqId in xhrs)) nx++;
      xhrs[reqId] = now;
      send({ t: 'xhr', reqId: reqId, opts: optionsJson, body: bodyB64 || '' });
    },
    xhrPoll: function (reqId) {
      var r = results[reqId];
      if (r === undefined) return null;
      dropXhr(reqId);
      return r.json;
    },
    xhrAbort: function (reqId) {
      dropXhr(reqId);
      send({ t: 'xhrAbort', reqId: reqId });
    },

    // —— Cookie ——
    cookies: function (url) { // 同步只能给非 HttpOnly 的本页 Cookie；完整列表走 cookieListAsync
      try { if (new URL(url, W.location.href).origin === W.location.origin) return D.cookie; } catch (e) {}
      return '';
    },
    cookieListAsync: function (url, cb) {
      if (ncb >= 64) { try { cb([]); } catch (e) {} return; }
      var cbId = 'ck' + Math.random().toString(36).slice(2);
      var timer = setT(function () { // 兜底：中继 / 后台页没回包时释放回调槽位
        if (cookieCbs[cbId]) { delete cookieCbs[cbId]; ncb--; try { cb([]); } catch (e) {} }
      }, 30000);
      cookieCbs[cbId] = function (arr) { try { clearT(timer); } catch (e) {} cb(arr); };
      ncb++;
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
