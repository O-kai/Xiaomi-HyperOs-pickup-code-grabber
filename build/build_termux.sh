#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# build_termux.sh - 取件码助手 v2 一键构建（Termux, root 可执行）
#
# 工具链（全部官方，均已就位）:
#   javac/d8/aapt2/apksigner (Termux) + android.jar(API34) + xposed-api-82.jar(官方)
#
# 用法:  su -c 'bash /sdcard/Download/pickup-v2/build_termux.sh'
# 输出:  /sdcard/Download/pickup-v2/pickup-code-grabber.apk
# ============================================================
set -e

PREFIX=/data/data/com.termux/files/usr
TERM_HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
JAVAC=$PREFIX/bin/javac
D8=$PREFIX/bin/d8
AAPT2=$PREFIX/bin/aapt2
APKSIGNER=$PREFIX/bin/apksigner
ZIP=$PREFIX/bin/zip
UNZIP=$PREFIX/bin/unzip
KEYTOOL=$PREFIX/bin/keytool

ANDROID_JAR=$TERM_HOME/android-sdk/platforms/android-34/android.jar
XPOSED_JAR=$TERM_HOME/xposed-api-82.jar
KEYSTORE=$TERM_HOME/.pickup-debug.keystore

PROJ=/sdcard/Download/pickup-v2
APP=$PROJ/app
BUILD=$PROJ/build
FINAL=$PROJ/pickup-code-grabber.apk

# 签名模式：默认 debug；传 --release 时使用正式密钥（release/ 目录需与 app 同级推送）
RELEASE_MODE=false
[ "$1" = "--release" ] && RELEASE_MODE=true
if $RELEASE_MODE; then
  RELEASE_KS=$PROJ/release/pickup-release.keystore
  RELEASE_PASS_FILE=$PROJ/release/keystore-pass.txt
  [ -f "$RELEASE_KS" ] || { echo "!! 缺少 release keystore"; exit 1; }
  [ -f "$RELEASE_PASS_FILE" ] || { echo "!! 缺少 release 密码文件"; exit 1; }
fi

echo "== 取件码助手 v2 构建 =="
echo "android.jar = $ANDROID_JAR"
echo "xposed jar  = $XPOSED_JAR"
[ -f "$ANDROID_JAR" ] || { echo "!! 缺少 android.jar"; exit 1; }
[ -f "$XPOSED_JAR" ] || { echo "!! 缺少 xposed-api-82.jar"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$BUILD/apk"

echo "[1/7] aapt2 compile 资源..."
$AAPT2 compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link..."
$AAPT2 link -o "$BUILD/linked.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 28 \
  --target-sdk-version 34 \
  "$BUILD/res.zip"

echo "[3/7] javac 编译..."
$JAVAC --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$XPOSED_JAR" \
  -d "$BUILD/obj" \
  $(find "$APP/src" "$BUILD/gen" -name "*.java")

echo "[4/7] d8 转 dex..."
$D8 --min-api 28 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(find "$BUILD/obj" -name "*.class")

echo "[5/7] 打包（增量，保护 resources.arsc 对齐）..."
rm -rf "$BUILD/staging"
mkdir -p "$BUILD/staging/assets"
cp "$BUILD/dex/classes.dex" "$BUILD/staging/"
# v2.7.0 修复：打包【全部】assets —— xposed_init + donate_qr.png + sqlite3 套件
# （旧版只装 xposed_init，是 v2.0 时代 21KB 包的遗留；sqlite3 二进制与打赏图全在 assets 下）
cp -r "$APP/assets/." "$BUILD/staging/assets/"
cp "$BUILD/linked.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/staging" && $ZIP -q -r "$BUILD/unsigned.apk" classes.dex assets )

echo "[6/7] 签名..."
if $RELEASE_MODE; then
  PASS=$(cat "$RELEASE_PASS_FILE")
  $APKSIGNER sign --ks "$RELEASE_KS" --ks-pass "pass:$PASS" \
    --key-pass "pass:$PASS" --ks-key-alias pickupcode \
    --out "$FINAL" "$BUILD/unsigned.apk"
  echo "RELEASE 签名完成（正式密钥）"
else
  if [ ! -f "$KEYSTORE" ]; then
    echo "生成 debug keystore..."
    $KEYTOOL -genkeypair -keystore "$KEYSTORE" -storepass android \
      -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
      -validity 10000 -dname "CN=PickupCode,O=Debug,C=CN" 2>/dev/null
  fi
  $APKSIGNER sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$FINAL" "$BUILD/unsigned.apk"
fi

echo "[7/7] 完成: $FINAL"
ls -l "$FINAL"
