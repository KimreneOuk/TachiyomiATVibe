@echo off
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
echo === force-stop old app ===
adb -s 192.168.100.207:37625 shell am force-stop app.kanade.tachiyomi.at.debug
echo === clear any stale translation.json under app data ===
adb -s 192.168.100.207:37625 shell "run-as app.kanade.tachiyomi.at.debug sh -c 'find . -name translation.json -o -name .translation.tmp 2>/dev/null | while read f; do echo rm $f; rm -f \"$f\"; done'"
echo === relaunch ===
adb -s 192.168.100.207:37625 shell monkey -p app.kanade.tachiyomi.at.debug -c android.intent.category.LAUNCHER 1
