# WebExtension popup/options rendering diagnostics (V4)

This change targets the shared WebView extension-page host, not any individual extension.

## Changes

- Extension page resource misses now return an explicit HTTP 404 response. Disabled/uninstalled extensions return 410. This prevents unknown paths under the synthetic extension origin from falling through as ordinary network requests.
- Successful resource responses log path, MIME type, and byte length; misses and disabled-extension responses are logged.
- Added `onPageCommitVisible` and `onPageFinished` viewport diagnostics.
- Added a read-only DOM/Resource Timing probe after the main document finishes. It records `readyState`, whether `body` exists, child count, resource count, and up to 20 resource responses reporting HTTP >= 400. It does not read page text or mutate the document.
- Logs injection warnings, first main-frame bridge handshake, and API request/response outcomes.
- Added MIME mappings for AVIF, webmanifest/source maps, and PDF.

## What this does not claim

- This is a diagnostic and resource-routing hardening pass, not proof that every extension popup now renders.
- Android/Kotlin compilation and device validation remain outstanding because the Gradle 9.9.1 distribution could not be downloaded in the build environment (DNS resolution for `services.gradle.org` failed).
- A successful page load with no bridge-ready log points toward document-start injection/bridge setup. A bridge-ready log followed by API errors points toward permission or API implementation. Resource MISS/HTTP errors point toward package paths or MIME/resource handling. DOM probe with body and resources loaded but a persistent spinner points toward extension-side initialization or unsupported browser APIs.

## Tests

- `node tools/ext-shim-test/run.js`: 61/61 passed.
- `node tools/webext-test/run.js`: passed.
