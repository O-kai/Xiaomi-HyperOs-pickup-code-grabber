#!/bin/sh
# Pure JVM regression; no Android SDK, root, system changes or proprietary fixtures.
# Optional argument: a local ROM JAR for additional read-only verification (never uploaded).
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
mkdir -p "$OUT/stubs/android/os" "$OUT/stubs/android/util" "$OUT/classes"
cat > "$OUT/stubs/android/os/Build.java" <<'EOF'
package android.os;
public final class Build {
    public static final String MANUFACTURER = "test";
    public static final String BRAND = "test";
}
EOF
cat > "$OUT/stubs/android/util/Log.java" <<'EOF'
package android.util;
public final class Log {
    public static int w(String tag, String text) { return 0; }
}
EOF
SRC="$ROOT/app/src/io/github/okaidev/pickupcode"
javac --release 11 -encoding UTF-8 -d "$OUT/classes" \
    "$OUT/stubs/android/os/Build.java" "$OUT/stubs/android/util/Log.java" \
    "$SRC/ArchiveClassProbe.java" "$SRC/ColorOs.java" "$ROOT/test/ArchiveClassProbeTest.java"
java -Xmx256m -cp "$OUT/classes" io.github.okaidev.pickupcode.ArchiveClassProbeTest "$@"
