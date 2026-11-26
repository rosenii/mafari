/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// mafari_sniff.js 的 X（Twitter）部分：用最小的假 DOM 在 vm 里跑，不需要浏览器。用法：node x-sniff.test.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const code = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/mafari_sniff.js'), 'utf8');
let pass = 0, fail = 0;
const ok = (n, c, x) => { c ? pass++ : fail++; console.log((c ? '  ok   ' : '  FAIL ') + n + (c ? '' : ' -> ' + JSON.stringify(x))); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const BASE = 'https://video.twimg.com/ext_tw_video/1700000000000000000/pu';
function tweetJson(id, dur) {
  return JSON.stringify({ data: { tweetResult: { result: {
    __typename: 'Tweet', rest_id: id,
    legacy: { id_str: id, extended_entities: { media: [{
      id_str: '999', type: 'video',
      video_info: { duration_millis: dur, variants: [
        { content_type: 'application/x-mpegURL', url: BASE + '/pl/AbCd.m3u8?tag=12' },
        { bitrate: 632000, content_type: 'video/mp4', url: BASE + '/vid/avc1/640x360/low.mp4?tag=12' },
        { bitrate: 2176000, content_type: 'video/mp4', url: BASE + '/vid/avc1/1280x720/high.mp4?tag=12' },
        { bitrate: 950000, content_type: 'video/mp4', url: BASE + '/vid/avc1/854x480/mid.mp4?tag=12' },
      ] },
    }] } },
  } } } });
}

function env(pathname) {
  const msgs = [];
  const listeners = {};
  const sandbox = {
    console, setTimeout, clearTimeout, Date, JSON, Math, Object, Array, isFinite, parseInt, parseFloat, RegExp, String, Number,
    unescape, atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    location: { hostname: 'x.com', pathname, href: 'https://x.com' + pathname },
    navigator: { userActivation: { isActive: true } },
    getComputedStyle: () => ({}),
    XMLHttpRequest: function () {},
    document: {
      readyState: 'complete', scripts: [],
      addEventListener: (n, f) => { (listeners[n] = listeners[n] || []).push(f); },
      querySelectorAll: () => [],
    },
  };
  sandbox.XMLHttpRequest.prototype.open = function () {};
  sandbox.window = sandbox; sandbox.window.top = sandbox;
  sandbox.window.addEventListener = () => {};
  sandbox.window.MafariSniff = { postMessage: (s) => msgs.push(JSON.parse(s)) };
  sandbox.fetch = (url) => Promise.resolve({
    url,
    headers: { get: (h) => (h.toLowerCase() === 'content-type' ? 'application/json' : null) },
    clone() { return { text: () => Promise.resolve(sandbox.__body) }; },
  });
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  return { sandbox, msgs, listeners };
}
const fakeVideo = (o) => Object.assign({
  tagName: 'VIDEO', src: 'blob:https://x.com/abc', currentSrc: 'blob:https://x.com/abc', muted: false, duration: 60,
  closest: () => null, getBoundingClientRect: () => ({ width: 400, height: 700 }),
}, o);

(async () => {
  // 1) 详情页：接口 JSON 返回后，没点播放就把 mp4（按码率从高到低，最多 3 条）报给原生，带时长，标 x
  {
    const { sandbox, msgs } = env('/someone/status/111');
    sandbox.__body = tweetJson('111', 61500);
    await sandbox.fetch('https://x.com/i/api/graphql/abc/TweetResultByRestId');
    await sleep(30);
    const urls = msgs.map((m) => m.u);
    ok('详情页：报 3 条 mp4', urls.length === 3, urls);
    ok('最高码率在最前', /1280x720\/high\.mp4/.test(urls[0]), urls);
    ok('带 x 标记 / 时长 / 非预览', msgs.every((m) => m.x === 1 && m.d === 61.5 && m.pv === false), msgs);
    ok('不重复报 HLS（交给原生请求拦截）', urls.every((u) => !/m3u8/.test(u)), urls);
  }
  // 2) 时间线：别的推文的视频不报；用户点开 / 取消静音后才报
  {
    const { sandbox, msgs, listeners } = env('/home');
    sandbox.__body = tweetJson('222', 90000);
    await sandbox.fetch('https://x.com/i/api/graphql/abc/HomeTimeline');
    await sleep(30);
    ok('时间线：只读不报', msgs.length === 0, msgs);
    const article = { querySelectorAll: () => [{ getAttribute: () => '/someone/status/222' }] };
    const v = fakeVideo({ muted: true, closest: () => article });
    sandbox.navigator.userActivation.isActive = false;
    listeners.playing.forEach((f) => f({ target: v }));
    ok('静音自动播放：不报', msgs.length === 0, msgs);
    v.muted = false;
    listeners.playing.forEach((f) => f({ target: v }));
    ok('取消静音：报 3 条，ua=1', msgs.length === 3 && msgs.every((m) => m.ua === 1), msgs);
  }
  // 3) 引用推文：视频归到引用推文自己的 id；GIF 标为预览
  {
    const { sandbox, msgs } = env('/a/status/300');
    const inner = JSON.parse(tweetJson('301', 5000));
    const gif = inner.data.tweetResult.result;
    gif.legacy.extended_entities.media[0].type = 'animated_gif';
    gif.legacy.extended_entities.media[0].video_info.variants = [{ bitrate: 0, content_type: 'video/mp4', url: 'https://video.twimg.com/tweet_video/G1.mp4' }];
    sandbox.__body = JSON.stringify({ outer: { rest_id: '300', legacy: { id_str: '300' }, quoted_status_result: { result: gif } } });
    await sandbox.fetch('https://x.com/i/api/graphql/abc/TweetDetail');
    await sleep(30);
    ok('引用推文的视频不算在当前详情页上', msgs.length === 0, msgs);
  }
  {
    const { sandbox, msgs } = env('/a/status/301');
    const inner = JSON.parse(tweetJson('301', 5000)).data.tweetResult.result;
    inner.legacy.extended_entities.media[0].type = 'animated_gif';
    inner.legacy.extended_entities.media[0].video_info.variants = [{ bitrate: 0, content_type: 'video/mp4', url: 'https://video.twimg.com/tweet_video/G1.mp4' }];
    sandbox.__body = JSON.stringify({ data: inner });
    await sandbox.fetch('https://x.com/i/api/2/timeline/conversation/301.json');
    await sleep(30);
    ok('GIF：报 1 条且 pv=true', msgs.length === 1 && msgs[0].pv === true, msgs);
  }
  // 4) 只有 HLS 没有 mp4：报 HLS 主清单
  {
    const { sandbox, msgs } = env('/a/status/400');
    const r = JSON.parse(tweetJson('400', 30000)).data.tweetResult.result;
    r.legacy.extended_entities.media[0].video_info.variants = r.legacy.extended_entities.media[0].video_info.variants.filter((v) => /mpegurl/i.test(v.content_type));
    sandbox.__body = JSON.stringify(r);
    await sandbox.fetch('https://x.com/i/api/graphql/abc/TweetDetail');
    await sleep(30);
    ok('只有 HLS：报主清单', msgs.length === 1 && /\/pl\/AbCd\.m3u8/.test(msgs[0].u), msgs);
  }
  // 5) 不是 X 的页面：接口 JSON 不走 X 分支；非 twimg 的地址不收
  {
    const { sandbox, msgs } = env('/a/status/500');
    const r = JSON.parse(tweetJson('500', 30000)).data.tweetResult.result;
    r.legacy.extended_entities.media[0].video_info.variants = [{ bitrate: 1, content_type: 'video/mp4', url: 'https://evil.example.com/a.mp4' }];
    sandbox.__body = JSON.stringify(r);
    await sandbox.fetch('https://x.com/i/api/graphql/abc/TweetDetail');
    await sleep(30);
    ok('非 twimg 地址不收', msgs.length === 0, msgs);
  }
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})();
