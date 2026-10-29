#!/bin/bash
# Self check: run regression tests against Gradle's compiled app classes
# (avoids needing the Xposed API jar for javac - app classes already compiled by assembleDebug)
# Usage: bash tools/run_tests.sh
export PATH="$JDK/bin:$PATH"
set -u
ROOT="D:/project/Xiaomi-HyperOs-pickup-code-grabber"
JDK="${JAVA_HOME:-D:/develop/Java/jdk-17.0.20.1+1}"
SDK="D:/develop/android-sdk/platforms/android-34/android.jar"
SRC="$ROOT/app/src/io/github/okaidev/pickupcode"
APPCLS="$ROOT/app/build/intermediates/javac/debug/classes"
OUT="$ROOT/test/out"

if [ ! -d "$APPCLS/io" ]; then
  echo "app classes missing, run: gradlew.bat assembleDebug first"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/testclasses"

# Whitelist: only tests that exercise pure logic (no Xposed hooks / Activities)
TFILES="$ROOT/test/FullReg.java $ROOT/test/ChannelParity.java $ROOT/test/PkgInputTest.java $ROOT/test/TierExclusionTest.java"

echo "javac compiling tests..."
"$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
  -classpath "$APPCLS;$SDK" -d "$OUT/testclasses" $TFILES 2> "$OUT/javac.log"
if [ $? -ne 0 ]; then
  echo "javac FAILED (first 25 lines):"
  head -25 "$OUT/javac.log"
  exit 1
fi
echo "javac OK"

CP="$OUT/testclasses;$APPCLS;$SDK"

echo ""
for T in FullReg ChannelParity PkgInputTest; do
  echo "===== $T ====="
  "$JDK/bin/java.exe" -cp "$CP" "$T" 2>&1 | tail -6
  echo ""
done

# TierExclusionTest is in package io.github.okaidev.pickupcode
echo "===== TierExclusionTest ====="
"$JDK/bin/java.exe" -cp "$CP" io.github.okaidev.pickupcode.TierExclusionTest 2>&1 | tail -6
echo ""

echo "===== ALL DONE ====="
