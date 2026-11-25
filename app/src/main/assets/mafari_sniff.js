/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

/*
 * 视频嗅探的页面侧补充（只在顶层框架运行；WebView 的 shouldInterceptRequest 已经能按地址后缀抓到大多数视频，
 * 这里补三种它看不到的：没有后缀的地址（靠响应头 Content-Type 判断）、<video> 实际在播的地址、fetch / XHR 拿到的 HLS）。
 * 另外补两类「没点播放就能拿到」的来源：页面里已经写好的地址（<video> / <source> / og:video / 内联脚本 / 苹果 CMS 的 player_aaaa 配置），
 * 和接口返回的小 JSON 里带的地址（很多站点点播放前就把播放接口请求完了）。
 * 只读不改：不改请求、不改响应；响应只有 JSON 才读文本（上限 200KB，只找视频地址）；报告走单向消息桥 MafariSniff（原生只受理主框架 http(s) 来源）。
 */
(function () {
  'use strict';
  try {
    if (window.top !== window || !window.MafariSniff || typeof window.MafariSniff.postMessage !== 'function') return;
    var bridge = window.MafariSniff;
    var seen = Object.create(null);
    var count = 0;
    var CT = /mpegurl|^video\//i;
    var SEG = /^video\/(mp2t|mpeg2t|iso\.segment|x-mpegts)/i;

    // 同一地址可以多次报告（先报地址，之后 <video> 的时长 / 是否预览 / 是否用户点播陆续补上）：
    // 只在信息有变化时再发，每个地址最多 6 次，总量有上限
    var sig = Object.create(null);
    var sent = Object.create(null);
    function report(u, ct, len, el, extra) {
      try {
        if (typeof u !== 'string' || u.length > 3500 || !/^https?:/i.test(u)) return;
        extra = extra || {};
        var s = (extra.d === undefined ? '' : extra.d) + '|' + (extra.pv === undefined ? '' : extra.pv) + '|' + (extra.ua ? 1 : 0);
        if (sig[u] === s || (sent[u] || 0) >= 6) return;
        if (!sent[u]) { if (count >= 40) return; count++; }
        sig[u] = s;
        sent[u] = (sent[u] || 0) + 1;
        var msg = { u: u, ct: ct || '', len: len >= 0 ? len : -1, el: !!el };
        if (extra.d !== undefined) msg.d = extra.d;
        if (extra.pv !== undefined) msg.pv = extra.pv;
        if (extra.ua) msg.ua = 1;
        bridge.postMessage(JSON.stringify(msg));
      } catch (e) {}
    }

    // 用户刚点过 / 触摸过页面（5 秒内）：fetch / XHR 拿到视频清单时据此标「用户点播」，原生播放器才知道该跟着换
    var lastTouch = 0;
    ['pointerdown', 'touchstart', 'click', 'keydown'].forEach(function (n) {
      document.addEventListener(n, function (e) { if (e.isTrusted) lastTouch = Date.now(); }, true);
    });
    function userActive() {
      try { if (navigator.userActivation && navigator.userActivation.isActive) return true; } catch (e) {}
      return Date.now() - lastTouch < 5000;
    }

    function check(u, ctRaw, lenRaw) {
      var ct = String(ctRaw || '').split(';')[0].trim().toLowerCase();
      if (!ct || SEG.test(ct) || !CT.test(ct)) return;
      var len = parseInt(lenRaw, 10);
      report(u, ct, isNaN(len) ? -1 : len, false, userActive() ? { ua: true } : null);
    }

    // ---- 静态来源：没点播放就能拿到的地址 ----
    var MEDIA_RE = /\.(m3u8|mp4|m4v|flv|webm|mkv|mov)(\?|#|$)/i;
    var URL_RE = /https?:(?:\\?\/){2}[^\s"'<>]+?\.(?:m3u8|mp4|m4v|flv|webm|mkv|mov)(?:\?[^\s"'<>\\]*)?/gi;
    var staticCount = 0;
    function reportStatic(raw) {
      try {
        if (typeof raw !== 'string' || staticCount >= 16) return;
        var u = raw.replace(/\\\//g, '/').replace(/\\u002f/gi, '/').replace(/&amp;/g, '&').trim();
        if (!/^(https?:)?\/\//i.test(u) && !/^\//.test(u)) return;
        u = new URL(u, location.href).href;
        if (!MEDIA_RE.test(u.split('?')[0] + (u.indexOf('?') >= 0 ? '?' : ''))) return;
        staticCount++;
        report(u, '', -1, false);
      } catch (e) {}
    }
    function scanText(text) {
      try {
        if (typeof text !== 'string' || text.length > 200000) return;
        var m = text.match(URL_RE);
        if (m) for (var i = 0; i < m.length && i < 8; i++) reportStatic(m[i]);
      } catch (e) {}
    }
    // 苹果 CMS 播放页的 player_aaaa：url 可能整体 escape（encrypt=1）或 base64 + escape（encrypt=2）
    function scanMacCms() {
      try {
        var p = window.player_aaaa;
        if (!p || typeof p.url !== 'string') return;
        var u = p.url, e = String(p.encrypt || 0);
        if (e === '1') u = unescape(u);
        else if (e === '2') u = unescape(atob(u));
        reportStatic(u);
      } catch (err) {}
    }
    function scanStatic() {
      try {
        var vs = document.querySelectorAll('video');
        for (var i = 0; i < vs.length && i < 6; i++) {
          var v = vs[i];
          reportStatic(v.getAttribute('src') || v.getAttribute('data-src') || v.getAttribute('data-url') || '');
          var ss = v.querySelectorAll('source[src]');
          for (var j = 0; j < ss.length && j < 4; j++) reportStatic(ss[j].getAttribute('src'));
        }
        var metas = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[name="twitter:player:stream"]');
        for (var k = 0; k < metas.length && k < 3; k++) reportStatic(metas[k].getAttribute('content') || '');
        scanMacCms();
        var scripts = document.scripts;
        for (var n = 0; n < scripts.length && n < 60; n++) {
          if (!scripts[n].src) scanText(scripts[n].textContent);
        }
      } catch (e) {}
    }
    // 页面一开始、加载完、之后两次（播放器多半是脚本稍后才初始化出来的）各扫一遍；扫描本身只读 DOM，量很小
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scanStatic);
    else scanStatic();
    window.addEventListener('load', function () { scanStatic(); setTimeout(scanStatic, 1500); setTimeout(scanStatic, 4000); });

    // 接口返回的 JSON（很多站点点播放前就把「取播放地址」的接口请求完了）：只在响应是小 JSON 时找里面的视频地址
    var jsonScanned = 0;
    function scanJson(ctRaw, lenRaw, getText) {
      try {
        var ct = String(ctRaw || '').toLowerCase();
        if (ct.indexOf('json') < 0 || jsonScanned >= 8) return;
        var len = parseInt(lenRaw, 10);
        if (!isNaN(len) && len > 200000) return;
        jsonScanned++;
        getText(scanText);
      } catch (e) {}
    }

    // <video> 实际在播的地址（blob: 是 MSE，没有可直接播的地址，靠请求拦截抓它背后的 m3u8 / 分片清单）。
    // 同时带上这个元素的特征，原生据此区分「正片」和「预览 / 封面循环 / 页面里的动态小元素」：
    //   d  时长（秒；直播 / 无限为 -2；还不知道不带）
    //   pv 看起来是预览 / 装饰：loop、渲染尺寸很小、不可见（布局还没出来、尺寸为 0 时不据此判断）
    //   ua 用户刚在页面里点过（navigator.userActivation）
    function vinfo(t) {
      var x = {};
      var d = t.duration;
      if (d === Infinity) x.d = -2;
      else if (isFinite(d) && d > 0) x.d = Math.round(d * 10) / 10;
      var pv = !!t.loop;
      try {
        var r = t.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          if (r.width < 220 || r.height < 124) pv = true;
          var cs = getComputedStyle(t);
          if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) pv = true;
        }
      } catch (e) {}
      x.pv = pv;
      try { if (navigator.userActivation && navigator.userActivation.isActive) x.ua = true; } catch (e) {}
      return x;
    }
    function onMedia(e) {
      try {
        var t = e.target;
        if (!t || t.tagName !== 'VIDEO') return;
        var u = t.currentSrc || t.src;
        if (u && !/^(blob|data):/i.test(u)) report(u, '', -1, true, vinfo(t));
      } catch (err) {}
    }
    ['loadstart', 'loadedmetadata', 'durationchange', 'play', 'playing'].forEach(function (n) {
      document.addEventListener(n, onMedia, true);
    });

    var xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function () {
      try {
        var x = this;
        x.addEventListener('readystatechange', function () {
          if (x.readyState === 2) check(x.responseURL, x.getResponseHeader('content-type'), x.getResponseHeader('content-length'));
          if (x.readyState === 4) {
            scanJson(x.getResponseHeader('content-type'), x.getResponseHeader('content-length'), function (cb) {
              if (x.responseType === '' || x.responseType === 'text') cb(x.responseText);
            });
          }
        });
      } catch (e) {}
      return xo.apply(this, arguments);
    };

    var of = window.fetch;
    if (typeof of === 'function') {
      window.fetch = function () {
        var p = of.apply(this, arguments);
        try {
          p.then(function (r) {
            try { check(r.url, r.headers.get('content-type'), r.headers.get('content-length')); } catch (e) {}
            try {
              scanJson(r.headers.get('content-type'), r.headers.get('content-length'), function (cb) {
                r.clone().text().then(cb, function () {});
              });
            } catch (e) {}
          }, function () {});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}
})();
