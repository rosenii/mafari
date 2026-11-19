package dev.dsh.miuixbrowser.browser

import android.webkit.WebView

/**
 * 长按元素检测（从 BrowserTab 拆出，仅 WebView 内核）：「标记为广告」按最近一次触摸位置取元素，
 * 以及页内长按广告浮层的检测脚本和 JS 桥。结果仍通过 [BrowserTab.onElementLongPress] 回调给界面。
 */
internal class TabElementInspector(private val tab: BrowserTab) {

    /** 以 "MafariElement" 名挂到 WebView 的 JS 桥 */
    val bridge = object {
        @android.webkit.JavascriptInterface
        fun onElement(selector: String, tag: String, imageUrl: String) {
            tab.webView.post {
                tab.onElementLongPress?.invoke(BrowserTab.ElementHit(tag, imageUrl, selector))
            }
        }
    }

    /** 最近一次按下的位置（WebView 像素），由触摸监听写入 */
    var lastTouchX = 0f
    var lastTouchY = 0f

    /**
     * 「标记为广告」：长按图片 / 链接时系统只给出图片地址或链接，拿不到元素。
     * 这里按最近一次按下的位置取页面元素，向上找到广告位容器（浮层 / 带广告类名 / iframe / ins / 紧贴的包装层），
     * 生成选择器后走与「长按广告浮层」相同的 ElementHit 流程（标记并移除 / 仅移除）。
     * [onNotFound] 在没找到可标记元素时回调。
     */
    fun markAdAtTouch(onNotFound: () -> Unit) {
        if (tab.isGecko) { onNotFound(); return }
        @Suppress("DEPRECATION")
        val scale = tab.webView.scale.takeIf { it > 0f } ?: tab.webView.context.resources.displayMetrics.density
        val cssX = lastTouchX / scale
        val cssY = lastTouchY / scale
        val js = """(function(){
  try {
    var el = document.elementFromPoint($cssX, $cssY);
    if (!el) return 0;
    var AD = /(^|[\s_-])(ad|ads|adv|advert|advertisement|banner|popup|float|floating|modal|overlay|mask|sponsor|sponsored|promo|gg|dsp)([\s_-]|${'$'})/i;
    function keyOf(n){
      var cls = (typeof n.className === 'string') ? n.className : '';
      return cls + ' ' + (n.id || '');
    }
    function selectorOf(node){
      var parts = [], cur = node;
      while (cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement && parts.length < 8) {
        if (cur.id) {
          var idSel = '#' + CSS.escape(cur.id);
          if (document.querySelectorAll(idSel).length === 1) { parts.unshift(idSel); break; }
        }
        var seg = cur.tagName.toLowerCase();
        var cls = (typeof cur.className === 'string' ? cur.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        for (var k = 0; k < cls.length; k++) seg += '.' + CSS.escape(cls[k]);
        var parent = cur.parentElement;
        if (parent) seg += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, cur) + 1) + ')';
        parts.unshift(seg);
        var sel = parts.join(' > ');
        try { if (document.querySelectorAll(sel).length === 1 && document.querySelector(sel) === node) return sel; } catch (e) {}
        cur = parent;
      }
      return parts.join(' > ');
    }
    var vw = innerWidth, vh = innerHeight;
    var target = null, cur = el, depth = 0;
    var base = el.getBoundingClientRect();
    while (cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement && depth < 8) {
      var tag = cur.tagName;
      var pos = getComputedStyle(cur).position;
      var r = cur.getBoundingClientRect();
      // 接近整页的容器不是广告位本身，不再往上找
      if (depth > 0 && r.width * r.height > vw * vh * 0.9) break;
      if (tag === 'INS' || tag === 'IFRAME' || pos === 'fixed' || pos === 'sticky' || AD.test(keyOf(cur))) { target = cur; break; }
      // 紧贴内容的包装层（宽高与触摸点元素相近）也算广告位的一部分，继续向上
      if (depth > 0 && r.width <= base.width * 1.2 + 2 && r.height <= base.height * 1.2 + 2) target = cur;
      cur = cur.parentElement; depth++;
    }
    if (!target) target = el.closest('a') || el;
    var img = target.tagName === 'IMG' ? target : (target.querySelector ? target.querySelector('img') : null);
    var src = img ? (img.currentSrc || img.src || '') : '';
    var sel = selectorOf(target);
    if (!sel) return 0;
    MafariElement.onElement(sel, target.tagName, src);
    return 1;
  } catch (e) { return 0; }
})()"""
        tab.evaluateJs(js) { raw ->
            if (raw?.trim() != "1") onNotFound()
        }
    }

    /** 只在广告浮层（fixed/absolute 且像广告或面积大）上响应长按，文字区域交给系统选词 */
    fun install(view: WebView) {
        if (!tab.pageEnhancements || !tab.adMarkLongPress) return
        val js = """(function(){
  if (window.__mafariInspector) return;
  window.__mafariInspector = true;
  function selectorOf(el){
    try {
      if (el.id) return '#' + CSS.escape(el.id);
      var parts = []; var cur = el;
      while (cur && cur.nodeType === 1 && parts.length < 4) {
        var p = cur.tagName.toLowerCase();
        var cls = (typeof cur.className === 'string' ? cur.className : '').trim().split(/\s+/).filter(Boolean).slice(0, 2);
        for (var k = 0; k < cls.length; k++) { p += '.' + CSS.escape(cls[k]); }
        var parent = cur.parentElement;
        if (parent) { p += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, cur) + 1) + ')'; }
        parts.unshift(p); cur = parent;
      }
      return parts.join(' > ');
    } catch (e) { return ''; }
  }
  function isAdLike(el){
    var st = getComputedStyle(el);
    var pos = st.position;
    if (pos !== 'fixed' && pos !== 'absolute') return false;
    var cls = (typeof el.className === 'string') ? el.className : '';
    var key = (cls + ' ' + (el.id || '')).toLowerCase();
    if (/(^|[\s_-])(ad|ads|advert|banner|popup|float|modal|overlay|mask|sponsor)([\s_-]|$)/.test(key)) return true;
    var r = el.getBoundingClientRect();
    return r.width > innerWidth * 0.5 && r.height > innerHeight * 0.25;
  }
  function fire(el){
    var tag = el.tagName;
    if (tag === 'IMG' || tag === 'INPUT' || tag === 'TEXTAREA') return;
    if (!isAdLike(el)) return;
    var img = el.querySelector ? el.querySelector('img') : null;
    var src = img ? (img.currentSrc || img.src || '') : '';
    try { MafariElement.onElement(selectorOf(el), tag, src); } catch (e) {}
    try { var sel = getSelection(); if (sel) sel.removeAllRanges(); } catch (e) {}
  }
  var timer = null, sx = 0, sy = 0, moved = false;
  document.addEventListener('touchstart', function(e){
    if (e.touches.length !== 1) return;
    var el = e.target;
    if (!el || el.nodeType !== 1) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; moved = false;
    clearTimeout(timer);
    timer = setTimeout(function(){ if (!moved) fire(el); }, 520);
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function(e){
    if (!e.touches.length) return;
    if (Math.abs(e.touches[0].clientX - sx) > 12 || Math.abs(e.touches[0].clientY - sy) > 12) {
      moved = true; clearTimeout(timer);
    }
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function(){ clearTimeout(timer); }, { capture: true, passive: true });
  document.addEventListener('touchcancel', function(){ clearTimeout(timer); }, { capture: true, passive: true });
})();"""
        view.evaluateJavascript(js, null)
    }
}
