# Changelog
## [2.1.73]

- **The bar-detection also recognises bottom input boxes and submit buttons** (chat composers, comment boxes, checkout "submit order" bars): a visible input / textarea / contenteditable or a submit button inside a fixed (or bottom-sticky) container that is >= 60% wide and sits at the bottom of the viewport now counts as a page bottom bar, so the split bar yields the row instead of covering it. Same logic in `PageBottomBar.kt` (WebView) and `scroll-metrics.js` (Gecko).
- **Long-press on the address pill opens the start page** (follows Settings → Customize home → Custom start page, same as the home button) instead of opening a new tab. The pill plays an elastic shrink-then-overshoot pop while it opens.
- **The compact title pill (shown while scrolling) is lower**: 32dp → 26dp, corner 16dp → 13dp.
- **WebView debug: new "Record page performance" switch** (page performance section, default on = previous behaviour). When off, FCP / LCP and new entries are no longer collected; existing records are kept.
- **WebView debug: the "Preconnect" info line now says which path is available** (queued `enqueuePreconnect`, direct `preconnect`, or neither / no multi-profile) instead of a bare "not supported". `enqueuePreconnect` only exists since androidx.webkit 1.17.0-alpha04, so a system WebView that is not new enough reports it as unsupported even when it is the latest installed one.
- **Cold start: WebView extensions no longer block the first frame / first tab.** Previously the first WebView tab built the extension system, read every extension's state + manifest, read the shim and read / bundled every content script **on the main thread** before the first page could load. Now `StartupCoordinator.prewarmExtensions` does all of that on a background thread right after process creation (`ExtensionSystem.prewarm`, `WebViewExtensionHost.prewarm`); `ExtensionInjection.build` is split into `prepare` (file reads, bundling, localisation; cached per extension, invalidated on install / enable / disable / uninstall, permission or dynamic-script change) and `finish` (per-tab string assembly with the tab id), so the main thread only concatenates. Output is byte-identical to before (`ExtensionInjectionTest.prepareThenFinishMatchesBuildForEveryTab`).
- **Cold start: extension background pages start when the main thread is idle and are staggered** (200 ms apart, 3 s idle timeout) instead of all being created in one main-thread burst 1.5 s after the first frame. The start delay itself is unchanged; a delayed start re-checks that the extension is still enabled and not already running.
- Not compiled here (no Android toolchain).

## [2.1.70]
## WebView extensions: blank popups (Dark Reader / Tampermonkey)

- **Dark Reader popup was blank.** `chrome.extension` in the page shim was the raw object, not wrapped by `cbObj`, so `chrome.extension.isAllowedFileSchemeAccess(cb)` / `isAllowedIncognitoAccess(cb)` never called their callback. Dark Reader's `collectData()` does `Promise.all([... new Promise(r => chrome.extension.isAllowedFileSchemeAccess(r)) ...])`, so on Firefox (port `getData`) the background never answered and the popup stayed empty. `chrome.extension` is now `cbObj(ext)`; promise-style `browser.extension.*` is unchanged.
- **`runtime.sendMessage` no longer rejects with `NO_RECEIVER` when a listener exists but does not respond** (Firefox semantics: resolves `undefined`). Only "no listener at all / undeliverable" is `NO_RECEIVER`. The shim now replies `{noResponse:true}` (listener present, no answer) vs `{noReceiver:true}` (no listener); `MessageBroker` resolves `undefined` when every target finished without a real reply and at least one had a listener. A real reply from any context still wins. Tampermonkey's popup sends fire-and-forget messages and then reads data over a Port; the rejection surfaced as "unable to load tree".
- Diagnostics: a `listener present but no response` line is logged for `noResponse` replies.
- Tests: `ext-shim-test` (chrome.extension callback style, noResponse reply), `MessageBrokerTest.listenerWithoutResponseResolvesUndefinedNotNoReceiver`. Not compiled for Android here. Two shim tests (`windows.create` UNSUPPORTED, API wiring list) already failed in the uploaded tree and are unchanged.

## WebView adoption, part 2: incognito Profile, WebMessageListener bridges

- **Incognito tabs use a separate WebView Profile** (`IncognitoProfile`, `MULTI_PROFILE`). The tab's WebView is bound with `setProfile` right after creation (incognito is fixed at tab creation, which is already how `createTab` uses it). Cookies, cache, localStorage / IndexedDB, service workers and site permissions are isolated from the default profile; logging in inside an incognito tab now persists for that session (the old process-wide `setAcceptCookie(false)` flip blocked all cookies). When the last bound WebView is destroyed the whole profile is deleted (retry once; leftovers from a killed process are removed on first incognito use, plus a sweep after the UI is shown). Without `MULTI_PROFILE` the old behaviour is kept.
- Cookie access that used the global `CookieManager` is now profile-aware: `applyPrivacy`, initial tab setup, `GmBridge` (GM_cookie / `GM_xmlhttpRequest` cookies) and the temp WebView of `window.open` (bound to the opener's profile). Cookie backup reads the default profile path and is unchanged; incognito downloads still send no cookies (`withCookies = !tab.incognito`).
- **One-way bridges moved to `WebMessageListener`** (`OneWayBridge`): `MafariTr` (page translation) and `MafariElement` (ad marking long-press) now only accept messages from the main frame with an http(s) origin, string payloads under a length cap. `addJavascriptInterface` remains as the fallback when `WEB_MESSAGE_LISTENER` is unsupported; the page scripts feature-detect `postMessage` vs the old method (`mafari_translate.js` and its Gecko copy kept identical; node test added).
- Not converted (they need a synchronous return value): `MafariAdblock.css`, `MafariFp.fp` (fallback only), `MafariBlob.begin` / chunk streaming. Tests: `OneWayBridgeTest`.
- Not compiled here; see the delivery notes for the device checklist.

## WebView adoption: render-process recovery, UA client hints, async startup

- **Main tabs now survive a renderer crash / OOM kill.** `TabWebViewSetup` handles `onRenderProcessGone` (returns `true`, so the app is no longer taken down with the renderer). `BrowserState.replaceCrashedTab` swaps in a fresh tab at the same position, keeping URL / title / desktop mode / opener chain / `tabs` row key. A foreground tab shows the existing "page crashed" error page (`ErrorPages.crashed`) and waits for Retry (no auto-reload, so a page that crashes on load cannot loop); a background tab becomes a pending-restore tab and loads when switched to.
- **UA client hints follow the UA string.** New `UaClientHints` derives platform / mobile from the effective UA and applies `WebSettingsCompat.setUserAgentMetadata` (`USER_AGENT_METADATA`) after every `userAgentString` change, so `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform` / `navigator.userAgentData` no longer contradict desktop mode or a custom UA. When the target UA has the same platform and mobile flag as the system default it is left to WebView (the reduced UA's Android version is frozen and less accurate). Brand list untouched. Test: `UaClientHintsTest`.
- **Async WebView start-up.** New `WebViewStartup` calls `WebViewCompat.startUpWebView` from `Application.onCreate` (main process, WebView default engine only). The cold-start content gate (`StartupCoordinator.awaitContentReady`) waits for it (<= 1.5 s, in parallel with the encrypted-DB warm-up), so the first `BrowserTab` WebView is created only after start-up finished; if it never finishes or is unsupported the old synchronous behaviour applies.
- Not compiled here; `startUpWebView` / `UserAgentMetadata` signatures are written from the androidx.webkit docs, see the device checklist in the delivery notes.

## WebView GM channel: step 1c (legacy bridge only on demand, `@connect`)
## [2.1.68]

- Isolated-world devices no longer expose `MafariGM` to page JS: `setupWebView` only creates the shared `GmBridge`; `TabScripts.ensureLegacyBridge()` attaches the `addJavascriptInterface` bridge only when the system WebView lacks isolated worlds or the channel failed to install.
- `TabScripts.injectMissing` skips late `evaluateJavascript` injection while the channel is active (such a script would have neither bridge nor relay).
- New `GmConnectPolicy` (pure JVM, same rules as the Gecko background page): `@connect` is enforced for `GM_xmlhttpRequest` (every redirect hop re-checked) and, more strictly, `GM_cookie` on the channel path. Private/LAN hosts need an explicit `@connect`.
- Script quick menu subtitle now shows the runtime environment after the running count ("N scripts running · Isolated-world channel" / "· In-page bridge", the latter with a red "!" that toasts an unsafe warning and advises updating WebView): `TabScripts.bridgeMode()` reports isolated world when the GM channel (or Gecko relay) is in use and in-page when the legacy `MafariGM` bridge is attached.
- Tests: `GmConnectPolicyTest`. Not compiled for Android here; see the device checklist in `docs/WEBVIEW_GM_CHANNEL.md`.

## androidx.webkit 1.19.0-alpha02 + true isolated worlds

- Bumped `androidx.webkit` 1.18.0 -> 1.19.0-alpha02.
- Content scripts and the message bridge now live in a per-extension `JavaScriptExecutionWorld` (needs `JS_INJECTION_IN_FRAME_AND_WORLD`; falls back to the old page-world path otherwise). `world: "MAIN"` scripts go to the page world without a bridge or extension APIs.
- `scripting.executeScript` (ISOLATED) runs in that world via `JavaScriptReplyProxy.executeJavaScript` when the extension already has a content script on the tab; otherwise it falls back to the page world.
- Shim: `mainWorld` / `isolatedWorld` modes; `browser`/`chrome` become globals only inside the isolated world.
- Not wired: `Profile.configureIsolatedSites` (experimental, opt-in) added in 1.19.0-alpha02.


## WebExtension adapter follow-up

- Added the `webRequest.onBeforeRequest` blocking decision path for `cancel` and `redirectUrl`, with a bounded 350 ms wait on WebView's request IO callback and fail-open timeout behavior.
- Bound blocking replies to the extension page context that registered a matching blocking listener. This prevents unrelated popup/options/background contexts from sending an empty reply that wins the same extension's request decision.
- Revalidated the responding extension's enabled state, `webRequest` / `webRequestBlocking` grants, original host access, and redirect target host access before applying a decision.
- Validated `webRequest` URL filters and rejected unsupported `extraInfoSpec` values instead of silently pretending to support request/response header mutation.
- Expanded the Mafari-only `browser.mafari.userscripts` / `chrome.mafari.userscripts` API with source URL install, per-script refresh, batch refresh, and auto-update mode accessors. The API is restricted to extension pages and the explicit `mafariScripts` permission.
- Updated the WebExtension compatibility matrix and added API usage documentation.


## WebExtension adapter continued implementation

- Added MV3 `declarativeNetRequest` dynamic rule storage and execution for `block`, `redirect.url`, and `allow`, with URL/resource-type/tab/method conditions, validation, a 5,000-rule cap, and persistent per-extension rules. Static rulesets and advanced conditions remain unsupported.
- Added the standard `browser.userScripts` / `chrome.userScripts` facade (`register`, `getScripts`, `update`, `unregister`) backed by the existing dynamic content-script injector; supports inline code and extension files with host-pattern validation.
- Added `webRequest.onBeforeRedirect` for Mafari-applied blocking redirects; blocked requests emit `onErrorOccurred`. Main-frame `onResponseStarted` is approximated from `onPageCommitVisible`; request/response headers and subresource response phases remain unavailable in WebView.
- Buffered early `storage.onChanged` notifications briefly so a background page that registers its listeners just after startup does not miss the first storage changes.
- Reduced the default `runtime.sendMessage` response timeout from 30 s to 10 s, added target-context timeout diagnostics, and release pending deliveries when an endpoint is destroyed.
- Updated compatibility matrix and documented the exact supported subsets and WebView limitations.


## WebExtension runtime context discovery

- Added Manifest V3 `runtime.getContexts(filter)` for querying the current extension's live background, popup, options and content-script contexts.
- Added context type, context ID, document URL/origin, frame, tab, window and incognito filtering; document IDs are Mafari host-generated identifiers rather than Chromium-native document tokens.
- Changed the unimplemented `runtime.setUninstallURL()` from a silent no-op to an explicit `UNSUPPORTED` rejection.
- Added tests for own-extension isolation, context filters and malformed filter rejection.

- Added a constrained MV2 `runtime.getBackgroundPage` bridge descriptor for non-background extension pages; MV3 correctly rejects the deprecated background-page API. The descriptor is not a cross-WebView `Window` object.

- Exposed synchronous manifest-backed `runtime.getVersion()`.

## WebExtension API continuation: offscreen, tabs capture, messaging and scripting

- Added partial MV3 `offscreen` support backed by one hidden extension-origin WebView per extension: `createDocument`, `closeDocument`, and `hasDocument`; creation waits for load plus bridge handshake, and `runtime.getContexts` reports `OFFSCREEN_DOCUMENT`.
- Restricted offscreen page shims to the `runtime` namespace and validated the declared `offscreen` permission, MV3 manifest, extension-page caller, packaged relative HTML path, reason enum and justification.
- Added `tabs.captureVisibleTab` for the active WebView viewport, returning PNG/JPEG data URLs; host permission or a current `activeTab` grant is required. Capture is bounded to 4 megapixels and uses WebView view drawing rather than compositor capture.
- Added target tab metadata to `tabs.sendMessage` sender objects, implemented single-window `tabs.onHighlighted` dispatch on tab activation, and explicitly rejected documentId-based message routing.
- Tightened scripting target validation: unsupported all-frame, non-main-frame and documentId injection requests now fail explicitly; CSS USER cascade origin is rejected rather than silently treated as AUTHOR.
- Corrected runtime message delivery so a listener that receives a message but never responds does not fabricate a successful undefined reply; explicit sync replies, returned true and Promise replies remain supported.
- Updated API compatibility notes and added unit coverage for capture options, sender metadata and unsupported scripting targets.
- Chrome offscreen reason-specific lifecycle behavior, true isolated worlds, multi-frame injection and compositor-level capture remain unsupported.
