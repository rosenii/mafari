// Mafari：给 GeckoView 补上 browser.bookmarks（GeckoView 没有这个 API）。
//
// 通道：Floccus 是普通安装的拓展（非特权），GeckoView 不给它 runtime.sendNativeMessage / connectNative
// （Android 上这两个 API 只对内置特权拓展开放，geckoViewAddons 权限对普通拓展无效），
// 所以不能像内置的 gm@mafari.dev 那样直连应用。改为：
//   Floccus 页面 / 后台 --runtime.sendMessage(BRIDGE_ID)--> 内置拓展 gm@mafari.dev 的 onMessageExternal
//   --sendNativeMessage('mafariBookmarks')--> 应用里的 BookmarksBridge（读写应用自己的书签）
// 后台页和设置页 / 工具栏弹窗都走同一条路，互不依赖，也不需要 session 级委托。
(function () {
  var b = (typeof browser !== 'undefined' && browser) || (typeof chrome !== 'undefined' && chrome);
  if (!b || !b.runtime || (b.bookmarks && b.bookmarks.getTree)) return;
  var BRIDGE_ID = 'gm@mafari.dev';
  var TIMEOUT_MS = 30000;

  function send(op, args) {
    return new Promise(function (resolve, reject) {
      var done = false;
      var timer = setTimeout(function () {
        if (done) return;
        done = true;
        reject(new Error('Mafari bookmarks bridge: timed out (' + op + ')'));
      }, TIMEOUT_MS);
      function finish(fn, v) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        fn(v);
      }
      var p;
      try {
        p = b.runtime.sendMessage(BRIDGE_ID, { t: 'bookmarks', op: op, args: args });
      } catch (e) {
        finish(reject, e);
        return;
      }
      Promise.resolve(p).then(function (res) {
        if (!res) {
          finish(reject, new Error('Mafari bookmarks bridge: no response (is the built-in Mafari GM extension running?)'));
        } else if (res.ok === false || res.error) {
          finish(reject, new Error(String(res.error || 'bridge error')));
        } else {
          finish(resolve, res.result);
        }
      }, function (e) {
        var msg = String((e && e.message) || e);
        if (/receiving end|could not establish/i.test(msg)) {
          msg = 'Mafari bookmarks bridge unavailable (built-in extension ' + BRIDGE_ID + ' not running): ' + msg;
        }
        finish(reject, new Error(msg));
      });
    });
  }

  function call(op, args) {
    return send(op, args).catch(function (e) {
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
