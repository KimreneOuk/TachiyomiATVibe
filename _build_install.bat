@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
echo === BUILD ===
call gradlew.bat :app:assembleStandardDebug --no-daemon > _build.log 2>&1
echo BUILD_EXITCODE=%ERRORLEVEL% >> _build.log
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"e: file" _build.log
echo === INSTALL ===
adb -s 192.168.100.207:37625 install -r app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk > _install.log 2>&1
echo INSTALL_EXITCODE=%ERRORLEVEL% >> _install.log
type _install.log
