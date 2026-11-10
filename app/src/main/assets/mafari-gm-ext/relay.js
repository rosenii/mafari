/*
 * ISOLATED 世界中继：页面（MAIN 世界的 shim）⇄ 扩展。
 * 通道是 document 上的 CustomEvent（detail 恒为 JSON 字符串，不跨 Xray 传对象）。
 * 注意：MAIN 世界与页面同域，页面脚本也能收发这些事件——安全边界靠「后台只认已注册脚本 + 来源校验」，
 * 不靠通道保密（见 docs/GECKO_GM.md「安全模型」）。
 */
(() => {
  'use strict';
  const REQ = 'mafari-gm:req';
  const RES = 'mafari-gm:res';
  const toPage = (o) => {
    try { document.dispatchEvent(new CustomEvent(RES, { detail: JSON.stringify(o) })); } catch (e) {}
  };

  // —— 原生端口：惰性连接，只有脚本真的用到通知 / 下载 / 开标签 / 菜单 / 写值时才建立 ——
  let port = null;
  function native(msg) {
    try {
      if (!port) {
        port = browser.runtime.connectNative('mafariGm');
        port.onMessage.addListener((m) => toPage(m));
        port.onDisconnect.addListener(() => { port = null; });
      }
      port.postMessage(msg);
    } catch (e) { port = null; }
  }

  const BG = new Set(['xhr', 'xhrAbort', 'cookieList', 'cookieSet']);
  const NATIVE = new Set(['set', 'del', 'toast', 'copy', 'copyHtml', 'notify', 'openTab', 'closeTab',
    'download', 'menu', 'ran']);

  document.addEventListener(REQ, (ev) => {
    let m;
    try { m = JSON.parse(ev.detail); } catch (e) { return; }
    if (!m || typeof m.t !== 'string') return;
    if (m.t === 'hi') { toPage({ t: 'ready' }); return; }
    if (BG.has(m.t)) {
      browser.runtime.sendMessage(m).then((r) => { if (r) toPage(r); }, () => {});
    } else if (NATIVE.has(m.t)) {
      native(m);
    }
  }, true);

  toPage({ t: 'ready' }); // 先于或晚于 shim 加载都可：shim 的 hi 与这条 ready 至少有一条会被对方收到

  // 值变化广播：后台把变化写进 storage.local.bus，所有标签 / 框架的中继都会收到
  browser.storage.onChanged.addListener((ch, area) => {
    if (area !== 'local' || !ch.bus || !ch.bus.newValue) return;
    const b = ch.bus.newValue;
    toPage({ t: 'val', id: b.id, k: b.k, raw: b.raw === undefined ? null : b.raw, src: b.src || '' });
  });
})();
