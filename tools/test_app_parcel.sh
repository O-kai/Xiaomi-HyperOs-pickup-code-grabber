#!/usr/bin/env bash
# Schema and caller-binding regression; only needs javac, android.jar and bundled org.json.
set -euo pipefail
PICKUP_REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PICKUP_ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-34/android.jar}"
if [[ ! -f "$PICKUP_ANDROID_JAR" ]]; then
    echo 'Set ANDROID_JAR to an Android SDK android.jar.' >&2
    exit 1
fi
PICKUP_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$PICKUP_TEST_DIR"' EXIT
PICKUP_TEST_CP="$PICKUP_REPO_DIR/test/libs/org.json.jar:$PICKUP_ANDROID_JAR"
PICKUP_SRC_DIR="$PICKUP_REPO_DIR/app/src/io/github/okaidev/pickupcode"
javac -encoding UTF-8 -cp "$PICKUP_TEST_CP" -d "$PICKUP_TEST_DIR" \
    "$PICKUP_SRC_DIR/AppParcelData.java" "$PICKUP_SRC_DIR/CallerPolicy.java" \
    "$PICKUP_REPO_DIR/test/AppParcelDataTest.java"
java -cp "$PICKUP_TEST_DIR:$PICKUP_TEST_CP" io.github.okaidev.pickupcode.AppParcelDataTest
