## Check Vivo device connected
adb devices -l

## Install the app
adb install -r app\build\outputs\apk\debug\app-debug.apk

## Logs
adb logcat -c

## Stop app
adb shell am force-stop com.example.siteshield

## Start app
adb shell am start -W -n com.example.siteshield/.MainActivity

## General Browsing v1 smoke test

1. Open Shield > Browse / Search and verify focus, keyboard, Cancel, URL, and query submission.
2. Browse across two unrelated HTTPS sites, then use Back and Forward.
3. Open YouTube from Generic results; verify Profile: YouTube, then Back restores Profile: Browse.
4. Repeat with Facebook when practical.
5. Cycle Google, DuckDuckGo, and Bing; restart and verify the selected provider persists.
6. Compare BALANCED and MAX on a generic site; MAX should suppress images while text/navigation remains usable.

## Crash and process check

adb logcat -d -v time | findstr /I /C:"FATAL EXCEPTION" /C:"AndroidRuntime" /C:"A WebView method was called on thread" /C:"chromium" /C:"com.example.siteshield"
adb shell pidof com.example.siteshield

## Tabs + Session Persistence v2 physical test

1. Create at least four logical tabs, including Browse Home and a query-based page such as `https://youtube.com/watch?v=...`.
2. Keep three runtimes live and switch repeatedly. The selected page must always be the only visible/interactable browser page; no stale page may cover it or receive touches.
3. Interact with each live page before switching, then return and verify its live state survives. A live background WebView may be detached but must not be destroyed merely because it is hidden.
4. Open the fourth tab to trigger LRU suspension. Return to the oldest tab and verify only its saved URL reloads, with approximate scroll restored after layout. A suspended Browse Home tab must return as Browse Home without a network load.
5. Force-stop and relaunch. Verify the selected tab is recreated, background logical tabs remain suspended, and the complete YouTube-style query still identifies the same video.
6. In separate tabs, verify profile label, title, Browse Home visibility, Data Saver display/effect, and debug markers always follow the selected tab. Background callbacks must not overwrite them.
7. If WebView renderer termination can be induced safely, verify the selected runtime recreates once without duplicate views or a recovery loop.
8. Check logcat for OOM, renderer, Chromium, and WebView-thread errors. Confirm no more than three runtimes are live and only one browser WebView is attached.

Process death cannot restore JS heap, form drafts, exact dynamic DOM, native back/forward history, video timestamp, or unsent POST state. It restores safe metadata and lets Chromium/WebView use its normal persistent cache and site storage.

Session URLs are local-only and preserve HTTPS query/fragment page identity while stripping user-info credentials and rejecting unsafe schemes.

## Adaptive Shield v2 physical test

1. In Generic Web, visit two unrelated domains and note the `AdaptiveScope` marker for each. The scopes must use different top-level hosts.
2. Set Adaptive to LEARN and browse/reload both sites. Verify observations/candidates accumulate locally without any `ADAPTIVE_BLOCK` event.
3. Review Adaptive Shield on each site. Evidence, rule IDs, counts, confidence, and functional/redirect/static evidence must belong only to that site's scope.
4. Set Adaptive to AUTO_SAFE. Verify only a qualified learned rule blocks; one site's learned host must not automatically affect the other site.
5. Type or tap a legitimate cross-site destination and verify explicit user navigation still works. Page-driven redirects/popups matching a learned navigation rule may be blocked.
6. Exercise image/video/font-heavy content and login/session flows. They may add functional evidence but must not become unsafe media/session blocks.
7. Recheck Mangakakalot, Aqua Reader, YouTube, Facebook, and Palworld.gg static protections and normal content behavior.
8. Disable or forget one rule/scope and verify unrelated Generic Web scopes remain intact across force-stop/relaunch.

DOM structural candidates remain review-only in v2; no DOM adaptive auto-enforcement is claimed. Mangakakalot retains its specialized reader health rollback. Generic Web intentionally has no speculative generic DOM-health rollback.

## Adaptive Shield v3 ad-evidence physical test

### Test A - unknown display ad

1. In Generic Web, set Adaptive to LEARN and open a normal site with a display ad that static rules currently miss.
2. Leave the ad visible and reload/browse enough for the same slot and loader or iframe relationship to occur at least three times.
3. Open Adaptive Shield diagnostics. Verify the scoped candidate shows explicit-slot, sponsored, iframe, overlay, or loader evidence without exposing a query string or page text.
4. Switch directly to AUTO_SAFE. Verify the qualified candidate changes to LEARNED without restarting the app and shows a promotion reason.
5. Reload. Verify the learned ad loader, ad path, or ad iframe is blocked and the advertisement no longer loads while the article remains usable.

### Test B - normal content safety

1. On the same site, exercise ordinary images, fonts, an embedded video, navigation, login/session flows, and a user-confirmed download.
2. Verify image/video/font requests are not directly Adaptive-blocked, explicit taps/navigation remain allowed, login/session infrastructure is not learned, and downloads still require native confirmation.

### Test C - native or sponsored content

1. Visit a page with a clearly attributed sponsored card and verify short exact attribution plus structural iframe/loader evidence appears.
2. Visit an article discussing sponsored content. Verify prose or a heading alone does not create an enforceable candidate.
3. Verify an uncertain native card can remain observed/candidate and does not auto-block from text or class names alone.

### Test D - sticky or interstitial content

1. Verify a fixed/sticky advertisement with attribution or an ad iframe records overlay evidence.
2. Open a cookie/consent dialog, navigation overlay, video lightbox, and accessibility control where practical. Verify layout alone does not create ad evidence or a learned rule.

### Test E - scope isolation

1. Learn a qualified ad candidate on Generic Site A.
2. Visit Generic Site B, preferably using the same third-party provider.
3. Verify Site A's rule ID and enforcement remain under Site A's `AdaptiveScope` and do not automatically block Site B.

Physical success requires visible before/after ad behavior and working main content; diagnostics or JVM tests alone are not physical acceptance.

## Adaptive Shield v3.1 late-ad runtime test

1. Set Adaptive to LEARN, load a page with ads, and wait longer than three seconds without reloading.
2. Scroll until a lazy ad appears. Verify `observer-drain` and slot-scoped structure/iframe evidence appears after the late resource activity.
3. If the page refreshes an ad, wait for the refresh and verify the changed iframe state produces new evidence for the same ephemeral slot.
4. Repeat on a page with multiple ad slots. Verify diagnostics use separate page-local slot numbers even when slots share a normalized provider path.
5. Turn the static Blocker OFF while Adaptive remains LEARN. Reload or browse and verify Adaptive evidence still accumulates, while static DOM cleanup and all static/Adaptive enforcement remain off.
6. Turn Adaptive OFF and verify observer drains stop. Turn it back to LEARN on the already-open selected page and verify observation resumes without restarting the app.
7. Open a second tab, then switch tabs. Verify only the selected attached page produces periodic observer drains; background/destroyed tabs must not keep polling.
8. On a page with several unrelated third-party scripts, verify ambiguous loader diagnostics remain observation-only rather than selecting a random script.
9. Switch to AUTO_SAFE, reload, and verify only qualified learned loader/iframe infrastructure is blocked while content, media, login, navigation, and downloads remain functional.

Late-ad acceptance requires real evidence appearing after the old three-second window. JVM lifecycle/correlation tests do not replace this physical check.

## Adaptive Shield v3.2 protocol and navigation-intent test

### ArenaScan protocol fixture acceptance

1. In Generic Web, open `arenascan.com`, open Adaptive Shield, and forget only the `arenascan.com` scope.
2. Set Adaptive to LEARN and Blocker to OFF. Browse, search, and read until the same type of advertising popup/redirect behavior occurs. LEARN must observe but must not block.
3. Review the scoped diagnostics. Verify relevant candidates show non-zero protocol placement, auction/bidder, impression/creative/click/popup, and cluster counts where the burst qualifies. Full queries, parameter values, campaign IDs, click IDs, publisher IDs, and link text must not appear.
4. Tap an ordinary non-link element that triggers an unrelated offsite popup or redirect. Verify `intent=CLICK_HIJACK_SUSPECTED` / `intentMismatch` evidence appears even though WebView reports a gesture.
5. Tap a real same-site link, a real external link, and a legitimate `target=_blank` link. Verify exact normalized host/path matches are treated as `PAGE_LINK_INTENDED` and continue to work. Also verify Back/Forward, Browse Home, omnibox navigation, login/OAuth, account/session pages, payments, and downloads.
6. Switch Blocker to ON and Adaptive to AUTO_SAFE, then reload and repeat the browsing flow.
7. Verify only qualified learned protocol infrastructure is blocked, unrelated learned popups/redirects are blocked, captured intended destinations still work, and main content remains functional.

The implementation is generic: acceptance must not depend on hardcoded ArenaScan or observed provider domains. A lone third-party request, repeated analytics request, or one suspicious parameter must remain insufficient for promotion.

### Blocked-ad placeholder acceptance

1. Open the movie site where a blocked advertisement previously left an empty box. Enable Blocker and AUTO_SAFE.
2. Verify the network log first proves the iframe/script resource was blocked by a static or learned Adaptive rule.
3. Verify the correlated iframe/script is hidden and the smallest empty ad-identified wrapper collapses.
4. Verify a visually empty normal layout container remains. Also verify nearby article text, reader content, images, video/audio/canvas, player controls, forms, buttons, navigation links, login controls, and profile preservation selectors remain intact.
5. Wait for a dynamically inserted or refreshed ad frame and verify bounded mutation cleanup collapses its correlated placeholder without a continuous full-document scan.

Physical success requires the real ArenaScan and movie-site before/after behavior. The deterministic JVM/JavaScript fixtures do not replace device acceptance.

## Adaptive Shield v3.3 host-evidence and cross-host cluster test

1. In Generic Web, open Adaptive Shield for `arenascan.com` and forget only that site scope.
2. Set Adaptive to LEARN and Blocker to OFF.
3. Load, reload, search, and browse ArenaScan enough for the advertising stack to initialize across at least three separate page generations.
4. Inspect `Adaptive network classifier` events and scoped candidates. Opaque/clean requests may still have `pathEvidence=none` and `queryEvidence=none`, but ad infrastructure should expose safe fields such as `hostAd`, `hostLoader`, `hostBidder`, `hostClick`, `clusterSeed`, and `clusterEpisodes`.
5. Confirm ordinary `googletagmanager.com`, `static.cloudflareinsights.com`, `fonts.googleapis.com`, generic CDN scripts, and API requests show `hostEvidence=none`, `clusterSeed=false`, and no learned host-semantic rule merely from repetition.
6. Trigger the previously observed hijacked popup/redirect. Confirm an unmatched destination near an active seeded cluster logs `intentMismatch=true` and navigation-cluster evidence. A deliberately clicked external link must remain intended and must not receive this evidence.
7. Switch Adaptive to AUTO_SAFE and Blocker to ON.
8. Reload. Expect `ADAPTIVE_PROMOTE`, followed on the next matching load by `ADAPTIVE_BLOCK` for the earliest qualified loader/infrastructure path, for example a stable third-party SCRIPT path rather than every rotating destination.
9. Verify the subsequent advertising request chain materially shrinks, visible ads stop loading, unrelated popups/redirects are blocked when learned, and ArenaScan reading/search/navigation content remains functional.
10. Only after a real `RESOURCE_BLOCK` or `ADAPTIVE_BLOCK`, recheck the existing v3.2 placeholder collapse. Do not interpret `DOM_CLEANUP removed=0` before a network block as a separate failure.

Diagnostics must never contain full queries, query values, cookies, bodies, tracking identifiers, or arbitrary page/link text. JVM fixtures establish deterministic classification and safety only; the material network-chain reduction remains a physical acceptance requirement.

## Downloads v1 physical test

1. Serve or open a safe page containing a small PDF, image, and ZIP/text link.
2. Tap each link and verify native confirmation shows a safe filename, MIME type, and known/unknown size.
3. Confirm the files appear in Android's download notification and Shield > Downloads.
4. Open the completed PDF/image and verify Android handles the content URI without a crash.
5. Try an automatic harmless download without tapping; verify no transfer starts.
6. Verify two deliberate downloads receive separate entries and that Cancel affects only a Site Shield-owned active ID.
7. Repeat one explicit file download in Data Saver BALANCED and MAX.
8. Recheck Browse > YouTube > Back > Browse plus Facebook and manga smoke paths.

Downloads v1 does not support `blob:`, `data:`, or `filesystem:` URLs and must not add a JavaScript bridge to extract them.

## Download security log check

adb logcat -d -v time | findstr /I /C:"FATAL EXCEPTION" /C:"AndroidRuntime" /C:"A WebView method was called on thread" /C:"SecurityException" /C:"FileUriExposedException" /C:"OutOfMemoryError" /C:"renderer" /C:"com.example.siteshield"
