# Site Shield Mobile

Native Kotlin Android app for protected single-WebView browsing. Fresh installs open the local Browse home; known sites automatically use optimized profiles while other HTTP(S) destinations use the conservative Generic Web profile.

## What v1 Includes

- Multi-site `SiteProfile` model with site id, display name, start URL, allowed hosts, page-type classifiers, baseline policy, page-type overrides, cookie/storage key patterns, and per-site flags.
- Reusable `PagePolicy` and `RequestRule` models for host, path, query-token, URL-token, first-party loader, navigation, resource, and DOM cleanup decisions.
- Optimized profiles for Mangakakalot, Palworld.gg, AquaReader, YouTube, and Facebook.
- `GenericWebProfile` fallback for ordinary cross-domain browsing.
- Native Browse Home and temporary omnibox with Google, DuckDuckGo, and Bing search.
- User-confirmed Android-native downloads with drive-by protection, safe filenames, session-owned status metadata, and explicit Open/Cancel controls.
- `SiteProfileRegistry` for profile lookup and URL/host matching.
- `GenericBlockerEngine` for reusable host classification, navigation decisions, resource blocking, and suspicious data-key checks.
- WebView-based in-app browser with external-link confirmation.
- WebViewClient navigation blocking and subresource interception.
- Local DOM cleanup script with profile-provided selectors/tokens and MutationObserver for late overlays, fake buttons, click traps, and suspicious iframes.
- Cookies stay enabled, while third-party cookies are profile-controlled and disabled by default.
- Manual suspicious site data cleanup for cookie, localStorage, and sessionStorage keys matching the active profile.
- Local-only blocker/debug/profile settings. No telemetry, analytics SDKs, remote rule loading, VPN, AccessibilityService, or JavaScript bridge.
- Local Data Saver modes: OFF, BALANCED, and MAX. BALANCED blocks only requests explicitly marked as prefetch and retains gesture-required media playback; MAX may additionally suppress network images according to each profile's policy.
- A session data meter based on Android per-UID RX/TX counters, including profile-active interval attribution. It reports actual app-UID traffic used, never estimated bytes saved.

## Data Saver semantics

- Fresh installs start in BALANCED. Existing installs without a saved Data Saver preference migrate to OFF so an upgrade does not silently change browsing behavior.
- OFF adds no saver restrictions and is independent from the blocker toggle.
- BALANCED preserves network images and selected media playback. Supported-site primary resources are not classified by filename or host as disposable media.
- MAX uses WebView's native network-image setting only where the active profile/page policy permits it. Manga chapter pages, Palworld map/tools, and YouTube watch pages protect their primary images.
- Changing mode does not reload the page. New and pending requests follow the updated WebView setting, and leaving MAX immediately restores normal network-image loading.
- Session usage comes from cumulative `TrafficStats` counters for Site Shield's UID. Per-profile numbers attribute counter deltas to the profile active during that interval; they are not per-request measurements.
- Usage remains on-device. Data Saver adds no VPN, proxy, DNS filtering, telemetry, cloud service, or Thinpipe integration.

## Run In Android Studio

1. Open this repository in Android Studio.
2. Let Gradle sync the project.
3. Use an Android emulator or device with internet access.
4. Run the `app` configuration.

Fresh installs default to the local Browse Home. A previously selected profile remains the startup profile.

## Architecture

- `SiteProfile.kt`: generic profile, page-type, request-rule, policy, and DOM cleanup model.
- `CommonRules.kt`: conservative reusable host, URL, data-key, and DOM heuristics.
- `MangakakalotProfile.kt`: first experimental profile.
- `DefaultProfile.kt`: generic fallback.
- `BrowseNavigation.kt`: pure omnibox parsing and encoded search-provider URLs.
- `GenericWebProfile.kt`: conservative cross-domain browsing policy.
- `SiteProfileRegistry.kt`: profile list and URL/host matching.
- `GenericBlockerEngine.kt`: profile and page-type-driven blocker decisions.
- `SiteShieldWebViewClient.kt`: WebView navigation/resource enforcement.
- `DownloadModels.kt`: pure request normalization, gesture intent, URL policy, filename/MIME, status, and progress models.
- `DownloadCoordinator.kt`: Android `DownloadManager`, privacy-minimal metadata persistence, status queries, cancellation, and content-URI opening.
- `SiteDataCleaner.kt`: profile-driven suspicious cookie/storage cleanup.
- `dom_cleanup.js`: local DOM cleanup logic fed by active profile rules.

## Add Another Site

1. Create a new file such as `SecondSiteProfile.kt`.
2. Define a `SiteProfile` with `id`, `displayName`, `startUrl`, `allowedHosts`, page-type rules, baseline policy, and any page-type overrides.
3. Add the profile to `SiteProfileRegistry.supportedProfiles`.
4. Run local unit tests and a debug build.
5. Tune selectors conservatively against the real site.

Most new-site work should stay in the new profile file.

## Navigation ownership

- Omnibox, Browse Home shortcuts, and user-gesture new-window links call an explicit-navigation entry point that resolves and atomically installs exactly one destination profile before loading.
- Generic top-level navigation can move between arbitrary HTTP(S) sites and activates an optimized profile when the destination is registered.
- A specialized profile keeps ownership while evaluating page-driven redirects and offsite links. An unknown callback never silently turns a protected-site redirect into Generic browsing.
- Subframes and subresources always use the immutable active top-level context. `shouldInterceptRequest()` reads request data plus the atomic snapshot and never calls WebView APIs.
- Back/Forward prepares the exact session-recorded profile for the destination history entry. The map is memory-only and stores no persistent browser history.

## Downloads v1

- `WebView.setDownloadListener` normalizes callback metadata but cannot enqueue by itself. A recent WebView tap creates a 1.5-second, atomic, single-use intent token; callbacks without one are blocked as automatic downloads.
- HTTPS downloads pass a separate conservative hostile-host policy and always require native confirmation. Cleartext HTTP, custom schemes, `blob:`, `data:`, and `filesystem:` are rejected.
- Android `DownloadManager` owns transfer, public Downloads storage, redirects, background continuation, duplicate handling, and completion notifications. Site Shield forwards the callback User-Agent and current WebView cookie header only into the system request; neither is logged or persisted.
- Persisted app metadata is limited to DownloadManager ID, sanitized filename, MIME type, creation time, and originating profile ID. Full URLs, query strings, cookies, and referrers are not retained.
- Opening is always an explicit user action using DownloadManager's content URI, `ACTION_VIEW`, and read permission delegation. Site Shield never auto-opens, executes, or installs a file.
- Data Saver does not truncate or block confirmed system downloads. Android DownloadManager normally transfers under a system UID, so its bytes are not added to Site Shield's WebView UID meter.

## Target Site Test Checklist

- Load Mangakakalot and confirm normal browsing/reading still works.
- Leave the page open long enough to trigger delayed ads or overlays.
- Enable debug mode and verify logs include active profile, blocked navigation, blocked resources, DOM cleanup, and data cleanup.
- Confirm suspicious redirects are blocked before leaving the page when detectable.
- Confirm unsolicited popups are blocked; a user-gesture HTTP(S) new-window link opens in the same Site Shield WebView.
- Confirm obvious ad iframes, fake close buttons, overlay traps, and deceptive continue/download/skip elements are removed or neutralized.
- Confirm the blocker toggle disables blocking for comparison.
- Run `Clean` and verify suspicious site data cleanup does not remove normal session state.

## Known Limitations

- WebView cannot expose every browser-level signal available inside Chrome.
- Request interception blocks by URL/host; it does not inspect all response bodies.
- DOM cleanup is heuristic and needs careful tuning against live site behavior.
- Fallback generic rules are intentionally conservative.
- Device-wide VPN/DNS filtering is future work only, not v1.
