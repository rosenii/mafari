/*
 * Mafari 阅读模式：检测「能否进入阅读模式」+ 提取正文 + 渲染阅读视图。WebView / Gecko 共用同一份文件。
 *
 * - WebView：原生侧用 evaluateJavascript 注入本文件（幂等），再调用
 *     window.__mafariReader.detect() / enter({dark}) / exit() / active()
 * - Gecko：本文件原样放在内置拓展 mafari-reader-ext（assets/mafari-reader-ext/reader.js）里当内容脚本；
 *   文末的「Gecko 胶水」经 runtime.connectNative('mafariReader') 把检测结果报给原生，并接收 enter / exit 指令。
 *   （src/gecko/assets/mafari-reader-ext/reader.js 必须与本文件逐字一致，ReaderCoreSyncTest 会校验。）
 *
 * 渲染方式：不新开文档，而是把页面 <body> 藏起来，在 <html> 下挂一个阅读层（closed shadow root 隔离页面样式）。
 *   - 滚动仍是文档自身的滚动，所以原生的「底栏自动收起 / 下拉刷新 / 滚动位置」逻辑照常工作；
 *   - 所有样式都走 CSSOM（element.style），不依赖 <style> 标签，不受页面 CSP 的 style-src 限制；
 *   - 退出时还原被改动的内联样式并回到进入前的滚动位置，页面状态（表单、播放进度之外的 DOM）原样保留。
 */
(function () {
  'use strict';
  var VERSION = 1;
  if (window.__mafariReader && window.__mafariReader.v === VERSION) return;

  var doc = document;
  var HOST_ID = 'mafari-reader-host';

  // ------------------------------------------------------------------ 常量（沿用 Mozilla Readability 的启发式）

  var UNLIKELY = /-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|yom-remote/i;
  var MAYBE = /and|article|body|column|content|main|shadow/i;
  var POSITIVE = /article|body|content|entry|hentry|h-entry|main|page|pagination|post|text|blog|story/i;
  var NEGATIVE = /-ad-|hidden|^hid$| hid$| hid |^hid |banner|combx|comment|com-|contact|footer|gdpr|masthead|media|meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|widget/i;
  var BLOCK_CHILD = { BLOCKQUOTE: 1, DL: 1, DIV: 1, IMG: 1, OL: 1, P: 1, PRE: 1, TABLE: 1, UL: 1, SECTION: 1, ARTICLE: 1 };
  var SKIP_PATH = /\.(pdf|mp4|m4v|webm|mp3|m4a|flac|wav|zip|rar|7z|apk|exe|dmg|jpe?g|png|gif|webp|svg|ico)$/i;
  var MIN_DETECT_LEN = 140;   // 与 Mozilla isProbablyReaderable 相同的阈值
  var MIN_DETECT_SCORE = 20;
  var MIN_ARTICLE_LEN = 200;  // 提取出的正文少于这个长度就算失败
  var MAX_NODES = 6000;

  // 整棵子树丢弃的标签
  var DROP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, IFRAME: 1, FRAME: 1, FRAMESET: 1, OBJECT: 1, EMBED: 1, APPLET: 1,
    FORM: 1, INPUT: 1, BUTTON: 1, SELECT: 1, OPTION: 1, TEXTAREA: 1, CANVAS: 1, SVG: 1, MATH: 1, AUDIO: 1, VIDEO: 1,
    LINK: 1, META: 1, NAV: 1, ASIDE: 1, FOOTER: 1, DIALOG: 1, MENU: 1, MAP: 1, AREA: 1, SOURCE: 1, TRACK: 1, HEAD: 1,
    TITLE: 1, BASE: 1, PARAM: 1
  };
  // 保留的标签（其余未知标签一律「拆壳」：丢掉标签、保留子内容）
  var KEEP = {
    P: 1, BR: 1, HR: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, DL: 1, DT: 1, DD: 1,
    BLOCKQUOTE: 1, PRE: 1, CODE: 1, EM: 1, STRONG: 1, B: 1, I: 1, U: 1, S: 1, DEL: 1, INS: 1, SUB: 1, SUP: 1,
    MARK: 1, SMALL: 1, A: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1, TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1, TR: 1,
    TD: 1, TH: 1, CAPTION: 1, DIV: 1, TIME: 1, ABBR: 1, CITE: 1, Q: 1, KBD: 1, SAMP: 1, VAR: 1
  };

  // ------------------------------------------------------------------ 小工具

  function toArray(list) { return Array.prototype.slice.call(list); }
  function cls(el) {
    var c = el && el.className;
    return typeof c === 'string' ? c : '';
  }
  function textOf(el) { return (el.textContent || '').replace(/\s+/g, ' ').trim(); }

  function isVisible(el) {
    try {
      if (el.hidden) return false;
      if (el.getAttribute('aria-hidden') === 'true' && !/fallback-image/.test(cls(el))) return false;
      var st = window.getComputedStyle(el);
      if (st.display === 'none' || st.visibility === 'hidden') return false;
      return true;
    } catch (e) { return true; }
  }

  function linkDensity(el) {
    var total = textOf(el).length;
    if (!total) return 0;
    var linkLen = 0;
    var links = el.getElementsByTagName('a');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      var coeff = href.charAt(0) === '#' ? 0.3 : 1;
      linkLen += textOf(links[i]).length * coeff;
    }
    return linkLen / total;
  }

  function classWeight(el) {
    var w = 0;
    var c = cls(el), id = el.id || '';
    if (c) { if (NEGATIVE.test(c)) w -= 25; if (POSITIVE.test(c)) w += 25; }
    if (id) { if (NEGATIVE.test(id)) w -= 25; if (POSITIVE.test(id)) w += 25; }
    return w;
  }

  function meta(name) {
    var el = doc.querySelector('meta[property="' + name + '"], meta[name="' + name + '"]');
    var v = el && el.getAttribute('content');
    return v ? v.trim() : '';
  }

  function absUrl(u) {
    try { return new URL(u, doc.baseURI || location.href).href; } catch (e) { return ''; }
  }

  // ------------------------------------------------------------------ 检测（Mozilla isProbablyReaderable 的移植）

  function shouldCheckPage() {
    if (!/^https?:$/.test(location.protocol)) return false;
    var ct = doc.contentType || 'text/html';
    if (ct !== 'text/html' && ct !== 'application/xhtml+xml') return false;
    if (!doc.body) return false;
    if (SKIP_PATH.test(location.pathname)) return false;
    // 站点首页几乎都是导航 / 列表页
    if ((location.pathname === '/' || location.pathname === '') && !location.search) return false;
    return true;
  }

  function detect() {
    try {
      if (state.active) return true;
      if (!shouldCheckPage()) return false;
      var nodes = toArray(doc.querySelectorAll('p, pre, article'));
      var brs = doc.querySelectorAll('div > br');
      if (brs.length) {
        var set = new Set(nodes);
        for (var b = 0; b < brs.length; b++) set.add(brs[b].parentNode);
        nodes = Array.from(set);
      }
      if (nodes.length > MAX_NODES) nodes.length = MAX_NODES;
      var score = 0;
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (!isVisible(node)) continue;
        var match = cls(node) + ' ' + (node.id || '');
        if (UNLIKELY.test(match) && !MAYBE.test(match)) continue;
        if (node.matches && node.matches('li p')) continue;
        var len = (node.textContent || '').trim().length;
        if (len < MIN_DETECT_LEN) continue;
        score += Math.sqrt(len - MIN_DETECT_LEN);
        if (score > MIN_DETECT_SCORE) return true;
      }
      return false;
    } catch (e) { return false; }
  }

  // ------------------------------------------------------------------ 提取（简化版 Readability）

  function initScore(el) {
    var s = 0;
    switch (el.tagName) {
      case 'DIV': s = 5; break;
      case 'PRE': case 'TD': case 'BLOCKQUOTE': s = 3; break;
      case 'ADDRESS': case 'OL': case 'UL': case 'DL': case 'DD': case 'DT': case 'LI': case 'FORM': s = -3; break;
      case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6': case 'TH': s = -5; break;
    }
    if (el.tagName === 'ARTICLE') s += 15;
    if (el.getAttribute && el.getAttribute('itemprop') === 'articleBody') s += 30;
    return s + classWeight(el);
  }

  function findTop() {
    var body = doc.body;
    if (!body) return null;
    var scores = new Map();
    var elems = toArray(body.querySelectorAll('p, pre, blockquote, td, div, section'));
    if (elems.length > MAX_NODES) elems.length = MAX_NODES;
    for (var i = 0; i < elems.length; i++) {
      var el = elems[i];
      var tag = el.tagName;
      if (tag === 'DIV' || tag === 'SECTION') {
        // 只把「没有块级子元素」的 div 当成段落（直接放文字的 div）
        var hasBlock = false;
        for (var k = 0; k < el.children.length; k++) {
          if (BLOCK_CHILD[el.children[k].tagName]) { hasBlock = true; break; }
        }
        if (hasBlock) continue;
      }
      if (!isVisible(el)) continue;
      var text = textOf(el);
      if (text.length < 25) continue;
      var match = cls(el) + ' ' + (el.id || '');
      if (UNLIKELY.test(match) && !MAYBE.test(match)) continue;
      var s = 1 + (text.split(/[,，、。]/).length - 1) + Math.min(Math.floor(text.length / 100), 3);
      var level = 0;
      for (var a = el.parentElement; a && a !== doc.documentElement && level < 5; a = a.parentElement, level++) {
        if (!scores.has(a)) scores.set(a, initScore(a));
        var divider = level === 0 ? 1 : (level === 1 ? 2 : level * 3);
        scores.set(a, scores.get(a) + s / divider);
      }
    }
    var top = null, topScore = -Infinity;
    scores.forEach(function (v, node) {
      var adj = v * (1 - linkDensity(node));
      scores.set(node, adj);
      if (adj > topScore) { topScore = adj; top = node; }
    });
    if (!top) return null;
    // 向上找得分更高的父级（正文被多层容器包住时）
    var lastScore = scores.get(top);
    var threshold = lastScore / 3;
    var parent = top.parentElement;
    while (parent && parent.tagName !== 'BODY') {
      if (!scores.has(parent)) { parent = parent.parentElement; continue; }
      var ps = scores.get(parent);
      if (ps < threshold) break;
      if (ps > lastScore) { top = parent; break; }
      lastScore = ps;
      parent = parent.parentElement;
    }
    return { top: top, scores: scores };
  }

  /** 被丢弃的元素（在原页面上判断：隐藏 / 固定定位的浮层 / 明显的非正文） */
  function shouldDrop(el, insideKeep) {
    var tag = el.tagName;
    if (DROP[tag]) return true;
    try {
      if (el.hidden) return true;
      if (el.getAttribute('aria-hidden') === 'true' && !/fallback-image/.test(cls(el))) return true;
      var st = window.getComputedStyle(el);
      if (st.display === 'none' || st.visibility === 'hidden') return true;
      if (st.position === 'fixed') return true; // 悬浮分享条 / 弹窗
    } catch (e) {}
    if (!insideKeep) {
      var match = cls(el) + ' ' + (el.id || '');
      if (match.length > 1 && UNLIKELY.test(match) && !MAYBE.test(match)) return true;
    }
    return false;
  }

  /** 原节点 o 与其深拷贝 c 同步遍历：在拷贝上删掉 o 中应丢弃的子树 */
  function pruneClone(o, c, insideKeep) {
    var oc = toArray(o.children), cc = toArray(c.children);
    for (var i = 0; i < oc.length; i++) {
      var oe = oc[i], ce = cc[i];
      if (!ce) continue;
      var tag = oe.tagName;
      var keep = insideKeep || tag === 'PRE' || tag === 'CODE' || tag === 'FIGURE' || tag === 'TABLE';
      if (shouldDrop(oe, keep)) { ce.parentNode.removeChild(ce); continue; }
      pruneClone(oe, ce, keep);
    }
  }

  function bestImgSrc(img) {
    var lazy = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') ||
      img.getAttribute('data-actualsrc') || img.getAttribute('data-echo') || '';
    var src = img.getAttribute('src') || '';
    if (lazy && (!src || /^data:/i.test(src) || /placeholder|blank|spacer|loading/i.test(src))) src = lazy;
    if (!src || /^data:image\/gif/i.test(src)) {
      var ss = img.getAttribute('data-srcset') || img.getAttribute('srcset') || '';
      if (ss) {
        var parts = ss.split(',');
        var last = parts[parts.length - 1].trim().split(/\s+/)[0];
        if (last) src = last;
      }
    }
    if (!src) {
      var pic = img.parentElement && img.parentElement.tagName === 'PICTURE' ? img.parentElement : null;
      if (pic) {
        var srcEl = pic.querySelector('source[srcset], source[data-srcset]');
        if (srcEl) {
          var s2 = (srcEl.getAttribute('srcset') || srcEl.getAttribute('data-srcset') || '').split(',');
          var l2 = s2[s2.length - 1].trim().split(/\s+/)[0];
          if (l2) src = l2;
        }
      }
    }
    return src;
  }

  var TRACKER_IMG = /(^data:image\/gif;base64,R0lGODlhAQABA)|pixel|tracking|beacon|spacer|1x1|\/ads?\//i;

  /** 白名单重建：只复制允许的标签和属性，URL 绝对化，样式由 [styler] 以 CSSOM 方式附上 */
  function sanitize(node, outParent, styler, inPre) {
    for (var n = node.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) {
        outParent.appendChild(doc.createTextNode(n.nodeValue));
        continue;
      }
      if (n.nodeType !== 1) continue;
      var tag = n.tagName;
      var cur = n; // 实际读取属性 / 递归的节点：<picture> 换成它里面的 <img>
      if (tag === 'PICTURE') {
        cur = n.querySelector('img');
        if (!cur) continue;
        tag = 'IMG';
      }
      if (DROP[tag]) continue;
      if (!KEEP[tag]) { sanitize(cur, outParent, styler, inPre); continue; } // 拆壳
      var low = tag.toLowerCase();
      var el = doc.createElement(low);
      if (tag === 'A') {
        var href = cur.getAttribute('href') || '';
        var abs = href && href.charAt(0) !== '#' && !/^\s*javascript:/i.test(href) ? absUrl(href) : '';
        if (abs && /^(https?:|mailto:|tel:)/i.test(abs)) {
          el.setAttribute('href', abs);
        } else {
          sanitize(cur, outParent, styler, inPre); // 页内锚点 / 非法链接：只留文字
          continue;
        }
      } else if (tag === 'IMG') {
        var src = bestImgSrc(cur);
        var u = /^data:image\//i.test(src) ? src : absUrl(src);
        if (!u || !/^(https?:|data:image\/)/i.test(u) || TRACKER_IMG.test(u)) continue;
        var wAttr = parseInt(cur.getAttribute('width') || '0', 10);
        var hAttr = parseInt(cur.getAttribute('height') || '0', 10);
        if ((wAttr && wAttr < 40) || (hAttr && hAttr < 40)) continue; // 图标 / 追踪像素
        el.setAttribute('src', u);
        var alt = cur.getAttribute('alt');
        if (alt) el.setAttribute('alt', alt);
        el.setAttribute('loading', 'lazy');
      } else if (tag === 'TD' || tag === 'TH') {
        var cs = cur.getAttribute('colspan'), rs = cur.getAttribute('rowspan');
        if (cs && /^\d{1,2}$/.test(cs)) el.setAttribute('colspan', cs);
        if (rs && /^\d{1,2}$/.test(rs)) el.setAttribute('rowspan', rs);
      } else if (tag === 'OL') {
        var st = cur.getAttribute('start');
        if (st && /^\d{1,5}$/.test(st)) el.setAttribute('start', st);
      } else if (tag === 'ABBR') {
        var ti = cur.getAttribute('title');
        if (ti) el.setAttribute('title', ti);
      }
      styler(el, low, inPre);
      if (tag !== 'IMG') sanitize(cur, el, styler, inPre || tag === 'PRE');
      outParent.appendChild(el);
    }
  }

  /** 去掉空壳、链接堆（相关阅读 / 标签云）等 */
  function tidy(root, title) {
    var firstHeadingDone = false;
    var hs = root.querySelectorAll('h1, h2');
    for (var h = 0; h < hs.length && !firstHeadingDone; h++) {
      if (textOf(hs[h]) === title) { hs[h].parentNode.removeChild(hs[h]); firstHeadingDone = true; }
    }
    var list = toArray(root.querySelectorAll('ul, ol, table, div, figure, p, span, li, blockquote'));
    for (var i = list.length - 1; i >= 0; i--) {
      var el = list[i];
      if (!el.parentNode) continue;
      var t = textOf(el);
      var hasMedia = !!el.querySelector('img, pre, table');
      if (!t.length && !hasMedia) { el.parentNode.removeChild(el); continue; }
      var tag = el.tagName;
      if ((tag === 'UL' || tag === 'OL' || tag === 'DIV' || tag === 'TABLE') && t.length < 800 && linkDensity(el) > 0.5 && !hasMedia) {
        el.parentNode.removeChild(el);
      }
    }
  }

  function pickTitle(top) {
    var h1 = top && top.querySelector ? top.querySelector('h1') : null;
    var t = h1 ? textOf(h1) : '';
    if (!t || t.length < 4 || t.length > 200) {
      var anyH1 = doc.querySelector('h1');
      t = anyH1 ? textOf(anyH1) : '';
    }
    if (!t || t.length < 4 || t.length > 200) t = meta('og:title') || meta('twitter:title');
    if (!t) {
      t = (doc.title || '').trim();
      var segs = t.split(/\s[|\-–—·»]\s/);
      if (segs.length > 1) {
        segs.sort(function (a, b) { return b.length - a.length; });
        t = segs[0].trim();
      }
    }
    return t;
  }

  function pickByline() {
    var cands = [meta('author'), meta('article:author'), meta('og:article:author')];
    for (var i = 0; i < cands.length; i++) {
      if (cands[i] && !/^https?:/i.test(cands[i]) && cands[i].length < 100) return cands[i];
    }
    var el = doc.querySelector('[rel~="author"], [itemprop~="author"], .byline, .author, .article-author, .post-author');
    var t = el ? textOf(el).replace(/^(by|作者[:：]?|文\s*[/／|:：])\s*/i, '') : '';
    return t.length >= 2 && t.length <= 100 ? t : '';
  }

  function pickDate() {
    var raw = meta('article:published_time') || meta('og:article:published_time') || meta('date') || '';
    if (!raw) { var te = doc.querySelector('time[datetime]'); raw = te ? te.getAttribute('datetime') : ''; }
    if (!raw) return '';
    try {
      var d = new Date(raw);
      if (isNaN(d.getTime())) return '';
      return d.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
    } catch (e) { return ''; }
  }

  /** 提取结果：{ title, byline, site, date, nodes: <div> 正文容器的构建函数 } ；失败返回 null */
  function extract(styler) {
    var found = findTop();
    if (!found) return null;
    var top = found.top, scores = found.scores;
    var parent = top.parentElement;
    var picked = [];
    if (!parent || top.tagName === 'BODY') {
      picked.push(top);
    } else {
      var threshold = Math.max(10, (scores.get(top) || 0) * 0.2);
      var sibs = toArray(parent.children);
      for (var i = 0; i < sibs.length; i++) {
        var sib = sibs[i];
        if (sib === top) { picked.push(sib); continue; }
        var ok = (scores.get(sib) || 0) >= threshold;
        if (!ok && sib.tagName === 'P') {
          var t = textOf(sib), ld = linkDensity(sib);
          if (t.length > 80 && ld < 0.25) ok = true;
          else if (t.length > 0 && t.length <= 80 && ld === 0 && /[.。!！?？]/.test(t)) ok = true;
        }
        if (ok && !shouldDrop(sib, true)) picked.push(sib);
      }
    }
    var title = pickTitle(top);
    var holder = doc.createElement('div');
    for (var p = 0; p < picked.length; p++) {
      var clone = picked[p].cloneNode(true);
      pruneClone(picked[p], clone, true);
      holder.appendChild(clone);
    }
    var article = doc.createElement('div');
    sanitize(holder, article, styler, false);
    tidy(article, title);
    var plain = textOf(article);
    if (plain.length < MIN_ARTICLE_LEN) return null;
    return {
      title: title,
      byline: pickByline(),
      site: meta('og:site_name') || location.hostname.replace(/^www\./, ''),
      date: pickDate(),
      article: article
    };
  }

  // ------------------------------------------------------------------ 渲染

  var LIGHT = { bg: '#faf9f6', fg: '#1c1c1e', muted: '#6b6b70', link: '#1a5fd0', border: '#dcdad3', codeBg: '#efede6' };
  var DARK = { bg: '#17181a', fg: '#d9d9db', muted: '#8e8e93', link: '#6cb2ff', border: '#3a3a3c', codeBg: '#242527' };
  var FONT = 'system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans CJK SC","PingFang SC","Microsoft YaHei",sans-serif';
  var MONO = 'ui-monospace,SFMono-Regular,Menlo,Consolas,"Liberation Mono",monospace';

  function makeStyler(T) {
    var heading = 'margin:1.6em 0 .6em;line-height:1.35;font-weight:700;';
    var table = {
      P: 'margin:0 0 1.1em;',
      H1: heading + 'font-size:1.5em;', H2: heading + 'font-size:1.35em;', H3: heading + 'font-size:1.2em;',
      H4: heading + 'font-size:1.1em;', H5: heading + 'font-size:1em;', H6: heading + 'font-size:1em;',
      A: 'color:' + T.link + ';text-decoration:underline;word-break:break-word;',
      IMG: 'display:block;max-width:100%;height:auto;margin:1em auto;border-radius:6px;',
      FIGURE: 'margin:1.2em 0;',
      FIGCAPTION: 'font-size:.85em;color:' + T.muted + ';text-align:center;margin-top:.4em;line-height:1.5;',
      BLOCKQUOTE: 'margin:1.2em 0;padding:.1em 0 .1em 1em;border-left:3px solid ' + T.border + ';color:' + T.muted + ';',
      PRE: 'margin:1.2em 0;padding:12px;overflow-x:auto;white-space:pre;font-family:' + MONO + ';font-size:.85em;line-height:1.5;background:' + T.codeBg + ';border-radius:8px;',
      UL: 'margin:0 0 1.1em;padding-left:1.4em;', OL: 'margin:0 0 1.1em;padding-left:1.6em;',
      LI: 'margin:.3em 0;',
      DL: 'margin:0 0 1.1em;', DT: 'font-weight:700;', DD: 'margin:0 0 .5em 1.2em;',
      TABLE: 'display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;margin:1em 0;font-size:.9em;',
      TH: 'border:1px solid ' + T.border + ';padding:6px 8px;text-align:left;font-weight:700;',
      TD: 'border:1px solid ' + T.border + ';padding:6px 8px;',
      HR: 'border:0;border-top:1px solid ' + T.border + ';margin:1.6em 0;',
      MARK: 'background:#fff3a3;color:#1c1c1e;'
    };
    return function (el, lowTag, inPre) {
      var up = lowTag.toUpperCase();
      var css = table[up];
      if (up === 'CODE') css = inPre ? 'font-family:' + MONO + ';' : 'font-family:' + MONO + ';font-size:.9em;background:' + T.codeBg + ';padding:.1em .3em;border-radius:4px;';
      if (up === 'KBD' || up === 'SAMP') css = 'font-family:' + MONO + ';font-size:.9em;';
      if (css) el.style.cssText = css;
    };
  }

  var state = { active: false, host: null, y: 0, saved: [] };

  /** 以 important 改内联样式，并记下旧值，退出时还原 */
  function setImp(el, prop, val) {
    state.saved.push({ el: el, prop: prop, val: el.style.getPropertyValue(prop), pri: el.style.getPropertyPriority(prop) });
    el.style.setProperty(prop, val, 'important');
  }
  function restoreStyles() {
    for (var i = state.saved.length - 1; i >= 0; i--) {
      var s = state.saved[i];
      try {
        if (s.val) s.el.style.setProperty(s.prop, s.val, s.pri); else s.el.style.removeProperty(s.prop);
      } catch (e) {}
    }
    state.saved = [];
  }

  function isRtl() {
    var d = (doc.documentElement.getAttribute('dir') || '').toLowerCase();
    if (d === 'rtl') return true;
    return /^(ar|he|fa|ur|ps|sd|yi)\b/i.test(doc.documentElement.lang || '');
  }

  function enter(opts) {
    try {
      if (state.active) return true;
      if (!doc.body) return false;
      opts = opts || {};
      var T = opts.dark ? DARK : LIGHT;
      var scale = opts.fontScale > 0.5 && opts.fontScale < 3 ? opts.fontScale : 1;
      var data = extract(makeStyler(T));
      if (!data) return false;

      // 页面里正在播放的媒体先暂停（藏起来后还在后台出声很怪）
      toArray(doc.querySelectorAll('video, audio')).forEach(function (m) { try { m.pause(); } catch (e) {} });

      var host = doc.createElement('div');
      host.id = HOST_ID;
      var hs = host.style;
      hs.setProperty('all', 'initial', 'important');
      hs.setProperty('display', 'block', 'important');
      hs.setProperty('position', 'relative', 'important');
      hs.setProperty('box-sizing', 'border-box', 'important');
      hs.setProperty('width', '100%', 'important');
      hs.setProperty('min-height', '100vh', 'important');
      hs.setProperty('background', T.bg, 'important');
      hs.setProperty('color', T.fg, 'important');
      hs.setProperty('color-scheme', opts.dark ? 'dark' : 'light', 'important');
      var root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;

      var wrap = doc.createElement('div');
      var ws = wrap.style;
      ws.maxWidth = '42rem';
      ws.margin = '0 auto';
      ws.boxSizing = 'border-box';
      ws.padding = '28px 20px 120px';
      try { ws.paddingTop = 'calc(env(safe-area-inset-top, 0px) + 28px)'; } catch (e) {}
      ws.fontFamily = FONT;
      ws.fontSize = (18 * scale) + 'px';
      ws.lineHeight = '1.8';
      ws.color = T.fg;
      ws.wordBreak = 'break-word';
      ws.overflowWrap = 'anywhere';
      ws.setProperty('-webkit-text-size-adjust', '100%');
      if (isRtl()) wrap.setAttribute('dir', 'rtl');

      if (data.title) {
        var h = doc.createElement('h1');
        h.textContent = data.title;
        h.style.cssText = 'margin:0 0 .5em;font-size:1.5em;line-height:1.35;font-weight:800;';
        wrap.appendChild(h);
      }
      var bits = [data.site, data.byline, data.date].filter(Boolean);
      if (bits.length) {
        var m = doc.createElement('div');
        m.textContent = bits.join(' · ');
        m.style.cssText = 'font-size:.78em;line-height:1.5;color:' + T.muted + ';margin:0 0 1.2em;padding-bottom:1em;border-bottom:1px solid ' + T.border + ';';
        wrap.appendChild(m);
      }
      wrap.appendChild(data.article);
      root.appendChild(wrap);

      state.y = window.pageYOffset || 0;
      var html = doc.documentElement;
      setImp(doc.body, 'display', 'none');
      setImp(html, 'overflow', 'auto');
      setImp(html, 'height', 'auto');
      setImp(html, 'min-height', '100%');
      setImp(html, 'background', T.bg);
      html.appendChild(host);
      state.host = host;
      state.active = true;
      window.scrollTo(0, 0);
      return true;
    } catch (e) {
      try { if (state.host && state.host.parentNode) state.host.parentNode.removeChild(state.host); } catch (e2) {}
      restoreStyles();
      state.active = false;
      state.host = null;
      return false;
    }
  }

  function exit() {
    if (!state.active) return;
    try { if (state.host && state.host.parentNode) state.host.parentNode.removeChild(state.host); } catch (e) {}
    restoreStyles();
    state.host = null;
    state.active = false;
    try { window.scrollTo(0, state.y); } catch (e) {}
  }

  var api = {
    v: VERSION,
    detect: detect,
    enter: enter,
    exit: exit,
    active: function () { return state.active; }
  };
  window.__mafariReader = api;

  // ------------------------------------------------------------------ Gecko 胶水（仅在内置拓展的内容脚本里运行）
  //
  // 原生侧（GeckoReader.kt）为每个标签的 session 登记了 "mafariReader" 的消息委托：
  //   内容脚本 → 原生：{ t:'state', readerable, active, url }
  //   原生 → 内容脚本：{ t:'hi' }（握手，回一条 state）、{ t:'enter', dark }、{ t:'exit' }

  var isGeckoExt = false;
  try {
    isGeckoExt = typeof browser !== 'undefined' && browser.runtime && browser.runtime.id === 'reader@mafari.dev' &&
      typeof browser.runtime.connectNative === 'function';
  } catch (e) { isGeckoExt = false; }
  if (!isGeckoExt) return;

  var port = null;
  var everConnected = false;
  var tries = 0;
  var readerable = false;
  var lastHref = location.href;

  function post() {
    if (!port) return;
    try { port.postMessage({ t: 'state', readerable: readerable, active: state.active, url: location.href }); } catch (e) {}
  }

  function connect() {
    try {
      port = browser.runtime.connectNative('mafariReader');
      port.onMessage.addListener(function (m) {
        if (!m || typeof m !== 'object') return;
        everConnected = true;
        if (m.t === 'enter') { if (!enter({ dark: !!m.dark, fontScale: m.fontScale })) readerable = false; }
        else if (m.t === 'exit') { exit(); }
        post();
      });
      port.onDisconnect.addListener(function () {
        port = null;
        // 原生那边的会话委托可能晚于页面就绪：首次握手前断开就退避重连几次
        if (!everConnected && tries < 6) setTimeout(connect, 300 * Math.pow(2, tries++));
      });
    } catch (e) { port = null; }
  }

  function check() {
    readerable = detect();
    post();
    return readerable;
  }

  function schedule() {
    check();
    [1500, 4000].forEach(function (d) { setTimeout(function () { if (!readerable && !state.active) check(); }, d); });
  }

  connect();
  schedule();

  // 单页应用：地址变了就重新检测（内容脚本拿不到页面的 pushState，轮询最省事）
  setInterval(function () {
    if (location.href === lastHref) return;
    lastHref = location.href;
    if (state.active) exit();
    readerable = false;
    post();
    setTimeout(schedule, 700);
  }, 1500);

  window.addEventListener('pageshow', function (ev) { if (ev.persisted) post(); });
})();
