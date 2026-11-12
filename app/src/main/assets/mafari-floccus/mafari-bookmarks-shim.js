// Mafari：给 GeckoView 补上 browser.bookmarks（GeckoView 没有这个 API）。
// 所有调用经 runtime.sendNativeMessage 转给应用里的 BookmarksBridge，由它读写应用自己的书签。
(function () {
  var b = (typeof browser !== 'undefined' && browser) || (typeof chrome !== 'undefined' && chrome);
  if (!b || !b.runtime || (b.bookmarks && b.bookmarks.getTree)) return;
  var APP = 'mafariBookmarks';

  function call(op, args) {
    return b.runtime.sendNativeMessage(APP, { op: op, args: args }).then(function (res) {
      if (!res) throw new Error('Mafari bookmarks bridge: no response');
      if (res.error) throw new Error(res.error);
      return res.result;
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
})();
