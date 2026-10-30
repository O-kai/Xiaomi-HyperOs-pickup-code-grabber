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
say ""
say "===== 本地库完整性 ====="
for db in pickup.db pickup-code.db; do
  p=$("$ADB" shell su -c "ls /data/data/$PKG/databases/$db 2>/dev/null" | tr -d '\r')
  if [ -n "$p" ]; then
    r=$("$ADB" shell su -c "/data/local/tmp/pickup_sqlite/sqlite3 /data/data/$PKG/databases/$db 'PRAGMA integrity_check;'" 2>/dev/null | tr -d '\r' | tail -1)
    say "$db: $r"
    say "  条数：$("$ADB" shell su -c "/data/local/tmp/pickup_sqlite/sqlite3 /data/data/$PKG/databases/$db 'SELECT COUNT(*) FROM pickup_records;'" 2>/dev/null | tr -d '\r' | tail -1)"
  fi
done

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
