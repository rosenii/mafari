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
        if (extra.x) msg.x = 1;
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

    // ---- X（Twitter）----
    // X 的 <video> 是 blob:（MSE），页面里没有直链；但推文接口（/i/api/graphql/…）返回的 JSON 里带着 video_info.variants：
    // 各清晰度的 mp4 直链 + HLS 清单 + 时长。这里读这些 JSON（只读、只在 x.com / twitter.com），按推文 id 记下来，
    // 在「详情页 / 用户点播」时才报告——时间线里静音自动播放的视频不报，免得列表被刷满。
    // HLS 清单本身仍由原生请求拦截抓（video.twimg.com 的 .m3u8），这里补的是它抓不到的 mp4 直链、时长和「没点播放」的详情页。
    var IS_X = /(^|\.)(x|twitter)\.com$/i.test(location.hostname);
    var X_API = /\/i\/api\/(graphql|2|1\.1)\//;
    var xTweets = Object.create(null); // 推文 id -> [{d, gif, mp4:[{u, br}], hls}]
    var xOrder = [];
    var xAt = Object.create(null);

    function xRes(u) {
      var m = /\/(\d{2,4})x(\d{2,4})\//.exec(u);
      return m ? (+m[1]) * (+m[2]) : 0;
    }
    function xTake(id, media) {
      if (!id || !Array.isArray(media)) return;
      var list = [];
      for (var i = 0; i < media.length && i < 4; i++) {
        var m = media[i], vi = m && m.video_info;
        if (!vi || !Array.isArray(vi.variants)) continue;
        var mp4 = [], hls = '';
        for (var j = 0; j < vi.variants.length; j++) {
          var v = vi.variants[j];
          if (!v || typeof v.url !== 'string' || !/^https:\/\/[^\/]*twimg\.com\//i.test(v.url)) continue;
          if (v.content_type === 'video/mp4') mp4.push({ u: v.url, br: +v.bitrate || 0 });
          else if (/mpegurl/i.test(v.content_type || '') && !hls) hls = v.url;
        }
        mp4.sort(function (a, b) { return (b.br - a.br) || (xRes(b.u) - xRes(a.u)); });
        if (!mp4.length && !hls) continue;
        var dm = +vi.duration_millis;
        list.push({ d: dm > 0 ? Math.round(dm / 100) / 10 : undefined, gif: m.type === 'animated_gif', mp4: mp4.slice(0, 3), hls: hls });
      }
      if (!list.length) return;
      if (!xTweets[id]) { xOrder.push(id); if (xOrder.length > 150) delete xTweets[xOrder.shift()]; }
      xTweets[id] = list;
    }
    // 在接口 JSON 里找 extended_entities.media：归到最近一层带 rest_id 的推文（转推 / 引用推文各有自己的 rest_id）
    function xWalk(node, tid, depth) {
      if (!node || typeof node !== 'object' || depth > 40) return;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) xWalk(node[i], tid, depth + 1);
        return;
      }
      var id = tid;
      if (typeof node.rest_id === 'string' && node.legacy && typeof node.legacy === 'object') id = node.rest_id;
      else if (typeof node.id_str === 'string' && node.extended_entities) id = node.id_str;
      if (node.extended_entities && node.extended_entities.media) xTake(id, node.extended_entities.media);
      for (var k in node) {
        if (k === 'extended_entities') continue;
        var c = node[k];
        if (c && typeof c === 'object') xWalk(c, id, depth + 1);
      }
    }
    function xFocalId() {
      var m = /\/status\/(\d+)/.exec(location.pathname);
      return m ? m[1] : '';
    }
    function xReport(id, ua) {
      var list = xTweets[id];
      if (!list) return;
      for (var i = 0; i < list.length; i++) {
        var md = list[i];
        var items = md.mp4.length ? md.mp4 : (md.hls ? [{ u: md.hls }] : []);
        for (var k = 0; k < items.length; k++) {
          var u = items[k].u, now = Date.now();
          if (now - (xAt[u] || 0) < 2000) continue;
          xAt[u] = now;
          // 换页（SPA）后原生那边可能已把旧条目挤掉：允许同一地址在用户再次看到它时重新报一次
          delete sig[u];
          if ((sent[u] || 0) > 3) sent[u] = 3;
          report(u, md.mp4.length ? 'video/mp4' : 'application/x-mpegurl', -1, false,
            { d: md.d, pv: md.gif, ua: ua, x: true });
        }
      }
    }
    function xHarvest(text) {
      try {
        if (typeof text !== 'string' || text.length > 8000000 || text.indexOf('"video_info"') < 0) return;
        // 解析放到下一轮，别占着接口回调
        setTimeout(function () {
          try {
            xWalk(JSON.parse(text), '', 0);
            var f = xFocalId();
            if (f) xReport(f, userActive());
          } catch (e) {}
        }, 0);
      } catch (e) {}
    }
    // blob: 的 <video> 开播 / 出时长：找它对应的推文。article 里的 /status/<id> 链接（时间戳、引用卡片）+ 当前地址里的 id 都是候选，
    // 候选里有记录的取一个（有多个就按时长对）。只在详情页（地址里有 /status/<id>）或用户点过 / 已取消静音时报告。
    function xOnVideo(v) {
      try {
        var ids = [];
        var a = v.closest ? v.closest('article') : null;
        if (a) {
          var ls = a.querySelectorAll('a[href*="/status/"]');
          for (var i = 0; i < ls.length && i < 12; i++) {
            var m = /\/status\/(\d+)/.exec(ls[i].getAttribute('href') || '');
            if (m && ids.indexOf(m[1]) < 0) ids.push(m[1]);
          }
        }
        var f = xFocalId();
        if (f && ids.indexOf(f) < 0) ids.push(f);
        var have = ids.filter(function (id) { return xTweets[id]; });
        if (!have.length) return;
        var pick = have[0];
        if (have.length > 1 && isFinite(v.duration)) {
          for (var j = 0; j < have.length; j++) {
            var d = xTweets[have[j]][0].d;
            if (d && Math.abs(d - v.duration) < 1.5) { pick = have[j]; break; }
          }
        }
        var ua = userActive() || !v.muted;
        if (ua || (f && pick === f)) xReport(pick, ua);
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
        if (IS_X && u && /^blob:/i.test(u)) { xOnVideo(t); return; }
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
            if (IS_X && X_API.test(x.responseURL || '')) {
              if (x.responseType === '' || x.responseType === 'text') xHarvest(x.responseText);
              return;
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
              if (IS_X && X_API.test(r.url || '')) { r.clone().text().then(xHarvest, function () {}); return; }
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
