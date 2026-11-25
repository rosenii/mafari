/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 检测页面自己是否带「固定在屏幕顶部的整宽栏」（网站的顶部导航 / 搜索栏 / 标题栏）。
 * 网页态的状态栏沉浸据此分两种处理：
 *  - 有：上滑进入沉浸浏览时，网页不再铺到状态栏下，状态栏区域保持原样（页面自己的栏不会被状态栏压住）；
 *  - 无：上滑后网页铺到屏幕顶，状态栏区域的渐进模糊直接采样下层网页（不镜像）。
 *
 * 判据（与 [PageBottomBar] 对称）：取屏幕最顶边中点，沿元素链向上找候选，要求同时满足：
 *  - position 为 fixed，或设了 top 偏移的 sticky（普通文档流里的头部不算）；可见、不透明
 *  - 整宽：宽度 ≥ 视口 90%，左右贴边；高度 36px ~ min(140px, 视口 22%)；上沿贴着视口顶边（-3 ~ +3px）
 *  - 自己（或点击链上 ≥ 60% 宽的元素）有底色 / 背景图 / 阴影 / 毛玻璃 / 下边框：纯透明的定位容器不算
 *  - 栏内至少 2 个可点元素（链接 / 按钮 / tab / 输入框）
 *  - 不是对话框，类名 / id 不含 cookie / consent / toast / popup / 广告 / 下载 App 之类横幅
 *  - 左 / 右 15% 处点到的也是同一个元素
 */
internal object PageTopBar {
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
  return parseFloat(cs.borderBottomWidth) > 0 && cs.borderBottomStyle !== 'none';
}
var BAD = /cookie|consent|gdpr|snackbar|toast|modal|dialog|popup|smartbanner|app-?install|install-?app|open-?in-?app|advert|adsbygoogle/;
function isBar(e, w, h, painted) {
  var cs = getComputedStyle(e);
  var pos = cs.position;
  if (pos !== 'fixed' && !(pos === 'sticky' && cs.top !== 'auto')) return false;
  if (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.05) return false;
  if (!painted) return false;
  var r = e.getBoundingClientRect();
  if (r.width < w * 0.9 || r.left > w * 0.05 || r.right < w * 0.95) return false;
  if (r.height < 36 || r.height > Math.min(140, h * 0.22)) return false;
  if (r.top < -3 || r.top > 3) return false;
  var role = e.getAttribute('role');
  if (role === 'dialog' || role === 'alertdialog' || e.getAttribute('aria-modal') === 'true') return false;
  var tag = ((e.id || '') + ' ' + (typeof e.className === 'string' ? e.className : '')).toLowerCase();
  if (BAD.test(tag)) return false;
  return e.querySelectorAll('a[href],button,[role=tab],[role=link],[role=button],input,textarea').length >= 2;
}
function hasTopBar() {
  try {
    var w = window.innerWidth, h = window.innerHeight;
    if (!w || !h) return false;
    var y = 3;
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
return hasTopBar();})()"""
}

/**
 * WebView：执行一次检测并写进 [BrowserTab.pageHasTopBar]（Gecko 不参与）。
 * 带滞后：页面的栏随滚动短暂滑走时不立刻翻转，连续两次没检测到才算没有（否则留白会来回跳）。
 */
internal suspend fun BrowserTab.pollPageTopBar() {
    val raw = suspendCancellableCoroutine<String?> { c ->
        evaluateJs(PageTopBar.DETECT_JS) { if (c.isActive) c.resume(it) }
    } ?: return
    if (raw == "true") {
        topBarMisses = 0
        pageHasTopBar = true
    } else if (pageHasTopBar && ++topBarMisses >= 2) {
        topBarMisses = 0
        pageHasTopBar = false
    }
}
