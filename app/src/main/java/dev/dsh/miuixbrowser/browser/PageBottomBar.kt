package dev.dsh.miuixbrowser.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 检测页面自己是否带「固定在屏幕底部的整宽栏」（底部导航 / 工具栏）。
 * 悬浮三段式只在页面有这种栏时才让出底部位置，免得盖住它；没有就让网页铺满。
 *
 * 判据（和 Gecko 的 scroll-metrics.js 保持一致，要改两处一起改）：取屏幕最底边中点，沿元素链向上找候选，要求同时满足：
 *  - position 为 fixed，或设了 bottom 偏移的 sticky（普通文档流里的页脚不算）；可见、不透明
 *  - 整宽：宽度 ≥ 视口 90%，左右贴边；高度 36px ~ min(140px, 视口 22%)；下沿贴着视口底边（-3 ~ +2px）
 *  - 自己（或点击链上 ≥ 60% 宽的元素）有底色 / 背景图 / 阴影 / 毛玻璃 / 上边框：纯透明的定位容器不算
 *  - 栏内至少 2 个可点元素（链接 / 按钮 / tab / 输入框）：单个悬浮按钮、纯展示条不算
 *  - 不是对话框（role=dialog / aria-modal），类名 / id 不含 cookie / consent / toast / popup / 广告 / 下载 App 之类横幅
 *  - 左 / 右 15% 处点到的也是同一个元素：只盖住中间一块的不算
 * 全屏遮罩 / 弹窗太高会被排除。
 */
internal object PageBottomBar {
    const val DETECT_JS = """(function(){
function hasPaint(e, w) {
  var r = e.getBoundingClientRect();
  if (r.width < w * 0.6) return false;
  var cs = getComputedStyle(e);
  var bg = cs.backgroundColor || '';
  var m = bg.match(/rgba?\(([^)]+)\)/);
  if (m) {
    var p = m[1].split(',');
    if (p.length < 4 || parseFloat(p[3]) >= 0.3) return true;
  } else if (bg && bg !== 'transparent') {
    return true;
  }
  if (cs.backgroundImage && cs.backgroundImage !== 'none') return true;
  if (cs.boxShadow && cs.boxShadow !== 'none') return true;
  if (cs.backdropFilter && cs.backdropFilter !== 'none') return true;
  return parseFloat(cs.borderTopWidth) > 0 && cs.borderTopStyle !== 'none';
}
function isBar(e, w, h, painted) {
  var cs = getComputedStyle(e);
  var pos = cs.position;
  if (pos !== 'fixed' && !(pos === 'sticky' && cs.bottom !== 'auto')) return false;
  if (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.05) return false;
  if (!painted) return false;
  var r = e.getBoundingClientRect();
  if (r.width < w * 0.9 || r.left > w * 0.05 || r.right < w * 0.95) return false;
  if (r.height < 36 || r.height > Math.min(140, h * 0.22)) return false;
  if (r.bottom < h - 3 || r.bottom > h + 2) return false;
  var role = e.getAttribute('role');
  if (role === 'dialog' || role === 'alertdialog' || e.getAttribute('aria-modal') === 'true') return false;
  var tag = ((e.id || '') + ' ' + (typeof e.className === 'string' ? e.className : '')).toLowerCase();
  if (/cookie|consent|gdpr|snackbar|toast|modal|dialog|popup|smartbanner|app-?install|install-?app|open-?in-?app|advert|adsbygoogle/.test(tag)) return false;
  return e.querySelectorAll('a[href],button,[role=tab],[role=link],[role=button],input,textarea').length >= 2;
}
function hasBottomBar() {
  try {
    var w = window.innerWidth, h = window.innerHeight;
    if (!w || !h) return false;
    var y = h - 3;
    var st = document.elementsFromPoint(w * 0.5, y);
    for (var j = 0; j < st.length && j < 8; j++) {
      var e = st[j], painted = false;
      while (e && e.nodeType === 1) {
        if (!painted) painted = hasPaint(e, w);
        if (isBar(e, w, h, painted)) {
          var l = document.elementsFromPoint(w * 0.15, y), r = document.elementsFromPoint(w * 0.85, y);
          if (l.indexOf(e) >= 0 && r.indexOf(e) >= 0) return true;
        }
        e = e.parentElement;
      }
    }
  } catch (x) {}
  return false;
}
return hasBottomBar();})()"""
}

/** WebView：执行一次检测并写进 [BrowserTab.pageHasBottomBar]（Gecko 没有 evaluateJavascript，走内容脚本回报）。 */
internal suspend fun BrowserTab.pollPageBottomBar() {
    val raw = suspendCancellableCoroutine<String?> { c ->
        evaluateJs(PageBottomBar.DETECT_JS) { if (c.isActive) c.resume(it) }
    }
    if (raw != null) pageHasBottomBar = raw == "true"
}
