/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

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
 *
 * 另外识别「底部输入框 / 提交按钮」（聊天输入条、评论框、结算页的提交订单条，它们常常不整宽、不满足上面的判据，也可能悬空留边）：
 *  - 页面里有可输入的元素（input 不含 checkbox / radio / file 等、textarea、contenteditable），或提交按钮
 *    （type=submit 的 input / button，或文字以 提交 / 发送 / 发布 / 评论 / 回复 / 下单 / 结算 / 支付 / Submit / Send / Post / Reply 开头的按钮）；
 *  - 它位于 fixed（或设了 bottom 的 sticky）祖先里：宽度 ≥ 视口 60%，高度 32px ~ min(240px, 视口 30%)，下沿离视口底边 -40 ~ +2px，且在视口下半屏；
 *  - 容器不是对话框 / cookie / 弹窗 / 横幅类。只扫描下半屏可见的前 60 个候选元素，避免长页面上耗时。
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
function blockedHost(c) {
  if (c.closest && c.closest('[role=dialog],[role=alertdialog],[aria-modal=true],dialog')) return true;
  var tag = ((c.id || '') + ' ' + (typeof c.className === 'string' ? c.className : '')).toLowerCase();
  return /cookie|consent|gdpr|snackbar|toast|modal|dialog|popup|smartbanner|app-?install|install-?app|open-?in-?app|advert|adsbygoogle/.test(tag);
}
function shown(e) {
  var cs = getComputedStyle(e);
  return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) >= 0.05;
}
function isInputLike(e) {
  var t = e.tagName;
  if (t === 'TEXTAREA') return !e.disabled && !e.readOnly;
  if (t === 'INPUT') {
    var ty = (e.getAttribute('type') || 'text').toLowerCase();
    return !e.disabled && !e.readOnly && !/^(hidden|checkbox|radio|range|file|color|image|button|submit|reset)/.test(ty);
  }
  var ce = e.getAttribute('contenteditable');
  return ce !== null && ce.toLowerCase() !== 'false';
}
function isSubmitLike(e) {
  var t = e.tagName;
  var ty = (e.getAttribute('type') || '').toLowerCase();
  if (e.disabled) return false;
  if (t === 'INPUT') return ty === 'submit' || ty === 'image';
  if (t === 'BUTTON' && ty === 'submit') return true;
  var s = ((t === 'INPUT' ? e.value : e.innerText) || '').replace(/^\s+/, '').slice(0, 12);
  return /^(submit|send|post|reply|comment|提交|发送|发布|评论|回复|下单|结算|支付|确认订单|立即购买)/i.test(s);
}
function inBottomHost(e, w, h) {
  var c = e;
  while (c && c.nodeType === 1 && c !== document.body && c !== document.documentElement) {
    var cs = getComputedStyle(c);
    if (cs.position === 'fixed' || (cs.position === 'sticky' && cs.bottom !== 'auto')) {
      var r = c.getBoundingClientRect();
      if (shown(c) && r.width >= w * 0.6 && r.height >= 32 && r.height <= Math.min(240, h * 0.3) &&
          r.bottom >= h - 40 && r.bottom <= h + 2 && r.top >= h * 0.5 && !blockedHost(c)) return true;
    }
    c = c.parentElement;
  }
  return false;
}
function hasBottomInput(w, h) {
  var list = document.querySelectorAll('input,textarea,[contenteditable],button,[role=button]');
  var n = 0;
  for (var i = 0; i < list.length && i < 400 && n < 60; i++) {
    var e = list[i];
    var r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || r.bottom < h * 0.5 || r.top > h) continue;
    n++;
    if (!(isInputLike(e) || isSubmitLike(e)) || !shown(e)) continue;
    if (inBottomHost(e, w, h)) return true;
  }
  return false;
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
    return hasBottomInput(w, h);
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
