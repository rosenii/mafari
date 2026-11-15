/*
 * 页面不可见时暂停所有音视频（Gecko）。
 *
 * GeckoSession.setActive(false)（切到别的标签 / 标签列表）和应用退到后台时，页面会变成 document.hidden，
 * 但 Gecko 只停渲染、不停声音；原生侧靠 MediaSession 暂停并不覆盖所有媒体（无声视频、没有激活媒体会话、iframe 内的播放器等）。
 * 这里在页面内兜底：变为不可见就暂停所有正在播放的 <video> / <audio>；不可见期间才开始的播放（缓冲完自动播放等）立刻再暂停。
 * 切回来后不自动恢复，由用户自己点播放。
 */
(function () {
  'use strict';
  if (window.__mafariPauseHidden) return;
  window.__mafariPauseHidden = true;

  // 记下播过的媒体元素：覆盖未挂进 DOM 的 new Audio()（它们收不到 document 上的捕获事件，但在页面不可见前 play 过的会在这里）
  var seen = new Set();

  function isMedia(el) {
    return !!el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO');
  }

  function pauseOne(el) {
    try { if (!el.paused) el.pause(); } catch (e) {}
  }

  function pauseAll() {
    try {
      var list = document.querySelectorAll('video,audio');
      for (var i = 0; i < list.length; i++) pauseOne(list[i]);
    } catch (e) {}
    seen.forEach(pauseOne);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) pauseAll();
  }, true);

  // play 事件不冒泡，用捕获才能在 document 上收到
  document.addEventListener('play', function (ev) {
    var el = ev.target;
    if (!isMedia(el)) return;
    seen.add(el);
    if (document.hidden) pauseOne(el);
  }, true);

  // 元素移出 DOM 后不再追踪，避免 Set 一直持有
  document.addEventListener('emptied', function (ev) {
    if (isMedia(ev.target)) seen.delete(ev.target);
  }, true);
})();
