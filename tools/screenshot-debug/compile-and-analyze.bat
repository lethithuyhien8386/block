@echo off
setlocal
set "ROOT=%~dp0..\.."
set "OUT=%~dp0.classes"
if not exist "%OUT%" mkdir "%OUT%"
javac -encoding UTF-8 -d "%OUT%" "%ROOT%\app\src\main\java\com\pixelblock\autoplay\vision\PixelAnalyzer.java" "%ROOT%\app\src\main\java\com\pixelblock\autoplay\strategy\StrategyEngine.java" "%ROOT%\app\src\main\java\com\pixelblock\autoplay\strategy\MoveVerifier.java" "%~dp0AnalyzeScreenshot.java"
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp "%OUT%" AnalyzeScreenshot "%~1"
