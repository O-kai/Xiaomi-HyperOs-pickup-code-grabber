#!/bin/bash
# Self check: run regression tests against Gradle's compiled app classes
# (avoids needing the Xposed API jar for javac - app classes already compiled by assembleDebug)
# Usage: bash tools/run_tests.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# Git Bash would otherwise rewrite Windows classpath 'D:/...' into a bogus 'D;...' entry.
export MSYS2_ARG_CONV_EXCL='*'
JDK="${JAVA_HOME:-D:/develop/Java/jdk-17.0.20.1+1}"
SDK="D:/develop/android-sdk/platforms/android-34/android.jar"
SRC="app/src/io/github/okaidev/pickupcode"
APPCLS="app/build/intermediates/javac/debug/classes"
OUT="test/out"

export PATH="$JDK/bin:$PATH"

if [ ! -d "$APPCLS/io" ]; then
  echo "app classes missing, run: gradlew.bat assembleDebug first"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/testclasses"

# Whitelist: only tests that exercise pure logic (no Xposed hooks / Activities)
TFILES="test/FullReg.java test/ChannelParity.java test/PkgInputTest.java test/TierExclusionTest.java test/CallerPolicyTest.java"

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
  "$JDK/bin/java.exe" -cp "$CP" "$T" 2>&1 | tail -8
  echo ""
done

# These tests live in package io.github.okaidev.pickupcode
for T in TierExclusionTest CallerPolicyTest; do
  echo "===== $T ====="
  "$JDK/bin/java.exe" -cp "$CP" "io.github.okaidev.pickupcode.$T" 2>&1 | tail -8
  echo ""
done

# ===== 规则实验台：直接加载 rules/rules.json 跑语料 =====
# 这一步校验的是「远端源」那份 JSON——规则改动后它必须自己就能通过，
# 而不是等同步到 Java 副本之后才发现问题（v7 之前就出过这种漂移）。
echo "===== RuleLab (rules/rules.json) ====="
"$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
  -classpath "$APPCLS;$SDK" -d "$OUT/testclasses" test/RuleLab.java 2>> "$OUT/javac.log"
if [ $? -ne 0 ]; then
  echo "RuleLab javac FAILED (first 15 lines):"
  tail -15 "$OUT/javac.log"
  exit 1
fi
"$JDK/bin/java.exe" -cp "$CP" RuleLab "$ROOT" 2>&1 | tail -8
if [ ${PIPESTATUS[0]} -ne 0 ]; then
  echo "RuleLab FAILED — 规则源与语料不一致"
  exit 1
fi
echo ""

echo "===== 规则三副本一致性 ====="
python test/check_rules_consistency.py 2>&1 | tail -8
if [ ${PIPESTATUS[0]} -ne 0 ]; then
  echo "三副本一致性 FAILED"
  exit 1
fi
echo ""

echo "===== ALL DONE ====="
