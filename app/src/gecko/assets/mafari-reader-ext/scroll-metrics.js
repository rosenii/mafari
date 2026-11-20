/*
 * 回报「距页面底部还剩多少像素」（Gecko）。
 *
 * GeckoSession.ScrollDelegate 只给滚动位置，不给页面总高度；固定在底部的导航栏要在页面滑到底时把位置让出来，
 * 就需要知道离底部多远。这里复用阅读模式拓展的原生通道 mafariReader：
 *   内容脚本 → 原生：{ t:"scroll", r }，r = 距页面底部的设备像素（CSS 像素 × devicePixelRatio），封顶 CAP。
 * 远离底部时 r 恒为 CAP，不会产生消息；只有接近底部那一段才逐帧回报。
 */
(function () {
  'use strict';
  if (window.__mafariScrollMetrics) return;
  window.__mafariScrollMetrics = true;

  var CAP = 2000;
  var port = null;
  var everConnected = false;
  var tries = 0;
  var queued = false;
  var last = -2;

  function remaining() {
    var de = document.documentElement;
    var b = document.body;
    var h = Math.max(de ? de.scrollHeight : 0, b ? b.scrollHeight : 0);
    var y = window.pageYOffset || (de ? de.scrollTop : 0) || 0;
    var left = (h - (y + window.innerHeight)) * (window.devicePixelRatio || 1);
    return Math.min(CAP, Math.max(0, Math.round(left)));
  }

  // 页面自己的固定底栏（底部导航 / 工具栏）：悬浮三段式据此决定是否让出底部位置。判据与 PageBottomBar.kt 完全一致（见那里的注释），改一处要同时改另一处
  var lastDock = null;
  var lastDockAt = 0;
  function hasPaint(e, w) {
    var r = e.getBoundingClientRect();
    if (r.width < w * 0.6) return false;
    var cs = getComputedStyle(e);
    var bg = cs.backgroundColor || '';
    var m = bg.match(/rgba?\(([^)]+)\)/);
    if (m) {
      var p = m[1].split(',');
      if (p.length < 4 || parseFloat(p[3]) >= 0.3) return true;
    } else if (bg && bg !== 'transparent') {
      return true;
    }
    if (cs.backgroundImage && cs.backgroundImage !== 'none') return true;
    if (cs.boxShadow && cs.boxShadow !== 'none') return true;
    if (cs.backdropFilter && cs.backdropFilter !== 'none') return true;
    return parseFloat(cs.borderTopWidth) > 0 && cs.borderTopStyle !== 'none';
  }
  function isBar(e, w, h, painted) {
    var cs = getComputedStyle(e);
    var pos = cs.position;
    if (pos !== 'fixed' && !(pos === 'sticky' && cs.bottom !== 'auto')) return false;
    if (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.05) return false;
    if (!painted) return false;
    var r = e.getBoundingClientRect();
    if (r.width < w * 0.9 || r.left > w * 0.05 || r.right < w * 0.95) return false;
    if (r.height < 36 || r.height > Math.min(140, h * 0.22)) return false;
    if (r.bottom < h - 3 || r.bottom > h + 2) return false;
    var role = e.getAttribute('role');
    if (role === 'dialog' || role === 'alertdialog' || e.getAttribute('aria-modal') === 'true') return false;
    var tag = ((e.id || '') + ' ' + (typeof e.className === 'string' ? e.className : '')).toLowerCase();
    if (/cookie|consent|gdpr|snackbar|toast|modal|dialog|popup|smartbanner|app-?install|install-?app|open-?in-?app|advert|adsbygoogle/.test(tag)) return false;
    return e.querySelectorAll('a[href],button,[role=tab],[role=link],[role=button],input,textarea').length >= 2;
  }
  function hasBottomBar() {
    try {
      var w = window.innerWidth, h = window.innerHeight;
      if (!w || !h) return false;
      var y = h - 3;
      var st = document.elementsFromPoint(w * 0.5, y);
      for (var j = 0; j < st.length && j < 8; j++) {
        var e = st[j], painted = false;
        while (e && e.nodeType === 1) {
          if (!painted) painted = hasPaint(e, w);
          if (isBar(e, w, h, painted)) {
            var l = document.elementsFromPoint(w * 0.15, y), r = document.elementsFromPoint(w * 0.85, y);
            if (l.indexOf(e) >= 0 && r.indexOf(e) >= 0) return true;
          }
          e = e.parentElement;
        }
      }
    } catch (x) {}
    return false;
  }

  function postDock() {
    var now = Date.now();
    if (lastDock !== null && now - lastDockAt < 500) return;
    lastDockAt = now;
    var b = hasBottomBar();
    if (b === lastDock) return;
    lastDock = b;
    try { port.postMessage({ t: 'dock', b: b }); } catch (e) {}
  }

  function post() {
    queued = false;
    if (!port) return;
    postDock();
    var r = remaining();
    if (r === last) return;
    last = r;
    try { port.postMessage({ t: 'scroll', r: r }); } catch (e) {}
  }

  function schedule() {
    if (queued) return;
    queued = true;
    if (window.requestAnimationFrame) window.requestAnimationFrame(post);
    else setTimeout(post, 16);
  }

  function connect() {
    try {
      port = browser.runtime.connectNative('mafariReader');
      port.onMessage.addListener(function () {
        // 握手：原生收到连接后回一条 hi，之后补报一次当前位置
        everConnected = true;
        last = -2;
        lastDock = null;
        schedule();
      });
      port.onDisconnect.addListener(function () {
        port = null;
        // 原生那边的会话委托可能晚于页面就绪：首次握手前断开就退避重连几次
        if (!everConnected && tries < 6) setTimeout(connect, 300 * Math.pow(2, tries++));
      });
    } catch (e) { port = null; }
  }

  try {
    if (typeof browser === 'undefined' || !browser.runtime || browser.runtime.id !== 'reader@mafari.dev' ||
        typeof browser.runtime.connectNative !== 'function') return;
  } catch (e) { return; }

  connect();
  window.addEventListener('scroll', schedule, { passive: true });
  window.addEventListener('resize', schedule, { passive: true });
  window.addEventListener('load', schedule);
  window.addEventListener('pageshow', function () { last = -2; lastDock = null; schedule(); });
  // 懒加载 / 无限滚动会改变页面高度而不触发滚动：低频补报一次
  setInterval(schedule, 2000);
})();
