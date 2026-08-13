## check device connected
adb devices

## Install the app
adb install -r app\build\outputs\apk\debug\app-debug.apk

## Logs
adb logcat -c

## Stop app
adb shell am force-stop com.example.siteshield

## Start app
adb shell am start -W -n com.example.siteshield/.MainActivity