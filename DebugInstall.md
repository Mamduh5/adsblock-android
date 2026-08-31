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
