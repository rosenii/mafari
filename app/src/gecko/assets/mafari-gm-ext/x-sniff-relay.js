/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

/*
 * X（x.com / twitter.com）视频嗅探的中继（ISOLATED 世界，只在顶层框架）：
 * 页面里（MAIN 世界）跑的是和 WebView 同一份 mafari_sniff.js，它把报告交给垫片 window.MafariSniff.postMessage，
 * 垫片发一个 CustomEvent 到 document；这里收下、校验形状，再用 runtime.sendMessage 交给后台页。
 * 页面能伪造这个事件，但效果和页面自己伪造 fetch 响应一样——只是往本页的「发现视频」列表里加候选地址，
 * 真正的判定在原生 MediaSniff.classify；后台页还会再校验发送者。{reset:1}（站内跳转）不转发：
 * Gecko 这边的嗅探结果本来就按页面地址分开存，站内跳转换了地址就是另一份。
 */
(() => {
  'use strict';
  if (window.top !== window) return;
  const EVT = 'mafari-sniff-x';
  document.addEventListener(EVT, (e) => {
    try {
      const s = e.detail;
      if (typeof s !== 'string' || s.length > 4200) return;
      const m = JSON.parse(s);
      if (!m || typeof m.u !== 'string') return;
      browser.runtime.sendMessage({ t: 'xsniff', u: m.u, ct: m.ct, len: m.len, d: m.d, pv: m.pv, br: m.br }).catch(() => {});
    } catch (err) {}
  }, true);
})();
