#!/usr/bin/env bash
# 4.0 设备浸泡测试（P0-11 的加速版）
#
# 真机反复走一遍关键路径，并持续盯着：
#   · 崩溃 / ANR
#   · 内存增长（RSS 持续上涨 = 泄漏）
#   · 本地库完整性（sqlite integrity_check）
#   · logcat 里的错误
#
# 用法：
#   bash tools/soak.sh 60        # 跑 60 轮
#   bash tools/soak.sh 60 5      # 每轮之间停 5 秒
#
# 输出写到 artifacts/soak-<时间戳>.log，末尾给出结论行。
set -u

ADB="${ADB:-D:/develop/android-sdk/platform-tools/adb.exe}"
PKG=io.github.okaidev.pickupcode
ACT="$PKG/.LauncherActivity"
ROUNDS="${1:-60}"
GAP="${2:-3}"

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/../artifacts"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/soak-$(date +%Y%m%d-%H%M%S).log"

say() { echo "$*" | tee -a "$LOG"; }

say "=============================================="
say " 4.0 浸泡测试  $(date '+%Y-%m-%d %H:%M:%S')"
say " 轮数=$ROUNDS 间隔=${GAP}s"
say "=============================================="

"$ADB" logcat -c 2>/dev/null

first_rss=""
crashes=0
anrs=0
fails=0

for i in $(seq 1 "$ROUNDS"); do
  # 1) 冷启动 → 首页
  "$ADB" shell am force-stop $PKG >/dev/null 2>&1
  "$ADB" shell am start -n $ACT >/dev/null 2>&1
  sleep 3

  # 2) 走一遍四个 Tab（进阶模式）
  for x in 468 732 996; do
    "$ADB" shell input tap $x 2468 >/dev/null 2>&1
    sleep 1
  done

  # 3) 打开取件记录页（触发 Room 查询 + 列表渲染）
  "$ADB" shell am start -n "$PKG/.PickupListActivity" >/dev/null 2>&1
  sleep 2

  # 4) 回到首页
  "$ADB" shell am force-stop $PKG >/dev/null 2>&1
  "$ADB" shell am start -n $ACT >/dev/null 2>&1
  sleep 2

  # 采样内存
  rss=$("$ADB" shell dumpsys meminfo $PKG 2>/dev/null \
        | grep -m1 -oE 'TOTAL +[0-9]+' | grep -oE '[0-9]+')
  [ -z "$rss" ] && rss=0

  if [ -z "$first_rss" ]; then
    first_rss=$rss
    say "第 $i 轮：RSS 基线 ${rss} KB"
  fi

  # 崩溃与 ANR
  c=$("$ADB" logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION|Unable to instantiate activity")
  a=$("$ADB" logcat -d 2>/dev/null | grep -cE "ANR in $PKG|Input dispatching timed out")
  if [ "$c" -gt 0 ] || [ "$a" -gt 0 ]; then
    crashes=$((crashes + c))
    anrs=$((anrs + a))
    say "第 $i 轮：发现崩溃 $c 次 / ANR $a 次"
    "$ADB" logcat -d 2>/dev/null | grep -A 12 -E "FATAL EXCEPTION|ANR in $PKG" \
      | head -40 >> "$LOG"
  fi

  # 每 10 轮报一次进度
  if [ $((i % 10)) -eq 0 ]; then
    say "第 $i 轮：RSS=${rss} KB（基线 ${first_rss} KB）崩溃=${crashes} ANR=${anrs}"
  fi

  sleep "$GAP"
done

say ""
say "===== 结果 ====="
say "崩溃：$crashes   ANR：$anrs"
say "RSS：基线 ${first_rss} KB → 末轮 ${rss} KB"

# 本地库完整性
#
# 注意两点：
#  1) 库名是 pickup_records.db（Room 按实体名建的），不是 pickup.db；
#  2) SQL 必须走文件重定向——su 会把命令里的双引号换成单引号，
#     内联 SQL 会被拆坏（这是本项目踩过的坑，见 STAGE-0-REPORT）。
say ""
say "===== 本地库完整性 ====="
DB="/data/data/$PKG/databases/pickup_records.db"
if "$ADB" shell su -c "ls $DB" >/dev/null 2>&1; then
  SQLF="/data/local/tmp/_soak_check.sql"
  printf 'PRAGMA integrity_check;\nSELECT status, COUNT(*) FROM pickup_records GROUP BY status;\nSELECT COUNT(*) FROM pickup_records;\n' > "$OUT_DIR/_soak_check.sql"
  # adb.exe 是 Windows 程序，不认 Git Bash 的 /d/... 路径，要转成 D:/...
  if command -v cygpath >/dev/null 2>&1; then
    LOCAL_SQL="$(cygpath -m "$OUT_DIR/_soak_check.sql")"
  else
    LOCAL_SQL="$OUT_DIR/_soak_check.sql"
  fi
  "$ADB" push "$LOCAL_SQL" "$SQLF" >/dev/null 2>&1
  rm -f "$OUT_DIR/_soak_check.sql"
  res=$("$ADB" shell su -c "/data/local/tmp/pickup_sqlite/sqlite3 $DB < $SQLF" 2>/dev/null | tr -d '\r')
  say "integrity_check：$(echo "$res" | head -1)"
  echo "$res" | tail -n +2 | while read -r line; do
    [ -n "$line" ] && say "  $line"
  done
else
  say "（未找到 $DB，跳过）"
fi

say ""
say "===== logcat 里的错误（去重） ====="
"$ADB" logcat -d 2>/dev/null \
  | grep -E "PICKUPDEBUG: .*(fail|error|exception|denied)" \
  | sed 's/.*PICKUPDEBUG: //' | sort -u | head -30 | tee -a "$LOG"

if [ "$crashes" -eq 0 ] && [ "$anrs" -eq 0 ]; then
  say ""
  say "SOAK RESULT: PASS（无崩溃、无 ANR）"
else
  say ""
  say "SOAK RESULT: FAIL"
fi
say "日志：$LOG"
