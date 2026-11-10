/*
 * ISOLATED 世界中继：页面（MAIN 世界的 shim）⇄ 扩展。
 *
 * 通道：每个 shim 启动时用 window.postMessage 递来一个 MessageChannel 的 port2（此时页面脚本还没执行），
 * 之后请求 / 响应都只走这条私有 MessagePort——页面拿不到端口，既看不到也伪造不了消息（旧版用 document 上的
 * CustomEvent，页面同样能监听和伪造，等于把 GM_xmlhttpRequest / GM_cookie 白送给任何网页）。
 *
 * 中继本身不做授权，只做转发和基本形状校验；真正的授权在后台页（xhr / cookie）和原生端（其余能力）：
 * 每条消息必须带 (sid 脚本 id, tok 能力令牌)，令牌由原生随机生成、只内联在对应脚本的闭包里。
 * 就算页面在握手窗口之外递来自己的端口，没有令牌也什么都做不了。
 */
(() => {
  'use strict';
  const MARK = 'mafariGm';
  const MAX_PORTS = 64;
  const ports = new Set();

  const send = (p, o) => { try { p.postMessage(JSON.stringify(o)); } catch (e) {} };
  const toAll = (o) => { for (const p of ports) send(p, o); };

  // —— 原生端口：惰性连接，只有脚本真的用到通知 / 下载 / 开标签 / 菜单 / 写值时才建立 ——
  let nativePort = null;
  function native(msg) {
    try {
      if (!nativePort) {
        nativePort = browser.runtime.connectNative('mafariGm');
        nativePort.onMessage.addListener((m) => { if (m && typeof m === 'object') toAll(m); });
        nativePort.onDisconnect.addListener(() => { nativePort = null; });
      }
      nativePort.postMessage(msg);
    } catch (e) { nativePort = null; }
  }

  const BG = new Set(['xhr', 'xhrAbort', 'cookieList', 'cookieSet']);
  const NATIVE = new Set(['set', 'del', 'toast', 'copy', 'copyHtml', 'notify', 'openTab', 'closeTab',
    'download', 'menu', 'ran']);

  function onScriptMessage(p, data) {
    if (typeof data !== 'string' || data.length > 80 * 1024 * 1024) return;
    let m;
    try { m = JSON.parse(data); } catch (e) { return; }
    if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
    if (typeof m.sid !== 'string' || typeof m.tok !== 'string') return; // 没带凭据的一律丢弃
    if (BG.has(m.t)) {
      browser.runtime.sendMessage(m).then((r) => { if (r) send(p, r); }, () => {});
    } else if (NATIVE.has(m.t)) {
      native(m);
    }
  }

  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (ev.source !== window || !d || d.mafariGm !== 'hi') return;
    const p = ev.ports && ev.ports[0];
    if (!p || ports.size >= MAX_PORTS) return;
    ports.add(p);
    p.onmessage = (e) => onScriptMessage(p, e.data);
    try { p.postMessage('ack'); } catch (e) {} // 裸字符串（不是 JSON），shim 据此确认接通
  }, true);

  // 先于或晚于 shim 加载都可：shim 启动时发 hi；中继晚到则靠这条 ready 让 shim 重发
  try { window.postMessage({ mafariGm: 'ready' }, '*'); } catch (e) {}

  // 值变化广播：后台把变化写进 storage.local.bus，所有标签 / 框架的中继都会收到
  browser.storage.onChanged.addListener((ch, area) => {
    if (area !== 'local' || !ch.bus || !ch.bus.newValue) return;
    const b = ch.bus.newValue;
    toAll({ t: 'val', id: b.id, k: b.k, raw: b.raw === undefined ? null : b.raw, src: b.src || '' });
  });
})();
