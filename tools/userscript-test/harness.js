/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// Test harness: mimics UserscriptEngine.fill() + a mock MafariGM native bridge.
const fs = require('fs');
const http = require('http');
const { chromium } = require('playwright');
const RT = fs.readFileSync(require('path').join(__dirname, '../../app/src/main/assets/mafari_gm_runtime.js'), 'utf8');

function build(meta, code, asyncFn) {
  const base = { id: 's1', name: 'T', namespace: '', version: '1.0', description: '', author: '', homepage: '', icon: '',
    runAt: 'document-idle', noframes: false, match: [], include: [], exclude: [], excludeMatch: [], userMatch: [],
    grant: [], require: [], resources: {}, meta: {}, metaBlock: '' };
  const m = JSON.stringify(Object.assign(base, meta));
  let out = '', cur = 0;
  for (const [mk, val] of [['__MAFARI_META__', m], ['__MAFARI_FN__', asyncFn ? 'async function' : 'function'], ['__MAFARI_CODE__', code + '\n']]) {
    const i = RT.indexOf(mk, cur); if (i < 0) throw new Error('marker ' + mk);
    out += RT.slice(cur, i) + val; cur = i + mk.length;
  }
  return out + RT.slice(cur);
}

const BRIDGE = `
(function(){
  if (window.MafariGM) return;
  var store = (window.top.__store = window.top.__store || {});   // shared across same-origin frames
  var res = {}; var aborted = {};
  function b64(buf){ var s='',u=new Uint8Array(buf); for(var i=0;i<u.length;i++) s+=String.fromCharCode(u[i]); return btoa(s); }
  window.MafariGM = {
    getValue: function(id,k){ var v = store[id+'::'+k]; return v===undefined?null:v; },
    setValue: function(id,k,v){ store[id+'::'+k]=v; },
    deleteValue: function(id,k){ delete store[id+'::'+k]; },
    listValues: function(id){ return JSON.stringify(Object.keys(store).filter(function(x){return x.indexOf(id+'::')===0}).map(function(x){return x.slice(id.length+2)})); },
    xhrStart: function(reqId, optsJson, bodyB64){
      var o = JSON.parse(optsJson);
      var init = { method:o.method, headers:o.headers };
      if (bodyB64) { var bin=atob(bodyB64), u=new Uint8Array(bin.length); for(var i=0;i<bin.length;i++)u[i]=bin.charCodeAt(i); init.body=u; }
      window.__lastXhrOpts = o; window.__lastXhrBody = bodyB64;
      fetch(o.url, init).then(function(r){ return r.arrayBuffer().then(function(buf){
        var hs=''; r.headers.forEach(function(v,k){ hs+=k+': '+v+'\\r\\n'; });
        if (aborted[reqId]) { res[reqId]=JSON.stringify({state:'abort'}); return; }
        res[reqId]=JSON.stringify({state:'load',status:r.status,statusText:r.statusText||'',finalUrl:r.url,headers:hs,body:b64(buf)});
      })}, function(e){ res[reqId]=JSON.stringify({state:'error',error:String(e)}); });
    },
    xhrPoll: function(id){ var r=res[id]; if(r===undefined) return null; delete res[id]; return r; },
    xhrAbort: function(id){ aborted[id]=1; },
    toast: function(m){ (window.top.__toasts=window.top.__toasts||[]).push(m); },
    openTab: function(u){ (window.top.__tabs=window.top.__tabs||[]).push(u); },
    download: function(u,n){ (window.top.__dl=window.top.__dl||[]).push([u,n]); },
    copy: function(t){ window.top.__clip=t; },
    cookies: function(){ return 'a=1; b=2'; },
    setCookie: function(u,c){ (window.top.__cookieSets=window.top.__cookieSets||[]).push(c); }
  };
})();`;

function startServer() {
  return new Promise(r => {
    const srv = http.createServer((req, res) => {
      const u = new URL(req.url, 'http://x');
      if (u.pathname === '/json') { res.setHeader('content-type', 'application/json'); return res.end('{"ok":true,"n":42}'); }
      if (u.pathname === '/echo') {
        let b = []; req.on('data', d => b.push(d)); req.on('end', () => {
          res.setHeader('content-type', 'application/json');
          res.end(JSON.stringify({ method: req.method, ct: req.headers['content-type'] || '', x: req.headers['x-test'] || '', body: Buffer.concat(b).toString('utf8') }));
        }); return;
      }
      if (u.pathname === '/nope404') { res.statusCode = 404; return res.end('nf'); }
      if (u.pathname === '/slow') { setTimeout(() => res.end('late'), 1500); return; }
      if (u.pathname === '/csp') {
        res.setHeader('Content-Security-Policy', "default-src 'self'; style-src 'self'; script-src 'self' 'unsafe-inline'");
        res.setHeader('content-type', 'text/html'); return res.end('<!doctype html><html><head></head><body><div id=t>x</div></body></html>');
      }
      if (u.pathname === '/frame') { res.setHeader('content-type', 'text/html'); return res.end('<!doctype html><html><body>frame</body></html>'); }
      if (u.pathname === '/bin') { res.setHeader('content-type', 'application/octet-stream'); return res.end(Buffer.from([0, 1, 2, 250, 251, 255])); }
      if (u.pathname === '/gbk') { res.setHeader('content-type', 'text/plain; charset=gbk'); return res.end(Buffer.from([0xc4, 0xe3, 0xba, 0xc3])); }
      res.setHeader('content-type', 'text/html');
      res.end('<!doctype html><html><head><title>t</title></head><body><div id=app>hello</div>' + (u.searchParams.get('frame') ? '<iframe id=f src="/frame"></iframe>' : '') + '</body></html>');
    });
    srv.listen(0, '127.0.0.1', () => r(srv));
  });
}
module.exports = { build, BRIDGE, startServer, chromium };
