# Site Shield Mobile

Native Kotlin Android app for a dedicated protected WebView around `https://example.com/`.

## What v1 Includes

- WebView-based in-app browser scoped to `example.com` and subdomains.
- WebViewClient navigation blocking for suspicious schemes, hosts, and redirect-like paths.
- Subresource interception for known ad, tracking, popup, redirect, and suspicious script hosts.
- Local DOM cleanup script with MutationObserver for late overlays, high-z-index traps, deceptive bait buttons, and suspicious iframes.
- Cookies stay enabled, while third-party cookies are disabled.
- Manual suspicious site data cleanup for cookie, localStorage, and sessionStorage keys matching configured ad/popup/redirect/interstitial/promo/campaign patterns.
- Local-only settings for blocker/debug toggles.
- Debug panel, off by default, for blocked navigations, resources, DOM removals, and cleanup actions.

## Run In Android Studio

1. Open this repository in Android Studio.
2. Let Gradle sync the project.
3. Use an Android emulator or device with internet access.
4. Run the `app` configuration.

The app opens `https://example.com/` by default. Update `BlockerConfig.TargetUrl` and `BlockerConfig.TargetHost` when replacing the placeholder with the real target site.

## Editable Local Config

Primary rules live in:

- `app/src/main/java/com/example/siteshield/BlockerConfig.kt`
- `app/src/main/assets/dom_cleanup.js`

Add suspicious hosts, URL path tokens, key patterns, or DOM heuristics there. No remote rule fetch, telemetry, analytics, or JavaScript bridge is used.

## Target Site Test Checklist

- Load the target site and confirm normal login/session behavior still works.
- Leave the page open long enough to trigger the delayed bad behavior.
- Confirm suspicious main-frame redirects are blocked or opened externally only after confirmation.
- Confirm obvious ad/popunder/redirect subresources appear in the debug panel when debug mode is enabled.
- Confirm full-screen overlays, fake close buttons, deceptive continue/download/skip buttons, and suspicious iframes are removed or neutralized.
- Confirm the blocker toggle disables blocking for comparison.
- Confirm suspicious site data cleanup does not remove normal auth/session cookies.

## Known Limitations

- WebView cannot see or classify every browser-level behavior that Chrome exposes internally.
- Request interception can block by URL/host, but it cannot inspect all response bodies.
- DOM cleanup is heuristic and should be tuned against the real target site to avoid false positives.
- Late DOM removals are logged through console messages captured by WebChromeClient, not a JavaScript bridge.
- Device-wide VPN/DNS filtering is intentionally left as future work, not v1.
