package dev.dsh.miuixbrowser.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 检测页面自己是否带「固定在屏幕底部的整宽栏」（底部导航 / 工具栏）。
 * 悬浮三段式只在页面有这种栏时才让出底部位置，免得盖住它；没有就让网页铺满。
 *
 * 判据（和 Gecko 的 scroll-metrics.js 保持一致）：屏幕最底边附近取 3 个点，沿元素链向上找 position 为 fixed / sticky 的祖先，
 * 要求：宽度 ≥ 视口 60%、高度在 28px ~ min(200px, 视口 30%)、下沿贴着视口底边（≤ 6px）、可见。
 * 全屏遮罩 / 弹窗太高会被排除。
 */
internal object PageBottomBar {
    const val DETECT_JS = """(function(){try{
var w=window.innerWidth,h=window.innerHeight;if(!w||!h)return false;
var xs=[w*0.2,w*0.5,w*0.8];
for(var i=0;i<xs.length;i++){
var st=document.elementsFromPoint(xs[i],h-3);
for(var j=0;j<st.length;j++){
var e=st[j];
while(e&&e.nodeType===1){
var cs=getComputedStyle(e);
if(cs.position==='fixed'||cs.position==='sticky'){
var r=e.getBoundingClientRect();
if(r.width>=w*0.6&&r.height>=28&&r.height<=Math.min(200,h*0.3)&&r.bottom>=h-6&&cs.visibility!=='hidden'&&parseFloat(cs.opacity)>0.05)return true;
}
e=e.parentElement;
}
}
}
return false;}catch(x){return false;}})()"""
}

/** WebView：执行一次检测并写进 [BrowserTab.pageHasBottomBar]（Gecko 没有 evaluateJavascript，走内容脚本回报）。 */
internal suspend fun BrowserTab.pollPageBottomBar() {
    val raw = suspendCancellableCoroutine<String?> { c ->
        evaluateJs(PageBottomBar.DETECT_JS) { if (c.isActive) c.resume(it) }
    }
    if (raw != null) pageHasBottomBar = raw == "true"
}
