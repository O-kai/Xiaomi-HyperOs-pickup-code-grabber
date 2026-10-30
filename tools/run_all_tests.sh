#!/bin/bash
# 全量脱机回归：一次跑完 4.0 的全部离线测试。
#
#   bash tools/run_all_tests.sh
#
# 覆盖：
#   FullReg          全量规则回归（码 + 地点，20 项）
#   ChannelParity    短信/通知双通道一致性（7 项）
#   PkgInputTest     包名/发送方输入校验（8 项）
#   TierExclusionTest 语义排除红线（21 项）
#   CallerPolicyTest IPC 调用方白名单（11 项）
#   UserRulesTest    用户自定义规则安全（45 项）
#   RulesRegression  内置规则副本回归（39 码 + 13 地点）
#   RuleLab          规则源 rules/rules.json 直跑（39 项）
#   FeatureTest      4.0 新功能单测（配色/提醒/导入解析）
#   规则单源一致性    三副本逐字段比对
#
# 退出码非 0 表示有失败项。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'

JDK="${JAVA_HOME:-D:/develop/Java/jdk-17.0.20.1+1}"
SDK="${ANDROID_JAR:-D:/develop/android-sdk/platforms/android-34/android.jar}"
SRC="app/src/io/github/okaidev/pickupcode"
APPCLS="app/build/intermediates/javac/debug/classes"
# 必须用 Windows 可见的路径：RulesRegression 会在 test/ 目录下执行
# （它把结果写进当前的 reg-result.txt），相对路径在 cd 之后就找不到了；
# 而 Git Bash 的 /c/... 形式 javac.exe 也不认，要转成 C:/...。
if command -v cygpath >/dev/null 2>&1; then
  WINROOT="$(cygpath -m "$ROOT")"
else
  WINROOT="$ROOT"
fi
OUT="$WINROOT/test/out-all"

export PATH="$JDK/bin:$PATH"

if [ ! -d "$APPCLS/io" ]; then
  echo "app classes missing, run: gradlew.bat assembleDebug first"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/testclasses" "$OUT/src"

FAILED=0
SUMMARY=""

# org.json 必须排在 android.jar 前面：android.jar 里的 org.json 只是空壳
# （调用即抛 Stub!），真实现放在 test/libs 里，供导出/导入的格式逻辑离线验证。
# 这个 jar 只用于离线测试，不会打进 APK。
JSONJAR="$WINROOT/test/libs/org.json.jar"
if [ -f "$JSONJAR" ]; then
  CP="$OUT/testclasses;$APPCLS;$JSONJAR;$SDK"
else
  CP="$OUT/testclasses;$APPCLS;$SDK"
fi

run_java() {
  # run_java <class> [args...]
  local cls="$1"; shift
  local out
  # stdout.encoding=UTF-8：Windows 默认 GBK，否则中文断言名在日志里是乱码，
  # grep 也可能把输出当二进制丢掉。
  out=$("$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
        -cp "$CP" "$cls" "$@" 2>&1)
  local code=$?
  echo "$out" | tail -6
  local last
  last=$(echo "$out" | grep -aEi 'TOTAL|总计|PASS=' | tail -1)
  if [ $code -ne 0 ]; then
    FAILED=1
    SUMMARY="$SUMMARY\n  [FAIL] $cls  ${last:-exit=$code}"
  else
    SUMMARY="$SUMMARY\n  [ OK ] $cls  ${last:-ok}"
  fi
}

echo "=============================================="
echo " 全量脱机回归  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="

echo ""
echo "===== javac：测试源码 ====="
"$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
  -classpath "$CP" -d "$OUT/testclasses" \
  test/FullReg.java test/ChannelParity.java test/PkgInputTest.java \
  test/TierExclusionTest.java test/CallerPolicyTest.java \
  test/UserRulesTest.java test/FeatureTest.java test/DataTest.java test/V401Test.java \
  test/WidgetTest.java 2> "$OUT/javac.log"
if [ $? -ne 0 ]; then
  echo "javac FAILED:"
  head -30 "$OUT/javac.log"
  exit 1
fi
echo "javac OK"

echo ""
echo "===== RulesRegression（内置规则副本） ====="
# 规则回归需要自己编译依赖链（含 GeneratedRules），单独一个输出目录。
# 注意：GeneratedRules 必须放成「包目录」结构（io/github/okaidev/pickupcode/），
# 否则 javac 找不到类（之前把 .class 拷到源目录根，编译直接失败）。
mkdir -p "$OUT/src/io/github/okaidev/pickupcode" "$OUT/cls"
# v4.0.3：不再手工维护依赖链清单。
# 原来这里列了一长串文件，test/RulesRegression.java 里还有第二份一模一样的清单——
# 「同一份内容散在两处」意味着新增一个类只要忘了改其中一处，编译就失败
# （这轮加 RootShell 就又踩了一次）。
# 现在改用 javac 的 -sourcepath：javac 会沿源码路径自动找出并隐式编译真正的依赖，
# 依赖链永远与源码同步，不再可能漏。
for f in ExtractorRules PickupExtractor UserRules TodoWriter MissedSmsStore \
         NotesBackend Repair Diagnostics NotesFormat PkgInput NetPolicy AppVersion; do
  cp "$SRC/$f.java" "$OUT/src/io/github/okaidev/pickupcode/" 2>/dev/null
done
GEN="$ROOT/app/build/generated/rules/java/io/github/okaidev/pickupcode/GeneratedRules.java"
if [ ! -f "$GEN" ]; then
  echo "GeneratedRules.java 缺失，先跑 generateRules："
  echo "  ./gradlew.bat :app:generateRules"
  FAILED=1
else
  cp "$GEN" "$OUT/src/io/github/okaidev/pickupcode/GeneratedRules.java"

  "$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
    -sourcepath "app/src" \
    -classpath "$SDK" -d "$OUT/cls" \
    "$OUT/src/io/github/okaidev/pickupcode/"*.java 2> "$OUT/javac-reg.log"
  if [ $? -ne 0 ]; then
    echo "规则依赖链 javac FAILED:"
    head -30 "$OUT/javac-reg.log"
    FAILED=1
  else
    "$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
      -classpath "$OUT/cls;$SDK" -d "$OUT/testclasses" test/RulesRegression.java 2>> "$OUT/javac-reg.log"
    if [ $? -ne 0 ]; then
      echo "RulesRegression javac FAILED:"
      tail -20 "$OUT/javac-reg.log"
      FAILED=1
    else
      cd "$ROOT/test"
      out=$("$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/testclasses;$OUT/cls;$SDK" RulesRegression "$WINROOT" "$SDK" 2>&1)
      code=$?
      echo "$out" | tail -4
      if [ $code -ne 0 ]; then FAILED=1; SUMMARY="$SUMMARY\n  [FAIL] RulesRegression"; else
        SUMMARY="$SUMMARY\n  [ OK ] RulesRegression  $(grep -E 'TOTAL (CODES|PLACE)' reg-result.txt | tr '\n' ' ')"; fi
      cd "$ROOT"
    fi
  fi
fi

echo ""
echo "===== RuleLab（rules/rules.json 源） ====="
"$JDK/bin/javac.exe" -encoding UTF-8 -nowarn \
  -classpath "$CP" -d "$OUT/testclasses" test/RuleLab.java 2>> "$OUT/javac.log"
if [ $? -ne 0 ]; then
  echo "RuleLab javac FAILED"; FAILED=1
else
  out=$("$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" RuleLab "$WINROOT" 2>&1)
  code=$?
  echo "$out" | tail -4
  if [ $code -ne 0 ]; then FAILED=1; SUMMARY="$SUMMARY\n  [FAIL] RuleLab"; else
    SUMMARY="$SUMMARY\n  [ OK ] RuleLab  $(echo "$out" | grep -Ei 'TOTAL' | tail -1)"; fi
fi

echo ""
echo "===== 各测试 ====="
for T in FullReg ChannelParity PkgInputTest; do
  echo "----- $T -----"
  run_java "$T"
done

for T in TierExclusionTest CallerPolicyTest UserRulesTest; do
  echo "----- $T -----"
  run_java "io.github.okaidev.pickupcode.$T"
done

echo "----- FeatureTest -----"
run_java "FeatureTest"

echo "----- DataTest -----"
run_java "DataTest"

echo "----- V401Test -----"
run_java "V401Test"

echo "----- WidgetTest -----"
run_java "WidgetTest"

echo ""
echo "===== 规则单源一致性 ====="
# 注意：这里不能用 `python ... | tail -6` 再看 PIPESTATUS——
# 管道里的 PIPESTATUS 在不同 bash 下取到的位置不一样，会误判成失败。
# 改成先落盘再取退出码。
_RULE_OUT="$OUT/_rule_consistency.txt"
if command -v python >/dev/null 2>&1; then
  python test/check_rules_consistency.py > "$_RULE_OUT" 2>&1
  _RULE_RC=$?
else
  python3 test/check_rules_consistency.py > "$_RULE_OUT" 2>&1
  _RULE_RC=$?
fi
tail -6 "$_RULE_OUT"
rm -f "$_RULE_OUT"
if [ $_RULE_RC -ne 0 ]; then
  FAILED=1
  SUMMARY="$SUMMARY\n  [FAIL] 规则单源一致性"
else
  SUMMARY="$SUMMARY\n  [ OK ] 规则单源一致性"
fi

echo ""
echo "=============================================="
echo " 汇总:"
echo -e "$SUMMARY"
echo "=============================================="
if [ $FAILED -ne 0 ]; then
  echo "RESULT: 有失败项"
  exit 1
fi
echo "RESULT: 全部通过"
exit 0
