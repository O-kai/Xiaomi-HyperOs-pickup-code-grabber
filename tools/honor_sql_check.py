#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""荣耀表结构自适应 SQL 的真机前验证（离线，用真实 sqlite3 跑一遍生成的 SQL）。

为什么需要它：``HonorSql`` 拼出来的 SQL 只有「真的被 sqlite 执行过」才算验证。
单元测试（``test/HonorSqlTest.java``）只能断言字符串里出现了哪些片段，
而「语句能不能跑、跑完是不是恰好一行、标记是不是 PICKUP_OK」只有数据库能回答。

用法::

    # 1) 生成几种表结构的 SQL（HonorSql 是纯 Java，不需要 Android SDK）
    javac -encoding UTF-8 -d out \\
        app/src/io/github/okaidev/pickupcode/{TodoMatch,HonorSql}.java test/HonorSqlTest.java
    java -cp out HonorSqlTest --dump /tmp/honor-sql

    # 2) 用真实 sqlite 逐条执行并断言
    python3 tools/honor_sql_check.py /tmp/honor-sql

退出码 0 = 全部通过。任何一项失败都会打印 FAIL 并以 1 退出。

注意：脚本里的表结构是**合成**的（AOSP 系 / ColorOS 系 / 荣耀 task.db 形状），
它们代表「可能遇到的形状」，不代表某台真机就是这个样子。
真机形状请用 ``tools/device-probe/honor_probe.sh`` 导出后回填。
"""
import os
import sqlite3
import sys

FAIL = []
CONTENT = "取件码 12-3-4567｜菜鸟驿站｜O'Brien 100%_test"

# 每个场景：建库语句、待办表名、正文列、完成列
SCENARIOS = {
    "aosp": {
        "ddl": [
            "CREATE TABLE Calendars(_id INTEGER PRIMARY KEY, _sync_account TEXT, _sync_account_type TEXT)",
            "INSERT INTO Calendars(_id,_sync_account,_sync_account_type) VALUES(7,'phone','LOCAL')",
            "CREATE TABLE Tasks(_id INTEGER PRIMARY KEY, calendar_id INTEGER, title TEXT,"
            " description TEXT, dtstart INTEGER, due INTEGER, completed INTEGER, priority INTEGER,"
            " _sync_account TEXT, _sync_dirty INTEGER)",
        ],
        "table": "Tasks", "text": "title", "done": "completed",
    },
    "coloros": {
        "ddl": [
            "CREATE TABLE Calendars(_id INTEGER PRIMARY KEY, _sync_account TEXT)",
            "INSERT INTO Calendars(_id,_sync_account) VALUES(3,'oppo')",
            "CREATE TABLE Tasks(local_id TEXT, global_id TEXT, local_global_id TEXT, dirty INTEGER,"
            " content TEXT, allDay INTEGER, star INTEGER, mutators TEXT, create_package TEXT,"
            " create_time INTEGER, update_time INTEGER, sort_time INTEGER, color INTEGER,"
            " timezone TEXT, sys_version INTEGER, force_reminder INTEGER, canPartiallyUpdate INTEGER,"
            " deleted INTEGER, finish_time INTEGER)",
        ],
        "table": "Tasks", "text": "content", "done": "finish_time",
    },
    # 荣耀真机（HONOR · MagicOS 11 / task.db）的真实结构（节选，约束照抄）
    "honor_real": {
        "ddl": [
            "CREATE TABLE Accounts(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL,"
            " account_name TEXT, account_type TEXT)",
            "INSERT INTO Accounts(uuid, account_name, account_type)"
            " VALUES('default_account_uuid','Phone','com.android.hihonor.phone')",
            "CREATE TABLE Folders(uuid TEXT NOT NULL PRIMARY KEY, display_name TEXT,"
            " account_uuid TEXT, type INTEGER NOT NULL DEFAULT 2)",
            "INSERT INTO Folders(uuid, display_name, account_uuid)"
            " VALUES('5546611e$451b$4836$9100$171972052567','default_unclassified','default_account_uuid')",
            "CREATE TABLE Instances(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, task_id INTEGER NOT NULL,"
            " task_uuid TEXT, begin INTEGER NOT NULL DEFAULT 0, end INTEGER NOT NULL DEFAULT 0,"
            " startDay INTEGER NOT NULL DEFAULT 0, endDay INTEGER NOT NULL DEFAULT 0,"
            " startMinute INTEGER NOT NULL DEFAULT 0, endMinute INTEGER NOT NULL DEFAULT 0)",
            "INSERT INTO Instances(_id,task_id) VALUES(9,999)",
            "CREATE TABLE Tasks(_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL,"
            " title TEXT, body TEXT, html_content TEXT, search_content TEXT, complete INTEGER NOT NULL DEFAULT 0,"
            " folder_uuid TEXT, account_uuid TEXT, dirty INTEGER NOT NULL DEFAULT 0,"
            " delete_flag INTEGER NOT NULL DEFAULT 0, delete_time INTEGER NOT NULL DEFAULT 0,"
            " create_time INTEGER NOT NULL DEFAULT 0, modify_time INTEGER NOT NULL DEFAULT 0,"
            " due_date INTEGER NOT NULL DEFAULT 0, date_completed INTEGER NOT NULL DEFAULT 0,"
            " isAllDay INTEGER NOT NULL DEFAULT 0, reminder_type INTEGER NOT NULL DEFAULT 0,"
            " time_type INTEGER NOT NULL DEFAULT 0, importance INTEGER NOT NULL DEFAULT 0,"
            " has_attachment INTEGER NOT NULL DEFAULT 0, custom_app_package TEXT, message TEXT)",
        ],
        "table": "Tasks", "text": "title", "done": "complete",
    },
    "escape": {
        "ddl": [
            "CREATE TABLE Tasks(_id INTEGER PRIMARY KEY, content TEXT, completed INTEGER, deleted INTEGER)",
        ],
        "table": "Tasks", "text": "content", "done": "completed",
    },
}


def read(directory, name, kind):
    with open(os.path.join(directory, "%s.%s.sql" % (name, kind)), encoding="utf-8") as f:
        return f.read().strip()


def split_sql(text):
    """按 sqlite3 的语句边界切分（HTML 里带分号，不能裸切 ';'）"""
    stmts, buf = [], ""
    for line in text.splitlines(True):
        buf += line
        if sqlite3.complete_statement(buf):
            if buf.strip():
                stmts.append(buf.strip())
            buf = ""
    if buf.strip():
        stmts.append(buf.strip())
    return stmts


def run(db, script):
    """逐条执行并汇总 SELECT 结果（与 sqlite3 CLI 连续输出相同）。"""
    result = []
    for stmt in split_sql(script):
        cur = db.execute(stmt)
        if cur.description:
            result.extend(cur.fetchall())
    return result


def build(name):
    db = sqlite3.connect(":memory:")
    for stmt in SCENARIOS[name]["ddl"]:
        db.execute(stmt)
    return db


def check(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        FAIL.append(msg)


def main(directory):
    for name in ("aosp", "coloros", "honor_real"):
        spec = SCENARIOS[name]
        table, text_col, done_col = spec["table"], spec["text"], spec["done"]
        print("== " + name)
        db = build(name)
        ins = read(directory, name, "insert")
        check(ins != "NULL", "生成了 INSERT")
        check("INSERT INTO %s " % table in ins, "打到正确的表 %s" % table)
        marker = run(db, ins)
        check(("PICKUP_OK",) in marker, "changes() 标记为 PICKUP_OK：%r" % (marker,))
        rowid = db.execute("SELECT rowid FROM %s" % table).fetchone()[0]
        check(("PICKUP_ROW:%s" % rowid,) in marker, "回执定位待办行，不是 Instances 行")
        check(db.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0] == 1, "恰好写入 1 行")
        got = db.execute("SELECT %s FROM %s" % (text_col, table)).fetchone()[0]
        check(got == CONTENT, "正文完整（含引号 / 百分号 / 下划线）")
        if name == "aosp":
            check(db.execute("SELECT calendar_id FROM Tasks").fetchone()[0] == 7,
                  "calendar_id 取自 Calendars")
            check(db.execute("SELECT _sync_account FROM Tasks").fetchone()[0] == "phone",
                  "_sync_account 取自 Calendars")
        if name == "coloros":
            ids = db.execute("SELECT local_id, global_id, local_global_id FROM Tasks").fetchone()
            check(len(set(ids)) == 3 and all(len(x) == 36 for x in ids), "三个 UUID 列各自成形")
            check(db.execute("SELECT finish_time FROM Tasks").fetchone()[0] is None,
                  "finish_time 初始为 NULL")
        check(run(db, read(directory, name, "exists"))[-1][0] == 1, "未完成时 COUNT=1")
        run(db, read(directory, name, "done"))
        val = db.execute("SELECT %s FROM %s" % (done_col, table)).fetchone()[0]
        check(val is not None and val != 0, "标记完成写入 %s" % done_col)
        check(run(db, read(directory, name, "exists"))[-1][0] == 0, "完成后不再计入未完成")

    print("== no_table（两个候选库里都没有可用待办表）")
    check(read(directory, "no_table", "insert") == "NULL", "拒绝生成 INSERT")
    check(read(directory, "no_table", "done") == "NULL", "拒绝生成 DONE")
    check(read(directory, "no_table", "exists") == "NULL", "拒绝生成 EXISTS")

    print("== 非法取件码拒绝（含 % 和 _）")
    db = build("escape")
    db.execute("INSERT INTO Tasks(content, completed, deleted) VALUES('1x2y3', 0, 0)")
    db.execute("INSERT INTO Tasks(content, completed, deleted) VALUES('1%2_3 真码', 0, 0)")
    check(run(db, read(directory, "escape", "exists"))[-1][0] == 0,
          "非法码不会用于查找待办")
    run(db, read(directory, "escape", "done"))
    rows = db.execute("SELECT content, completed FROM Tasks ORDER BY _id").fetchall()
    check(rows[0][1] == 0, "1x2y3 未被误标完成")
    check(rows[1][1] == 0, "非法码不会完成任何一条待办")

    print()
    if FAIL:
        print("FAILED %d 项" % len(FAIL))
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
