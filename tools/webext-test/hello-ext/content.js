// Mafari WebView 拓展自测：content script。
// 横幅依次显示三项结果：① 注入成功 ② storage.local 计数 ③ 后台页回复（后台页没起来时显示「无响应」，不影响前两项）。
(function () {
  var api = typeof browser !== 'undefined' ? browser : chrome;
  if (document.getElementById('__mafari_hello_ext')) return;

  var bar = document.createElement('div');
  bar.id = '__mafari_hello_ext';
  bar.style.cssText = [
    'position:fixed', 'left:8px', 'right:8px', 'bottom:8px', 'z-index:2147483647',
    'padding:8px 12px', 'border-radius:10px', 'background:#1b8a3a', 'color:#fff',
    'font:13px/1.4 sans-serif', 'box-shadow:0 2px 8px rgba(0,0,0,.3)'
  ].join(';');
  var parts = ['content script 已注入'];
  function render() { bar.textContent = 'Mafari Hello ✓ ' + parts.join(' · ') + '（点击关闭）'; }
  bar.addEventListener('click', function () { bar.remove(); });
  render();
  (document.body || document.documentElement).appendChild(bar);

  api.storage.local.get('count').then(function (r) {
    var n = ((r && r.count) || 0) + 1;
    return api.storage.local.set({ count: n }).then(function () { return n; });
  }).then(function (n) {
    parts.push('storage 计数 ' + n);
    render();
  }).catch(function (e) {
    parts.push('storage 失败：' + (e && e.message));
    render();
  });

  api.runtime.sendMessage({ type: 'ping' }).then(function (res) {
    parts.push('后台页：' + (res && res.pong));
    render();
  }).catch(function () {
    parts.push('后台页无响应');
    render();
  });
})();
