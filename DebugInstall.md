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

adb logcat -d -v time | findstr /I /C:"FATAL EXCEPTION" /C:"AndroidRuntime" /C:"A WebView method was called on thread" /C:"SecurityException" /C:"FileUriExposedException" /C:"com.example.siteshield"
