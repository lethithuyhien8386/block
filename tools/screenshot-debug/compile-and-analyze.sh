#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/tools/screenshot-debug/.classes"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
 "$ROOT/app/src/main/java/com/pixelblock/autoplay/vision/PixelAnalyzer.java" \
 "$ROOT/app/src/main/java/com/pixelblock/autoplay/strategy/StrategyEngine.java" \
 "$ROOT/app/src/main/java/com/pixelblock/autoplay/strategy/MoveVerifier.java" \
 "$ROOT/tools/screenshot-debug/AnalyzeScreenshot.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" AnalyzeScreenshot "$1"
