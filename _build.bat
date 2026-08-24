@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
echo === COMPILE CHECK ===
call gradlew.bat :app:compileStandardDebugKotlin > _compile.log 2>&1
echo COMPILE_EXITCODE=%ERRORLEVEL% >> _compile.log
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"e: file" _compile.log
