@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat :app:compileStandardDebugKotlin --no-daemon > _compile.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> _compile.log
