#!/data/data/com.termux/files/usr/bin/bash
# API 102 requires dependency resolution, Room generation and service manifest merging.
# Run from a checkout with JDK 17+, Android SDK 37.0 and Gradle-compatible aapt2.
set -eu
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [ "${1:-}" = "--release" ]; then
    exec bash ./gradlew :app:assembleRelease
fi
exec bash ./gradlew :app:assembleDebug
