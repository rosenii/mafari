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

  // 页面自己的固定底栏（底部导航 / 工具栏）：悬浮三段式据此决定是否让出底部位置。判据与 PageBottomBar.kt 一致
  var lastDock = null;
  var lastDockAt = 0;
  function hasBottomBar() {
    try {
      var w = window.innerWidth, h = window.innerHeight;
      if (!w || !h) return false;
      var xs = [w * 0.2, w * 0.5, w * 0.8];
      for (var i = 0; i < xs.length; i++) {
        var st = document.elementsFromPoint(xs[i], h - 3);
        for (var j = 0; j < st.length; j++) {
          var e = st[j];
          while (e && e.nodeType === 1) {
            var cs = getComputedStyle(e);
            if (cs.position === 'fixed' || cs.position === 'sticky') {
              var r = e.getBoundingClientRect();
              if (r.width >= w * 0.6 && r.height >= 28 && r.height <= Math.min(200, h * 0.3) &&
                  r.bottom >= h - 6 && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.05) return true;
            }
            e = e.parentElement;
          }
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
