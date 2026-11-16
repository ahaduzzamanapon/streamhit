@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "E:\P\movie bot\android"
call gradlew.bat --stop
call gradlew.bat assembleRelease --no-daemon
