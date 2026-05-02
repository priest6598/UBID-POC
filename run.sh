#!/usr/bin/env bash
# Compile and run the UBID POC end-to-end. No external dependencies — only JDK 17+.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/main/java"
BUILD="$ROOT/build"
OUT="$ROOT/output"

echo "▸ Compiling Java sources to $BUILD ..."
mkdir -p "$BUILD" "$OUT"
find "$SRC" -name '*.java' -print0 | xargs -0 javac -d "$BUILD" --release 17

echo "▸ Running com.karnataka.ubid.Main ..."
java -cp "$BUILD" com.karnataka.ubid.Main "$OUT"

echo
echo "▸ Done. Open the HTML report:"
echo "    open $OUT/ubid-report.html"
