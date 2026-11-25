/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

/*
 * WebView 版 ISOLATED 世界中继：页面（MAIN 世界的 mafari_gm_shim.js）⇄ 原生（GmChannel）。
 * 由 Kotlin 经 addJavaScriptOnEvent(DOCUMENT_START, originRules, world) 注入隔离世界；同一个世界里，
 * 用 addWebMessageListener("mafariGmRelay", originRules, world, listener) 装了原生通道对象 mafariGmRelay
 * （只存在于该世界，页面世界看不到）。必须先装监听器、再注入本脚本；没有对象就什么都不做。
 *
 * 通道：shim 首次用到桥时用 window.postMessage 递来一个 MessageChannel 的 port2。中继在捕获阶段收下并
 * stopImmediatePropagation——页面的 message 监听器看不到这个端口（页面拿不到端口，既看不到也伪造不了消息）。
 *
 * 绑定：端口接通后的第一条消息必须是 bind{sid,tok}。中继把 {t:'bind', pid, sid, tok} 交给原生校验
 * （令牌 + 浏览器给出的 sourceOrigin 在该脚本匹配范围内），原生回 {t:'bound', pid, ok}，通过后端口才算「已绑定」：
 *   - 只有已绑定端口的消息才会被转发，且中继用绑定的 sid / tok 覆盖消息里的值，并盖上 pid；
 *   - 只有已绑定端口才会收到下行消息：带 pid 的只发给该端口；带 id（值广播）的发给绑定了该脚本的端口；
 *     都没有的（菜单执行等）发给本框架所有已绑定端口。
 * 页面伪造的端口没有令牌，绑不上，既发不出请求，也收不到别的脚本的值。10 秒内没绑定成功的端口会被关闭。
 *
 * 与 Gecko 版 relay.js 协议一致，区别只在上游：这里没有后台页，校验与分发都在原生。
 */
(() => {
  'use strict';
  let NAT = null;
  try { NAT = typeof mafariGmRelay !== 'undefined' ? mafariGmRelay : null; } catch (e) { NAT = null; }
  if (!NAT || typeof NAT.postMessage !== 'function') return;
  if (NAT.__relayOn) return; // 同一世界重复注入（例如 handler 重建）：只留第一份
  try { NAT.__relayOn = 1; } catch (e) {}

  const MAX_PORTS = 128;
  const BIND_TIMEOUT_MS = 10000;
  const MAX_MSG_CHARS = 64 * 1024 * 1024;    // 单条上限（xhr 请求体 base64 / 文件流分片）
  const MAX_PENDING_BYTES = 4 * 1024 * 1024; // 绑定完成前每个端口最多暂存的字节数
  const MAX_PENDING_ITEMS = 2000;
  // 能经通道发给原生的消息类型；其它一律丢弃（原生那边也会再校验一次）
  const ALLOWED = new Set(['set', 'del', 'getValue', 'xhr', 'xhrAbort', 'cookieList', 'cookieSet', 'toast', 'copy',
    'copyHtml', 'notify', 'openTab', 'closeTab', 'download', 'menu', 'ran', 'host', 'stream']);

  const ports = new Map(); // pid -> st: { p, pid, state:'new'|'binding'|'ok', sid, tok, q, qBytes, timer }
  let pidSeq = 0;

  const up = (o) => { try { NAT.postMessage(JSON.stringify(o)); } catch (e) {} };
  const sendPort = (p, o) => { try { p.postMessage(JSON.stringify(o)); } catch (e) {} };
  const close = (st) => {
    ports.delete(st.pid);
    try { clearTimeout(st.timer); } catch (e) {}
    try { st.p.onmessage = null; st.p.close(); } catch (e) {}
  };

  /** 已绑定端口的一条消息：用绑定的凭据盖章后交给原生 */
  function dispatch(st, data) {
    let m;
    try { m = JSON.parse(data); } catch (e) { return; }
    if (!m || typeof m !== 'object' || typeof m.t !== 'string' || !ALLOWED.has(m.t)) return;
    m.pid = st.pid; m.sid = st.sid; m.tok = st.tok;
    up(m);
  }

  function startBind(st) {
    st.state = 'binding';
    up({ t: 'bind', pid: st.pid, sid: st.sid, tok: st.tok });
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

  /** 下行：原生 → 脚本。pid 精确投递；id 按脚本广播；都没有就发给所有已绑定端口 */
  function deliver(o) {
    const pid = o.pid;
    delete o.pid;
    if (typeof pid === 'number') {
      const st = ports.get(pid);
      if (st && st.state === 'ok') sendPort(st.p, o);
      return;
    }
    const id = typeof o.id === 'string' ? o.id : null;
    for (const st of ports.values()) if (st.state === 'ok' && (id === null || st.sid === id)) sendPort(st.p, o);
  }

  NAT.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev && ev.data); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'bound') {
      const st = ports.get(m.pid);
      if (!st || st.state !== 'binding') return;
      if (!m.ok) { close(st); return; }
      st.state = 'ok';
      try { clearTimeout(st.timer); } catch (e) {}
      const q = st.q; st.q = []; st.qBytes = 0;
      for (const raw of q) dispatch(st, raw);
      return;
    }
    deliver(m);
  };

  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (ev.source !== window || !d || d.mafariGm !== 'hi') return;
    const p = ev.ports && ev.ports[0];
    if (!p) return;
    ev.stopImmediatePropagation(); // 页面的监听器看不到这个端口
    if (ports.size >= MAX_PORTS) { try { p.close(); } catch (e) {} return; }
    const st = { p, pid: ++pidSeq, state: 'new', sid: '', tok: '', q: [], qBytes: 0, timer: null };
    st.timer = setTimeout(() => { if (st.state !== 'ok') close(st); }, BIND_TIMEOUT_MS);
    ports.set(st.pid, st);
    p.onmessage = (e) => onScriptMessage(st, e.data);
    try { p.postMessage('ack'); } catch (e) {} // 裸字符串（不是 JSON），shim 据此确认接通
  }, true);

  // shim 先于中继执行时（它的 hi 没人收），靠这条 ready 让它重发
  try { window.postMessage({ mafariGm: 'ready' }, '*'); } catch (e) {}
})();
