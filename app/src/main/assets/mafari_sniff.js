/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

/*
 * 视频嗅探的页面侧补充（只在顶层框架运行；WebView 的 shouldInterceptRequest 已经能按地址后缀抓到大多数视频，
 * 这里补三种它看不到的：没有后缀的地址（靠响应头 Content-Type 判断）、<video> 实际在播的地址、fetch / XHR 拿到的 HLS）。
 * 另外补两类「没点播放就能拿到」的来源：页面里已经写好的地址（<video> / <source> / og:video / 内联脚本 / 苹果 CMS 的 player_aaaa 配置），
 * 和接口返回的小 JSON 里带的地址（很多站点点播放前就把播放接口请求完了）。
 * X（x.com / twitter.com）是单页应用，视频走 blob: 的 MSE，这里单独读它的接口 JSON（只在状态页 /status/<id> 上、只取这条推文自己的 video_info），
 * 把各档 mp4 直链和主清单上报（见下面「X」一节）。
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
    var IS_X = /(^|\.)(x|twitter)\.com$/i.test(location.hostname);
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
        if (extra.br > 0) msg.br = extra.br;
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
        // X 的内联脚本里是整页的时间线数据，会把别的推文的视频都扫进来；X 走下面专用的接口解析
        for (var n = 0; !IS_X && n < scripts.length && n < 60; n++) {
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
        if (IS_X) return; // X 的接口 JSON 里是整条时间线，通用的「找视频地址」会把没在看的推文也报上来
        var ct = String(ctRaw || '').toLowerCase();
        if (ct.indexOf('json') < 0 || jsonScanned >= 8) return;
        var len = parseInt(lenRaw, 10);
        if (!isNaN(len) && len > 200000) return;
        jsonScanned++;
        getText(scanText);
      } catch (e) {}
    }

    // ---- X（x.com / twitter.com）：接口 JSON 里的 video_info ----
    // 网页端用 MSE 播放，<video> 是 blob:，拦截只能看到主清单 / 分片；各档 mp4 直链只在接口 JSON 里：
    //   tweet.legacy.extended_entities.media[].video_info = { duration_millis, variants: [{ bitrate, content_type, url }] }
    // 只在状态页（/<用户>/status/<id>）解析，并且只取 rest_id == 这个 id 的那条推文（转推取被转的原推文）：
    // 时间线 / 回复里别的推文的视频不是用户正在看的，不上报。路径变了（站内跳转，页面不会重新加载）就通知原生清空重新统计。
    var X_API = /\/i\/api\/(?:graphql|2|1\.1)\//;
    var xPath = location.pathname;
    var xScanned = 0;
    function xSyncPath() {
      try {
        if (location.pathname === xPath) return;
        xPath = location.pathname;
        sig = Object.create(null); sent = Object.create(null); count = 0; staticCount = 0; xScanned = 0;
        bridge.postMessage('{"reset":1}');
      } catch (e) {}
    }
    if (IS_X) window.addEventListener('popstate', xSyncPath);

    function xTweetMedia(t) {
      var lg = t && t.legacy;
      if (!lg) return null;
      var ms = (lg.extended_entities && lg.extended_entities.media) || (lg.entities && lg.entities.media);
      if (ms && ms.length) return ms;
      var rt = lg.retweeted_status_result && lg.retweeted_status_result.result; // 转推：视频在被转的原推文里
      if (rt) { rt = rt.tweet || rt; return xTweetMedia(rt); }
      return null;
    }
    function xFind(node, id, depth) {
      if (!node || typeof node !== 'object' || depth > 40) return null;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) { var r = xFind(node[i], id, depth + 1); if (r) return r; }
        return null;
      }
      if (node.rest_id === id && node.legacy) return xTweetMedia(node) || [];
      for (var k in node) {
        if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
        var v = node[k];
        if (v && typeof v === 'object') { var r2 = xFind(v, id, depth + 1); if (r2) return r2; }
      }
      return null;
    }
    function xReport(ms) {
      for (var i = 0; i < ms.length && i < 4; i++) {
        var m = ms[i], vi = m && m.video_info;
        if (!vi || !vi.variants) continue;
        var base = { pv: m.type === 'animated_gif' }; // GIF 是循环小视频，不当正片自动播
        if (vi.duration_millis > 0) base.d = Math.round(vi.duration_millis / 100) / 10;
        var vs = vi.variants, hls = [], mp4 = [];
        for (var j = 0; j < vs.length && j < 12; j++) {
          var v = vs[j];
          if (!v || typeof v.url !== 'string') continue;
          if (/mpegurl/i.test(v.content_type || '')) hls.push(v);
          else if (/^video\/mp4/i.test(v.content_type || '')) mp4.push(v);
        }
        mp4.sort(function (a, b) { return (b.bitrate || 0) - (a.bitrate || 0); });
        // 主清单在前（带清晰度选择，自动播放先取它），再按码率从高到低列各档 mp4
        hls.forEach(function (v) { report(v.url, 'application/x-mpegurl', -1, false, { d: base.d, pv: base.pv }); });
        mp4.forEach(function (v) { report(v.url, 'video/mp4', -1, false, { d: base.d, pv: base.pv, br: v.bitrate || 0 }); });
      }
    }
    function xScanText(text, id) {
      try {
        if (typeof text !== 'string' || text.length > 6000000 || text.indexOf('"video_info"') < 0) return;
        var ms = xFind(JSON.parse(text), id, 0);
        if (ms && ms.length) xReport(ms);
      } catch (e) {}
    }
    function xOnResponse(u, ctRaw, lenRaw, getText) {
      try {
        if (!IS_X || typeof u !== 'string') return;
        var url = new URL(u, location.href);
        if (!/(^|\.)(x|twitter)\.com$/i.test(url.hostname) || !X_API.test(url.pathname)) return;
        xSyncPath();
        var st = /\/status(?:es)?\/(\d+)/.exec(location.pathname);
        if (!st || xScanned >= 60 || String(ctRaw || '').toLowerCase().indexOf('json') < 0) return;
        var len = parseInt(lenRaw, 10);
        if (!isNaN(len) && len > 4000000) return;
        xScanned++;
        getText(function (text) { xScanText(text, st[1]); });
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
            if (IS_X) {
              xOnResponse(x.responseURL, x.getResponseHeader('content-type'), x.getResponseHeader('content-length'), function (cb) {
                if (x.responseType === '' || x.responseType === 'text') cb(x.responseText);
              });
            }
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
              if (IS_X) {
                xOnResponse(r.url, r.headers.get('content-type'), r.headers.get('content-length'), function (cb) {
                  r.clone().text().then(cb, function () {});
                });
              }
            } catch (e) {}
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
