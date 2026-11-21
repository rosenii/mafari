# Mafari WebView: DNR and userScripts

This document describes the implemented subset, not full Chromium/Firefox parity.

## declarativeNetRequest

Declare `declarativeNetRequest` in `manifest.json` (and host permissions when the extension uses `declarativeNetRequestWithHostAccess`). Dynamic rules are persisted per extension.

```js
await browser.declarativeNetRequest.updateDynamicRules({
  addRules: [
    {
      id: 1,
      priority: 10,
      action: { type: "block" },
      condition: {
        urlFilter: "||ads.example^",
        resourceTypes: ["image", "script", "xmlhttprequest"]
      }
    },
    {
      id: 2,
      priority: 20,
      action: { type: "redirect", redirect: { url: "https://example.org/clean" } },
      condition: { urlFilter: "|https://legacy.example/", tabIds: [3] }
    }
  ]
});

const rules = await browser.declarativeNetRequest.getDynamicRules();
await browser.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [1, 2] });
```

Supported actions: `block`, `redirect.url`, `allow`. Supported conditions: `urlFilter`, `resourceTypes`, `excludedResourceTypes`, `tabIds`, `excludedTabIds`, and `requestMethods`. Up to 5,000 dynamic rules per extension.

Not yet supported: static rulesets, session rules, header conditions/modification, `allowAllRequests`, `upgradeScheme`, redirect transforms/substitutions, `getMatchedRules`, and `onRuleMatchedDebug`. `getEnabledRulesets()` returns an empty array; enabling a non-empty static ruleset returns `UNSUPPORTED`.

Rules run in `WebViewClient.shouldInterceptRequest`. A block is represented by an empty 204 response and a redirect by 302 + Location; this is not identical to Chromium's network-service implementation.

## Standard userScripts API

Declare `userScripts` in `permissions`. The following methods are available in extension pages:

```js
const registration = {
  id: "site-helper",
  matches: ["https://example.com/*"],
  js: [{ code: "document.documentElement.dataset.mafari = '1';" }],
  runAt: "document_start",
  world: "MAIN",
  persistAcrossSessions: true
};

await browser.userScripts.register([registration]);
const scripts = await browser.userScripts.getScripts();
await browser.userScripts.update([{ id: "site-helper", runAt: "document_idle" }]);
await browser.userScripts.unregister({ ids: ["site-helper"] });
```

`js` accepts `{code}` or `{file}`. The requested `matches` must be covered by the extension's granted host patterns. Registrations are backed by the same dynamic content-script store as `scripting.registerContentScripts`.

Limitations: no true isolated-world security boundary in WebView; `allFrames` is stored but current injection is main-frame-only; `onBeforeScript` and `onAfterScript` are inert event objects; document-start changes do not retroactively run in an already-loaded document.
