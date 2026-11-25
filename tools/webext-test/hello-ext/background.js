/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// Mafari WebView 拓展自测：后台页（MV3 service_worker 在 WebView 里以隐藏页面模拟）。
var api = typeof browser !== 'undefined' ? browser : chrome;
api.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
  if (msg && msg.type === 'ping') {
    sendResponse({ pong: 'pong ' + new Date().toLocaleTimeString() });
  }
});
