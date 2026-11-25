/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// Mafari 桌面模式：在 document-start 注入，让页面真正按桌面环境渲染与判断。
// 1) 重写 / 补上 viewport，使布局宽度 >= 1280px（页面随后由 WebView 缩放以适应屏幕）
// 2) 伪装 platform / userAgentData / 触控特征 / 指针媒体查询 / screen 尺寸
(function () {
  if (window.__mafariDesktop) return;
  window.__mafariDesktop = true;

  var realW = 0, realH = 0;
  try { realW = screen.width || 0; realH = screen.height || 0; } catch (e) {}
  var W = Math.max(1280, realW);

  function def(obj, key, value) {
    try {
      Object.defineProperty(obj, key, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  }

  // ---- 身份：platform / userAgentData ----
  try {
    var m = /Chrome\/(\d+)/.exec(navigator.userAgent);
    var v = m ? m[1] : '130';
    var brands = [
      { brand: 'Chromium', version: v },
      { brand: 'Google Chrome', version: v },
      { brand: 'Not?A_Brand', version: '99' }
    ];
    var uad = {
      brands: brands,
      mobile: false,
      platform: 'Windows',
      getHighEntropyValues: function () {
        return Promise.resolve({
          brands: brands, mobile: false, platform: 'Windows', platformVersion: '10.0.0',
          architecture: 'x86', bitness: '64', model: '', uaFullVersion: v + '.0.0.0',
          fullVersionList: brands
        });
      },
      toJSON: function () { return { brands: brands, mobile: false, platform: 'Windows' }; }
    };
    def(Navigator.prototype, 'platform', 'Win32');
    def(Navigator.prototype, 'maxTouchPoints', 0);
    def(Navigator.prototype, 'userAgentData', uad);
    def(Navigator.prototype, 'vendor', 'Google Inc.');
  } catch (e) {}

  // ---- 触控特征：很多站点用 'ontouchstart' in window / window.orientation 判断手机 ----
  try {
    ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (k) {
      try { delete window[k]; } catch (e) {}
      try { delete Document.prototype[k]; } catch (e) {}
      try { delete HTMLElement.prototype[k]; } catch (e) {}
    });
    Object.defineProperty(window, 'orientation', { get: function () { return undefined; }, configurable: true });
  } catch (e) {}

  // ---- 指针 / 悬停媒体查询：桌面 = 精确指针 + 可悬停 ----
  try {
    var realMM = window.matchMedia;
    var noop = function () {};
    window.matchMedia = function (q) {
      var s = String(q);
      var forced = null;
      if (/(any-)?pointer\s*:\s*coarse|(any-)?hover\s*:\s*none/i.test(s)) forced = false;
      else if (/(any-)?pointer\s*:\s*fine|(any-)?hover\s*:\s*hover/i.test(s)) forced = true;
      if (forced === null) return realMM.call(window, q);
      return {
        matches: forced, media: s, onchange: null,
        addListener: noop, removeListener: noop,
        addEventListener: noop, removeEventListener: noop,
        dispatchEvent: function () { return false; }
      };
    };
  } catch (e) {}

  // ---- screen 尺寸：不小于布局宽度，避免站点按 screen.width 判定手机 ----
  try {
    if (realW > 0 && realW < W) {
      var h = Math.round(realH * W / realW);
      ['width', 'availWidth'].forEach(function (k) { def(Screen.prototype, k, W); });
      ['height', 'availHeight'].forEach(function (k) { def(Screen.prototype, k, h); });
    }
  } catch (e) {}

  // ---- viewport：强制桌面宽度，并允许缩放 ----
  var CONTENT = 'width=' + W + ', minimum-scale=0.1, maximum-scale=10, user-scalable=yes';
  function fixViewport() {
    try {
      var head = document.head || document.documentElement;
      if (!head) return;
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      if (metas.length === 0) {
        var meta = document.createElement('meta');
        meta.setAttribute('name', 'viewport');
        meta.setAttribute('content', CONTENT);
        head.appendChild(meta);
        return;
      }
      for (var i = 0; i < metas.length; i++) {
        if (metas[i].getAttribute('content') !== CONTENT) metas[i].setAttribute('content', CONTENT);
      }
    } catch (e) {}
  }
  function touchesViewport(records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') {
        if (r.target && r.target.tagName === 'META') return true;
      } else {
        for (var j = 0; j < r.addedNodes.length; j++) {
          var t = r.addedNodes[j].tagName;
          if (t === 'META' || t === 'HEAD') return true;
        }
        for (var k = 0; k < r.removedNodes.length; k++) {
          if (r.removedNodes[k].tagName === 'META') return true;
        }
      }
    }
    return false;
  }
  try {
    fixViewport();
    new MutationObserver(function (records) {
      if (touchesViewport(records)) fixViewport();
    }).observe(document, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']
    });
    document.addEventListener('DOMContentLoaded', fixViewport);
  } catch (e) {}
})();
