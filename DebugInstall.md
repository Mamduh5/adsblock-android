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
