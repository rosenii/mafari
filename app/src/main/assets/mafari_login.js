/* Mafari：WebView 页面里的登录框检测（只在主框架运行）。
 * 只做两件事：① 聚焦登录框时通知原生「可以列出账号了」；② 表单提交时把账号密码交给原生去问「要不要保存」。
 * 填充由原生调 window.__mafariLoginFill(user, pass) 完成，页面脚本拿不到任何已保存的凭据。
 * 来源（站点）一律由原生按 WebMessageListener 给出的 sourceOrigin 判断，这里不上报任何 URL。 */
(function () {
  'use strict';
  try {
    if (window.top !== window || window.__mafariLogin) return;
  } catch (e) { return; }
  var ch = window.MafariLogin;
  if (!ch || typeof ch.postMessage !== 'function') return;
  window.__mafariLogin = true;

  var TEXT_TYPES = ['', 'text', 'email', 'tel', 'search', 'url', 'number'];
  var last = null; // { u: 用户名输入框或 null, p: 密码输入框 }

  function send(o) { try { ch.postMessage(JSON.stringify(o)); } catch (e) {} }
  function ac(el) { return (el.getAttribute('autocomplete') || '').toLowerCase(); }
  function visible(el) {
    if (!el || !el.getClientRects || el.getClientRects().length === 0) return false;
    var s = window.getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function isInput(el) { return !!el && el.tagName === 'INPUT'; }
  function isPass(el) { return isInput(el) && (el.type || '').toLowerCase() === 'password'; }
  function isText(el) { return isInput(el) && TEXT_TYPES.indexOf((el.type || '').toLowerCase()) >= 0; }
  function scopeOf(el) { return (el.closest && el.closest('form')) || document; }
  function passFields(scope) {
    var out = [], all = scope.querySelectorAll('input[type=password]');
    for (var i = 0; i < all.length; i++) if (visible(all[i]) && !all[i].disabled) out.push(all[i]);
    return out;
  }
  // 密码框前面最近的一个可见文本框
  function userFieldFor(p, scope) {
    var all = scope.querySelectorAll('input'), best = null;
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      if (c === p || !isText(c) || !visible(c) || c.disabled) continue;
      if (p.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_PRECEDING) best = c;
    }
    return best;
  }
  function setVal(el, v) {
    if (!el) return;
    var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    if (d && d.set) d.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (!isInput(el) || el.readOnly || el.disabled) return;
    var scope = scopeOf(el), pass;
    if (isPass(el)) {
      pass = el;
    } else if (isText(el)) {
      pass = passFields(scope)[0];
    }
    if (!pass || ac(pass) === 'new-password') return; // 注册 / 改密码：不列已有账号
    last = { u: isPass(el) ? userFieldFor(el, scope) : el, p: pass };
    send({ op: 'focus' });
  }, true);

  window.__mafariLoginFill = function (user, pass) {
    var t = last;
    if (!t || !t.p || !t.p.isConnected) return;
    if (t.u && t.u.isConnected) setVal(t.u, user);
    setVal(t.p, pass);
  };

  var lastKey = '';
  function report(scope) {
    var ps = passFields(scope), p = null;
    for (var i = 0; i < ps.length; i++) if (ps[i].value) { p = ps[i]; break; }
    if (!p) return;
    var u = userFieldFor(p, scope), uv = u ? u.value : '';
    var key = uv + '\u0000' + p.value;
    if (key === lastKey) return;
    lastKey = key;
    send({ op: 'submit', u: uv, p: p.value });
  }
  document.addEventListener('submit', function (e) {
    var f = e.target;
    report(f && f.querySelectorAll ? f : document);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && isPass(e.target)) report(scopeOf(e.target));
  }, true);
  document.addEventListener('click', function (e) {
    // 只认真正的提交按钮：「显示密码」这类 type=button 的小眼睛不算
    var b = e.target && e.target.closest && e.target.closest('button, input[type=submit]');
    if (!b) return;
    var t = (b.getAttribute('type') || (b.tagName === 'BUTTON' ? 'submit' : '')).toLowerCase();
    if (t !== 'submit') return;
    report(scopeOf(b));
  }, true);
})();
