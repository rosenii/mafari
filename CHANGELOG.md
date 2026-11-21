# Changelog

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
