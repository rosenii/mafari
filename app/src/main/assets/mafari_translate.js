/*
 * Mafari 页内翻译：遍历页面文本节点 → 分批交给原生（由用户选的翻译源翻译）→ 原地替换文本。WebView / Gecko 共用同一份文件。
 *
 * 为什么翻译请求不在页面里发：页面有 CORS / CSP 限制，也不该把翻译源的 token / key 暴露给页面脚本；
 * 所以页面脚本只负责「取文本、贴译文、切换原文 / 译文」，真正的网络请求在原生侧（见 translate/TranslateService）。
 *
 * 通道（transport）：页面 → 原生 发 JSON 对象
 *   { t:'req',   id, texts:[...] }                       请求翻译一批文本
 *   { t:'state', phase, total, done, err? }              进度 / 状态（phase: idle | translating | done | error | original）
 * 原生 → 页面 调 window.__mafariTr.recv(msg)
 *   { t:'res', id, texts:[...] }                          译文（与请求等长同序）
 *   { t:'err', id, kind, detail }                         这一批失败
 *
 * - WebView：原生用 evaluateJavascript 注入本文件（幂等），随后调 attachWebView() / start({target})；
 *   页面 → 原生走 addJavascriptInterface 的 window.MafariTr.post(json)，原生 → 页面走 evaluateJavascript 调 recv。
 * - Gecko：本文件原样放在内置拓展 mafari-translate-ext（assets/mafari-translate-ext/translate.js）里当内容脚本；
 *   文末的「Gecko 胶水」经 runtime.connectNative('mafariTranslate') 收发。
 *   （两份必须逐字一致，TranslateCoreSyncTest 会校验。）
 *
 * 只改文本节点的 nodeValue 和少量属性（placeholder / title / alt），不动 DOM 结构，所以页面脚本、事件、样式都不受影响；
 * 每个被改的节点都记着原文，随时可以切回原文或完全还原。
 */
(function () {
  'use strict';
  var VERSION = 1;
  if (window.__mafariTr && window.__mafariTr.v === VERSION) return;

  // ------------------------------------------------------------------ 常量

  var MAX_NODES = 4000;          // 一次最多处理的文本单元（长页面只译前面这些；之后新增的内容另算）
  var BATCH_TEXTS = 20;          // 一批最多条数
  var BATCH_CHARS = 3000;        // 一批最多字数
  var MAX_INFLIGHT = 3;          // 同时在途的批数
  var MUTATION_DELAY = 350;      // 页面动态内容的去抖时间
  var ATTRS = ['placeholder', 'title', 'alt'];

  // 里面的文本不翻译：脚本 / 样式 / 代码 / 输入框内容 / 嵌入物
  var SKIP_TAGS = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, TEXTAREA: 1, CODE: 1, PRE: 1, KBD: 1, SAMP: 1,
    SVG: 1, MATH: 1, CANVAS: 1, IFRAME: 1, OBJECT: 1, EMBED: 1, AUDIO: 1, VIDEO: 1, OPTION: 0, HEAD: 1
  };

  var HAS_LETTER;
  try { HAS_LETTER = new RegExp('\\p{L}', 'u'); } catch (e) { HAS_LETTER = /[A-Za-zÀ-￿]/; }

  // ------------------------------------------------------------------ 状态

  var transport = null;
  var target = 'zh-CN';
  var phase = 'idle';            // idle | translating | done | error | original
  var errInfo = null;
  var recs = [];                 // 文本单元（文本节点或属性）
  var byNode = typeof WeakMap === 'function' ? new WeakMap() : null;
  var queue = [];                // 等待发送的 rec
  var inflight = {};             // id -> [rec]
  var inflightCount = 0;
  var nextId = 1;
  var observer = null;
  var mutTimer = 0;
  var pendingRoots = [];
  var titleRec = null;
  var gen = 0;                   // 每次 start / stop 递增：过期的响应直接丢弃
  var showingOriginal = false;

  // ------------------------------------------------------------------ 小工具

  function send(msg) {
    try { if (transport) transport(msg); } catch (e) {}
  }

  function reportState() {
    var done = 0;
    for (var i = 0; i < recs.length; i++) if (recs[i].tr !== null) done++;
    var m = { t: 'state', phase: phase, total: recs.length, done: done };
    if (errInfo) m.err = errInfo;
    send(m);
  }

  function setPhase(p) {
    if (phase === p && !errInfo) { reportState(); return; }
    phase = p;
    reportState();
  }

  /** 译文已经是目标语言的文字时没必要再翻：目标是中日韩，且文本里这类文字占多数 */
  function looksTarget(text) {
    var re;
    if (target.indexOf('zh') === 0) re = /[㐀-鿿豈-﫿]/g;
    else if (target === 'ja') re = /[぀-ヿ㐀-鿿]/g;
    else if (target === 'ko') re = /[가-힯ᄀ-ᇿ]/g;
    else return false;
    var m = text.match(re);
    if (!m) return false;
    var letters = 0;
    try { letters = (text.match(/\p{L}/gu) || []).length; } catch (e) { letters = text.length; }
    return letters > 0 && m.length / letters >= 0.6;
  }

  function translatable(text) {
    if (!text || text.length > 5000) return false;
    if (!HAS_LETTER.test(text)) return false;
    if (looksTarget(text)) return false;
    return true;
  }

  function skipElement(el) {
    for (var n = el; n && n.nodeType === 1; n = n.parentNode) {
      if (SKIP_TAGS[n.tagName.toUpperCase()] === 1) return true;
      if (n.isContentEditable === true) return true;
      var t = n.getAttribute && n.getAttribute('translate');
      if (t && t.toLowerCase() === 'no') return true;
      if (n.classList && n.classList.contains('notranslate')) return true;
      if (n.hasAttribute && n.hasAttribute('hidden')) return true;
    }
    return false;
  }

  function split(raw) {
    var m = /^(\s*)([\s\S]*?)(\s*)$/.exec(raw);
    return { lead: m[1], core: m[2].replace(/\s+/g, ' '), trail: m[3] };
  }

  // ------------------------------------------------------------------ 收集文本单元

  function addTextNode(node) {
    var parent = node.parentNode;
    if (!parent || parent.nodeType !== 1 || skipElement(parent)) return;
    var raw = node.nodeValue;
    var existing = byNode && byNode.get(node);
    if (existing) {
      // 已登记：只有页面自己改了文本才需要重新翻（我们自己写的译文不算）
      if (raw === existing.shown) return;
      if (raw === existing.orig) { existing.shown = raw; return; }
      existing.orig = raw;
      var s0 = split(raw);
      if (!translatable(s0.core)) { existing.tr = null; existing.skip = true; return; }
      existing.skip = false; existing.lead = s0.lead; existing.core = s0.core; existing.trail = s0.trail;
      existing.tr = null; existing.shown = raw;
      enqueue(existing);
      return;
    }
    var s = split(raw);
    if (!translatable(s.core)) return;
    var rec = { kind: 'text', node: node, orig: raw, shown: raw, lead: s.lead, core: s.core, trail: s.trail, tr: null, skip: false };
    recs.push(rec);
    if (byNode) byNode.set(node, rec);
    enqueue(rec);
  }

  function addAttrs(el) {
    if (skipElement(el)) return;
    for (var i = 0; i < ATTRS.length; i++) {
      var name = ATTRS[i];
      if (!el.hasAttribute(name)) continue;
      var raw = el.getAttribute(name);
      var s = split(raw);
      if (!translatable(s.core)) continue;
      // 属性单元按「元素 + 属性名」去重
      var key = '__mafariTr_' + name;
      if (el[key]) continue;
      var rec = { kind: 'attr', node: el, attr: name, orig: raw, shown: raw, lead: s.lead, core: s.core, trail: s.trail, tr: null, skip: false };
      el[key] = rec;
      recs.push(rec);
      enqueue(rec);
    }
  }

  function collect(root) {
    if (!root || recs.length >= MAX_NODES) return;
    if (root.nodeType === 3) { addTextNode(root); return; }
    if (root.nodeType !== 1 && root.nodeType !== 9 && root.nodeType !== 11) return;
    if (root.nodeType === 1 && skipElement(root)) return;
    var base = root.nodeType === 9 ? (root.body || root.documentElement) : root;
    if (!base) return;
    var walker = document.createTreeWalker(base, 1 | 4, null);   // SHOW_ELEMENT | SHOW_TEXT
    if (base.nodeType === 1) addAttrs(base);
    var n;
    while ((n = walker.nextNode())) {
      if (recs.length >= MAX_NODES) break;
      if (n.nodeType === 3) addTextNode(n);
      else addAttrs(n);
    }
  }

  function collectTitle() {
    var t = document.title;
    if (!t || titleRec) return;
    var s = split(t);
    if (!translatable(s.core)) return;
    titleRec = { kind: 'title', node: null, orig: t, shown: t, lead: s.lead, core: s.core, trail: s.trail, tr: null, skip: false };
    recs.push(titleRec);
    enqueue(titleRec);
  }

  // ------------------------------------------------------------------ 发送 / 应用

  function enqueue(rec) {
    rec.queued = true;
    queue.push(rec);
  }

  /** 离视口近的先译（用户马上要看的内容先出来） */
  function sortQueueByViewport() {
    var vh = window.innerHeight || 800;
    function dist(rec) {
      try {
        var el = rec.kind === 'text' ? rec.node.parentNode : rec.node;
        if (!el || !el.getBoundingClientRect) return 1e9;
        var r = el.getBoundingClientRect();
        if (r.bottom >= 0 && r.top <= vh) return 0;
        return r.top > vh ? r.top - vh : -r.bottom;
      } catch (e) { return 1e9; }
    }
    var keyed = queue.map(function (rec, i) { return { rec: rec, d: dist(rec), i: i }; });
    keyed.sort(function (a, b) { return a.d - b.d || a.i - b.i; });
    queue = keyed.map(function (k) { return k.rec; });
  }

  function pump() {
    if (phase !== 'translating') return;
    while (inflightCount < MAX_INFLIGHT && queue.length) {
      var batch = [];
      var chars = 0;
      while (queue.length && batch.length < BATCH_TEXTS) {
        var rec = queue[0];
        if (rec.skip || rec.tr !== null) { queue.shift(); rec.queued = false; continue; }
        if (batch.length && chars + rec.core.length > BATCH_CHARS) break;
        queue.shift();
        rec.queued = false;
        rec.sent = rec.orig;
        batch.push(rec);
        chars += rec.core.length;
      }
      if (!batch.length) break;
      var id = nextId++;
      inflight[id] = { recs: batch, gen: gen };
      inflightCount++;
      send({ t: 'req', id: id, texts: batch.map(function (r) { return r.core; }) });
    }
    if (!inflightCount && !queue.length && phase === 'translating') setPhase('done');
  }

  function apply(rec, text) {
    var full = rec.lead + text + rec.trail;
    rec.tr = text;
    rec.trFull = full;
    if (showingOriginal) return;
    write(rec, full);
  }

  function write(rec, value) {
    rec.shown = value;
    try {
      if (rec.kind === 'text') { if (rec.node.nodeValue !== value) rec.node.nodeValue = value; }
      else if (rec.kind === 'attr') rec.node.setAttribute(rec.attr, value);
      else if (rec.kind === 'title') document.title = value;
    } catch (e) {}
  }

  function recv(msg) {
    if (!msg || typeof msg !== 'object') return;
    var job = inflight[msg.id];
    if (!job) return;
    delete inflight[msg.id];
    inflightCount--;
    if (job.gen !== gen) { pump(); return; }
    if (msg.t === 'res' && msg.texts && msg.texts.length === job.recs.length) {
      for (var i = 0; i < job.recs.length; i++) {
        var rec = job.recs[i];
        // 等译文回来的这段时间页面又改了这个节点：丢掉这条，下一轮按新内容重译
        if (rec.sent !== rec.orig) continue;
        var cur = rec.kind === 'text' ? rec.node.nodeValue : rec.kind === 'attr' ? rec.node.getAttribute(rec.attr) : document.title;
        if (cur !== rec.orig && cur !== rec.shown) continue;
        apply(rec, String(msg.texts[i]));
      }
      reportState();
      pump();
    } else {
      // 这一批失败：放回队列头部以便「重试」，并停掉后续发送，等用户决定
      for (var j = job.recs.length - 1; j >= 0; j--) { job.recs[j].queued = true; queue.unshift(job.recs[j]); }
      errInfo = { kind: String(msg.kind || 'unknown'), detail: String(msg.detail || '') };
      phase = 'error';
      reportState();
    }
  }

  // ------------------------------------------------------------------ 动态内容

  function startObserver() {
    if (observer || typeof MutationObserver !== 'function') return;
    observer = new MutationObserver(function (list) {
      if (phase === 'original' || phase === 'idle') return;
      for (var i = 0; i < list.length; i++) {
        var m = list[i];
        if (m.type === 'characterData') pendingRoots.push(m.target);
        else if (m.type === 'childList') for (var j = 0; j < m.addedNodes.length; j++) pendingRoots.push(m.addedNodes[j]);
        else if (m.type === 'attributes') pendingRoots.push(m.target);
      }
      if (pendingRoots.length && !mutTimer) mutTimer = setTimeout(flushMutations, MUTATION_DELAY);
    });
    observer.observe(document.documentElement, {
      childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS
    });
  }

  function flushMutations() {
    mutTimer = 0;
    var roots = pendingRoots;
    pendingRoots = [];
    if (phase === 'original' || phase === 'idle') return;
    var before = queue.length;
    for (var i = 0; i < roots.length; i++) {
      var r = roots[i];
      if (!r || !r.isConnected) continue;
      if (r.nodeType === 1 && r.hasAttribute) {
        // 属性变化：重新登记
        for (var k = 0; k < ATTRS.length; k++) {
          var rec = r['__mafariTr_' + ATTRS[k]];
          if (rec && r.getAttribute(ATTRS[k]) !== rec.shown && r.getAttribute(ATTRS[k]) !== rec.orig) {
            rec.orig = r.getAttribute(ATTRS[k]); rec.shown = rec.orig;
            var s = split(rec.orig); rec.lead = s.lead; rec.core = s.core; rec.trail = s.trail;
            rec.tr = null; rec.skip = !translatable(s.core);
            if (!rec.skip && !rec.queued) enqueue(rec);
          }
        }
      }
      collect(r);
    }
    if (queue.length > before || queue.length) {
      if (phase === 'done') { errInfo = null; phase = 'translating'; }
      sortQueueByViewport();
      reportState();
      pump();
    }
  }

  function stopObserver() {
    if (observer) { observer.disconnect(); observer = null; }
    if (mutTimer) { clearTimeout(mutTimer); mutTimer = 0; }
    pendingRoots = [];
  }

  // ------------------------------------------------------------------ 对外接口

  function start(opts) {
    opts = opts || {};
    if (opts.target) target = String(opts.target);
    gen++;
    showingOriginal = false;
    errInfo = null;
    // 若已有登记（重试 / 换目标语言重新翻）：全部还原，从头来
    if (recs.length) restoreAll();
    recs = [];
    queue = [];
    inflight = {};
    inflightCount = 0;
    titleRec = null;
    if (byNode) byNode = new WeakMap();
    phase = 'translating';
    collectTitle();
    collect(document);
    if (!recs.length) { setPhase('done'); return true; }
    sortQueueByViewport();
    reportState();
    startObserver();
    pump();
    return true;
  }

  /** 出错后「重试」：只重发还没译好的，已译的保留 */
  function retry() {
    if (phase !== 'error') return false;
    errInfo = null;
    phase = 'translating';
    reportState();
    sortQueueByViewport();
    pump();
    return true;
  }

  function restoreAll() {
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (r.shown !== r.orig) write(r, r.orig);
    }
  }

  function showOriginal() {
    if (!recs.length || phase === 'idle') return false;
    showingOriginal = true;
    restoreAll();
    setPhase('original');
    return true;
  }

  function showTranslation() {
    if (phase !== 'original') return false;
    showingOriginal = false;
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (r.tr !== null) write(r, r.trFull);
    }
    // 切回原文期间新增的内容、以及没译完的
    errInfo = null;
    phase = 'translating';
    reportState();
    startObserver();
    sortQueueByViewport();
    pump();
    return true;
  }

  function stop() {
    gen++;
    stopObserver();
    restoreAll();
    recs = [];
    queue = [];
    inflight = {};
    inflightCount = 0;
    titleRec = null;
    showingOriginal = false;
    errInfo = null;
    if (byNode) byNode = new WeakMap();
    setPhase('idle');
    return true;
  }

  function status() {
    var done = 0;
    for (var i = 0; i < recs.length; i++) if (recs[i].tr !== null) done++;
    return { phase: phase, total: recs.length, done: done, target: target, err: errInfo };
  }

  function attachWebView() {
    transport = function (msg) {
      if (window.MafariTr && typeof window.MafariTr.post === 'function') window.MafariTr.post(JSON.stringify(msg));
    };
  }

  window.__mafariTr = {
    v: VERSION,
    start: start,
    retry: retry,
    stop: stop,
    showOriginal: showOriginal,
    showTranslation: showTranslation,
    status: status,
    recv: recv,
    setTransport: function (fn) { transport = fn; },
    attachWebView: attachWebView
  };

  // ------------------------------------------------------------------ Gecko 胶水（仅在内置拓展里运行）
  var isGeckoExt = false;
  try {
    isGeckoExt = typeof browser !== 'undefined' && browser.runtime && browser.runtime.id === 'translate@mafari.dev' &&
      typeof browser.runtime.connectNative === 'function';
  } catch (e) { isGeckoExt = false; }
  if (!isGeckoExt) return;

  var port = null;
  var everConnected = false;
  var tries = 0;

  function connect() {
    try {
      port = browser.runtime.connectNative('mafariTranslate');
      port.onMessage.addListener(function (m) {
        if (!m || typeof m !== 'object') return;
        everConnected = true;
        if (m.t === 'start') start({ target: m.target });
        else if (m.t === 'retry') retry();
        else if (m.t === 'original') showOriginal();
        else if (m.t === 'translation') showTranslation();
        else if (m.t === 'stop') stop();
        else if (m.t === 'res' || m.t === 'err') recv(m);
        else if (m.t === 'hi') reportState();
      });
      port.onDisconnect.addListener(function () {
        port = null;
        // 原生那边的会话委托可能晚于页面就绪：首次握手前断开就退避重连几次
        if (!everConnected && tries < 6) setTimeout(connect, 300 * Math.pow(2, tries++));
      });
    } catch (e) { port = null; }
  }

  transport = function (msg) { if (port) port.postMessage(msg); };
  connect();
})();
