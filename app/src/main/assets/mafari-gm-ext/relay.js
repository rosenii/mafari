/*
 * ISOLATED 世界中继：页面（MAIN 世界的 shim）⇄ 扩展。
 * 由后台页按「所有已启用脚本的匹配范围并集」用 contentScripts.register 动态注册（先于脚本注册），
 * 所以没有任何脚本会运行的页面 / 框架根本不会加载它。
 *
 * 通道：shim 首次用到桥时用 window.postMessage 递来一个 MessageChannel 的 port2。中继在捕获阶段收下并
 * stopImmediatePropagation——页面的 message 监听器看不到这个端口（页面拿不到端口，既看不到也伪造不了消息）。
 *
 * 绑定：端口接通后的第一条消息必须是 bind{sid,tok}。中继把它交给后台页校验（令牌 + 本框架 URL 在该脚本
 * 匹配范围内），通过后端口才算「已绑定」：
 *   - 只有已绑定端口的消息才会被转发（中继会用绑定的 sid / tok 覆盖消息里的值）；
 *   - 只有已绑定端口才会收到任何下行消息（值广播、getValue 结果、回调、菜单）。
 * 页面伪造的端口没有令牌，绑不上，既发不出请求，也收不到别的脚本的值。10 秒内没绑定成功的端口会被关闭。
 */
(() => {
  'use strict';
  const MAX_PORTS = 128;
  const BIND_TIMEOUT_MS = 10000;
  const MAX_MSG_CHARS = 64 * 1024 * 1024;   // 单条上限（xhr 请求体 base64 的上限在后台页另有校验）
  const MAX_PENDING_BYTES = 4 * 1024 * 1024; // 绑定完成前每个端口最多暂存的字节数
  const MAX_PENDING_ITEMS = 2000;

  const ports = new Set(); // st: { p, state:'new'|'binding'|'ok', sid, tok, q, qBytes, timer }

  const send = (p, o) => { try { p.postMessage(JSON.stringify(o)); } catch (e) {} };
  const close = (st) => {
    ports.delete(st);
    try { clearTimeout(st.timer); } catch (e) {}
    try { st.p.onmessage = null; st.p.close(); } catch (e) {}
  };
  /** 下行：给定 id 只发给绑定了该脚本的端口；没有 id（回调 / 菜单）发给所有已绑定端口 */
  const deliver = (o) => {
    const id = typeof o.id === 'string' ? o.id : null;
    for (const st of ports) if (st.state === 'ok' && (id === null || st.sid === id)) send(st.p, o);
  };

  // —— 后台页长连接：绑定校验 + 值广播。第一个端口开始绑定时才建立 ——
  let bg = null;
  let rid = 0;
  const binding = new Map(); // rid -> st
  function bgPort() {
    if (bg) return bg;
    try {
      bg = browser.runtime.connect({ name: 'mafariRelay' });
      bg.onMessage.addListener((m) => {
        if (!m || typeof m !== 'object') return;
        if (m.t === 'bound') {
          const st = binding.get(m.rid);
          if (!st) return;
          binding.delete(m.rid);
          if (!m.ok) { close(st); return; }
          st.state = 'ok';
          try { clearTimeout(st.timer); } catch (e) {}
          const q = st.q; st.q = []; st.qBytes = 0;
          for (const raw of q) dispatch(st, raw);
        } else if (m.t === 'val') {
          deliver(m);
        }
      });
      bg.onDisconnect.addListener(() => {
        bg = null;
        binding.clear();
        // 后台页重启：已绑定的端口重新绑定（令牌在中继闭包里），未完成的暂存消息保留
        for (const st of ports) if (st.state !== 'new') startBind(st);
      });
    } catch (e) { bg = null; }
    return bg;
  }
  function startBind(st) {
    const b = bgPort();
    if (!b) return;
    st.state = 'binding';
    const id = ++rid;
    binding.set(id, st);
    try { b.postMessage({ t: 'bind', rid: id, sid: st.sid, tok: st.tok }); } catch (e) { binding.delete(id); }
  }

  // —— 原生端口：惰性连接，只有脚本真的用到通知 / 下载 / 开标签 / 菜单 / 写值时才建立 ——
  let nativePort = null;
  function native(msg) {
    try {
      if (!nativePort) {
        nativePort = browser.runtime.connectNative('mafariGm');
        nativePort.onMessage.addListener((m) => { if (m && typeof m === 'object') deliver(m); });
        nativePort.onDisconnect.addListener(() => { nativePort = null; });
      }
      nativePort.postMessage(msg);
    } catch (e) { nativePort = null; }
  }

  const BG = new Set(['xhr', 'xhrAbort', 'cookieList', 'cookieSet']);
  const NATIVE = new Set(['set', 'del', 'getValue', 'toast', 'copy', 'copyHtml', 'notify', 'openTab', 'closeTab',
    'download', 'menu', 'ran']);

  /** 已绑定端口的一条消息：用绑定的凭据盖章后分发 */
  function dispatch(st, data) {
    let m;
    try { m = JSON.parse(data); } catch (e) { return; }
    if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
    m.sid = st.sid; m.tok = st.tok;
    if (BG.has(m.t)) {
      browser.runtime.sendMessage(m).then((r) => { if (r) send(st.p, r); }, () => {});
    } else if (NATIVE.has(m.t)) {
      native(m);
    }
  }

  function onScriptMessage(st, data) {
    if (typeof data !== 'string' || data.length > MAX_MSG_CHARS) return;
    if (st.state === 'ok') { dispatch(st, data); return; }
    if (st.state === 'new') { // 第一条必须是 bind
      let m;
      try { m = JSON.parse(data); } catch (e) { close(st); return; }
      if (!m || m.t !== 'bind' || typeof m.sid !== 'string' || typeof m.tok !== 'string' ||
          m.sid.length > 200 || m.tok.length > 200) { close(st); return; }
      st.sid = m.sid; st.tok = m.tok;
      startBind(st);
      return;
    }
    // binding：暂存，绑定通过后按序处理
    if (st.q.length < MAX_PENDING_ITEMS && st.qBytes + data.length <= MAX_PENDING_BYTES) {
      st.q.push(data); st.qBytes += data.length;
    }
  }

  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (ev.source !== window || !d || d.mafariGm !== 'hi') return;
    const p = ev.ports && ev.ports[0];
    if (!p) return;
    ev.stopImmediatePropagation(); // 页面的监听器看不到这个端口
    if (ports.size >= MAX_PORTS) { try { p.close(); } catch (e) {} return; }
    const st = { p, state: 'new', sid: '', tok: '', q: [], qBytes: 0, timer: null };
    st.timer = setTimeout(() => { if (st.state !== 'ok') close(st); }, BIND_TIMEOUT_MS);
    ports.add(st);
    p.onmessage = (e) => onScriptMessage(st, e.data);
    try { p.postMessage('ack'); } catch (e) {} // 裸字符串（不是 JSON），shim 据此确认接通
  }, true);

  // shim 先于中继执行时（它的 hi 没人收），靠这条 ready 让它重发
  try { window.postMessage({ mafariGm: 'ready' }, '*'); } catch (e) {}
})();
