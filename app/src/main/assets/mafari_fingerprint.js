/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

/*
 * Mafari Fingerprint Protection Engine 3.0
 *
 * 架构：origin-scoped document-start 注入 + 原生内联配置（详见 docs/FINGERPRINT.md）。
 *
 * 原生侧（FingerprintInjector）为每个允许注入的 origin 单独生成一份脚本：把本文件末尾的
 * 配置占位符替换成该 origin 自己的 {mask, seed, origin}，再用
 * WebViewCompat.addDocumentStartJavaScript(view, script, setOf(origin)) 注册。
 * 来源匹配由 WebView 框架在注入前完成；配置只存在于该脚本的闭包参数里，
 * 页面既不能询问「我是谁、该用什么配置」，也无法通过伪造 host / topHost 枚举其他站点的配置。
 *
 * 配置对象：
 *   mask  1 = Canvas   2 = WebGL   4 = Audio   8 = Fonts
 *   seed  32 位无符号整数，由原生侧按「会话密钥 + 可注册域名」派生
 *   rules / embedders  见下文「来源校验」
 *
 * 降级路径（WebView 不支持 DOCUMENT_START_SCRIPT）：占位符保持为 null，脚本改为调用
 * 无参的 MafariFp.fp()（仅降级时才安装该桥）。原生侧只返回「它自己维护的顶层 origin」的配置，
 * 脚本再核对返回的 origin 与 self.origin 一致后才使用。该路径只保护主框架 / 同源 iframe，
 * 且无法在页面第一个脚本之前就位，也无法对页面隐藏 seed，见文档中的安全边界。
 *
 * 来源校验（rules / embedders）：
 *   rules      注册时的来源规则集合，形如 "https://a.com" 或 "https://*.a.com"（通配符只能是前导 "*."，
 *              且不匹配裸域，裸域须单独写一条）。self.origin 必须命中其中一条，否则整段脚本不执行。
 *   embedders  允许嵌入本 frame 的跨源祖先规则（同样的规则语法），由原生按「预注册的嵌入方清单」内联；
 *              没有清单的 origin 此项为空。
 *
 * 祖先链判定：location.ancestorOrigins（浏览器提供，页面无法改写）里的每一个祖先，必须
 * 等于自身 origin、或命中自己的 rules（同站点跨子域）、或命中 embedders，否则直接退出。
 * 所以：集合外的跨源 iframe、以及「被未登记的站点嵌入」的已登记 origin，一律不保护（fail-open，这是有意的）。
 *
 * 本脚本不声称覆盖 Worker、WebGL2 PBO、所有 CSS 字体探测或 OfflineAudioContext 的 getChannelData 路径。
 */
(function (CFG) {
  'use strict';

  try {
    var OWN = '';
    try { OWN = String(self.origin); } catch (_) {}
    if (!OWN || OWN === 'null') return;

    // 来源规则匹配：规则只有两种形态，scheme://host[:port] 与 scheme://*.host[:port]。
    function matchRule(rule, origin) {
      if (typeof rule !== 'string' || typeof origin !== 'string') return false;
      var ri = rule.indexOf('://');
      var oi = origin.indexOf('://');
      if (ri < 1 || oi < 1) return false;
      if (rule.slice(0, ri) !== origin.slice(0, oi)) return false;
      var rh = rule.slice(ri + 3);
      var oh = origin.slice(oi + 3);
      if (rh.indexOf('*.') === 0) {
        var suffix = rh.slice(1); // ".host[:port]"
        return oh.length > suffix.length && oh.slice(oh.length - suffix.length) === suffix;
      }
      return rh === oh;
    }

    function matchAny(list, origin) {
      if (!list || typeof list.length !== 'number') return false;
      for (var i = 0; i < list.length; i++) {
        if (matchRule(list[i], origin)) return true;
      }
      return false;
    }

    var RULES = CFG ? CFG.rules : null;
    var EMBEDDERS = CFG ? CFG.embedders : null;

    // 主路径：self.origin 必须命中注册规则。
    if (CFG && !matchAny(RULES, OWN)) return;

    // 祖先链：每个祖先都得是自己人（同源 / 同规则集 / 预注册的嵌入方），否则不保护。
    // 降级路径（无 CFG）没有规则可用，只接受同源祖先。
    try {
      var ancestors = location.ancestorOrigins;
      if (ancestors) {
        for (var ai = 0; ai < ancestors.length; ai++) {
          var anc = ancestors[ai];
          if (anc === OWN) continue;
          if (CFG && (matchAny(RULES, anc) || matchAny(EMBEDDERS, anc))) continue;
          return;
        }
      } else if (window.top !== window) {
        return;
      }
    } catch (_) { return; }

    var MASK = 0;
    var SEED = 0;

    if (CFG) {
      // 主路径：原生内联的配置（来源已在上面校验）。
      MASK = Number(CFG.mask) | 0;
      SEED = Number(CFG.seed) >>> 0;
    } else {
      // 降级路径：无参桥 + 原生维护的顶层 origin。
      var bridge = window.MafariFp;
      if (!bridge || typeof bridge.fp !== 'function') return;
      var raw = '';
      try { raw = String(bridge.fp()); } catch (_) { return; }
      try { delete window.MafariFp; } catch (_) {}
      var parts = raw.split('|');
      if (parts.length < 3 || parts.slice(2).join('|') !== OWN) return;
      MASK = Number.parseInt(parts[0], 10) | 0;
      SEED = Number.parseInt(parts[1], 10) >>> 0;

      // 降级注入可能被执行多次（onPageStarted 等），需要幂等；主路径由原生按 origin 去重，不留标记。
      if (window.__mafariFp3) return;
      try {
        Object.defineProperty(window, '__mafariFp3', {
          value: 1, configurable: false, enumerable: false, writable: false
        });
      } catch (_) { return; }
    }

    MASK = MASK & 15;
    if (!MASK) return;

    // 低频统计：只累计「被处理的读取次数」，在页面隐藏 / 卸载时经 WebMessageListener 上报一次。
    // 只有主框架上报；通道对象在 document-start 就被捕获，之后页面改写 window.MafariTrusted 不影响。
    // 上报内容只有一个计数，页面伪造也只能影响它自己的计数；没有该通道（降级 / 未安装）时是 no-op。
    var reads = 0;
    var post = null;
    try {
      var channel = window.MafariTrusted;
      if (channel && typeof channel.postMessage === 'function' && window.top === window) {
        post = channel.postMessage.bind(channel);
      }
    } catch (_) {}

    function noteRead() { reads++; }

    function flushStats() {
      if (!post || !reads) return;
      var n = reads;
      reads = 0;
      try { post(JSON.stringify({ op: 'fp.stats', n: n })); } catch (_) {}
    }

    if (post) {
      try {
        window.addEventListener('pagehide', flushStats);
        document.addEventListener('visibilitychange', function () {
          if (document.visibilityState === 'hidden') flushStats();
        });
      } catch (_) {}
    }

    var CANVAS = 1;
    var WEBGL = 2;
    var AUDIO = 4;
    var FONTS = 8;
    var MAX_PIXELS = 4000000;

    var nativeToString = Function.prototype.toString;
    var fakeSrc = new WeakMap();

    function mix32(x) {
      x = Math.imul(x ^ (x >>> 16), 0x7feb352d);
      x = Math.imul(x ^ (x >>> 15), 0x846ca68b);
      return (x ^ (x >>> 16)) >>> 0;
    }

    function hashString(str, initial) {
      var h = (initial === undefined ? SEED : initial) >>> 0;
      for (var i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h = Math.imul(h, 16777619);
      }
      return mix32(h);
    }

    // 不使用 Math.random：同一 frame / 站点 / API 输出保持确定性。
    function keyedValue(label, a, b, c) {
      var h = hashString(String(label), SEED ^ 0x9e3779b9);
      h = mix32(h ^ (a >>> 0));
      h = mix32(h ^ (b >>> 0));
      h = mix32(h ^ (c >>> 0));
      return h;
    }

    function unitNoise(label, a, b, c) {
      return keyedValue(label, a, b, c) / 4294967296;
    }

    function wrap(orig, impl) {
      var proxy = new Proxy(orig, {
        apply: function (target, thisArg, args) {
          return impl.apply(thisArg, args);
        }
      });
      try { fakeSrc.set(proxy, nativeToString.call(orig)); } catch (_) {}
      return proxy;
    }

    function hook(obj, name, make) {
      try {
        if (!obj) return;
        var d = Object.getOwnPropertyDescriptor(obj, name);
        if (!d || typeof d.value !== 'function') return;
        d.value = wrap(d.value, make(d.value));
        Object.defineProperty(obj, name, d);
      } catch (_) {}
    }

    function hookGetter(obj, name, make) {
      try {
        if (!obj) return;
        var d = Object.getOwnPropertyDescriptor(obj, name);
        if (!d || typeof d.get !== 'function') return;
        d.get = wrap(d.get, make(d.get));
        Object.defineProperty(obj, name, d);
      } catch (_) {}
    }

    function patchToString(W) {
      try {
        var proto = W.Function.prototype;
        var original = proto.toString;
        var proxy = new Proxy(original, {
          apply: function (target, thisArg, args) {
            var source;
            try {
              if (thisArg && (typeof thisArg === 'function' ||
                              typeof thisArg === 'object')) {
                source = fakeSrc.get(thisArg);
              }
            } catch (_) {}
            return source !== undefined
              ? source
              : Reflect.apply(target, thisArg, args);
          }
        });
        fakeSrc.set(proxy, nativeToString.call(original));
        var d = Object.getOwnPropertyDescriptor(proto, 'toString');
        if (d) {
          d.value = proxy;
          Object.defineProperty(proto, 'toString', d);
        }
      } catch (_) {}
    }

    /* ---------------- Canvas / WebGL 像素扰动 ---------------- */

    var canvasIds = new WeakMap();
    var nextCanvasId = 1;

    function canvasId(canvas) {
      var id = canvasIds.get(canvas);
      if (!id) {
        id = nextCanvasId++;
        canvasIds.set(canvas, id);
      }
      return id;
    }

    // 轻量、确定性、内容相关的像素扰动。不会修改原始画布，只修改读回副本。
    function perturbPixels(data, width, height, originX, originY, id, label, changedIndexes) {
      var count = width * height;
      if (!data || width <= 0 || height <= 0 ||
          count < 64 || count > MAX_PIXELS ||
          data.length < count * 4) return false;

      var changed = false;
      for (var i = 0; i < count; i++) {
        var offset = i * 4;
        var alpha = data[offset + 3];
        if (alpha === 0) continue;

        var x = (originX + (i % width)) | 0;
        var y = (originY + ((i / width) | 0)) | 0;

        // 将像素原值纳入输入，避免不同图像共享完全相同的噪声图。
        var color = (data[offset] |
                    (data[offset + 1] << 8) |
                    (data[offset + 2] << 16) |
                    (alpha << 24)) >>> 0;

        var h = keyedValue(label, id ^ x, y, color);
        // 约 1/128 像素修改一个 RGB 通道，幅度最多 1。
        if ((h & 127) !== 0) continue;

        var channel = offset + ((h >>> 7) % 3);
        var value = data[channel];
        if (value === 0) data[channel] = 1;
        else if (value === 255) data[channel] = 254;
        else data[channel] = (h & 0x100) ? value - 1 : value + 1;

        changed = true;
        if (changedIndexes) changedIndexes.push(i);
      }
      return changed;
    }

    var htmlCanvasType = new WeakMap();
    var offscreenType = new WeakMap();

    function isWebGLType(type) {
      return /webgl|experimental/i.test(String(type || ''));
    }

    function shouldProtectCanvas(type) {
      return isWebGLType(type) ? !!(MASK & WEBGL) : !!(MASK & CANVAS);
    }

    function copyNoisyCanvas(source, width, height, create2DContext,
                             getImageData, putImageData, drawImage,
                             makeImageData, id, label) {
      var ctx = create2DContext();
      if (!ctx) return null;

      drawImage.call(ctx, source, 0, 0);
      var image = getImageData.call(ctx, 0, 0, width, height);
      var changed = [];

      if (!perturbPixels(
        image.data, width, height, 0, 0, id, label, changed
      )) return null;

      // 仅写回变更像素，避免整幅透明图像进行预乘 / 反预乘往返。
      for (var k = 0; k < changed.length; k++) {
        var index = changed[k];
        var off = index * 4;
        var x = index % width;
        var y = (index / width) | 0;
        var one = makeImageData();
        one.data[0] = image.data[off];
        one.data[1] = image.data[off + 1];
        one.data[2] = image.data[off + 2];
        one.data[3] = image.data[off + 3];
        putImageData.call(ctx, one, x, y);
      }

      return ctx.canvas || null;
    }

    var CP = window.HTMLCanvasElement && HTMLCanvasElement.prototype;
    var C2 = window.CanvasRenderingContext2D &&
             CanvasRenderingContext2D.prototype;

    if (CP && C2 && (MASK & (CANVAS | WEBGL))) {
      var origGetContext = CP.getContext;
      var origGetImageData = C2.getImageData;
      var origPutImageData = C2.putImageData;
      var origDrawImage = C2.drawImage;

      hook(CP, 'getContext', function (orig) {
        return function getContext(type) {
          var result = Reflect.apply(orig, this, arguments);
          if (result && !htmlCanvasType.has(this)) {
            htmlCanvasType.set(this, String(type || '2d'));
          }
          return result;
        };
      });

      if (MASK & CANVAS) {
        hook(C2, 'getImageData', function (orig) {
          return function getImageData(sx, sy, sw, sh) {
            var image = Reflect.apply(orig, this, arguments);
            try {
              var x = Number(sx) || 0;
              var y = Number(sy) || 0;
              if (Number(sw) < 0) x += Number(sw);
              if (Number(sh) < 0) y += Number(sh);
              perturbPixels(
                image.data, image.width, image.height,
                Math.trunc(x), Math.trunc(y),
                canvasId(this.canvas), 'canvas-pixels'
              );
              noteRead();
            } catch (_) {}
            return image;
          };
        });
      }

      function canvasExporter(methodName) {
        hook(CP, methodName, function (orig) {
          return function () {
            try {
              var width = this.width;
              var height = this.height;
              var type = htmlCanvasType.get(this) || '2d';

              if (width > 0 && height > 0 &&
                  width * height <= MAX_PIXELS &&
                  shouldProtectCanvas(type)) {
                var id = canvasId(this);
                var noisy = copyNoisyCanvas(
                  this, width, height,
                  function () {
                    var temp = document.createElement('canvas');
                    temp.width = width;
                    temp.height = height;
                    return origGetContext.call(temp, '2d');
                  },
                  origGetImageData, origPutImageData, origDrawImage,
                  function () { return new ImageData(1, 1); },
                  id, 'canvas-pixels'
                );
                if (noisy) { noteRead(); return Reflect.apply(orig, noisy, arguments); }
              }
            } catch (_) {
              // 保留原生异常行为，例如 tainted canvas 的 SecurityError。
            }
            return Reflect.apply(orig, this, arguments);
          };
        });
      }

      canvasExporter('toDataURL');
      canvasExporter('toBlob');
    }

    /* ---------------- OffscreenCanvas ---------------- */

    var OCP = window.OffscreenCanvas && OffscreenCanvas.prototype;
    var OC2 = window.OffscreenCanvasRenderingContext2D &&
              OffscreenCanvasRenderingContext2D.prototype;

    if (OCP && (MASK & (CANVAS | WEBGL))) {
      var origOffscreenGetContext = OCP.getContext;
      hook(OCP, 'getContext', function (orig) {
        return function getContext(type) {
          var result = Reflect.apply(orig, this, arguments);
          if (result && !offscreenType.has(this)) {
            offscreenType.set(this, String(type || '2d'));
          }
          return result;
        };
      });

      if (OC2 && (MASK & CANVAS)) {
        hook(OC2, 'getImageData', function (orig) {
          return function getImageData(sx, sy, sw, sh) {
            var image = Reflect.apply(orig, this, arguments);
            try {
              var x = Number(sx) || 0;
              var y = Number(sy) || 0;
              if (Number(sw) < 0) x += Number(sw);
              if (Number(sh) < 0) y += Number(sh);
              perturbPixels(
                image.data, image.width, image.height,
                Math.trunc(x), Math.trunc(y),
                canvasId(this.canvas), 'offscreen-pixels'
              );
              noteRead();
            } catch (_) {}
            return image;
          };
        });
      }

      hook(OCP, 'convertToBlob', function (orig) {
        return function convertToBlob() {
          try {
            var width = this.width;
            var height = this.height;
            var type = offscreenType.get(this) || '2d';

            if (width > 0 && height > 0 &&
                width * height <= MAX_PIXELS &&
                shouldProtectCanvas(type)) {
              var source = this;
              var id = canvasId(source);
              var noisy = copyNoisyCanvas(
                source, width, height,
                function () {
                  return new OffscreenCanvas(width, height).getContext('2d');
                },
                function (x, y, w, h) {
                  return Reflect.apply(origOCGetImageData, this, [x, y, w, h]);
                },
                function (image, x, y) {
                  return Reflect.apply(origOCPutImageData, this, [image, x, y]);
                },
                function (src, x, y) {
                  return Reflect.apply(origOCDrawImage, this, [src, x, y]);
                },
                function () { return new ImageData(1, 1); },
                id, 'offscreen-pixels'
              );
              if (noisy) { noteRead(); return Reflect.apply(orig, noisy, arguments); }
            }
          } catch (_) {}
          return Reflect.apply(orig, this, arguments);
        };
      });
    }

    /* ---------------- WebGL readPixels ---------------- */

    function patchWebGL(W, name) {
      var P = W[name] && W[name].prototype;
      if (!P) return;

      hook(P, 'readPixels', function (orig) {
        return function readPixels(x, y, width, height,
                                   format, type, pixels, dstOffset) {
          var result = Reflect.apply(orig, this, arguments);

          try {
            // 仅处理可直接访问的 RGBA / UNSIGNED_BYTE TypedArray。
            // WebGL2 的 PBO（pixels 为数值偏移）需配合 getBufferSubData 单独处理。
            if (format !== 0x1908 || type !== 0x1401 ||
                !pixels || typeof pixels === 'number' ||
                !ArrayBuffer.isView(pixels) ||
                width <= 0 || height <= 0 ||
                width * height > MAX_PIXELS) return result;

            var gl = this;
            var offset = Number(dstOffset) || 0;
            var rowLength = width;
            var skipRows = 0;
            var skipPixels = 0;
            var alignment = 4;

            if (typeof gl.PACK_ROW_LENGTH === 'number') {
              rowLength = gl.getParameter(gl.PACK_ROW_LENGTH) || width;
              skipRows = gl.getParameter(gl.PACK_SKIP_ROWS) || 0;
              skipPixels = gl.getParameter(gl.PACK_SKIP_PIXELS) || 0;
              alignment = gl.getParameter(gl.PACK_ALIGNMENT) || 4;
            }

            var rowBytes = rowLength * 4;
            var stride = Math.ceil(rowBytes / alignment) * alignment;
            var start = offset + skipRows * stride + skipPixels * 4;
            var required = start + (height - 1) * stride + width * 4;

            if (required > pixels.length) return result;

            var id = canvasId(gl.canvas);
            for (var row = 0; row < height; row++) {
              var rowStart = start + row * stride;
              var rowView = pixels.subarray(rowStart, rowStart + width * 4);
              perturbPixels(
                rowView, width, 1, x, y + row, id, 'webgl-read'
              );
            }
            noteRead();
          } catch (_) {}
          return result;
        };
      });
    }

    if (MASK & WEBGL) {
      patchWebGL(window, 'WebGLRenderingContext');
      patchWebGL(window, 'WebGL2RenderingContext');
    }

    /* ---------------- Audio：只扰动读出副本 ---------------- */

    if (MASK & AUDIO) {
      var ABP = window.AudioBuffer && AudioBuffer.prototype;

      // 不包装 getChannelData：它返回 AudioBuffer 内部声道数据，
      // 修改它会改变后续音频处理 / 播放，不符合“读指纹不影响播放”的目标。
      hook(ABP, 'copyFromChannel', function (orig) {
        return function copyFromChannel(destination, channelNumber, bufferOffset) {
          var result = Reflect.apply(orig, this, arguments);
          try {
            if (destination && destination.length <= MAX_PIXELS) {
              var start = Number(bufferOffset) || 0;
              var channel = Number(channelNumber) || 0;
              for (var i = 0; i < destination.length; i += 4) {
                var v = destination[i];
                if (!Number.isFinite(v) || v === 0) continue;
                var n = unitNoise('audio-copy', channel, start + i, 0) - 0.5;
                destination[i] = v + n * 2e-7;
              }
              noteRead();
            }
          } catch (_) {}
          return result;
        };
      });

      var AN = window.AnalyserNode && AnalyserNode.prototype;

      function perturbAnalyserArray(array, label, scale, stride) {
        if (!array || !array.length) return;
        for (var i = 0; i < array.length; i += stride) {
          var value = array[i];
          if (!Number.isFinite(value)) continue;
          var n = unitNoise(label, i, array.length, 0) - 0.5;
          if (array instanceof Uint8Array ||
              array instanceof Uint8ClampedArray) {
            array[i] = Math.max(0, Math.min(255, value + (n < 0 ? -1 : 1)));
          } else {
            array[i] = value + n * scale;
          }
        }
        noteRead();
      }

      [
        ['getFloatFrequencyData', 'audio-freq-float', 0.02, 2],
        ['getFloatTimeDomainData', 'audio-time-float', 1e-7, 4],
        ['getByteFrequencyData', 'audio-freq-byte', 1, 8],
        ['getByteTimeDomainData', 'audio-time-byte', 1, 8]
      ].forEach(function (entry) {
        hook(AN, entry[0], function (orig) {
          return function () {
            var result = Reflect.apply(orig, this, arguments);
            try {
              perturbAnalyserArray(
                arguments[0], entry[1], entry[2], entry[3]
              );
            } catch (_) {}
            return result;
          };
        });
      });
    }

    /* ---------------- Fonts ---------------- */

    if (MASK & FONTS) {
      var COMMON_FONTS = Object.create(null);
      [
        'arial', 'helvetica', 'times new roman', 'times', 'courier new',
        'courier', 'georgia', 'verdana', 'tahoma', 'trebuchet ms',
        'impact', 'roboto', 'noto sans', 'noto serif', 'droid sans',
        'sans-serif', 'serif', 'monospace', 'system-ui', 'cursive', 'fantasy'
      ].forEach(function (name) { COMMON_FONTS[name] = true; });

      function wrapTextMetrics(ctx, text, metrics) {
        noteRead();
        var key = hashString(String(ctx.font) + '|' + String(text));
        var factor = 1 + (((key % 2001) - 1000) / 1000) * 3e-4;
        return new Proxy(metrics, {
          get: function (target, prop, receiver) {
            var value = Reflect.get(target, prop, receiver);
            return typeof value === 'number' ? value * factor : value;
          }
        });
      }

      [C2, OC2].forEach(function (P) {
        hook(P, 'measureText', function (orig) {
          return function measureText(text) {
            var metrics = Reflect.apply(orig, this, arguments);
            try { return wrapTextMetrics(this, text, metrics); }
            catch (_) { return metrics; }
          };
        });
      });

      // FontFace(local()) 保护：只对纯 local() 来源做限制，不拦截包含网络 URL 的字体。
      try {
        var OriginalFontFace = window.FontFace;
        if (OriginalFontFace) {
          var localOnly = new WeakMap();

          var FontFaceProxy = new Proxy(OriginalFontFace, {
            construct: function (target, args, newTarget) {
              var face = Reflect.construct(target, args, newTarget);
              try {
                var source = args[1];
                if (typeof source === 'string' &&
                    /local\s*\(/i.test(source) &&
                    !/url\s*\(/i.test(source)) {
                  var names = [];
                  source.replace(
                    /local\s*\(\s*(['"]?)([^'")]+)\1\s*\)/ig,
                    function (_, quote, name) {
                      names.push(name.trim().toLowerCase());
                      return _;
                    }
                  );
                  localOnly.set(face, names);
                }
              } catch (_) {}
              return face;
            }
          });
          // 保持 FontFace.prototype.constructor === FontFace，并隐藏代理构造器源码差异。
          fakeSrc.set(FontFaceProxy, nativeToString.call(OriginalFontFace));
          try {
            Object.defineProperty(OriginalFontFace.prototype, 'constructor', {
              value: FontFaceProxy, configurable: true, writable: true
            });
          } catch (_) {}
          window.FontFace = FontFaceProxy;

          hook(OriginalFontFace.prototype, 'load', function (orig) {
            return function load() {
              var names = localOnly.get(this);
              if (names && !names.some(function (name) {
                return COMMON_FONTS[name] === true;
              })) {
                return Promise.reject(
                  new DOMException('Local font access is restricted.',
                                   'NotAllowedError')
                );
              }
              return Reflect.apply(orig, this, arguments);
            };
          });
        }
      } catch (_) {}

      // Local Font Access API 实际挂在 Navigator.prototype，而不是 window。
      try {
        var NavigatorPrototype = window.Navigator && Navigator.prototype;
        hook(NavigatorPrototype, 'queryLocalFonts', function (orig) {
          return function queryLocalFonts() {
            noteRead();
            return Promise.reject(
              new DOMException('Local font access is restricted.',
                               'NotAllowedError')
            );
          };
        });
      } catch (_) {}
    }

    patchToString(window);

    // 不从父 frame 向 iframe 复制 MASK / SEED：
    // 每个 frame 的配置只来自 WebView 按 origin 匹配后交付给它自己的那份脚本。
  } catch (_) {
    // 指纹保护不得阻断网页加载。
  }
})(/*@MAFARI_FP_CFG@*/null);
