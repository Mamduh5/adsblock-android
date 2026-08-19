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

## Tabs + Session Persistence v1 physical test

1. Create six tabs from Shield > Tabs: Mangakakalot, YouTube, Facebook, and Generic Browse among them. Verify a seventh tab explains the six-tab limit.
2. Switch among three tabs and verify pages return live; confirm hidden media pauses acceptably and resumes when selected.
3. Select enough tabs to exceed the three-live-WebView budget. Return to the oldest tab and verify it reloads through Site Shield and restores approximate scroll after layout.
4. In separate tabs, verify Mangakakalot popup blocking, Generic cross-domain browsing, Facebook, and YouTube retain their own profiles. A popup or background request must log its originating profile, not the visible tab's profile.
5. Trigger a deliberate safe download in one tab, switch tabs, and confirm another tab cannot consume the first tab's gesture token.
6. Background and reopen the app without force-stop; verify the live selected page remains useful. Then force-stop/relaunch and verify tab order, selected tab, URLs/profiles, titles, and approximate scroll reconstruct without eagerly loading every tab.
7. Verify login cookies and normal cache/site storage survive switching, suspension, and relaunch. Do not enter or expose test credentials in logs.
8. Observe memory with one tab, three live tabs, and six logical tabs. Confirm only three WebViews remain live and no OOM/renderer loop occurs.

Process death cannot restore JS heap, form drafts, exact dynamic DOM, native back/forward history, video timestamp, or unsent POST state. It restores safe metadata and lets Chromium/WebView use its normal persistent cache and site storage.

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
