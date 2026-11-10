#!/system/bin/sh
# =============================================================================
#  荣耀（MagicOS）适配侦察脚本 —— 在手机上用 root 跑一遍，把输出贴回 Issue
# -----------------------------------------------------------------------------
#  为什么需要它：荣耀的「日历待办」库长什么样，维护者手上没有真机样本。
#  模块的写入侧已经做成「按实际表结构自适应」（见 app/src/.../HonorSql.java），
#  但要确认它落在正确的库/表/列上，仍然需要一份真机现场。
#
#  用法（电脑上，手机已 root、已装本模块并打开过一次）：
#      adb push tools/device-probe/honor_probe.sh /data/local/tmp/
#      adb shell su -c "sh /data/local/tmp/honor_probe.sh" > honor-probe.txt
#
#  或者手机端（Termux / 任意 root shell）：
#      su -c "sh /sdcard/honor_probe.sh" > /sdcard/honor-probe.txt
#
#  跑之前请先做一件事：打开「日历 → 待办」，手动建一条内容为
#  「测试待办AAA」的待办。待办库是懒创建的，没建过时库可能根本不存在，
#  脚本会误报「找不到库」。
#
#  输出里不含短信正文，但会包含一条待办样本（你自己刚建的那条）。
#  贴之前扫一眼，别把自己的取件地址贴出去。
# =============================================================================

PKG_APP=io.github.okaidev.pickupcode
SQLITE=/data/data/$PKG_APP/files/rt/sqlite3
LIBS=/data/data/$PKG_APP/files/rt/lib
LEGACY=/data/local/tmp/pickup_sqlite/sqlite3

say() { echo ""; echo "===== $1 ====="; }

# 找一个能用的 sqlite3：模块自带的优先，其次系统自带的
pick_sqlite() {
  if [ -x "$SQLITE" ]; then echo "$SQLITE"; return; fi
  if [ -x "$LEGACY" ]; then echo "$LEGACY"; return; fi
  if command -v sqlite3 >/dev/null 2>&1; then command -v sqlite3; return; fi
  echo ""
}

SQL="$(pick_sqlite)"
q() {
  # q <db> <sql>
  if [ -z "$SQL" ]; then echo "(没有可用的 sqlite3：先在模块里点「一键部署 sqlite3」)"; return; fi
  LD_LIBRARY_PATH=$LIBS "$SQL" "$1" "$2" 2>&1
}

say "设备"
getprop ro.product.brand
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.build.version.magic
getprop ro.build.version.emui
getprop ro.build.display.id

say "短信 / 信息 App"
pm list packages 2>/dev/null | grep -iE 'mms|telephony' | sed 's/package://'

say "短信接收器（找 SMS_RECEIVED / SMS_DELIVER 的接收类名）"
for p in com.hihonor.mms com.android.mms com.huawei.mms; do
  if pm list packages 2>/dev/null | grep -q "package:$p$"; then
    echo "--- $p"
    dumpsys package "$p" 2>/dev/null \
      | grep -iE 'SMS_RECEIVED|SMS_DELIVER|Receiver' \
      | grep -iE 'com\.[a-z0-9_.]+\.[A-Za-z0-9_$]+' \
      | sed 's/^[[:space:]]*//' | sort -u | head -40
  fi
done

say "日历 / 备忘录 App"
pm list packages 2>/dev/null | grep -iE 'calendar|notepad|note' | sed 's/package://'

say "日历 Provider 能否直接查（不需要 root 的那条路）"
if command -v content >/dev/null 2>&1; then
  content query --uri content://com.android.calendar/calendars \
    --projection _id:account_name:account_type:calendar_display_name 2>&1 | head -10
  echo "--- tasks"
  content query --uri content://com.android.calendar/tasks --projection _id:title:completed 2>&1 | head -10
fi

say "候选库文件"
ls -l /data/user/0/com.android.providers.calendar/databases/ 2>&1
ls -l /data/user/0/com.hihonor.notepad/databases/ 2>&1
ls -l /data/user/0/com.hihonor.calendar/databases/ 2>&1
echo "--- 全盘扫一遍（只列名字）"
ls -1 /data/user/0/*/databases/*.db 2>/dev/null | grep -iE 'calendar|task|note|todo' | head -20

CALDB=/data/user/0/com.android.providers.calendar/databases/calendar.db
if [ -f "$CALDB" ]; then
  say "calendar.db 里的表"
  q "$CALDB" ".tables"
  say "Tasks 表结构（这一行最关键，贴回来就能定稿）"
  q "$CALDB" "PRAGMA table_info(Tasks);"
  say "Calendars 表结构"
  q "$CALDB" "PRAGMA table_info(Calendars);"
  say "Calendars 里的账号类型（LOCAL 就是本机日历）"
  q "$CALDB" "SELECT _id, _sync_account, _sync_account_type FROM Calendars LIMIT 5;"
  say "Tasks 样本（你自己刚建的那条；确认列语义用）"
  q "$CALDB" "SELECT * FROM Tasks ORDER BY rowid DESC LIMIT 1;"
else
  say "calendar.db 不存在"
  echo "先在「日历 → 待办」里手动建一条「测试待办AAA」，再重跑本脚本。"
fi

say "备忘录库（备用，暂未适配，留样本）"
for db in /data/user/0/com.hihonor.notepad/databases/*.db; do
  [ -f "$db" ] || continue
  echo "--- $db"
  q "$db" ".tables"
done

say "模块自身状态"
ls -l /data/data/$PKG_APP/files/rt/ 2>&1 | head -8
cat /data/local/tmp/pickup_sqlite/app_state.json 2>/dev/null | head -c 600
echo ""

say "结束"
echo "把整份输出（可以删掉含地址的那行样本）贴到 Issue 即可。"
