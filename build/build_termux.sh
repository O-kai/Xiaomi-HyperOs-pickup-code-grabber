#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# build_termux.sh - 取件码助手一键构建（Termux, root 可执行）
#
# 工具链（全部官方）:
#   javac/d8/aapt2/apksigner (Termux) + android.jar(API34) + xposed-api-82.jar(官方)
#
# 用法（仓库推送到手机后）:
#   su -c 'bash /sdcard/Download/pickup-code-grabber/build/build_termux.sh'
# 输出:  <PROJ>/pickup-code-grabber.apk（默认 /sdcard/Download/pickup-code-grabber/）
#
# 可用环境变量覆盖（缺省即默认值）:
#   PICKUP_PROJ       项目目录（内含 app/ 与 build/），默认 /sdcard/Download/pickup-code-grabber
#   ANDROID_JAR       android.jar 路径，默认 ~/android-sdk/platforms/android-34/android.jar
#   XPOSED_JAR        xposed-api-82.jar 路径，默认 ~/xposed-api-82.jar
#   PICKUP_KEYSTORE   签名 keystore（不传则自动生成 debug 密钥，仅自用）
#   PICKUP_KEYSTORE_PASS / PICKUP_KEY_ALIAS  正式签名时使用
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

ANDROID_JAR="${ANDROID_JAR:-$TERM_HOME/android-sdk/platforms/android-34/android.jar}"
XPOSED_JAR="${XPOSED_JAR:-$TERM_HOME/xposed-api-82.jar}"
PROJ="${PICKUP_PROJ:-/sdcard/Download/pickup-code-grabber}"
KEYSTORE="${PICKUP_KEYSTORE:-$TERM_HOME/.pickup-debug.keystore}"
KEYSTORE_PASS="${PICKUP_KEYSTORE_PASS:-android}"
KEY_ALIAS="${PICKUP_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${PICKUP_KEY_PASS:-$KEYSTORE_PASS}"

APP=$PROJ/app
BUILD=$PROJ/build
FINAL=$PROJ/pickup-code-grabber.apk

echo "== 取件码助手构建 =="
echo "android.jar = $ANDROID_JAR"
echo "xposed jar  = $XPOSED_JAR"
echo "keystore    = $KEYSTORE"
[ -f "$ANDROID_JAR" ] || { echo "!! 缺少 android.jar"; exit 1; }
[ -f "$XPOSED_JAR" ] || { echo "!! 缺少 xposed-api-82.jar"; exit 1; }
[ -d "$APP" ] || { echo "!! 项目目录错误（未找到 $APP）"; exit 1; }

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
cp "$APP/assets/xposed_init" "$BUILD/staging/assets/"
cp "$BUILD/linked.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/staging" && $ZIP -q "$BUILD/unsigned.apk" classes.dex assets/xposed_init )

echo "[6/7] 签名..."
if [ ! -f "$KEYSTORE" ]; then
  echo "生成 debug keystore（正式发布请使用自己的 keystore: PICKUP_KEYSTORE/... 参数）..."
  $KEYTOOL -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" \
    -alias "$KEY_ALIAS" -keypass "$KEY_PASS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=PickupCode,O=Debug,C=CN" 2>/dev/null
fi
$APKSIGNER sign --ks "$KEYSTORE" --ks-pass pass:"$KEYSTORE_PASS" \
  --key-pass pass:"$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$FINAL" "$BUILD/unsigned.apk"

echo "[7/7] 完成: $FINAL"
ls -l "$FINAL"
