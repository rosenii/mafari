// Mafari：给 GeckoView 补上 browser.bookmarks（GeckoView 没有这个 API）。
// 所有调用经 runtime.sendNativeMessage 转给应用里的 BookmarksBridge，由它读写应用自己的书签。
//
// Floccus 的界面（设置页 / 工具栏弹窗，同一个 options.html）是在浏览器标签页或弹出页 session 里打开的
// 扩展页，GeckoView 对这类页面的 sendNativeMessage 走「session 级」通道，后台页走「扩展级」通道。
// 为了不依赖哪一路挂上了委托：页面里的调用先经 runtime.sendMessage 交给后台页代发（后台页走扩展级），
// 没人应答时再直接 sendNativeMessage（走 session 级）。
(function () {
  var b = (typeof browser !== 'undefined' && browser) || (typeof chrome !== 'undefined' && chrome);
  if (!b || !b.runtime || (b.bookmarks && b.bookmarks.getTree)) return;
  var APP = 'mafariBookmarks';
  var RELAY = '__mafariBookmarksRelay';

  function native(op, args) {
    return b.runtime.sendNativeMessage(APP, { op: op, args: args }).then(function (res) {
      if (!res) throw new Error('Mafari bookmarks bridge: no response');
      if (res.error) throw new Error(res.error);
      return res.result;
    });
  }

  // 当前上下文是不是后台页（manifest 的 background.page，或脚本式后台的生成页）
  var isBackground = (function () {
    try {
      var bg = b.runtime.getManifest().background || {};
      var p = location.pathname.replace(/^\//, '');
      return (!!bg.page && p === bg.page) || /_generated_background_page\.html$/.test(p);
    } catch (e) { return false; }
  })();

  if (isBackground) {
    // 只应答自己的中继消息；其它消息返回 undefined，不抢 Floccus 自己的 onMessage 监听
    b.runtime.onMessage.addListener(function (msg) {
      if (!msg || msg[RELAY] !== 1) return undefined;
      return native(msg.op, msg.args).then(
        function (result) { return { ok: true, result: result }; },
        function (e) { return { ok: false, error: String((e && e.message) || e) }; }
      );
    });
  }

  function call(op, args) {
    var p;
    if (isBackground) {
      p = native(op, args);
    } else {
      var m = { op: op, args: args };
      m[RELAY] = 1;
      p = b.runtime.sendMessage(m).then(function (r) {
        if (r && r.ok === true) return r.result;
        if (r && r.ok === false) throw new Error(r.error);
        return native(op, args); // 后台没应答：直连
      }, function () { return native(op, args); });
    }
    return p.catch(function (e) {
      try { console.error('[mafari bookmarks] ' + op + ' failed:', e); } catch (_) {}
      throw e;
    });
  }

  function event() {
    var ls = [];
    return {
      addListener: function (f) { if (ls.indexOf(f) < 0) ls.push(f); },
      removeListener: function (f) { var i = ls.indexOf(f); if (i >= 0) ls.splice(i, 1); },
      hasListener: function (f) { return ls.indexOf(f) >= 0; },
      hasListeners: function () { return ls.length > 0; }
    };
  }

  var api = {
    getTree: function () { return call('getTree', []); },
    getSubTree: function (id) { return call('getSubTree', [id]); },
    get: function (id) { return call('get', [id]); },
    getChildren: function (id) { return call('getChildren', [id]); },
    search: function (q) { return call('search', [q]); },
    create: function (o) { return call('create', [o]); },
    update: function (id, ch) { return call('update', [id, ch]); },
    move: function (id, dest) { return call('move', [id, dest]); },
    remove: function (id) { return call('remove', [id]); },
    removeTree: function (id) { return call('removeTree', [id]); },
    // 应用内的书签改动不会推给扩展；Floccus 按间隔同步时会整树比对，不依赖这些事件
    onCreated: event(), onRemoved: event(), onChanged: event(), onMoved: event(),
    onChildrenReordered: event(), onImportBegan: event(), onImportEnded: event()
  };
  try { b.bookmarks = api; } catch (e) {}
  if (!b.bookmarks) {
    try { Object.defineProperty(b, 'bookmarks', { value: api, configurable: true }); } catch (e) {}
  }

  // Floccus 点开「本地文件夹」选择器时，会先用 tabs.query({active, currentWindow}) 探测 Vivaldi，
  // 之后才去取书签树；GeckoView 里这个探测若挂住或报错，选择器就一直转圈。只对这一种探测调用兜底
  // （超时 / 出错都当作「没有 Vivaldi 特有字段」），其它 tabs.query 原样放行。
  try {
    if (b.tabs && b.tabs.query) {
      var rawQuery = b.tabs.query.bind(b.tabs);
      b.tabs.query = function (info) {
        var keys = info ? Object.keys(info) : [];
        var probe = keys.length === 2 && info.active === true && info.currentWindow === true;
        if (!probe) return rawQuery(info);
        return Promise.race([
          Promise.resolve().then(function () { return rawQuery(info); }),
          new Promise(function (resolve) { setTimeout(function () { resolve([]); }, 3000); })
        ]).catch(function () { return []; });
      };
    }
  } catch (e) {}
})();
