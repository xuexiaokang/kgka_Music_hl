@echo off
REM ============================================================
REM  KA Music 本地 release APK 一键构建
REM  用法: 双击运行, 或在命令行执行 scripts\build-android.bat
REM  产物: build\app\outputs\flutter-apk\app-release.apk
REM  正式签名依赖 android\key.properties + android\release-keystore.p12
REM  （二者均已被 .gitignore 忽略, 不入库; 缺省时回退 debug 签名）
REM  路径可用环境变量覆盖: FLUTTER_HOME / JAVA_HOME / ANDROID_HOME
REM ============================================================
setlocal
if "%FLUTTER_HOME%"=="" set "FLUTTER_HOME=C:\flutter\flutter"
if "%JAVA_HOME%"==""   set "JAVA_HOME=C:\Users\XXK\scoop\apps\temurin17-jdk\current"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=C:\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
REM 国内镜像加速(如需走官方源, 注释掉下面两行)
set "FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn"
set "PUB_HOSTED_URL=https://pub.flutter-io.cn"
set "PATH=%FLUTTER_HOME%\bin;%PATH%"

cd /d "%~dp0.." || goto :err

echo [1/3] flutter pub get ...
call flutter pub get || goto :err

echo [2/3] flutter analyze (仅 error 阻断) ...
call flutter analyze --no-fatal-infos --no-fatal-warnings || goto :err

echo [3/3] flutter build apk --release ...
call flutter build apk --release || goto :err

echo.
echo ============================================================
echo  构建成功: build\app\outputs\flutter-apk\app-release.apk
echo ============================================================
endlocal & exit /b 0

:err
echo.
echo  构建失败, 请查看上方日志。
endlocal & exit /b 1
