/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// 缓存 URL.createObjectURL 产生的 Blob，供原生 extractBlob 直接读取，
// 避免对 blob: 再 fetch（WebView 里常报 Failed to fetch / CORS）。
// 缓存有上限：revoke 后延迟删除，数量超限时淘汰最早的，避免长期占用内存。
(function () {
  if (window.__mafariBlobCacheInstalled) return;
  window.__mafariBlobCacheInstalled = true;
  var MAX_ENTRIES = 64;
  var REVOKE_GRACE_MS = 30000;
  var cache = Object.create(null);
  var order = [];
  window.__mafariBlobCache = cache;
  // blob URL -> 页面指定的原始文件名（<a download="..."> 或 File.name），下载时原样使用，不重命名
  var names = Object.create(null);
  window.__mafariBlobNames = names;

  function rememberName(a) {
    try {
      if (!a || !a.getAttribute) return;
      var href = a.href || a.getAttribute('href') || '';
      if (href.slice(0, 5).toLowerCase() !== 'blob:') return;
      var n = a.getAttribute('download');
      if (n) names[href] = n;
    } catch (e) {}
  }

  try {
    // 点击链接（含用户点击、冒泡前的捕获阶段）
    document.addEventListener('click', function (ev) {
      try {
        var t = ev.target;
        while (t && t.nodeType === 1 && t.tagName !== 'A') t = t.parentNode;
        if (t && t.tagName === 'A') rememberName(t);
      } catch (e) {}
    }, true);
    // 脚本里 a.click()（常为未插入文档的 <a>）
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      rememberName(this);
      return origClick.apply(this, arguments);
    };
    var origDispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function (ev) {
      try { if (this instanceof HTMLAnchorElement && ev && ev.type === 'click') rememberName(this); } catch (e) {}
      return origDispatch.apply(this, arguments);
    };
  } catch (e) {}

  function drop(url) {
    if (!(url in cache)) return;
    delete cache[url];
    delete names[url];
    var i = order.indexOf(url);
    if (i >= 0) order.splice(i, 1);
  }

  try {
    var origCreate = URL.createObjectURL;
    if (origCreate) {
      URL.createObjectURL = function (obj) {
        var url = origCreate.apply(URL, arguments);
        try {
          if (obj && (obj instanceof Blob || (typeof File !== 'undefined' && obj instanceof File))) {
            cache[url] = obj;
            order.push(url);
            while (order.length > MAX_ENTRIES) {
              var old = order.shift();
              delete cache[old];
              delete names[old];
            }
          }
        } catch (e) {}
        return url;
      };
    }
    // revoke 后下载回调可能仍会带着该 URL，所以延迟一段时间再从缓存删除
    var origRevoke = URL.revokeObjectURL;
    if (origRevoke) {
      URL.revokeObjectURL = function (url) {
        try {
          setTimeout(function () { drop(url); }, REVOKE_GRACE_MS);
        } catch (e) {}
        return origRevoke.apply(URL, arguments);
      };
    }
  } catch (e) {}
})();
